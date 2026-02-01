/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal.memberships.comm;

import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * Framework-agnostic metrics interface for Ethereal gossip operations.
 *
 * @author hal.hildebrand
 */
public interface EtherealMetrics extends EndpointMetrics {

    /** Record gossip reply message size in bytes */
    void recordGossipReplySize(int bytes);

    /** Record gossip response message size in bytes */
    void recordGossipResponseSize(int bytes);

    /** Record gossip round duration in nanoseconds */
    void recordGossipRoundDuration(long nanos);

    /** Record inbound gossip message size in bytes */
    void recordInboundGossipSize(int bytes);

    /** Record inbound gossip processing duration in nanoseconds */
    void recordInboundGossipDuration(long nanos);

    /** Record inbound update message size in bytes */
    void recordInboundUpdateSize(int bytes);

    /** Record inbound update processing duration in nanoseconds */
    void recordInboundUpdateDuration(long nanos);

    /** Record outbound gossip message size in bytes */
    void recordOutboundGossipSize(int bytes);

    /** Record outbound gossip processing duration in nanoseconds */
    void recordOutboundGossipDuration(long nanos);

    /** Record outbound update message size in bytes */
    void recordOutboundUpdateSize(int bytes);

    /** Record outbound update processing duration in nanoseconds */
    void recordOutboundUpdateDuration(long nanos);
}
