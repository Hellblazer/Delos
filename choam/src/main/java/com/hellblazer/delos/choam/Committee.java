/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.choam.proto.SubmitResult.Result;
import com.hellblazer.delos.choam.support.BatchVerificationHelper;
import com.hellblazer.delos.choam.support.BatchVerificationMetrics;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.cryptography.Verifier.DefaultVerifier;
import com.hellblazer.delos.ethereal.Dag;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.MockMember;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hellblazer.delos.cryptography.QualifiedBase64.publicKey;
import static io.grpc.Status.ABORTED;

/**
 * @author hal.hildebrand
 */
public interface Committee {

    static Map<Member, Verifier> validatorsOf(Reconfigure reconfigure, Context<Member> context, Digest member,
                                              Logger log) {
        assert Dag.validate(reconfigure.getJoinsCount()) : "Reconfigure joins: %s is not BFT".formatted(
        reconfigure.getJoinsCount());

        final boolean strictValidation = FeatureFlags.VERIFIER_VALIDATION.isEnabled();
        var validators = new HashMap<Member, Verifier>();
        int excluded = 0;

        for (var e : reconfigure.getJoinsList()) {
            var id = new Digest(e.getMember().getVm().getId());
            var m = context.getMember(id);
            Member memberKey = m != null ? m : new MockMember(id);
            var vm = e.getMember().getVm();

            if (vm.hasConsensusKey()) {
                validators.put(memberKey, new DefaultVerifier(publicKey(vm.getConsensusKey())));
            } else if (strictValidation) {
                // Exclude validator without consensus key — no NO_VERIFIER bypass
                log.warn("Validator {} missing consensus key, excluded from committee on: {}",
                         Digest.from(vm.getId()), member);
                excluded++;
            } else {
                validators.put(memberKey, Verifier.NO_VERIFIER);
            }
        }

        if (excluded > 0) {
            log.warn("Excluded {} validators missing consensus keys ({} remaining of {} total) on: {}",
                     excluded, validators.size(), reconfigure.getJoinsCount(), member);
            if (!Dag.validate(validators.size())) {
                throw new IllegalStateException(
                    String.format("Insufficient BFT validators: %d remaining of %d after excluding %d missing keys on: %s",
                                 validators.size(), reconfigure.getJoinsCount(), excluded, member));
            }
        }

        assert !validators.isEmpty() : "No validators in this reconfiguration of: " + context.getId();
        return validators;
    }

    /**
     * Create a view based on the cut of the supplied hash across the rings of the base context
     */
    static Context<Member> viewFor(Digest hash, Context<? super Member> baseContext) {
        Set<Member> successors = (Set<Member>) baseContext.bftSubset(hash);
        var newView = new StaticContext<>(hash, baseContext.getProbabilityByzantine(), 3, successors,
                                          baseContext.getEpsilon(), successors.size());
        return newView;
    }

    void accept(HashedCertifiedBlock next);

    default void assemble(Assemble assemble) {
    }

    void complete();

    boolean isMember();

    default void join(SignedViewMember nextView, Digest from) {
        log().trace("Error joining by: {} view: {} diadem: {} invalid committee: {} on: {}", from,
                    Digest.from(nextView.getVm().getView()), Digest.from(nextView.getVm().getView()),
                    this.getClass().getSimpleName(), params().member().getId());
        throw new StatusRuntimeException(ABORTED);
    }

    Logger log();

    void nextView(Digest diadem, Context<Member> pendingView);

    Parameters params();

    default void regenerate() {
        throw new IllegalStateException("Should not be called on this implementation");
    }

    default SubmitResult submit(Transaction request) {
        log().debug("Cannot submit txn, inactive committee: {} on: {}", getClass().getSimpleName(),
                    params().member().getId());
        return SubmitResult.newBuilder().setResult(Result.INACTIVE).build();
    }

    default SubmitResult submitTxn(Transaction transaction) {
        log().debug("Cannot process txn, inactive committee: {} on: {}", getClass().getSimpleName(),
                    params().member().getId());
        return SubmitResult.newBuilder().setResult(Result.UNAVAILABLE).build();
    }

    boolean validate(HashedCertifiedBlock hb);

    default boolean validate(HashedCertifiedBlock hb, Certification c, Map<Member, Verifier> validators) {
        Parameters params = params();
        Digest wid = new Digest(c.getId());
        var witness = params.context().getMember(wid);
        if (witness == null) {
            log().debug("Witness does not exist: {} in: {} validating: {} on: {}", wid, params.context().getId(), hb,
                        params.member().getId());
            return false;
        }
        var verify = validators.get(witness);
        if (verify == null) {
            log().debug("Witness: {} is not a validator for: {} validating: {} on: {}", wid, params.context().getId(),
                        hb, params.member().getId());
            return false;
        }

        final boolean verified = verify.verify(new JohnHancock(c.getSignature()), hb.block.getHeader().toByteString());
        if (!verified) {
            log().debug("Failed verification: {} hash: {} height: {} using: {} : {} on: {}", hb.block.getBodyCase(),
                        hb.hash, hb.height(), witness.getId(), verify, params.member().getId());
        } else if (log().isTraceEnabled()) {
            log().trace("Verified: {} hash: {} height: {} using: {} : {} on: {}", hb.block.getBodyCase(), hb.hash,
                        hb.height(), witness.getId(), verify, params.member().getId());
        }
        return verified;
    }

    default boolean validate(HashedCertifiedBlock hb, Map<Member, Verifier> validators) {
        Parameters params = params();
        var certifications = hb.certifiedBlock.getCertificationsList();
        log().trace("Validating block: {} hash: {} height: {} certs: {} on: {}", hb.block.getBodyCase(), hb.hash,
                    hb.height(),
                    certifications.stream().map(c -> new Digest(c.getId())).toList(),
                    params.member().getId());

        // Use batch verification for BLS signatures where possible
        // Get metrics from params if available for proper metrics accumulation
        var metrics = params.metrics() != null
                      ? params.metrics().batchVerificationMetrics()
                      : BatchVerificationMetrics.NOOP;
        var helper = new BatchVerificationHelper(BLSProvider.getDefault(), metrics);
        byte[] message = hb.block.getHeader().toByteString().toByteArray();
        int valid = helper.verifyCertifications(message, certifications, validators, params.member().getId());

        final int toleranceLevel = params.context().toleranceLevel();
        log().trace("Validate: {} height: {} count: {} needed: {} on: {}", hb.hash, hb.height(), valid, toleranceLevel,
                    params.member().getId());
        return valid > toleranceLevel;
    }

    default boolean validateRegeneration(HashedCertifiedBlock hb) {
        if (!Objects.requireNonNull(hb.block).hasGenesis()) {
            return false;
        }
        // During Genesis, block certifications are signed with member identity keys
        // (not consensus keys) because consensus keys haven't been exchanged yet.
        // Use member identity verifiers for validation.
        var reconfigure = hb.block.getGenesis().getInitialView();
        try {
            var validators = identityValidatorsOf(reconfigure, params().context(), params().member().getId(), log());
            return !validators.isEmpty() && validate(hb, validators);
        } catch (IllegalStateException e) {
            // VERIFIER_VALIDATION is enabled and a member is missing from context.
            // This indicates a Byzantine or malformed genesis block.
            log().error("Genesis validation rejected: member missing from context: {} on: {}",
                        e.getMessage(), params().member().getId());
            return false;
        }
    }

    /**
     * Create validators using member identity keys (for Genesis validation).
     * During Genesis, blocks are signed with member identity keys, not consensus keys.
     */
    static Map<Member, Verifier> identityValidatorsOf(Reconfigure reconfigure, Context<Member> context, Digest member,
                                                       Logger log) {
        assert Dag.validate(reconfigure.getJoinsCount()) : "Reconfigure joins: %s is not BFT".formatted(
        reconfigure.getJoinsCount());

        var validators = new HashMap<Member, Verifier>();
        int excluded = 0;

        for (var e : reconfigure.getJoinsList()) {
            var id = new Digest(e.getMember().getVm().getId());
            var m = context.getMember(id);

            if (m == null) {
                if (FeatureFlags.VERIFIER_VALIDATION.isEnabled()) {
                    // Exclude member without identity verifier — no NO_VERIFIER bypass
                    log.warn("Member {} missing identity verifier, excluded on: {}", id, member);
                    excluded++;
                } else {
                    validators.put(new MockMember(id), Verifier.NO_VERIFIER);
                }
            } else {
                validators.put(m, (Verifier) m);
            }
        }

        if (excluded > 0) {
            log.warn("Excluded {} members missing identity verifiers ({} remaining of {} total) on: {}",
                     excluded, validators.size(), reconfigure.getJoinsCount(), member);
            if (!Dag.validate(validators.size())) {
                throw new IllegalStateException(
                    String.format("Insufficient BFT validators: %d remaining of %d after excluding %d missing identity on: %s",
                                 validators.size(), reconfigure.getJoinsCount(), excluded, member));
            }
        }

        assert !validators.isEmpty() : "No validators in this reconfiguration of: " + context.getId();
        return validators;
    }

}
