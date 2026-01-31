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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Logging-only implementation of ReceiptGossipBroadcaster for testing.
 * <p>
 * Logs broadcast requests but does not perform actual Fireflies gossip.
 * Useful for:
 * - Unit testing receipt broadcast integration
 * - Development environments without full Fireflies setup
 * - Verifying broadcast triggers occur at correct times
 * <p>
 * Thread-safe: Logging is thread-safe.
 *
 * @author hal.hildebrand
 * @since Phase 1A (Fireflies-KERI Integration)
 */
public class LoggingReceiptBroadcaster implements ReceiptGossipBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(LoggingReceiptBroadcaster.class);

    private volatile long broadcastCount = 0;

    @Override
    public void broadcast(EventCoordinates event, AggregateWitnessReceipt aggregateReceipt) {
        Objects.requireNonNull(event, "event required");
        Objects.requireNonNull(aggregateReceipt, "aggregateReceipt required");

        broadcastCount++;

        log.info("Broadcasting receipt #{} for event: {} (signatures: {}, format: {})",
            broadcastCount,
            event,
            aggregateReceipt.signerIndices().size(),
            aggregateReceipt.format()
        );

        if (log.isDebugEnabled()) {
            log.debug("Receipt details: epoch={}, timestamp={}, signerIndices={}",
                aggregateReceipt.epoch(),
                aggregateReceipt.timestamp(),
                aggregateReceipt.signerIndices()
            );
        }
    }

    /**
     * Get count of broadcast calls (for testing).
     *
     * @return Total number of broadcast() calls
     */
    public long getBroadcastCount() {
        return broadcastCount;
    }

    /**
     * Reset broadcast counter (for testing).
     */
    public void resetCount() {
        broadcastCount = 0;
    }
}
