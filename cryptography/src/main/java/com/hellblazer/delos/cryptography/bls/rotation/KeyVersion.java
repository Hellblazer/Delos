/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

import com.hellblazer.delos.cryptography.bls.ProofOfPossession;

import java.time.Instant;
import java.util.Objects;

/**
 * Metadata for a versioned BLS key.
 * <p>
 * Tracks key lifecycle: GENERATED → ACTIVE → DEPRECATED → ARCHIVED
 * <p>
 * Each key version includes:
 * - Version number (monotonically increasing)
 * - Creation timestamp
 * - Optional expiration timestamp
 * - Lifecycle status
 * - Rotation operation ID (for audit)
 * - Proof of Possession (for KERI verification)
 * <p>
 * Phase 1C-3-A-1: Core BLS key rotation mechanism
 *
 * @param versionNumber Sequence number (1, 2, 3, ...) for this key
 * @param createdAt     When the key was generated
 * @param expiresAt     Expiration timestamp (null if no expiration)
 * @param status        Current lifecycle status
 * @param rotationId    Unique identifier for the rotation operation
 * @param popProof      Proof of Possession for KERI verification
 * @author hal.hildebrand
 */
public record KeyVersion(
    int versionNumber,
    Instant createdAt,
    Instant expiresAt,
    KeyStatus status,
    String rotationId,
    ProofOfPossession popProof
) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException     if required fields are null
     * @throws IllegalArgumentException if expiresAt is before createdAt
     */
    public KeyVersion {
        Objects.requireNonNull(createdAt, "createdAt required");
        Objects.requireNonNull(status, "status required");
        Objects.requireNonNull(rotationId, "rotationId required");
        Objects.requireNonNull(popProof, "popProof required");

        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }

    /**
     * Check if this key version has expired.
     *
     * @param now Current timestamp
     * @return true if key has expired, false otherwise
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    /**
     * Check if this key version is currently active.
     * A key is active if its status is ACTIVE and it has not expired.
     *
     * @param now Current timestamp
     * @return true if key is active and not expired, false otherwise
     */
    public boolean isActive(Instant now) {
        return status == KeyStatus.ACTIVE && !isExpired(now);
    }
}
