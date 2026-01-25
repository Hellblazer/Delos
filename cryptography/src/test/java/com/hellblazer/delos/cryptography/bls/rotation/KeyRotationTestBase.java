/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

import com.hellblazer.delos.cryptography.bls.BLSProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Base class for BLS key rotation tests.
 * Provides common test infrastructure, helpers, and fixtures.
 * <p>
 * Phase 1C-3-A-3: Key rotation testing infrastructure
 *
 * @author hal.hildebrand
 */
abstract class KeyRotationTestBase {

    // Shared infrastructure
    protected static final BLSProvider PROVIDER = BLSProvider.getDefault();
    protected static final Random RANDOM = new Random(42);

    // Byzantine fault tolerance parameters
    protected static final int COMMITTEE_SIZE_SMALL = 4;  // f=1
    protected static final int COMMITTEE_SIZE_MEDIUM = 7; // f=2
    protected static final int COMMITTEE_SIZE_LARGE = 13; // f=4

    protected static final Duration GRACE_PERIOD_SHORT = Duration.ofSeconds(30);
    protected static final Duration GRACE_PERIOD_MEDIUM = Duration.ofMinutes(5);
    protected static final Duration GRACE_PERIOD_LONG = Duration.ofMinutes(30);

    /**
     * Create a BLS key rotation manager with default settings.
     *
     * @param memberId Member identifier
     * @return Configured manager
     */
    protected BLSKeyRotationManager createManager(String memberId) {
        return createManager(memberId, Duration.ofDays(30), COMMITTEE_SIZE_MEDIUM);
    }

    /**
     * Create a BLS key rotation manager with custom settings.
     *
     * @param memberId         Member identifier
     * @param rotationInterval Rotation interval
     * @param committeeSize    Total committee size
     * @return Configured manager
     */
    protected BLSKeyRotationManager createManager(
        String memberId,
        Duration rotationInterval,
        int committeeSize
    ) {
        var policy = new TimeBasedRotationPolicy(rotationInterval);
        var f = (committeeSize - 1) / 3; // Byzantine tolerance
        return new BLSKeyRotationManager(PROVIDER, memberId, policy, f);
    }

    /**
     * Create a committee of rotation managers.
     *
     * @param size Committee size
     * @return List of managers (one per member)
     */
    protected List<BLSKeyRotationManager> createCommittee(int size) {
        return createCommittee(size, Duration.ofDays(30));
    }

    /**
     * Create a committee of rotation managers with custom rotation interval.
     *
     * @param size             Committee size
     * @param rotationInterval Rotation interval
     * @return List of managers (one per member)
     */
    protected List<BLSKeyRotationManager> createCommittee(int size, Duration rotationInterval) {
        var committee = new ArrayList<BLSKeyRotationManager>(size);
        for (int i = 0; i < size; i++) {
            committee.add(createManager("member-" + i, rotationInterval, size));
        }
        return committee;
    }

    /**
     * Initiate rotation on all committee members.
     *
     * @param committee Committee managers
     * @return List of rotation IDs (one per member)
     */
    protected List<String> initiateRotationOnCommittee(List<BLSKeyRotationManager> committee) {
        var rotationIds = new ArrayList<String>(committee.size());
        for (var manager : committee) {
            var keyPair = manager.generateNewKeyPair();
            var rotationId = manager.initiateRotation(keyPair);
            rotationIds.add(rotationId);
        }
        return rotationIds;
    }

    /**
     * Activate rotated keys on all committee members.
     *
     * @param committee     Committee managers
     * @param versionNumber Version to activate
     */
    protected void activateOnCommittee(List<BLSKeyRotationManager> committee, int versionNumber) {
        for (var manager : committee) {
            manager.activateKeyVersion(versionNumber);
        }
    }

    /**
     * Create a test instant at a specific offset from now.
     *
     * @param offset Duration offset (positive = future, negative = past)
     * @return Instant offset from now
     */
    protected Instant timeOffset(Duration offset) {
        return Instant.now().plus(offset);
    }

    /**
     * Calculate Byzantine quorum for committee size.
     *
     * @param committeeSize Total committee size
     * @return Minimum quorum (2f+1)
     */
    protected int calculateQuorum(int committeeSize) {
        int f = (committeeSize - 1) / 3;
        return 2 * f + 1;
    }

    /**
     * Calculate maximum Byzantine failures.
     *
     * @param committeeSize Total committee size
     * @return Maximum Byzantine failures (f)
     */
    protected int calculateMaxByzantine(int committeeSize) {
        return (committeeSize - 1) / 3;
    }
}
