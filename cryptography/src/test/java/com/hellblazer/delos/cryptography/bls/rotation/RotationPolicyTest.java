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

    @Test
    void eventBasedPolicyShouldTriggerOnByzantineDetection() {
        var policy = new EventBasedRotationPolicy(RotationEvent.BYZANTINE_DETECTED);

        assertThat(policy.shouldRotateOnEvent(RotationEvent.BYZANTINE_DETECTED)).isTrue();
        assertThat(policy.shouldRotateOnEvent(RotationEvent.VIEW_CHANGE)).isFalse();
        assertThat(policy.shouldRotateOnEvent(RotationEvent.MANUAL_TRIGGER)).isFalse();
        assertThat(policy.shouldRotateOnEvent(RotationEvent.EMERGENCY)).isFalse();
    }

    @Test
    void eventBasedPolicyShouldTriggerOnEmergency() {
        var policy = new EventBasedRotationPolicy(RotationEvent.EMERGENCY);

        assertThat(policy.shouldRotateOnEvent(RotationEvent.EMERGENCY)).isTrue();
        assertThat(policy.shouldRotateOnEvent(RotationEvent.VIEW_CHANGE)).isFalse();
        assertThat(policy.shouldRotateOnEvent(RotationEvent.BYZANTINE_DETECTED)).isFalse();
        assertThat(policy.shouldRotateOnEvent(RotationEvent.MANUAL_TRIGGER)).isFalse();
    }

    @Test
    void eventBasedPolicyShouldTriggerOnManualTrigger() {
        var policy = new EventBasedRotationPolicy(RotationEvent.MANUAL_TRIGGER);

        assertThat(policy.shouldRotateOnEvent(RotationEvent.MANUAL_TRIGGER)).isTrue();
        assertThat(policy.shouldRotateOnEvent(RotationEvent.VIEW_CHANGE)).isFalse();
        assertThat(policy.shouldRotateOnEvent(RotationEvent.BYZANTINE_DETECTED)).isFalse();
        assertThat(policy.shouldRotateOnEvent(RotationEvent.EMERGENCY)).isFalse();
    }

    @Test
    void timeBasedPolicyShouldHandleBoundaryConditions() {
        var policy = new TimeBasedRotationPolicy(Duration.ofDays(30));
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var now = Instant.now();

        // Create key version created exactly 30 days ago
        var boundaryKeyVersion = new KeyVersion(
            1,
            now.minus(Duration.ofDays(30)),
            null,
            KeyStatus.ACTIVE,
            "rot-boundary",
            keyPair.publicKey().proofOfPossession()
        );

        var state = new KeyRotationState();
        state.addKeyVersion(boundaryKeyVersion);
        state.activateKeyVersion(1);

        // At exact boundary, should trigger
        assertThat(policy.shouldRotate(state, now)).isTrue();

        // Just before boundary (1 second less than 30 days)
        var justBefore = now.minusSeconds(1);
        assertThat(policy.shouldRotate(state, justBefore)).isFalse();
    }

    @Test
    void timeBasedPolicyShouldHandleVeryShortInterval() {
        var policy = new TimeBasedRotationPolicy(Duration.ofSeconds(10));
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var now = Instant.now();

        var keyVersion = new KeyVersion(
            1,
            now.minus(Duration.ofSeconds(11)),
            null,
            KeyStatus.ACTIVE,
            "rot-short",
            keyPair.publicKey().proofOfPossession()
        );

        var state = new KeyRotationState();
        state.addKeyVersion(keyVersion);
        state.activateKeyVersion(1);

        // Should trigger after 11 seconds
        assertThat(policy.shouldRotate(state, now)).isTrue();
    }

    @Test
    void timeBasedPolicyShouldHandleVeryLongInterval() {
        var policy = new TimeBasedRotationPolicy(Duration.ofDays(365));
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var now = Instant.now();

        var keyVersion = new KeyVersion(
            1,
            now.minus(Duration.ofDays(100)),
            null,
            KeyStatus.ACTIVE,
            "rot-long",
            keyPair.publicKey().proofOfPossession()
        );

        var state = new KeyRotationState();
        state.addKeyVersion(keyVersion);
        state.activateKeyVersion(1);

        // Should not trigger after only 100 days
        assertThat(policy.shouldRotate(state, now)).isFalse();
    }

    @Test
    void timeBasedPolicyShouldNotTriggerOnEvents() {
        var policy = new TimeBasedRotationPolicy(Duration.ofDays(30));

        // Time-based policy ignores all events
        assertThat(policy.shouldRotateOnEvent(RotationEvent.VIEW_CHANGE)).isFalse();
        assertThat(policy.shouldRotateOnEvent(RotationEvent.BYZANTINE_DETECTED)).isFalse();
        assertThat(policy.shouldRotateOnEvent(RotationEvent.MANUAL_TRIGGER)).isFalse();
        assertThat(policy.shouldRotateOnEvent(RotationEvent.EMERGENCY)).isFalse();
    }

    @Test
    void policiesShouldProvideAppropriateGracePeriods() {
        var timePolicy = new TimeBasedRotationPolicy(Duration.ofDays(30));
        var eventPolicy = new EventBasedRotationPolicy(RotationEvent.VIEW_CHANGE);

        // Time-based has longer grace period (5 minutes)
        assertThat(timePolicy.getGracePeriod()).isEqualTo(Duration.ofMinutes(5));

        // Event-based has shorter grace period (30 seconds)
        assertThat(eventPolicy.getGracePeriod()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void shouldSupportMultiplePoliciesIndependently() {
        // Multiple policies can coexist and be evaluated independently
        var timePolicy = new TimeBasedRotationPolicy(Duration.ofDays(30));
        var viewChangePolicy = new EventBasedRotationPolicy(RotationEvent.VIEW_CHANGE);
        var byzantinePolicy = new EventBasedRotationPolicy(RotationEvent.BYZANTINE_DETECTED);

        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var now = Instant.now();
        var keyVersion = new KeyVersion(
            1,
            now.minus(Duration.ofDays(31)),
            null,
            KeyStatus.ACTIVE,
            "rot-multi",
            keyPair.publicKey().proofOfPossession()
        );

        var state = new KeyRotationState();
        state.addKeyVersion(keyVersion);
        state.activateKeyVersion(1);

        // Time policy should trigger
        assertThat(timePolicy.shouldRotate(state, now)).isTrue();

        // Event policies should not trigger on time
        assertThat(viewChangePolicy.shouldRotate(state, now)).isFalse();
        assertThat(byzantinePolicy.shouldRotate(state, now)).isFalse();

        // Event policies should trigger on their respective events
        assertThat(viewChangePolicy.shouldRotateOnEvent(RotationEvent.VIEW_CHANGE)).isTrue();
        assertThat(byzantinePolicy.shouldRotateOnEvent(RotationEvent.BYZANTINE_DETECTED)).isTrue();
    }
}
