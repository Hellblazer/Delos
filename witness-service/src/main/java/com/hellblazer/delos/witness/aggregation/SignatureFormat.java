/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.aggregation;

/**
 * Signature format types supported by the witness service. Sealed enum restricts to two concrete formats:
 * legacy Ed25519 and modern BLS12-381 aggregatable signatures.
 *
 * @author hal.hildebrand
 */
public enum SignatureFormat {
    /**
     * Legacy Ed25519 signatures - non-aggregatable, must be validated individually.
     */
    ED25519,

    /**
     * BLS12-381 pairing-based signatures - supports efficient aggregation and batch verification.
     */
    BLS_12_381
}
