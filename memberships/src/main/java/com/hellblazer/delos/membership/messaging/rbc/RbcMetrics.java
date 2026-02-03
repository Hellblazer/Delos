/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.messaging.rbc;

import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * Framework-agnostic metrics interface for Reliable Broadcast.
 * <p>
 * Note: This interface uses semantic method names to decouple from metrics implementation.
 * The implementation (currently Dropwizard) will be replaced with Micrometer in Phase 2.
 *
 * @author hal.hildebrand
 */
public interface RbcMetrics extends EndpointMetrics {

    // === Size Recording (Histograms) ===

    void recordGossipReplySize(int bytes);

    void recordGossipResponseSize(int bytes);

    void recordInboundGossipSize(int bytes);

    void recordInboundUpdateSize(int bytes);

    void recordOutboundGossipSize(int bytes);

    void recordOutboundUpdateSize(int bytes);

    // === Duration Recording (Timers) ===

    void recordGossipRoundDuration(long nanos);

    void recordInboundGossipDuration(long nanos);

    void recordInboundUpdateDuration(long nanos);

    void recordOutboundGossipDuration(long nanos);

    void recordOutboundUpdateDuration(long nanos);

    // === Buffer Observability (Delos-xwen) ===

    /**
     * Record current buffer size for capacity monitoring
     */
    void recordBufferSize(int size);

    /**
     * Increment dedup counter when a duplicate message is filtered
     */
    void incrementDedupCount();

    /**
     * Increment verification failure counter
     */
    void incrementVerificationFailure();

    /**
     * Record signature verification duration
     */
    void recordVerificationDuration(long nanos);

    /**
     * Record GC cycle metrics (items freed)
     */
    void recordGcCycle(int itemsFreed);

    /**
     * Record message age when received (for age distribution)
     */
    void recordMessageAge(int age);

    /**
     * Increment rate limit rejection counter (Byzantine defense)
     */
    void incrementRateLimitRejection();
}
