/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy;

/**
 * Registry for tracking revoked key states. Revocation differs from rotation
 * in that revoked keys are explicitly marked as compromised and should not
 * be used for verification or signing, even for historical operations.
 *
 * @author hal.hildebrand
 */
public interface KeyRevocationRegistry {

    /**
     * Check if a key state at the given coordinates has been revoked
     *
     * @param coordinates the event coordinates to check
     * @return true if the key state is revoked, false otherwise
     */
    boolean isRevoked(EventCoordinates coordinates);

    /**
     * Mark a key state at the given coordinates as revoked
     *
     * @param coordinates the event coordinates to revoke
     */
    void revoke(EventCoordinates coordinates);

    /**
     * Remove a revocation entry (un-revoke a key state)
     *
     * @param coordinates the event coordinates to un-revoke
     */
    void unrevoke(EventCoordinates coordinates);
}
