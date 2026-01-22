/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

/**
 * Types of Byzantine anomaly detectors.
 *
 * @author hal.hildebrand
 * @since 1.0 (Phase 1C-3-D)
 */
public enum DetectorType {
    /**
     * Signature anomaly detector (invalid signatures, forgery attempts).
     */
    SIGNATURE,

    /**
     * Timing anomaly detector (excessive receipt latency).
     */
    TIMING,

    /**
     * Rate anomaly detector (abnormal receipt rates, flooding).
     */
    RATE
}
