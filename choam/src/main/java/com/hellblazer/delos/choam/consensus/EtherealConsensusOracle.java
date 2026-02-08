/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.consensus;

import com.hellblazer.delos.ethereal.Ethereal;
import com.hellblazer.delos.ethereal.memberships.ChRbcGossip;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapter wrapping Ethereal consensus engine and ChRbcGossip coordinator.
 * Encapsulates the lifecycle of both components and provides a unified interface
 * for consensus operations.
 * <p>
 * Thread Safety: This class is thread-safe. Lifecycle operations (start/stop/completeIt)
 * use atomic CAS operations for idempotency. The underlying Ethereal and ChRbcGossip
 * components provide their own thread safety.
 * <p>
 * Lifecycle: start() must be called before the oracle is operational. stop() gracefully
 * shuts down both gossip and consensus, waiting for in-flight operations to complete.
 *
 * @author hal.hildebrand
 */
public class EtherealConsensusOracle implements ConsensusOracle {
    private final Ethereal      ethereal;
    private final ChRbcGossip   gossip;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * Constructs an EtherealConsensusOracle wrapping the given components.
     *
     * @param ethereal the consensus engine
     * @param gossip   the gossip coordinator
     */
    public EtherealConsensusOracle(Ethereal ethereal, ChRbcGossip gossip) {
        this.ethereal = ethereal;
        this.gossip = gossip;
    }

    @Override
    public void start(Duration gossipDuration) {
        if (!started.compareAndSet(false, true)) {
            return;  // Already started
        }

        ethereal.start();
        gossip.start(gossipDuration);
    }

    @Override
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;  // Already stopped
        }

        // Stop in reverse order of start
        gossip.stop();
        ethereal.stop();
    }

    @Override
    public void completeIt() {
        ethereal.completeIt();
    }

    @Override
    public Object processor() {
        return ethereal.processor();
    }
}
