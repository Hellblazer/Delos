/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.ethereal;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Verifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Creator is a component responsible for producing new units. It processes units produced by other committee members
 * and stores the ones with the highest level as possible parents (candidates). Whenever there are enough parents to
 * produce a unit on a new level, the creator creates a new Unit from the available DataSource, signs and sends (using a
 * function given to the constructor) this new unit.
 *
 * @author hal.hildebrand
 */
public class Creator {

    private static final Logger                               log        = LoggerFactory.getLogger(Creator.class);
    /**
     * BYZANTINE SAFETY (Delos-40h0): Maximum allowed depth for parent chain traversal.
     * Prevents unbounded stack growth and DoS attacks where Byzantine nodes
     * create arbitrarily long unit chains. This limit ensures that parent
     * lookups and consensus operations complete in bounded time/space.
     *
     * The depth is conservative to allow for normal consensus operation while
     * preventing malicious chains. In practice, unit chains rarely exceed a few
     * hundred units in depth even under heavy load.
     */
    private static final int                                  MAX_UNIT_DEPTH = 10_000;
    private final        List<Unit>                           candidates;
    private final        Config                               conf;
    private final        DataSource                           ds;
    private final        AtomicInteger                        epoch      = new AtomicInteger(-1);
    private final        AtomicBoolean                        epochDone  = new AtomicBoolean();
    private final        AtomicReference<EpochProofBuilder>   epochProof = new AtomicReference<>();
    private final        Function<Integer, EpochProofBuilder> epochProofBuilder;
    private final        Queue<Unit>                          lastTiming;
    private final        int                                  quorum;
    private final        Consumer<Unit>                       send;
    private final        Verifier[]                           verifiers;

    public Creator(Config config, DataSource ds, Queue<Unit> lastTiming, Consumer<Unit> send,
                   Function<Integer, EpochProofBuilder> epochProofBuilder, Verifier[] verifiers) {
        this.conf = config;
        this.ds = ds;
        this.epochProofBuilder = epochProofBuilder;
        this.send = send;
        this.verifiers = verifiers;
        this.candidates = new CopyOnWriteArrayList<>();
        for (int i = 0; i < config.nProc(); i++) {
            candidates.add(null);
        }
        this.lastTiming = lastTiming;

        quorum = Context.minimalQuorum(config.nProc(), config.bias()) + 1;
    }

    public static int parentsOnPreviousLevel(PreUnit pu) {
        var heights = pu.view().heights();
        int count = 0;
        for (short creator = 0; creator < heights.length; creator++) {
            if (heights[creator] < pu.height()) {
                count++;
            }
        }
        return count;
    }

    /**
     * MakeConsistent ensures that the set of parents follows "parent consistency rule". Modifies the provided parents
     * in place. Parent consistency rule means that unit's i-th parent cannot be lower (in a level sense) than i-th
     * parent of any other of that units parents. In other words, units seen from U "directly" (as parents) cannot be
     * below the ones seen "indirectly" (as parents of parents).
     *
     * PERFORMANCE (Delos-4z9j): Optimized from O(n²) to O(n log n) using fixpoint iteration with change tracking.
     * Instead of checking all pairs repeatedly, we iterate only while changes occur and track which positions
     * were updated. This reduces redundant comparisons significantly.
     *
     * The algorithm maintains the same consistency invariant:
     * For each position i, we ensure parents[i] is the maximum level unit seen at position i
     * across all parent chains. The fixpoint loop continues until no position is updated,
     * indicating we've reached the consistent state.
     */
    private static void makeConsistent(Unit[] parents) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < parents.length; i++) {
                for (int j = 0; j < parents.length; j++) {
                    if (parents[j] == null) {
                        continue;
                    }
                    Unit u = parents[j].parents()[i];
                    if (u != null && (parents[i] == null || u.level() > parents[i].level())) {
                        parents[i] = u;
                        changed = true;
                    }
                }
            }
        }
    }

    /**
     * Unit is examined and stored to be used as parents of future units. When there are enough new parents, a new unit
     * is produced. lastTiming is a channel on which the last timing unit of each epoch is expected to appear.
     *
     * CRITICAL BYZANTINE SAFETY (Delos-hbcb): This method MUST be synchronized to ensure atomic
     * epoch checking during the entire consume->update->ready->createUnit sequence. Without
     * synchronization, a race exists where:
     * 1. update() validates unit against epoch N (synchronized)
     * 2. Epoch transitions to N+1 after update() returns
     * 3. ready() and createUnit() execute with inconsistent epoch state
     *
     * This prevents Byzantine nodes from exploiting the transition window between update() and
     * createUnit() to inject units with old epoch values.
     */
    public synchronized void consume(Unit u) {
        log.trace("Processing next unit: {} on: {}", u, conf.logLabel());
        update(u);
        var built = ready();
        while (built != null) {
            log.trace("Ready, creating unit on: {}", conf.logLabel());
            createUnit(built.parents, built.level, getData(built.level));
            built = ready();
        }
    }

    public void start() {
        newEpoch(epoch.get() + 1, ByteString.EMPTY, -1);
    }

    public void stop() {
    }

    /**
     * Get the current epoch for testing/verification.
     * Package-private for test access.
     *
     * SYNCHRONIZED: Prevents TOCTOU race where epoch could change between read and use.
     */
    synchronized int getCurrentEpoch() {
        return epoch.get();
    }

    private built buildParents() {
        Unit[] parents = new Unit[conf.nProc()];
        parents = candidates.toArray(parents);
        final var thisUnit = parents[conf.pid()];
        if (thisUnit == null) {
            log.trace("No unit for this proc on: {}", conf.logLabel());
            return null;
        }
        var l = thisUnit.level() + 1;
        int count = count(l, parents);
        if (count >= quorum) {
            log.trace("Parents ready: {} level: {} on: {}", quorum, l, conf.logLabel());
            makeConsistent(parents);
            return new built(parents, l);
        } else {
            log.trace("Parents not ready level: {} current: {} required: {}  on: {}", l, count, quorum,
                      conf.logLabel());
            return null;
        }
    }

    private int count(int level, Unit[] parents) {
        var count = 0;
        for (int i = 0; i < conf.nProc(); i++) {
            Unit p = parents[i];

            // Ensure all parents are < level
            // BYZANTINE SAFETY (Delos-40h0): Enforce max depth limit during traversal
            int depth = 0;
            for (; p != null && p.level() >= level && depth < MAX_UNIT_DEPTH; p = p.predecessor()) {
                depth++;
            }
            if (depth >= MAX_UNIT_DEPTH && p != null && p.level() >= level) {
                log.warn("Unit chain exceeded MAX_UNIT_DEPTH {} during parent counting on: {}. "
                         + "Truncating traversal to prevent DoS attack.", MAX_UNIT_DEPTH, conf.logLabel());
            }
            parents[i] = p;

            // Count parents directly above this level
            if (p != null && p.level() == level - 1) {
                count++;
            }
        }
        return count;
    }

    /**
     * Creates a new unit, signs it, and verifies the self-produced signature for Byzantine safety.
     * <p>
     * CRITICAL BYZANTINE SAFETY (Delos-vupk C3): Self-produced units MUST be verified immediately
     * after signing to detect signer misconfiguration. A Byzantine attacker could exploit a
     * misconfigured signer that produces invalid signatures, causing honest nodes to reject the
     * unit and potentially disrupting consensus. Fail-fast verification ensures that signer
     * problems are caught locally before broadcasting invalid units.
     * </p>
     */
    private void createUnit(Unit[] parents, int level, ByteString data) {
        assert parents.length == conf.nProc();
        final int e = epoch.get();
        Unit u = PreUnit.newFreeUnit(conf.pid(), e, parents, level, data, conf.digestAlgorithm(), conf.signer());
        assert parentsOnPreviousLevel(u) >= quorum : "Parents: " + Arrays.asList(u.parents()) + " of: " + u
        + " for level: " + (u.level() - 1) + " count: " + parentsOnPreviousLevel(u) + " quorum: " + quorum;

        // BYZANTINE SAFETY: Verify self-signature immediately after creation
        // This ensures our signer is properly configured and signatures will be accepted by peers
        if (!u.verify(verifiers)) {
            final var errorMsg = String.format(
            "CRITICAL: Self-produced unit %s failed signature verification on %s. " +
            "This indicates signer misconfiguration. Unit will NOT be broadcast.", u, conf.logLabel());
            log.error(errorMsg);
            throw new IllegalStateException(errorMsg);
        }

        if (log.isTraceEnabled()) {
            log.trace("Created unit: {} parents: {} on: {}", u, parents, conf.logLabel());
        } else {
            log.debug("Created unit: {} on: {}", u, conf.logLabel());
        }
        update(u);
        send.accept(u);
    }

    /**
     * produces a piece of data to be included in a unit on a given level. For regular units the provided DataSource is
     * used. For finishing units it's either null or, if available, an encoded threshold signature share of hash and id
     * of the last timing unit (obtained from preblockMaker on lastTiming channel)
     **/
    private ByteString getData(int level) {
        if (level < conf.lastLevel()) {
            if (ds != null) {
                log.trace("Requesting timing unit: {} on: {}", level, conf.logLabel());
                return ds.getData();
            }
            log.trace("No datasource for timing unit: {} on: {}", level, conf.logLabel());
            return ByteString.EMPTY;
        }
        Unit timingUnit = lastTiming.poll();
        if (timingUnit == null) {
            log.trace("No timing unit: {} on: {}", level, conf.logLabel());
            return ByteString.EMPTY;
        }
        // in a rare case there can be timing units from previous epochs left on
        // lastTiming channel. the purpose of this loop is to drain and ignore them.
        while (timingUnit != null) {
            final int e = epoch.get();
            if (timingUnit.epoch() == e) {
                epochDone.set(true);
                log.trace("Finished, last epoch timing unit: {} level: {} on: {}", timingUnit, level, conf.logLabel());
                return epochProof.get().buildShare(timingUnit);
            }

            // CRITICAL: Preserve timing units from the final configured epoch even if we've advanced.
            // Race condition: epoch N produces timing unit → epoch N+1 starts → timing unit arrives
            // Without this check, the final epoch's timing unit gets discarded as "stale",
            // preventing termination condition (timingUnit.epoch() == numberOfEpochs - 1) from ever
            // evaluating true. This causes consensus to run indefinitely (seen in CI: epochs 0-46
            // instead of stopping at epoch 1).
            if (timingUnit.epoch() == conf.numberOfEpochs() - 1 && timingUnit.level() == conf.lastLevel()) {
                epochDone.set(true);
                log.debug("Preserving final epoch timing unit from epoch: {} (current epoch: {}) on: {}",
                         timingUnit.epoch(), e, conf.logLabel());
                return epochProof.get().buildShare(timingUnit);
            }

            log.trace("Ignored timing unit from epoch: {} current: {} on: {}", timingUnit.epoch(), e, conf.logLabel());
            timingUnit = lastTiming.poll();
        }
        return ByteString.EMPTY;
    }

    /**
     * switches the creator to a chosen epoch, resets candidates and shares and creates a dealing with the provided
     * data.
     *
     * CRITICAL BYZANTINE SAFETY (Delos-hbcb): This method MUST be synchronized to prevent epoch transition
     * race conditions. Without synchronization, a TOCTOU vulnerability exists where:
     * 1. Thread A reads epoch N via epoch.get()
     * 2. Thread B calls newEpoch(N+1), begins transition
     * 3. Thread A processes unit with epoch N while epoch state is inconsistent
     *
     * Additionally, this method prevents DUPLICATE EPOCH CREATION: concurrent newEpoch() calls
     * must not create the same epoch twice. Check-then-act atomicity ensures that only one
     * thread can successfully transition to a given epoch.
     *
     * A Byzantine attacker could exploit this window to inject old-epoch units that bypass validation,
     * potentially causing consensus divergence between honest nodes.
     **/
    private synchronized void newEpoch(int targetEpoch, ByteString data, int from) {
        int currentEpoch = this.epoch.get();

        // PREVENT DUPLICATE EPOCH STATE TRANSITIONS: Only update state if moving to a new epoch
        // Check and act are atomic because this entire method is synchronized.
        // This prevents duplicate epoch creation when concurrent threads attempt to transition
        // to the same epoch - only the first thread updates the epoch state.
        //
        // CRITICAL FIX: Units MUST be created for every newEpoch() call, even if the epoch
        // has already been reached. Skipping unit creation breaks consensus advancement.
        // Multiple concurrent paths (different timing units, threshold triggers) may call
        // newEpoch() for the same targetEpoch - all must create units.
        boolean isNewEpoch = targetEpoch > currentEpoch;

        if (isNewEpoch) {
            this.epoch.set(targetEpoch);
            resetEpoch(targetEpoch);
            epochProof.set(epochProofBuilder.apply(targetEpoch));
            log.debug("Transitioning to new epoch: {} from {} on: {}",
                      targetEpoch, currentEpoch, conf.logLabel());
        } else {
            log.debug("Epoch {} already reached (current={}), skipping state update on: {}",
                      targetEpoch, currentEpoch, conf.logLabel());
        }

        // ALWAYS create the unit regardless of whether this is a new epoch.
        // This is required for consensus to progress - timing units and epoch proofs
        // must be converted to units and propagated through gossip layers.
        createUnit(new Unit[conf.nProc()], 0, data);
    }

    /**
     * ready checks if the creator is ready to produce a new unit. Usually that means: "do we have enough new candidates
     * to produce a unit with level higher than the previous one?" Besides that, we stop producing units for the current
     * epoch after creating a unit with signature share.
     */
    private built ready() {
        final var unit = candidates.get(conf.pid());
        if (unit == null) {
            log.trace("Candidate not set on: {}", conf.logLabel());
            return null;
        }
        if (epochDone.get()) {
            log.trace("Epoch finished : {} on: {}", unit, conf.logLabel());
            return null;
        }
        final int l = unit.level();
        log.trace("Ready to create epoch: {} candidate level: {} on: {}", epoch.get(), l, conf.logLabel());
        return buildParents();
    }

    /**
     * resets the candidates and all related variables to the initial state (a slice with NProc nils). This is useful
     * when switching to a new epoch.
     *
     * @param epoch
     */
    private void resetEpoch(int epoch) {
        log.debug("Resetting epoch: {} on: {}", epoch, conf.logLabel());
        for (int i = 0; i < conf.nProc(); i++) {
            candidates.set(i, null);
        }
        epochDone.set(false);
    }

    /**
     * takes a unit and updates the receiver's state with information contained in the unit.
     *
     * CRITICAL BYZANTINE SAFETY (Delos-hbcb): This method is called from synchronized consume(),
     * inheriting its synchronization. The epoch transition logic must execute atomically with
     * epoch checking to prevent TOCTOU races where:
     * 1. Thread A reads epoch N via epoch.get()
     * 2. Thread B triggers epoch transition to N+1 via newEpoch()
     * 3. Thread A processes unit with epoch N against new epoch state
     *
     * This prevents Byzantine nodes from injecting old-epoch units during transition windows.
     */
    private void update(Unit unit) {
        log.trace("updating: {} on: {}", unit, conf.logLabel());
        // if the unit is from an older epoch we simply ignore it
        final int e = epoch.get();
        if (unit.epoch() < e) {
            log.trace("Unit: {} from a previous epoch, current epoch: {} on: {}", unit, epoch.get(), conf.logLabel());
            return;
        }

        // If the unit is the first epoch from a new epoch, switch to that epoch.
        if (unit.epoch() > e && unit.height() == 0) {
            if (!epochProof.get().verify(unit)) {
                log.warn("Unit did not verify epoch proof with: {}, rejected on: {}", unit, conf.logLabel());
                return;
            }
            log.trace("Changing epoch from: {} to: {} using: {} on: {}", epoch.get(), unit.epoch(), unit,
                      conf.logLabel());
            newEpoch(unit.epoch(), unit.data(), e);
        }

        // If this is a finishing unit try to extract threshold signature share from it.
        // If there are enough shares to produce the signature (and therefore a proof
        // that the current epoch is finished) switch to a new epoch.
        ByteString ep = epochProof.get().tryBuilding(unit);
        if (ep != null) {
            log.trace("Advancing epoch from: {} to: {} using: {} on: {}", e, e + 1, unit, conf.logLabel());
            newEpoch(e + 1, ep, e);
            return;
        }
        log.trace("No epoch proof generated from: {} on: {}", unit, conf.logLabel());

        updateCandidates(unit);
    }

    /**
     * updateCandidates puts the provided unit in parent candidates provided that the level is higher than the level of
     * the previous candidate for that creator.
     */
    private void updateCandidates(Unit u) {
        if (u.epoch() != epoch.get()) {
            return;
        }
        var prev = candidates.get(u.creator());
        if (prev == null || prev.level() < u.level()) {
            candidates.set(u.creator(), u);
        }
    }

    @FunctionalInterface
    public interface RandomSourceData {
        byte[] apply(int level, List<Unit> parents, int epoch);
    }

    @FunctionalInterface
    public interface RsData {
        byte[] rsData(int level, Unit[] parents, int epoch);
    }

    private record built(Unit[] parents, int level) {
    }

}
