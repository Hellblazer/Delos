/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.gossip;

import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.witness.receipt.AggregateWitnessReceipt;

/**
 * Broadcaster for propagating witness receipts via Fireflies gossip.
 * <p>
 * Called when witness receipt threshold is achieved to broadcast receipts
 * across the Fireflies overlay for anti-entropy and distributed queries.
 * <p>
 * Implementations:
 * - FirefliesReceiptBroadcaster: Actual gossip via Fireflies View
 * - NoOpReceiptBroadcaster: Placeholder for testing/bootstrap
 * <p>
 * Thread-safety: Implementations must be thread-safe as broadcasts may occur
 * from multiple WitnessReceiptManager threads concurrently.
 *
 * @author hal.hildebrand
 * @since Phase 1A (Fireflies-KERI Integration)
 */
@FunctionalInterface
public interface ReceiptGossipBroadcaster {

    /**
     * Broadcast aggregate receipt to Fireflies overlay.
     * <p>
     * Called when M-of-N threshold is achieved for an event.
     * Converts AggregateWitnessReceipt to GossipableReceipt(s) and broadcasts
     * via Fireflies gossip protocol.
     * <p>
     * Non-blocking: Should queue for async broadcast, not block caller.
     * Failures should be logged but not propagated (best-effort delivery).
     *
     * @param event           Event coordinates being witnessed
     * @param aggregateReceipt Threshold-achieved aggregate receipt
     * @throws NullPointerException if any parameter is null
     */
    void broadcast(EventCoordinates event, AggregateWitnessReceipt aggregateReceipt);

    /**
     * No-op broadcaster for testing or when Fireflies integration disabled.
     * <p>
     * Logs broadcast requests but does not perform actual gossip.
     * Useful for:
     * - Unit testing WitnessReceiptManager without Fireflies dependency
     * - Bootstrap scenarios where gossip not yet configured
     * - Development/debugging
     *
     * @return No-op broadcaster that logs and returns
     */
    static ReceiptGossipBroadcaster noOp() {
        return (event, aggregateReceipt) -> {
            // Intentionally empty - no-op implementation
        };
    }
}
