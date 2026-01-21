/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

/**
 * Types of Byzantine anomalies detected.
 *
 * @author hal.hildebrand
 */
public enum AnomalyType {
    /**
     * Invalid signature detected.
     */
    SIGNATURE_INVALID,

    /**
     * Signature forgery attempt detected.
     */
    SIGNATURE_FORGERY,

    /**
     * Equivocation detected (signing conflicting values).
     */
    EQUIVOCATION,

    /**
     * Timing anomaly detected (coordinated late submissions).
     */
    TIMING_ANOMALY,

    /**
     * Rate anomaly detected (unusual failure patterns).
     */
    RATE_ANOMALY,

    /**
     * Coordinated attack pattern detected.
     */
    COORDINATED_ATTACK
}
