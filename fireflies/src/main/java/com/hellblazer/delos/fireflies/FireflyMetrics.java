/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * Framework-agnostic metrics interface for Fireflies membership service.
 * <p>
 * Note: This interface uses semantic method names to decouple from metrics implementation.
 * The implementation (currently Dropwizard) will be replaced with Micrometer in Phase 2.
 *
 * @author hal.hildebrand
 */
public interface FireflyMetrics extends EndpointMetrics {

    // === Event Counters (Meters) ===

    void recordAccusation();

    void recordFilteredNote();

    void recordJoin();

    void recordLeave();

    void recordNote();

    void recordShunnedGossip();

    void recordViewChange();

    // === Size Recording (Histograms) - inbound ===

    void recordInboundGatewaySize(int bytes);

    void recordInboundGossipSize(int bytes);

    void recordInboundJoinSize(int bytes);

    void recordInboundRedirectSize(int bytes);

    void recordInboundSeedSize(int bytes);

    void recordInboundUpdateSize(int bytes);

    // === Size Recording (Histograms) - outbound ===

    void recordOutboundGatewaySize(int bytes);

    void recordOutboundGossipSize(int bytes);

    void recordOutboundJoinSize(int bytes);

    void recordOutboundRedirectSize(int bytes);

    void recordOutboundSeedSize(int bytes);

    void recordOutboundUpdateSize(int bytes);

    // === Size Recording (Histograms) - gossip reply/response ===

    void recordGossipReplySize(int bytes);

    void recordGossipResponseSize(int bytes);

    // === Duration Recording (Timers) ===

    void recordEnjoinDuration(long nanos);

    void recordInboundGossipDuration(long nanos);

    void recordInboundJoinDuration(long nanos);

    void recordInboundSeedDuration(long nanos);

    void recordInboundUpdateDuration(long nanos);

    void recordJoinDuration(long nanos);

    void recordOutboundUpdateDuration(long nanos);

    void recordSeedDuration(long nanos);
}
