/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Current state of BLS key rotation.
 * <p>
 * Tracks all key versions and their lifecycle. Thread-safe using modern
 * concurrency utilities (ReentrantReadWriteLock instead of synchronized).
 * <p>
 * Phase 1C-3-A-1: Core BLS key rotation mechanism
 *
 * @author hal.hildebrand
 */
public class KeyRotationState {

    private final SortedMap<Integer, KeyVersion> keyVersionsMap = new TreeMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Optional<Integer> activeVersionNumber = Optional.empty();
    private Optional<Integer> previousVersionNumber = Optional.empty();

    /**
     * Add a new key version.
     *
     * @param version Key version to add
     * @throws IllegalArgumentException if version number already exists
     */
    public void addKeyVersion(KeyVersion version) {
        lock.writeLock().lock();
        try {
            if (keyVersionsMap.containsKey(version.versionNumber())) {
                throw new IllegalArgumentException(
                    "Version " + version.versionNumber() + " already exists"
                );
            }
            keyVersionsMap.put(version.versionNumber(), version);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Update an existing key version.
     * Used to transition keys through lifecycle states.
     *
     * @param version Updated key version
     * @throws IllegalArgumentException if version does not exist
     */
    public void updateKeyVersion(KeyVersion version) {
        lock.writeLock().lock();
        try {
            if (!keyVersionsMap.containsKey(version.versionNumber())) {
                throw new IllegalArgumentException(
                    "Version " + version.versionNumber() + " does not exist"
                );
            }
            keyVersionsMap.put(version.versionNumber(), version);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Activate a key version.
     * Previous active key becomes the previous key.
     *
     * @param versionNumber Version number to activate
     * @throws IllegalArgumentException if version not found
     */
    public void activateKeyVersion(int versionNumber) {
        lock.writeLock().lock();
        try {
            var version = keyVersionsMap.get(versionNumber);
            if (version == null) {
                throw new IllegalArgumentException("Version " + versionNumber + " not found");
            }

            previousVersionNumber = activeVersionNumber;
            activeVersionNumber = Optional.of(versionNumber);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get currently active key version.
     *
     * @return Active key version if one is activated, empty otherwise
     */
    public Optional<KeyVersion> getActiveKey() {
        lock.readLock().lock();
        try {
            return activeVersionNumber.map(keyVersionsMap::get);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get previous key version (during grace period).
     *
     * @return Previous key version if exists, empty otherwise
     */
    public Optional<KeyVersion> getPreviousKey() {
        lock.readLock().lock();
        try {
            return previousVersionNumber.map(keyVersionsMap::get);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get all valid keys for verification (active + deprecated within grace period).
     *
     * @param now         Current timestamp
     * @param gracePeriod Grace period duration
     * @return Map of version number to key version for all valid keys
     */
    public Map<Integer, KeyVersion> getValidKeys(Instant now, Duration gracePeriod) {
        lock.readLock().lock();
        try {
            var result = new TreeMap<Integer, KeyVersion>();

            for (var entry : keyVersionsMap.entrySet()) {
                var version = entry.getValue();

                // Include if active or within grace period
                if (version.status() == KeyStatus.ACTIVE) {
                    result.put(entry.getKey(), version);
                } else if (version.status() == KeyStatus.DEPRECATED) {
                    var deprecatedAt = version.expiresAt();
                    if (deprecatedAt != null) {
                        var graceEnd = deprecatedAt.plus(gracePeriod);
                        if (now.isBefore(graceEnd)) {
                            result.put(entry.getKey(), version);
                        }
                    }
                }
            }

            return Collections.unmodifiableMap(result);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Archive old key versions.
     * Keeps only the most recent N versions.
     *
     * @param keepVersions Number of versions to keep
     */
    public void archiveOldVersions(int keepVersions) {
        lock.writeLock().lock();
        try {
            int versionCount = keyVersionsMap.size();
            if (versionCount <= keepVersions) return;

            int toArchive = versionCount - keepVersions;
            int archived = 0;

            for (var entry : keyVersionsMap.entrySet()) {
                if (archived >= toArchive) break;

                var version = entry.getValue();
                if (version.status() == KeyStatus.DEPRECATED) {
                    // Update to archived
                    var archivedVersion = new KeyVersion(
                        version.versionNumber(),
                        version.createdAt(),
                        version.expiresAt(),
                        KeyStatus.ARCHIVED,
                        version.rotationId(),
                        version.popProof()
                    );
                    keyVersionsMap.put(entry.getKey(), archivedVersion);
                    archived++;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get read-only view of all key versions.
     *
     * @return Unmodifiable sorted map of key versions
     */
    public SortedMap<Integer, KeyVersion> keyVersions() {
        lock.readLock().lock();
        try {
            return Collections.unmodifiableSortedMap(new TreeMap<>(keyVersionsMap));
        } finally {
            lock.readLock().unlock();
        }
    }
}
