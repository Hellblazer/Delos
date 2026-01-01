/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of KeyRevocationRegistry using thread-safe collections
 *
 * @author hal.hildebrand
 */
public class MemKeyRevocationRegistry implements KeyRevocationRegistry {

    private final Set<EventCoordinates> revokedKeys = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isRevoked(EventCoordinates coordinates) {
        return revokedKeys.contains(coordinates);
    }

    @Override
    public void revoke(EventCoordinates coordinates) {
        revokedKeys.add(coordinates);
    }

    @Override
    public void unrevoke(EventCoordinates coordinates) {
        revokedKeys.remove(coordinates);
    }

    /**
     * Clear all revocation entries
     */
    public void clear() {
        revokedKeys.clear();
    }

    /**
     * Get the number of revoked keys
     */
    public int size() {
        return revokedKeys.size();
    }
}
