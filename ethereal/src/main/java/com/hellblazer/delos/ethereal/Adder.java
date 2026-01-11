/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.ethereal;

import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.bloomFilters.BloomFilter.DigestBloomFilter;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.cryptography.batch.BatchVerificationContext;
import com.hellblazer.delos.cryptography.proto.Biff;
import com.hellblazer.delos.ethereal.proto.*;
import com.hellblazer.delos.utils.Entropy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import static com.hellblazer.delos.ethereal.Creator.parentsOnPreviousLevel;
import static com.hellblazer.delos.ethereal.PreUnit.id;

/**
 * Implements the chain Reliable Broadcast of Aleph.
 *
 * The public methods of the Adder correspond to the gossip replication protocol actions.
 *
 * @author hal.hildebrand
 */
public class Adder {

    private static final Logger                     log                = LoggerFactory.getLogger(Adder.class);
    private static final int                        MAX_COLLECTION_SIZE = 100_000; // Prevent DoS via collection exhaustion

    private final        Map<Digest, Set<Short>>    commits         = new TreeMap<>();
    private final        Config                     conf;
    private final        Dag                        dag;
    private final        int                        epoch;
    private final        Set<Digest>                failed;
    private final        ReentrantLock              lock            = new ReentrantLock(true);
    private final        int                        maxSize;
    private final        Map<Long, List<Waiting>>   missing         = new TreeMap<>();
    private final        Map<Digest, Set<Short>>    prevotes        = new TreeMap<>();
    private final        Map<Digest, SignedCommit>  signedCommits   = new TreeMap<>();
    private final        Map<Digest, SignedPreVote> signedPrevotes  = new TreeMap<>();
    private final        int                        threshold;
    private final        Verifier[]                 verifiers;
    private final        Map<Digest, Waiting>       waiting         = new TreeMap<>();
    private final        Map<Long, Waiting>         waitingById     = new TreeMap<>();
    private final        Map<Digest, Waiting>       waitingForRound = new TreeMap<>();
    private volatile     int                        round           = 0;

    // CRITICAL (Delos-wfz7): Track units by (creator, height) to detect equivocation
    // Byzantine nodes may produce multiple units with same (creator, height) but different content
    private final        Map<Short, Map<Integer, Waiting>> unitsByCreatorHeight = new HashMap<>();
    // CRITICAL (Delos-wfz7): Blacklist creators that have equivocated
    // Once equivocation detected, all future units/votes from that creator are rejected
    private final        Set<Short>                 blacklistedCreators = new ConcurrentSkipListSet<>();

    public Adder(int epoch, Dag dag, int maxSize, Config conf, Set<Digest> failed, Verifier[] verifiers) {
        this.epoch = epoch;
        this.dag = dag;
        this.conf = conf;
        this.failed = failed;
        this.verifiers = verifiers;
        this.threshold = Dag.threshold(conf.nProc());
        this.maxSize = maxSize;
    }

    public static Signed<SignedCommit> commit(final Long id, final Digest hash, final short pid, Signer signer,
                                              DigestAlgorithm algo) {
        final var commit = Commit.newBuilder().setUnit(id).setSource(pid).setHash(hash.toDigeste()).build();
        signer.sign(commit.toByteString());
        JohnHancock signature = signer.sign(commit.toByteString());
        return new Signed<>(signature.toDigest(algo),
                            SignedCommit.newBuilder().setCommit(commit).setSignature(signature.toSig()).build());
    }

    public static Signed<SignedPreVote> prevote(final Long id, final Digest hash, final short pid, Signer signer,
                                                DigestAlgorithm algo) {
        final var prevote = PreVote.newBuilder().setUnit(id).setSource(pid).setHash(hash.toDigeste()).build();
        signer.sign(prevote.toByteString());
        JohnHancock signature = signer.sign(prevote.toByteString());
        return new Signed<>(signature.toDigest(algo),
                            SignedPreVote.newBuilder().setVote(prevote).setSignature(signature.toSig()).build());
    }

    public void close() {
        log.trace("Closing adder epoch: {} on: {}", dag.epoch(), conf.logLabel());
        locked(() -> {
            waiting.clear();
            waitingById.clear();
            waitingForRound.clear();
            signedCommits.clear();
            signedPrevotes.clear();
            prevotes.clear();
            missing.clear();
            unitsByCreatorHeight.clear();
            blacklistedCreators.clear();
        });
    }

    public String dump() {
        return locked(() -> {
            var buff = new StringBuffer();
            buff.append('\t')
                .append("pid: ")
                .append(conf.pid())
                .append('\n')
                .append('\t')
                .append("round: ")
                .append(round)
                .append('\n')
                .append('\t')
                .append("failed: ")
                .append(failed)
                .append('\n')
                .append('\t')
                .append("missing: ")
                .append(missing)
                .append('\n')
                .append('\t')
                .append("waiting: ")
                .append(waiting.values().stream().toList())
                .append('\n')
                .append('\t')
                .append("commits: ")
                .append(commits.entrySet()
                               .stream()
                               .filter(e -> e.getValue().size() < 2 * threshold + 1)
                               .map(e -> e.getKey() + ":" + e.getValue())
                               .toList())
                .append('\n')
                .append('\t')
                .append("prevotes: ")
                .append(prevotes.entrySet()
                                .stream()
                                .filter(e -> e.getValue().size() < 2 * threshold + 1)
                                .map(e -> e.getKey() + ":" + e.getValue())
                                .toList());
            var units = new ArrayList<Unit>();

            dag.iterateUnits(u -> {
                if (u.epoch() == epoch) {
                    units.add(u);
                }
                return true;
            });

            units.sort(PreUnit.topologicalComparator());

            buff.append('\n').append('\n').append('\t').append("Dag Units: ").append('\n');
            units.forEach(u -> {
                buff.append('\t').append(u).append('\n');
            });
            return buff.toString();
        });
    }

    /**
     * Answer the Have state of the receiver - commits, prevotes, and proposed units
     *
     * @return the Have state of the receiver
     */
    public Have have() {
        return locked(() -> {
            return Have.newBuilder()
                       .setEpoch(epoch)
                       .setHaveCommits(haveCommits())
                       .setHavePreVotes(havePreVotes())
                       .setHaveUnits(haveUnits())
                       .build();
        });
    }

    /**
     * Produce a Unit on this node.
     *
     * @param u - the Unit to produce
     */
    public void produce(Unit u) {
        if (u.epoch() != epoch) {
            throw new IllegalStateException("incorrect epoch: " + u + " only accepting: " + epoch);
        }
        if (dag.contains(u.hash())) {
            log.trace("Produced duplicated unit: {} on: {}", u, conf.logLabel());
            return;
        }
        locked(() -> {
            assert u.creator() == conf.pid();
            round = u.height();
            log.trace("Producing unit: {}:{} on: {}", u.hash(), u, conf.logLabel());
            final var wpu = new Waiting(u.toPreUnit(), u.toPreUnit_s());
            waiting.put(wpu.hash(), wpu);
            checkIfMissing(wpu);
            prevote(wpu);
            commit(wpu);
            output(wpu);
            advance();
        });
    }

    /**
     * Provide the missing state from the receiver state from the supplied update.
     *
     * @param haves - the have state of the partner
     * @return Missing based on the current state and the haves of the receiver
     */
    public Missing updateFor(Have haves) {
        assert haves.getEpoch() == epoch : "Have from incorrect epoch: " + haves.getEpoch() + " expected: " + epoch
        + " on: " + conf.logLabel();
        return locked(() -> {
            final var builder = Missing.newBuilder();
            builder.setEpoch(epoch);
            Adder.this.update(haves, builder);
            return builder.setHaves(have()).build();
        });
    }

    /**
     * Update the commit, prevote and unit state from the supplied update.
     *
     * CRITICAL: Processes prevotes and commits in deterministic order (sorted by unit hash, then source)
     * to prevent Byzantine consensus divergence. All honest nodes must process votes in the same order
     * regardless of gossip message ordering.
     */
    public void updateFrom(Missing update) {
        assert update.getEpoch() == epoch : "Update from incorrect epoch: " + update.getEpoch() + " expected: " + epoch
        + " on: " + conf.logLabel();
        locked(() -> {
            update.getUnitsList().forEach(u -> {
                final var signature = JohnHancock.from(u.getSignature());
                final var digest = signature.toDigest(conf.digestAlgorithm());
                if (!failed.contains(digest)) {
                    log.trace("propose: {} : {} on: {}", digest, PreUnit.decode(u.getId()), conf.logLabel());
                    propose(digest, u);
                }
            });

            processPrevotesWithBatching(update.getPrevotesList());
            processCommitsWithBatching(update.getCommitsList());
        });
    }

    /**
     * Process prevotes with batch verification for improved performance.
     *
     * Maintains deterministic sorted order (by unit_hash, source) for Byzantine consensus safety.
     * Collects prevotes for batch verification, validates them together, then processes results
     * in sorted order to ensure all honest nodes reach identical state.
     */
    private void processPrevotesWithBatching(List<SignedPreVote> prevotes) {
        // CRITICAL: Sort by (unit_hash, source) for deterministic processing order
        var sortedPrevotes = prevotes.stream()
            .sorted((pv1, pv2) -> {
                var hash1 = Digest.from(pv1.getVote().getHash());
                var hash2 = Digest.from(pv2.getVote().getHash());
                var hashCompare = hash1.compareTo(hash2);
                if (hashCompare != 0) {
                    return hashCompare;
                }
                return Short.compare((short) pv1.getVote().getSource(), (short) pv2.getVote().getSource());
            })
            .toList();

        // Collect prevotes for batch verification
        List<SignedPreVote> toVerify = new ArrayList<>();
        for (var pv : sortedPrevotes) {
            final var hash = Digest.from(pv.getVote().getHash());
            if (failed.contains(hash)) {
                continue;
            }
            var source = pv.getVote().getSource();
            if (source < 0 || source >= verifiers.length || verifiers[source] == null) {
                log.warn("Invalid prevote source: {} on: {}", source, conf.logLabel());
                continue;
            }
            final var signature = JohnHancock.from(pv.getSignature());
            final var signatureDigest = signature.toDigest(conf.digestAlgorithm());
            if (!signedPrevotes.containsKey(signatureDigest)) {
                toVerify.add(pv);
            }
        }

        // Batch verify if we have multiple prevotes, otherwise verify individually
        boolean[] validationResults;
        if (toVerify.size() >= 4) {
            validationResults = batchVerifyPrevotes(toVerify);
            // If batch verification failed (returned null), fall back to individual verification
            if (validationResults == null) {
                validationResults = new boolean[toVerify.size()];
                for (int i = 0; i < toVerify.size(); i++) {
                    validationResults[i] = validate(toVerify.get(i));
                }
            }
        } else {
            validationResults = new boolean[toVerify.size()];
            for (int i = 0; i < toVerify.size(); i++) {
                validationResults[i] = validate(toVerify.get(i));
            }
        }

        // Process results in sorted order to maintain Byzantine safety
        int verifyIndex = 0;
        for (var pv : sortedPrevotes) {
            final var hash = Digest.from(pv.getVote().getHash());
            if (failed.contains(hash)) {
                continue;
            }
            var source = pv.getVote().getSource();
            if (source < 0 || source >= verifiers.length || verifiers[source] == null) {
                continue;
            }
            final var signature = JohnHancock.from(pv.getSignature());
            final var signatureDigest = signature.toDigest(conf.digestAlgorithm());

            // Find the corresponding validation result
            boolean validated = false;
            if (!signedPrevotes.containsKey(signatureDigest)) {
                if (verifyIndex < validationResults.length) {
                    validated = validationResults[verifyIndex++];
                }
                if (validated) {
                    signedPrevotes.put(signatureDigest, pv);
                }
            } else {
                validated = signedPrevotes.get(signatureDigest) != null;
            }

            if (validated) {
                prevote(Digest.from(pv.getVote().getHash()), (short) pv.getVote().getSource());
            }
        }
    }

    /**
     * Batch verify prevotes using signature batch verification.
     * Returns array of boolean results corresponding to input prevotes.
     */
    private boolean[] batchVerifyPrevotes(List<SignedPreVote> prevotes) {
        byte[][] messages = new byte[prevotes.size()][];
        byte[][] signatures = new byte[prevotes.size()][];
        byte[][] publicKeys = new byte[prevotes.size()][];

        try {
            for (int i = 0; i < prevotes.size(); i++) {
                var pv = prevotes.get(i);
                var source = pv.getVote().getSource();

                messages[i] = pv.getVote().toByteArray();
                signatures[i] = pv.getSignature().toByteArray();

                // Extract public key bytes from verifier
                var verifier = verifiers[source];
                var key = verifier.getKey();
                if (key == null) {
                    // Fallback to individual verification if public key unavailable
                    log.trace("No public key available for batch verification of prevote from source: {}", source);
                    return null;
                }
                publicKeys[i] = key.getEncoded();
            }

            // Perform batch verification
            final long startBatch = System.nanoTime();
            var results = com.hellblazer.delos.cryptography.batch.BatchVerifierFactory
                .createVerifier()
                .batchVerify(messages, signatures, publicKeys);
            final long batchTimeUs = (System.nanoTime() - startBatch) / 1000;
            log.debug("Timing - prevote batch verify: {}μs count={} on: {}", batchTimeUs, prevotes.size(),
                     conf.logLabel());
            return results;
        } catch (Exception e) {
            log.warn("Batch verification failed, falling back to individual verification: {}", e.getMessage());
            // Fall back to individual verification
            boolean[] results = new boolean[prevotes.size()];
            for (int i = 0; i < prevotes.size(); i++) {
                results[i] = validate(prevotes.get(i));
            }
            return results;
        }
    }

    /**
     * Process commits with batch verification for improved performance.
     *
     * Maintains deterministic sorted order (by unit_hash, source) for Byzantine consensus safety.
     */
    private void processCommitsWithBatching(List<SignedCommit> commits) {
        // CRITICAL: Sort by (unit_hash, source) for deterministic processing order
        var sortedCommits = commits.stream()
            .sorted((c1, c2) -> {
                var hash1 = Digest.from(c1.getCommit().getHash());
                var hash2 = Digest.from(c2.getCommit().getHash());
                var hashCompare = hash1.compareTo(hash2);
                if (hashCompare != 0) {
                    return hashCompare;
                }
                return Short.compare((short) c1.getCommit().getSource(), (short) c2.getCommit().getSource());
            })
            .toList();

        // Collect commits for batch verification
        List<SignedCommit> toVerify = new ArrayList<>();
        for (var c : sortedCommits) {
            final var hash = Digest.from(c.getCommit().getHash());
            if (failed.contains(hash)) {
                continue;
            }
            var source = c.getCommit().getSource();
            if (source < 0 || source >= verifiers.length || verifiers[source] == null) {
                log.warn("Invalid commit source: {} on: {}", source, conf.logLabel());
                continue;
            }
            final var signature = JohnHancock.from(c.getSignature());
            final var signatureDigest = signature.toDigest(conf.digestAlgorithm());
            if (!signedCommits.containsKey(signatureDigest)) {
                toVerify.add(c);
            }
        }

        // Batch verify if we have multiple commits, otherwise verify individually
        boolean[] validationResults;
        if (toVerify.size() >= 4) {
            validationResults = batchVerifyCommits(toVerify);
            // If batch verification failed (returned null), fall back to individual verification
            if (validationResults == null) {
                validationResults = new boolean[toVerify.size()];
                for (int i = 0; i < toVerify.size(); i++) {
                    validationResults[i] = validate(toVerify.get(i));
                }
            }
        } else {
            validationResults = new boolean[toVerify.size()];
            for (int i = 0; i < toVerify.size(); i++) {
                validationResults[i] = validate(toVerify.get(i));
            }
        }

        // Process results in sorted order to maintain Byzantine safety
        int verifyIndex = 0;
        for (var c : sortedCommits) {
            final var hash = Digest.from(c.getCommit().getHash());
            if (failed.contains(hash)) {
                continue;
            }
            var source = c.getCommit().getSource();
            if (source < 0 || source >= verifiers.length || verifiers[source] == null) {
                continue;
            }
            final var signature = JohnHancock.from(c.getSignature());
            final var digest = signature.toDigest(conf.digestAlgorithm());

            // Find the corresponding validation result
            boolean validated = false;
            if (!signedCommits.containsKey(digest)) {
                if (verifyIndex < validationResults.length) {
                    validated = validationResults[verifyIndex++];
                }
                if (validated) {
                    signedCommits.put(digest, c);
                }
            } else {
                validated = signedCommits.get(digest) != null;
            }

            if (validated) {
                commit(Digest.from(c.getCommit().getHash()), (short) c.getCommit().getSource());
            }
        }
    }

    /**
     * Batch verify commits using signature batch verification.
     * Returns array of boolean results corresponding to input commits.
     */
    private boolean[] batchVerifyCommits(List<SignedCommit> commits) {
        byte[][] messages = new byte[commits.size()][];
        byte[][] signatures = new byte[commits.size()][];
        byte[][] publicKeys = new byte[commits.size()][];

        try {
            for (int i = 0; i < commits.size(); i++) {
                var c = commits.get(i);
                var source = c.getCommit().getSource();

                messages[i] = c.getCommit().toByteArray();
                signatures[i] = c.getSignature().toByteArray();

                // Extract public key bytes from verifier
                var verifier = verifiers[source];
                var key = verifier.getKey();
                if (key == null) {
                    // Fallback to individual verification if public key unavailable
                    log.trace("No public key available for batch verification of commit from source: {}", source);
                    return null;
                }
                publicKeys[i] = key.getEncoded();
            }

            // Perform batch verification
            final long startBatch = System.nanoTime();
            var results = com.hellblazer.delos.cryptography.batch.BatchVerifierFactory
                .createVerifier()
                .batchVerify(messages, signatures, publicKeys);
            final long batchTimeUs = (System.nanoTime() - startBatch) / 1000;
            log.debug("Timing - commit batch verify: {}μs count={} on: {}", batchTimeUs, commits.size(),
                     conf.logLabel());
            return results;
        } catch (Exception e) {
            log.warn("Batch verification failed, falling back to individual verification: {}", e.getMessage());
            // Fall back to individual verification
            boolean[] results = new boolean[commits.size()];
            for (int i = 0; i < commits.size(); i++) {
                results[i] = validate(commits.get(i));
            }
            return results;
        }
    }

    /**
     * A commit was received
     *
     * @param digest - the digest of the unit
     * @param member - the index of the member
     */
    void commit(Digest digest, short member) {
        // CRITICAL (Delos-wfz7): Reject commits from blacklisted creators
        if (blacklistedCreators.contains(member)) {
            log.trace("Ignoring commit from blacklisted creator: {} on: {}", member, conf.logLabel());
            return;
        }

        if (failed.contains(digest)) {
            return;
        }
        if (dag.contains(digest)) {
            return; // already output
        }

        // CRITICAL: Bound commits collection (Delos-v0k5)
        if (commits.size() >= MAX_COLLECTION_SIZE) {
            log.warn("Commits collection exhausted ({} entries) - possible DoS attack on: {}", MAX_COLLECTION_SIZE,
                     conf.logLabel());
            return;
        }
        final Set<Short> committed = commits.computeIfAbsent(digest, h -> new HashSet<>());
        var wpu = waiting.get(digest);

        if (!committed.add(member)) {
            log.trace("Already committed: {} count: {} on: {}", wpu, committed.size(), conf.logLabel());
            return;
        }
        log.trace("Committed: {} count: {} on: {}", wpu == null ? digest : wpu, committed.size(), conf.logLabel());

        if (committed.size() <= threshold) {
            return;
        }

        // Check for an existing proposal
        if (wpu == null) {
            log.trace("Committed, but no proposal: {} count: {} on: {}", digest, committed.size(), conf.logLabel());
            return;
        }

        switch (wpu.state()) {
        case PREVOTED:
            if (committed.size() > threshold) {
                log.trace("Committing: {} on: {}", wpu, conf.logLabel());
                commit(wpu);
            }
            break;
        case COMMITTED:
            if (committed.size() > 2 * threshold) {
                log.trace("Outputting: {} on: {}", wpu, conf.logLabel());
                output(wpu);
            }
            break;
        default:
            log.trace("No commit action: {} count: {} on: {}", wpu, committed.size(), conf.logLabel());
            break;
        }
    }

    Map<Digest, Set<Short>> getCommits() {
        return commits;
    }

    Dag getDag() {
        return dag;
    }

    Map<Long, List<Waiting>> getMissing() {
        return missing;
    }

    Map<Digest, Set<Short>> getPrevotes() {
        return prevotes;
    }

    Map<Digest, SignedCommit> getSignedCommits() {
        return signedCommits;
    }

    Map<Digest, SignedPreVote> getSignedPrevotes() {
        return signedPrevotes;
    }

    Map<Digest, Waiting> getWaiting() {
        return waiting;
    }

    Map<Long, Waiting> getWaitingById() {
        return waitingById;
    }

    Map<Digest, Waiting> getWaitingForRound() {
        return waitingForRound;
    }

    Set<Short> getBlacklistedCreators() {
        return blacklistedCreators;
    }

    /**
     * A preVote was received
     *
     * @param digest - the digest of the unit
     * @param member - the index of the member
     */
    void prevote(Digest digest, short member) {
        // CRITICAL (Delos-wfz7): Reject prevotes from blacklisted creators
        if (blacklistedCreators.contains(member)) {
            log.trace("Ignoring prevote from blacklisted creator: {} on: {}", member, conf.logLabel());
            return;
        }

        if (failed.contains(digest)) {
            return;
        }
        if (dag.contains(digest)) {
            return; // already output
        }

        // CRITICAL: Bound prevotes collection (Delos-v0k5)
        if (prevotes.size() >= MAX_COLLECTION_SIZE) {
            log.warn("Prevotes collection exhausted ({} entries) - possible DoS attack on: {}", MAX_COLLECTION_SIZE,
                     conf.logLabel());
            return;
        }
        final Set<Short> prepared = prevotes.computeIfAbsent(digest, h -> new HashSet<>());
        if (!prepared.add(member)) {
            return;
        }
        var wpu = waiting.get(digest);

        // We only care if we've gotten the proposal
        if (wpu == null) {
            log.trace("Prevoted, but no proposal: {} count: {} on: {}", digest, prepared.size(), conf.logLabel());
            return;
        }

        // We only care if the # of prevotes is >= 2*f + 1
        if (prepared.size() <= 2 * threshold) {
            return;
        }

        log.trace("Prevoting: {} wpu: {} count: {} on: {}", digest, wpu, prepared.size(), conf.logLabel());

        switch (wpu.state()) {
        case PREVOTED:
            waitingById.put(wpu.id(), wpu);
            checkParents(wpu);
            checkIfMissing(wpu);
            if (wpu.parentsOutput()) {
                commit(wpu);
            } else {
                wpu.setState(State.WAITING_FOR_PARENTS);
                log.trace("Waiting for parents: {} on: {}", wpu, conf.logLabel());
            }
            break;
        case WAITING_FOR_PARENTS:
            if (wpu.parentsOutput()) {
                commit(wpu);
            }
            break;
        default:
            log.trace("No prevote action: {} prevote count: {} on: {}", wpu, prepared.size(), conf.logLabel());
            break;
        }
    }

    /**
     * A unit has been proposed.
     *
     * @param digest - the digest identifying the unit
     * @param u      - the serialized preUnit
     */
    void propose(Digest digest, PreUnit_s u) {
        if (failed.contains(digest)) {
            log.trace("Failed preunit: {} on: {}", digest, conf.logLabel());
            return;
        }
        var wpu = waiting.get(digest);
        if (wpu != null) {
            return;
        }
        final var existing = dag.get(digest);
        if (existing != null) {
            return;
        }
        final var decoded = PreUnit.decode(u.getId());
        if (decoded.creator() == conf.pid()) {
            return;
        }
        if (decoded.epoch() != epoch) {
            log.trace("Invalid epoch: {} expected {} unit: {} on: {}", decoded.epoch(), epoch, decoded,
                      conf.logLabel());
            return;
        }

        if (decoded.creator() >= conf.nProc() || decoded.creator() < 0) {
            failed.add(digest);
            log.debug("Invalid creator: {} on: {}", decoded, conf.nProc() - 1, conf.logLabel());
            return;
        }

        // CRITICAL (Delos-wfz7): Check if creator is blacklisted for equivocation
        if (blacklistedCreators.contains(decoded.creator())) {
            log.debug("Rejecting unit from blacklisted creator: {} on: {}", decoded, conf.logLabel());
            return;
        }

        // TODO: Delos-vupk - Add PreUnit signature verification when gossip protocol updated to sign units
        // Currently skipped as gossip protocol doesn't populate signatures in PreUnit_s

        if (u.toByteString().size() > maxSize) {
            failed.add(digest);
            log.trace("Invalid size: {} > {} id: {} on: {}", u.toByteString().size(), maxSize, decoded,
                      conf.logLabel());
            return;
        }

        var preunit = PreUnit.from(u, conf.digestAlgorithm());
        wpu = new Waiting(preunit, u);

        if (!validateParents(wpu)) {
            failed.add(digest);
            log.warn("Invalid parents: {} on: {}", decoded, conf.nProc() - 1, conf.logLabel());
            return;
        }

        // CRITICAL (Delos-wfz7): Detect equivocation - same (creator, height) with different content
        var existingAtHeight = unitsByCreatorHeight.computeIfAbsent(decoded.creator(), k -> new HashMap<>())
                                                   .get(decoded.height());
        if (existingAtHeight != null) {
            // Same (creator, height) already seen - check if it's the same unit
            if (!existingAtHeight.hash().equals(digest)) {
                // EQUIVOCATION DETECTED: Different unit at same (creator, height)
                // This is definitive proof of Byzantine behavior
                blacklistedCreators.add(decoded.creator());
                failed.add(digest);

                // Cleanup: remove from waiting if it was added (defensive - ensures no partial state)
                waiting.remove(digest);

                log.error("EQUIVOCATION DETECTED: creator={} height={} existing_hash={} new_hash={} on: {}. "
                          + "Creator blacklisted.", decoded.creator(), decoded.height(), existingAtHeight.hash(),
                          digest, conf.logLabel());
                throw new IllegalStateException(
                String.format("Equivocation detected: creator=%d height=%d produced conflicting units %s and %s",
                              decoded.creator(), decoded.height(), existingAtHeight.hash(), digest));
            }
            // Same unit proposed twice - idempotent, just return
            return;
        }

        // CRITICAL: Bound waiting collection to prevent DoS (Delos-v0k5)
        if (waiting.size() >= MAX_COLLECTION_SIZE) {
            failed.add(digest);
            log.warn("Waiting collection exhausted ({} entries) - possible DoS attack on: {}", MAX_COLLECTION_SIZE,
                     conf.logLabel());
            return;
        }
        waiting.put(digest, wpu);

        // CRITICAL (Delos-wfz7): Track this unit by (creator, height) for equivocation detection
        unitsByCreatorHeight.get(decoded.creator()).put(decoded.height(), wpu);

        if (preunit.height() - 1 > round) {
            wpu.setState(State.WAITING_ON_ROUND);
            log.trace("Proposed, waiting: {} current round: {} on: {}", wpu, round, conf.logLabel());

            // CRITICAL: Bound waitingForRound collection (Delos-v0k5)
            if (waitingForRound.size() >= MAX_COLLECTION_SIZE) {
                failed.add(digest);
                log.warn("WaitingForRound collection exhausted ({} entries) - possible DoS attack on: {}",
                         MAX_COLLECTION_SIZE, conf.logLabel());
                return;
            }
            waitingForRound.put(digest, wpu);
            return;
        }

        log.trace("Proposed: {} on: {}", wpu, conf.logLabel());
        prevote(wpu);
    }

    /**
     * LIVENESS (Delos-vyai): Check for and cleanup stale waiting units.
     *
     * Byzantine nodes may withhold critical parent units to cause liveness failures.
     * This method detects units that have been waiting beyond timeout threshold and
     * removes them from the waiting queue.
     *
     * Liveness Guarantee:
     * - Detects Byzantine withholding: units waiting > configured timeout
     * - Removes stale units to prevent memory exhaustion
     * - Logs timeout events for diagnosis
     *
     * Byzantine Safety:
     * - Timeout NEVER compromises safety (signature verification still required)
     * - Only removes from waiting queue (doesn't mark as valid/committed)
     * - Conservative timeout (5s) prevents false positives from slow networks
     *
     * Should be called periodically (e.g., every 1 second) by consensus loop.
     */
    public void runTimeoutCheck() {
        locked(() -> {
            var now = System.currentTimeMillis();
            var staleUnits = new ArrayList<Digest>();

            // Find stale units
            for (var entry : waiting.entrySet()) {
                var waitingUnit = entry.getValue();
                if (waitingUnit.isStaleAfterMillis(conf.unitTimeoutMillis())) {
                    staleUnits.add(entry.getKey());
                    log.warn(
                    "LIVENESS TIMEOUT: Removing stale waiting unit {} after {}ms - possible Byzantine parent withholding on: {}",
                    waitingUnit, conf.unitTimeoutMillis(), conf.logLabel());
                }
            }

            // Remove stale units
            for (var hash : staleUnits) {
                var staleUnit = waiting.remove(hash);
                if (staleUnit != null) {
                    // Also remove from waitingById if present
                    waitingById.remove(staleUnit.id());
                    // Also remove from waitingForRound if present
                    waitingForRound.remove(hash);
                    // Also remove from unitsByCreatorHeight
                    var creatorMap = unitsByCreatorHeight.get(staleUnit.creator());
                    if (creatorMap != null) {
                        creatorMap.remove(staleUnit.height());
                    }
                }
            }

            return null;
        });
    }

    // Advance the state of the RBC by one round
    private void advance() {
        var ready = new ArrayList<Waiting>();
        var iterator = waitingForRound.entrySet().iterator();
        while (iterator.hasNext()) {
            var e = iterator.next();
            if (e.getValue().height() - 1 <= round) {
                ready.add(e.getValue());
            } else {
                log.trace("Waiting for round: {} current: {} on: {}", e.getValue(), round, conf.logLabel());
            }
        }
        ready.forEach(w -> {
            log.trace("Advanced: {} clearing round: {} on: {}", w, round, conf.logLabel());
            prevote(w);
        });
    }

    /**
     * checkIfMissing sets the children() attribute of a newly created waitingPreunit, depending on if it was missing
     */
    private void checkIfMissing(Waiting wp) {
        log.trace("Checking if missing: {} on: {}", wp, conf.logLabel());
        var neededBy = missing.get(wp.id());
        if (neededBy != null) {
            wp.clearAndAdd(neededBy);
            for (var ch : wp.children()) {
                ch.decMissing();
                ch.incWaiting();
                log.trace("Found parent {} for: {} on: {}", wp, ch, conf.logLabel());
            }
            missing.remove(wp.id());
        } else {
            wp.clearChildren();
        }
    }

    /**
     * finds out which parents of a newly created WaitingPreUnit are in the dag, which are waiting, and which are
     * missing. Sets values of waitingParents() and missingParents accordingly. Additionally, returns maximal heights of
     * dag.
     */
    private int[] checkParents(Waiting wp) {
        var epoch = wp.epoch();
        var maxHeights = dag.maxView().heights();
        var heights = wp.pu().view().heights();
        for (short creator = 0; creator < heights.length; creator++) {
            var height = heights[creator];
            if (height > maxHeights[creator]) {
                long parentID = id(height, creator, epoch);
                var par = waitingById.get(parentID);
                if (par != null) {
                    wp.incWaiting();
                    par.addChild(wp);
                    log.trace("Waiting: {} for parent: {} on: {}", wp, par, conf.logLabel());
                } else {
                    if (!dag.contains(parentID)) {
                        log.trace("Missing: {} for: {} not found in DAG on: {}", PreUnit.decode(parentID), wp,
                                  conf.logLabel());
                        wp.incMissing();
                        registerMissing(parentID, wp);
                    }
                }
            }
        }
        return maxHeights;
    }

    private void commit(Waiting wpu) {
        try {
            wpu.setState(State.COMMITTED);
            Signed<SignedCommit> sc = commit(wpu.id(), wpu.hash(), conf.pid(), conf.signer(), conf.digestAlgorithm());
            signedCommits.put(sc.hash(), sc.signed());
            log.trace("Committing unit: {} on: {}", wpu, conf.logLabel());
            commit(wpu.hash(), conf.pid());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private boolean decodeParents(Waiting wp) {
        var decoded = dag.decodeParents(wp.pu());
        if (decoded.inError()) {
            switch (decoded.classification()) {
            case CORRECT:
                return true;
            case DUPLICATE_PRE_UNIT:
            case DUPLICATE_UNIT:
            case UNKNOWN_PARENTS:
                return false;
            case AMBIGUOUS_PARENTS:
            case COMPLIANCE_ERROR:
            case DATA_ERROR:
                removeFailed(wp, decoded);
                return false;
            default:
                break;
            }

            if (decoded.classification() != Correctness.DUPLICATE_UNIT) {
                removeFailed(wp, decoded);
            }
            return false;
        }
        var parents = decoded.parents();
        var digests = Stream.of(parents).map(e -> e == null ? null : e.hash()).map(e -> e).toList();
        Digest calculated = Digest.combine(conf.digestAlgorithm(), digests.toArray(new Digest[digests.size()]));
        if (!calculated.equals(wp.pu().view().controlHash())) {
            removeFailed(wp);
            log.debug("Invalid control hash witness: {} parents: {} on: {}", wp, parents, conf.logLabel());
            return false;
        }
        var freeUnit = dag.build(wp.pu(), parents);

        var err = dag.check(freeUnit);
        if (err != null) {
            removeFailed(wp, err);
            log.warn("Failed: {} check: {} on: {}", freeUnit, err, conf.logLabel());
        }
        wp.setDecoded(freeUnit);
        return true;
    }

    /**
     * Answer the bloom filter with the commits the receiver has
     */
    private Biff haveCommits() {
        var n = conf.epochLength() * conf.nProc() * 4;
        var bff = new DigestBloomFilter(Entropy.nextBitsStreamLong(), n, 1.0 / ((double) n * 2.0));
        signedCommits.keySet().forEach(d -> bff.add(d));
        return bff.toBff();
    }

    /**
     * Answer the bloom filter with the prevotes the receiver has
     */
    private Biff havePreVotes() {
        var n = conf.epochLength() * conf.nProc() * 4;
        var bff = new DigestBloomFilter(Entropy.nextBitsStreamLong(), n, 1.0 / ((double) n * 2));
        signedPrevotes.keySet().forEach(d -> bff.add(d));
        return bff.toBff();
    }

    /**
     * Answer the bloom filter with the units the receiver has
     */
    private Biff haveUnits() {
        var n = conf.epochLength() * conf.nProc() * 4;
        var bff = new DigestBloomFilter(Entropy.nextBitsStreamLong(), n, 1.0 / ((double) n * 2));
        waiting.keySet().forEach(d -> bff.add(d));
        dag.have(bff);
        return bff.toBff();
    }

    /**
     * Exclusively lock the state of the receiver
     *
     * @param <T>
     * @param call
     * @return
     */
    private <T> T locked(Callable<T> call) {
        lock.lock();
        try {
            return call.call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Exclusively lock the state of the receiver
     *
     * @param r
     */
    private void locked(Runnable r) {
        lock.lock();
        try {
            r.run();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Update the gossip builder with the missing units filtered by the supplied bloom filter indicating units already
     * known
     */
    private void missing(BloomFilter<Digest> have, Missing.Builder builder) {
        var pus = new TreeMap<Digest, PreUnit_s>();
        dag.missing(have, pus);
        waiting.entrySet()
               .stream()
               .filter(e -> !have.contains(e.getKey()))
               .filter(e -> !failed.contains(e.getKey()))
               .forEach(e -> pus.putIfAbsent(e.getKey(), e.getValue().serialized()));
        pus.values().forEach(pu -> builder.addUnits(pu));
    }

    /**
     * Terminal state. The waiting unit is output to the DAG the receiver maintains.
     *
     * @param wpu
     */
    private void output(Waiting wpu) {
        boolean valid = wpu.height() == 0 || wpu.height() - 1 <= round;
        assert valid : wpu + " is not <= " + round;
        if (!decodeParents(wpu)) {
            return;
        }
        wpu.setState(State.OUTPUT);

        final var decoded = wpu.decoded();
        log.trace("Inserting unit: {} on: {}", decoded, conf.logLabel());

        dag.insert(decoded);

        for (var ch : wpu.children()) {
            ch.decWaiting();
            if (ch.state() == State.WAITING_FOR_PARENTS && ch.parentsOutput()) {
                log.trace("Parents output, committing: {} parent: {} on: {}", ch, wpu, conf.logLabel());
                wpu.setState(State.COMMITTED);
                commit(ch);
            } else {
                log.trace("Continuing to wait for remaining parents: {} on: {}", ch, conf.logLabel());
            }
        }
        remove(wpu);
    }

    private void prevote(Waiting wpu) {
        wpu.setState(State.PREVOTED);
        Signed<SignedPreVote> spv = prevote(wpu.id(), wpu.hash(), conf.pid(), conf.signer(), conf.digestAlgorithm());
        signedPrevotes.put(spv.hash(), spv.signed());
        log.trace("Prevoting unit: {} on: {}", wpu, conf.logLabel());
        prevote(wpu.hash(), conf.pid());
    }

    /**
     * registerMissing registers the fact that the given WaitingPreUnit needs an unknown unit with the given id.
     */
    private void registerMissing(long id, Waiting wp) {
        missing.computeIfAbsent(id, i -> new ArrayList<>()).add(wp);
        log.trace("missing parent: {} for: {} on: {}", PreUnit.decode(id), wp, conf.logLabel());
    }

    private void remove(Waiting wpu) {
        prevotes.remove(wpu.hash());
        commits.remove(wpu.hash());
        waiting.remove(wpu.hash());
        waitingForRound.remove(wpu.hash());
        waitingById.remove(wpu.id());
    }

    private void removeFailed(Waiting wp) {
        wp.setState(State.FAILED);
        log.warn("Failed: {} on: {}", wp, conf.logLabel());
        failed.add(wp.hash());
        remove(wp);
        for (var ch : wp.children()) {
            removeFailed(ch);
        }
    }

    private void removeFailed(Waiting wp, Object failure) {
        wp.setState(State.FAILED);
        log.warn("Failed: {} reason: {} on: {}", wp, failure, conf.logLabel());
        failed.add(wp.hash());
        remove(wp);
        for (var ch : wp.children()) {
            removeFailed(ch);
        }
    }

    /**
     * Provide the missing state from the receiver based on the supplied haves
     */
    private void update(Have have, Missing.Builder builder) {
        final var cbf = BloomFilter.from(have.getHaveCommits());
        signedCommits.entrySet().forEach(e -> {
            if (!cbf.contains(e.getKey())) {
                builder.addCommits(e.getValue());
            }
        });
        final var pbf = BloomFilter.from(have.getHavePreVotes());
        signedPrevotes.entrySet().forEach(e1 -> {
            if (!pbf.contains(e1.getKey())) {
                builder.addPrevotes(e1.getValue());
            }
        });
        final BloomFilter<Digest> pubf = BloomFilter.from(have.getHaveUnits());
        missing(pubf, builder);
    }

    /**
     * Validate signed commit signature.
     * CRITICAL: Fails CLOSED (rejects) when verifiers unavailable.
     * Never accepts signatures when verifiers cannot validate them (fail-safe design).
     */
    private boolean validate(SignedCommit c) {
        if (verifiers == null || verifiers.length == 0) {
            log.warn("Cannot validate commit - verifiers unavailable on: {}", conf.logLabel());
            return false;  // CRITICAL FIX: Fail-closed, not fail-open
        }
        var commit = c.getCommit();
        var source = commit.getSource();
        if (source < 0 || source >= verifiers.length) {
            log.warn("Invalid commit source: {} (verifiers length: {}) on: {}", source, verifiers.length,
                     conf.logLabel());
            return false;
        }
        var verifier = verifiers[source];
        if (verifier == null) {
            log.warn("No verifier for commit source: {} on: {}", source, conf.logLabel());
            return false;
        }
        var signature = JohnHancock.from(c.getSignature());
        final long startVerify = System.nanoTime();
        var valid = verifier.verify(signature, commit.toByteString());
        final long verifyTimeUs = (System.nanoTime() - startVerify) / 1000;
        if (!valid) {
            log.warn("Invalid commit signature from source: {} signer algo: {} verifier: {} on: {}",
                     source, conf.signer().algorithm(), verifier, conf.logLabel());
        } else {
            log.debug("Timing - commit sig verify: {}μs source={} on: {}", verifyTimeUs, source, conf.logLabel());
        }
        return valid;
    }

    /**
     * Validate signed pre-vote signature.
     * CRITICAL: Fails CLOSED (rejects) when verifiers unavailable.
     * Never accepts signatures when verifiers cannot validate them (fail-safe design).
     */
    private boolean validate(SignedPreVote pv) {
        if (verifiers == null || verifiers.length == 0) {
            log.warn("Cannot validate prevote - verifiers unavailable on: {}", conf.logLabel());
            return false;  // CRITICAL FIX: Fail-closed, not fail-open
        }
        var vote = pv.getVote();
        var source = vote.getSource();
        if (source < 0 || source >= verifiers.length) {
            log.warn("Invalid prevote source: {} (verifiers length: {}) on: {}", source, verifiers.length,
                     conf.logLabel());
            return false;
        }
        var verifier = verifiers[source];
        if (verifier == null) {
            log.warn("No verifier for prevote source: {} on: {}", source, conf.logLabel());
            return false;
        }
        var signature = JohnHancock.from(pv.getSignature());
        final long startVerify = System.nanoTime();
        var valid = verifier.verify(signature, vote.toByteString());
        final long verifyTimeUs = (System.nanoTime() - startVerify) / 1000;
        if (!valid) {
            log.warn("Invalid prevote signature from source: {} signer algo: {} verifier: {} on: {}",
                     source, conf.signer().algorithm(), verifier, conf.logLabel());
        } else {
            log.debug("Timing - prevote sig verify: {}μs source={} on: {}", verifyTimeUs, source, conf.logLabel());
        }
        return valid;
    }

    private boolean validateParents(Waiting wp) {
        int count = parentsOnPreviousLevel(wp.pu());
        int minimumTrusted = 2 * threshold;
        boolean result = count > minimumTrusted;
        if (!result) {
            log.error("Failed validation: {} expected: {} found: {} heights: {} on: {}", wp, count, minimumTrusted + 1,
                      wp.pu().view().heights(), conf.logLabel());
        }
        return result;
    }

    /**
     * PROPOSED -> WAITING_ON_ROUND -> PREVOTED -> WAITING_FOR_PARENTS -> COMMITTED -> OUTPUT
     *
     * FAILED can occur at each state transition
     */
    public enum State {
        COMMITTED, FAILED, OUTPUT, PREVOTED, PROPOSED, WAITING_FOR_PARENTS, WAITING_ON_ROUND
    }

    public record Signed<T>(Digest hash, T signed) {
    }
}
