/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.*;
import com.hellblazer.delos.choam.fsm.Combine;
import com.hellblazer.delos.choam.proto.Reconfigure;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.ethereal.Dag;
import com.hellblazer.delos.membership.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static com.hellblazer.delos.choam.Committee.validatorsOf;

/**
 * Coordinates view reconfiguration for CHOAM consensus.
 * Handles committee transitions, view state updates, and memory pressure checks.
 * <p>
 * Extracted from CHOAM.java (Phase 3.3) using explicit dependency injection
 * pattern consistent with Phase 2 extractions.
 * </p>
 * <p>
 * Thread Safety: Acquires viewStateLock during view state updates. All StateHolder
 * access is thread-safe by delegation. The lock is passed as an explicit parameter
 * to limit its accessibility (addressing S4 risk from plan-auditor).
 * </p>
 *
 * @author hal.hildebrand
 */
public class ReconfigurationCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ReconfigurationCoordinator.class);

    private final ViewStateHolder          viewStateHolder;
    private final ReentrantLock            viewStateLock;
    private final CommitteeStateHolder     committeeState;
    private final BlockChainStateHolder    blockChainState;
    private final ControlStateHolder       controlState;
    private final Session                  session;
    private final Parameters               params;
    private final ReconfigurationCallbacks callbacks;
    private final Combine.Transitions      transitions;

    /**
     * Construct a ReconfigurationCoordinator.
     *
     * @param viewStateHolder view state management
     * @param viewStateLock   lock for view state updates (explicit parameter for safety)
     * @param committeeState  committee state management
     * @param blockChainState blockchain state management
     * @param controlState    control state management (for join protocol)
     * @param session         view session management
     * @param params          CHOAM parameters
     * @param transitions     FSM transitions
     * @param callbacks       committee construction callbacks
     */
    public ReconfigurationCoordinator(ViewStateHolder viewStateHolder, ReentrantLock viewStateLock,
                                      CommitteeStateHolder committeeState, BlockChainStateHolder blockChainState,
                                      ControlStateHolder controlState, Session session, Parameters params,
                                      Combine.Transitions transitions, ReconfigurationCallbacks callbacks) {
        this.viewStateHolder = viewStateHolder;
        this.viewStateLock = viewStateLock;
        this.committeeState = committeeState;
        this.blockChainState = blockChainState;
        this.controlState = controlState;
        this.session = session;
        this.params = params;
        this.transitions = transitions;
        this.callbacks = callbacks;
    }

    /**
     * Execute view reconfiguration with memory pressure check and atomic committee transition.
     *
     * @param hash        view identifier hash
     * @param reconfigure reconfiguration proto
     */
    public void reconfigure(Digest hash, Reconfigure reconfigure) {
        // Check memory pressure before proceeding with reconfiguration
        if (isMemoryPressureHigh()) {
            log.error("Rejecting reconfiguration due to high memory pressure on: {}", params.runtime().member().getId());
            throw new IllegalStateException("Memory pressure too high for reconfiguration - heap usage exceeds threshold");
        }

        // Phase 1 (locked): Collect callbacks for deterministic computation
        List<Runnable> callbacks;
        viewStateLock.lock();
        try {
            log.info("Setting next view id: {} on: {}", hash, params.runtime().member().getId());
            viewStateHolder.setNextViewId(hash);

            // Update pending views
            var advanced = viewStateHolder.getPendingViews().advance();
            viewStateHolder.setPendingViews(advanced);
            var pv = advanced.last();
            if (pv != null) {
                params.runtime().context().setContext(pv.context());
            }

            // Collect callbacks for execution outside lock
            callbacks = collectReconfigureCallbacks(hash, reconfigure);
        } finally {
            viewStateLock.unlock();
        }

        // Phase 2 (unlocked): Execute collected callbacks
        // This allows callbacks to acquire other locks without reentrancy risks
        log.debug("Executing reconfigure callbacks on: {}", params.runtime().member().getId());
        for (int i = 0; i < callbacks.size(); i++) {
            try {
                callbacks.get(i).run();
            } catch (Throwable t) {
                if (t instanceof Error) {
                    log.error("Fatal error in callback {} during reconfigure on: {}: {}", i, params.runtime().member().getId(),
                              t.getMessage(), t);
                    throw t; // Fail-fast on Error types (OOM, StackOverflow, etc.)
                }
                log.error("Callback {} execution failed during reconfigure on: {}", i, params.runtime().member().getId(), t);
                // Continue with remaining callbacks for non-fatal exceptions
            }
        }
    }

    /**
     * Check if memory pressure is too high for safe reconfiguration.
     * Uses MemoryMXBean to check heap usage against configured threshold.
     *
     * @return true if heap usage exceeds (1.0 - minFreeMemoryRatio), false otherwise
     */
    public boolean isMemoryPressureHigh() {
        var heapUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var usedRatio = (double) heapUsage.getUsed() / heapUsage.getMax();
        var threshold = 1.0 - params.minFreeMemoryRatio();

        if (usedRatio > threshold) {
            log.warn("Memory pressure detected: {}% heap used (threshold: {}%) on: {}",
                     String.format("%.1f", usedRatio * 100), String.format("%.1f", threshold * 100),
                     params.runtime().member().getId());
            return true;
        }
        return false;
    }

    /**
     * Collect reconfiguration callbacks for atomic committee transition.
     * Callbacks are executed outside the viewStateLock to avoid reentrancy issues.
     *
     * @param hash        view identifier hash
     * @param reconfigure reconfiguration proto
     * @return list of callbacks to execute
     */
    private List<Runnable> collectReconfigureCallbacks(Digest hash, Reconfigure reconfigure) {
        List<Runnable> callbackList = new ArrayList<>();

        // Capture old committee reference for later cleanup
        // NOTE: oldCommittee captured here - remains valid even if current is modified
        final Committee oldCommittee = committeeState.getCommittee();

        // Determine which committee type to create and the associated setup
        var validators = validatorsOf(reconfigure, params.runtime().context(), params.runtime().member().getId(), log);
        final HashedCertifiedBlock h = blockChainState.getHead();
        final var currentView = viewStateHolder.getNext();

        // Callback 1: Rotate view keys
        callbackList.add(() -> {
            log.trace("Rotating view keys on: {}", params.runtime().member().getId());
            transitions.rotateViewKeys();
        });

        // Callback 2: Update view state and session
        callbackList.add(() -> {
            log.trace("Updating view state on: {}", params.runtime().member().getId());
            blockChainState.setView(h);
            session.setView(h);
        });

        // Callback 3: Atomic committee transition (create new, swap, stop old)
        // CRITICAL: This callback must be atomic to avoid race conditions:
        // 1. Create new committee (starts producer)
        // 2. Atomically swap current reference
        // 3. Immediately stop old committee
        // This ensures no window where current points to a stopped producer (NO_COMMITTEE),
        // while minimizing the window where both producers are running.
        callbackList.add(() -> {
            log.trace("Transitioning committee on: {}", params.runtime().member().getId());
            Committee newCommittee = null;

            // Step 1: Create new committee (may throw, old committee remains active if this fails)
            try {
                if (validators.containsKey(params.runtime().member())) {
                    if (Dag.validate(validators.size())) {
                        newCommittee = callbacks.createAssociate(h, validators, (NextView) currentView);
                    } else {
                        log.warn("Reconfiguration to associate failed: {} committee: {} in view: {} on:{}",
                                 validators.size(), hash, committeeState.getCommittee().getClass().getSimpleName(),
                                 params.runtime().member().getId());
                        transitions.fail();
                        return; // Keep old committee active
                    }
                } else {
                    newCommittee = callbacks.createClient(validators, viewStateHolder.getNextViewId());
                }
            } catch (Throwable e) {
                log.error("Failed to create new committee on: {}", params.runtime().member().getId(), e);
                transitions.fail();
                return; // Keep old committee active
            }

            // Step 2: Atomic swap - new committee now handles all transactions
            committeeState.setCommittee(newCommittee);

            // Step 3: Stop old committee immediately after swap
            // Note: oldCommittee can be null during recovery/startup
            if (oldCommittee != null) {
                try {
                    oldCommittee.complete();
                } catch (Throwable e) {
                    log.error("Failed to complete old committee on: {}", params.runtime().member().getId(), e);
                    // Continue - new committee is already active and handling transactions
                }
            } else {
                log.debug("No old committee to complete (recovery scenario) on: {}", params.runtime().member().getId());
            }
        });

        // Callback 4: Log completion
        callbackList.add(() -> {
            if (controlState.endJoinWithCAS()) {
                log.trace("Halting ongoing join on: {}", params.runtime().member().getId());
            }
            log.info("Reconfigured to view: {} committee: {} validators: {} on: {}",
                     hash, committeeState.getCommittee().getClass().getSimpleName(),
                     validators.entrySet().stream()
                                .map(e -> String.format("id: %s key: %s",
                                                        e.getKey().getId(),
                                                        params.digestAlgorithm()
                                                              .digest(e.toString())))
                                .toList(),
                     params.runtime().member().getId());
        });

        return callbackList;
    }
}
