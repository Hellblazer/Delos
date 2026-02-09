/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.consensus;

import java.time.Duration;

/**
 * Abstracts the consensus engine lifecycle and integration. Implementations encapsulate consensus
 * engine creation, gossip integration, and lifecycle management. The data flow (transaction sources
 * and result callbacks) is handled internally by implementations.
 * <p>
 * This interface uses a lifecycle-oriented model rather than request-response. Consensus engines
 * like Ethereal operate via producer-consumer callbacks where the engine pulls data from sources
 * and pushes results via callbacks. This interface wraps that complexity, providing simple
 * lifecycle operations.
 * <p>
 * Thread Safety: Implementations must provide thread-safe lifecycle operations. The start/stop
 * methods may be called from different threads than the consensus callback threads. Implementations
 * must coordinate lifecycle transitions with in-flight callbacks appropriately.
 *
 * @author hal.hildebrand
 * @see ConsensusOracleFactory for creating instances with proper callback wiring
 */
public interface ConsensusOracle {

    /**
     * Start the consensus engine with the specified gossip duration.
     * <p>
     * This method is idempotent - calling start on an already-started oracle has no effect.
     * Implementations must handle concurrent start calls safely.
     *
     * @param gossipDuration the duration for gossip rounds
     * @throws IllegalStateException if the oracle has been stopped and cannot be restarted
     */
    void start(Duration gossipDuration);

    /**
     * Stop the consensus engine, releasing resources and stopping gossip.
     * <p>
     * This method is idempotent - calling stop on an already-stopped oracle has no effect.
     * Implementations must coordinate with in-flight consensus callbacks, allowing them to
     * complete or be cancelled appropriately.
     * <p>
     * Thread Safety: Must be safe to call concurrently with consensus callbacks executing on
     * other threads. The cancellation semantics for pending callbacks are implementation-defined.
     */
    void stop();

    /**
     * Signal completion of consensus operations.
     * <p>
     * This is typically called when the system is shutting down or transitioning to a new view.
     * Implementations should flush any pending consensus state and ensure clean termination.
     */
    void completeIt();

    /**
     * Get the consensus processor for gossip integration.
     * <p>
     * The returned object is the consensus engine's processor that gossip protocols use to
     * coordinate consensus rounds. The type is intentionally Object to avoid exposing
     * consensus engine internals in the interface contract.
     * <p>
     * For Ethereal implementations, this returns the Ethereal instance itself.
     * For mock implementations, this may return a stub processor.
     *
     * @return the consensus processor for gossip integration
     */
    Object processor();
}
