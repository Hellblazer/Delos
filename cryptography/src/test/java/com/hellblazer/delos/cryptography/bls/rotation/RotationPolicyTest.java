/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

import com.hellblazer.delos.cryptography.bls.BLSKeyPair;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for RotationPolicy and its implementations.
 *
 * @author hal.hildebrand
 */
class RotationPolicyTest {

    private static final BLSProvider PROVIDER = BLSProvider.getDefault();
    private static final Random RANDOM = new Random(42);

    @Test
    void timeBasedPolicyShouldTriggerAfterInterval() {
        var policy = new TimeBasedRotationPolicy(Duration.ofDays(30));
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var now = Instant.now();

        // Create a key version created 31 days ago
        var oldKeyVersion = new KeyVersion(
            1,
            now.minus(Duration.ofDays(31)),
            null,
            KeyStatus.ACTIVE,
            "rot-1",
            keyPair.publicKey().proofOfPossession()
        );

        // Create mock state with old key
        var state = new KeyRotationState();
        state.addKeyVersion(oldKeyVersion);
        state.activateKeyVersion(1);

        // Should trigger rotation
        assertThat(policy.shouldRotate(state, now)).isTrue();
    }

    @Test
    void timeBasedPolicyShouldNotTriggerBeforeInterval() {
        var policy = new TimeBasedRotationPolicy(Duration.ofDays(30));
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var now = Instant.now();

        // Create a key version created 10 days ago
        var recentKeyVersion = new KeyVersion(
            1,
            now.minus(Duration.ofDays(10)),
            null,
            KeyStatus.ACTIVE,
            "rot-1",
            keyPair.publicKey().proofOfPossession()
        );

        // Create mock state with recent key
        var state = new KeyRotationState();
        state.addKeyVersion(recentKeyVersion);
        state.activateKeyVersion(1);

        // Should not trigger rotation
        assertThat(policy.shouldRotate(state, now)).isFalse();
    }

    @Test
    void timeBasedPolicyShouldRejectNegativeInterval() {
        assertThatThrownBy(() -> new TimeBasedRotationPolicy(Duration.ofDays(-1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("rotationInterval must be positive");
    }

    @Test
    void timeBasedPolicyShouldRejectZeroInterval() {
        assertThatThrownBy(() -> new TimeBasedRotationPolicy(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("rotationInterval must be positive");
    }

    @Test
    void eventBasedPolicyShouldTriggerOnMatchingEvent() {
        var policy = new EventBasedRotationPolicy(RotationEvent.VIEW_CHANGE);

        assertThat(policy.shouldRotateOnEvent(RotationEvent.VIEW_CHANGE)).isTrue();
        assertThat(policy.shouldRotateOnEvent(RotationEvent.MANUAL_TRIGGER)).isFalse();
        assertThat(policy.shouldRotateOnEvent(RotationEvent.BYZANTINE_DETECTED)).isFalse();
    }

    @Test
    void eventBasedPolicyShouldNotTriggerOnTime() {
        var policy = new EventBasedRotationPolicy(RotationEvent.VIEW_CHANGE);
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var now = Instant.now();

        // Create a key version created 100 days ago (way past any reasonable interval)
        var oldKeyVersion = new KeyVersion(
            1,
            now.minus(Duration.ofDays(100)),
            null,
            KeyStatus.ACTIVE,
            "rot-1",
            keyPair.publicKey().proofOfPossession()
        );

        var state = new KeyRotationState();
        state.addKeyVersion(oldKeyVersion);
        state.activateKeyVersion(1);

        // Event-based policy ignores time
        assertThat(policy.shouldRotate(state, now)).isFalse();
    }

    @Test
    void timeBasedPolicyShouldProvideGracePeriod() {
        var policy = new TimeBasedRotationPolicy(Duration.ofDays(30));
        var gracePeriod = policy.getGracePeriod();

        assertThat(gracePeriod).isNotNull();
        assertThat(gracePeriod).isPositive();
        assertThat(gracePeriod).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void eventBasedPolicyShouldProvideGracePeriod() {
        var policy = new EventBasedRotationPolicy(RotationEvent.VIEW_CHANGE);
        var gracePeriod = policy.getGracePeriod();

        assertThat(gracePeriod).isNotNull();
        assertThat(gracePeriod).isPositive();
        assertThat(gracePeriod).isEqualTo(Duration.ofSeconds(30));
    }
}
