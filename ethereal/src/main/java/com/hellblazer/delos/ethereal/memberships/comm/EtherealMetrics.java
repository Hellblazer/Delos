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
 * Framework-agnostic metrics interface for Ethereal consensus operations.
 * <p>
 * Provides comprehensive metrics for performance baseline and optimization:
 * - Gossip protocol metrics (message sizes, durations)
 * - Lock contention metrics (Adder lock hold/wait times)
 * - Unit processing metrics (DAG insert latency, backlog)
 * - Throughput metrics (consensus rounds, transaction latency)
 *
 * @author hal.hildebrand
 */
public interface EtherealMetrics extends EndpointMetrics {

    // ==================== Gossip Protocol Metrics ====================

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

    // ==================== Lock Contention Metrics ====================

    /** Record Adder lock hold duration in nanoseconds */
    default void recordAdderLockHoldDuration(long nanos) {}

    /** Record Adder lock wait duration in nanoseconds (time waiting to acquire) */
    default void recordAdderLockWaitDuration(long nanos) {}

    /** Increment count of lock contention events */
    default void incrementLockContentionCount() {}

    // ==================== Unit Processing Metrics ====================

    /** Record DAG insert operation duration in nanoseconds */
    default void recordDagInsertDuration(long nanos) {}

    /** Record number of units processed in a batch */
    default void recordUnitsProcessed(int count) {}

    /** Record current backlog size (waiting units) */
    default void recordBacklogSize(int size) {}

    /** Record a unit being proposed */
    default void incrementUnitsProposed() {}

    /** Record a unit being committed */
    default void incrementUnitsCommitted() {}

    /** Record a unit being output to DAG */
    default void incrementUnitsOutput() {}

    // ==================== Throughput Metrics ====================

    /** Record consensus round completion duration in nanoseconds */
    default void recordConsensusRoundDuration(long nanos) {}

    /** Record end-to-end transaction latency in nanoseconds */
    default void recordTransactionLatency(long nanos) {}

    /** Increment count of completed consensus rounds */
    default void incrementConsensusRounds() {}
}
