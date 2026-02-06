/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.fsm.Combine;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.membership.Member;
import org.slf4j.Logger;

/**
 * FSM Combiner for CHOAM consensus - handles block combination and synchronization
 *
 * @author hal.hildebrand
 */
public class CombinerFSM implements Combine {
    private static final String AWAIT_REGEN = "Await Regeneration";
    private static final String AWAIT_SYNC  = "Await Synchronization";

    private final CHOAM  choam;
    private final Logger log;

    public CombinerFSM(CHOAM choam, Logger log) {
        this.choam = choam;
        this.log = log;
    }

    @Override
    public void anchor() {
        HashedCertifiedBlock anchor = choam.blockChainState().pollPending();
        if (anchor == null) {
            return;
        }
        var pendingView = choam.getPendingViews().last();
        var pending = pendingView == null ? null : pendingView.context();
        if (pending != null && choam.blockChainState().getPendingSize() >= pending.majority()) {
            log.info("Synchronizing from anchor: {} cardinality: {} on: {}", anchor.hash, choam.blockChainState().getPendingSize(),
                     choam.params().member().getId());
            choam.transitionsBootstrap(anchor);
        } else {
            // Re-queue the anchor block so it is not lost; more blocks may arrive later
            choam.blockChainState().addPending(anchor);
        }
    }

    @Override
    public void awaitRegeneration() {
        if (!choam.controlState().isStarted()) {
            return;
        }
        final HashedCertifiedBlock g = choam.blockChainState().getGenesis();
        if (g != null) {
            return;
        }
        HashedCertifiedBlock anchor = choam.blockChainState().pollPending();
        if (anchor != null) {
            log.info("Synchronizing from anchor: {} on: {}", anchor.hash, choam.params().member().getId());
            choam.transitionsBootstrap(anchor);
            return;
        }
        log.info("No anchor to synchronize, waiting: {} cycles on: {}", choam.params().synchronizationCycles(),
                 choam.params().member().getId());
        choam.roundScheduler().schedule(AWAIT_REGEN, () -> {
            choam.cancelSynchronization();
            awaitRegeneration();
        }, choam.params().regenerationCycles());
    }

    @Override
    public void awaitSynchronization() {
        if (!choam.controlState().isStarted()) {
            return;
        }
        HashedCertifiedBlock anchor = choam.blockChainState().pollPending();
        if (anchor != null) {
            log.info("Synchronizing from anchor: {} on: {}", anchor.hash, choam.params().member().getId());
            choam.asyncOperationState().resetSyncAttempts();  // Reset attempts on successful anchor acquisition
            choam.transitionsBootstrap(anchor);
            return;
        }
        choam.roundScheduler().schedule(AWAIT_SYNC, () -> {
            log.trace("Synchronization failed on: {}", choam.params().member().getId());
            try {
                synchronizationFailed();
            } catch (IllegalStateException e) {
                final var c = choam.committeeState().getCommittee();
                Context<Member> memberContext = choam.context();
                int attempts = choam.asyncOperationState().incrementSyncAttempts();
                log.debug(
                "Synchronization quorum formation failed: {}, members: {} desired: {} required: {}, no anchor to recover from: {} attempt: {} on: {}",
                e.getMessage(), memberContext.size(), choam.context().getRingCount(), choam.params().majority(),
                c == null ? "<no formation>" : c.getClass().getSimpleName(), attempts, choam.params().member().getId());

                if (attempts >= choam.params().maxSyncAttempts()) {
                    log.warn("Synchronization circuit breaker triggered: max attempts ({}) exceeded on: {}",
                             choam.params().maxSyncAttempts(), choam.params().member().getId());
                    return;
                }
                awaitSynchronization();
            }
        }, choam.params().synchronizationCycles());
    }

    @Override
    public void cancelTimer(String timer) {
        choam.roundScheduler().cancel(timer);
    }

    @Override
    public void combine() {
        log.trace("Starting block processor for: {} on: {}", choam.context().getId(), choam.params().member().getId());
        choam.blockProcessor().start();
    }

    @Override
    public void fail() {
        log.info("Failed!  Shutting down on: {}", choam.params().member().getId());
        choam.stop();
        choam.params().onFailure().complete(null);
    }

    @Override
    public void recover(HashedCertifiedBlock anchor) {
        choam.committeeState().setCommittee(new GenesisFormation(choam, log));
        choam.asyncOperationState().resetSyncAttempts();  // Reset attempts on successful recovery
        log.info("Anchor discovered: {} hash: {} height: {} committee: {} on: {}", anchor.block.getBodyCase(),
                 anchor.hash, anchor.height(), choam.committeeState().getCommittee().getClass().getSimpleName(), choam.params().member().getId());
        choam.recover(anchor);
    }

    @Override
    public void regenerate() {
        choam.committeeState().getCommittee().regenerate();
    }

    @Override
    public void rotateViewKeys() {
        choam.rotateViewKeys();
    }

    private void synchronizationFailed() {
        choam.cancelSynchronization();
        Context<Member> memberContext = choam.context();
        var activeCount = memberContext.size();
        var count = choam.context().getRingCount();
        if (choam.params().generateGenesis() && activeCount >= choam.context().getRingCount()) {
            if (choam.committeeState().getCommittee() == null && choam.committeeState().compareAndSetCommittee(null, new GenesisFormation(choam, log))) {
                log.info(
                "Quorum achieved, triggering regeneration. members: {} required: {} forming Genesis committee on: {}",
                activeCount, count, choam.params().member().getId());
                choam.transitionsRegenerate();
            } else {
                log.info("Quorum achieved, members: {} required: {} existing committee: {} on: {}", activeCount,
                         count, choam.committeeState().getCommittee().getClass().getSimpleName(), choam.params().member().getId());
            }
        } else {
            final var c = choam.committeeState().getCommittee();
            log.trace("Synchronization failed; members: {}, no anchor to recover from: {} on: {}", activeCount,
                      c == null ? "<no committee>" : c.getClass().getSimpleName(), choam.params().member().getId());
            awaitSynchronization();
        }
    }
}
