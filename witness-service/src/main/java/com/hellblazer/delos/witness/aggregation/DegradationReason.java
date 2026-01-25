/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

/**
 * Reasons for buffering signatures during graceful degradation.
 * Indicates why normal signature accumulation has been suspended.
 *
 * @author hal.hildebrand
 */
public enum DegradationReason {
    /** Awaiting view change completion before resuming normal accumulation. */
    VIEW_CHANGE_PENDING,

    /** Byzantine member detected, threshold recalculation needed. */
    BYZANTINE_MEMBER_DETECTED,

    /** Suspected network partition, buffering until connectivity restored. */
    NETWORK_PARTITION,

    /** Recovering from degraded state, buffer holds pending signatures. */
    RECOVERY_IN_PROGRESS,

    /** Manually buffered via API for testing or administrative purposes. */
    MANUAL_BUFFER
}
