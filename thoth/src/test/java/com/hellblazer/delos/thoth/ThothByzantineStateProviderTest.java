/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.byzantine.IntelligenceConfig;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ThothByzantineStateProvider.
 */
class ThothByzantineStateProviderTest {

    private ThothByzantineStateProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ThothByzantineStateProvider(Duration.ofMinutes(15));
    }

    @Test
    void shouldReturnThothLayerName() {
        assertThat(provider.getLayerName()).isEqualTo(IntelligenceConfig.LAYER_THOTH);
    }

    @Test
    void shouldReturnEmptyMapWhenNoFailures() {
        var states = provider.getMemberAnomalyStates();
        assertThat(states).isEmpty();
    }

    @Test
    void shouldTrackValidationFailure() {
        var digest = DigestAlgorithm.DEFAULT.digest("failing-member");
        var identifier = new SelfAddressingIdentifier(digest);

        provider.recordValidationFailure(identifier, "Invalid signature");

        var states = provider.getMemberAnomalyStates();
        assertThat(states).hasSize(1);
        assertThat(states).containsKey(identifier);

        var state = states.get(identifier);
        assertThat(state.anomalyScore()).isGreaterThan(0);
        assertThat(state.activeSignals()).anyMatch(s -> s.contains("VALIDATION_FAILURE"));
        assertThat(state.evidenceSummary()).contains("validation=1");
    }

    @Test
    void shouldTrackQuorumFailure() {
        var digest = DigestAlgorithm.DEFAULT.digest("quorum-failing-member");
        var identifier = new SelfAddressingIdentifier(digest);

        provider.recordQuorumFailure(identifier);

        var states = provider.getMemberAnomalyStates();
        assertThat(states).hasSize(1);

        var state = states.get(identifier);
        assertThat(state.activeSignals()).contains("QUORUM_FAILURE");
        assertThat(state.evidenceSummary()).contains("quorum=1");
    }

    @Test
    void shouldTrackTimeout() {
        var digest = DigestAlgorithm.DEFAULT.digest("timeout-member");
        var identifier = new SelfAddressingIdentifier(digest);

        provider.recordTimeout(identifier);

        var states = provider.getMemberAnomalyStates();
        assertThat(states).hasSize(1);

        var state = states.get(identifier);
        assertThat(state.activeSignals()).contains("TIMEOUT");
        assertThat(state.evidenceSummary()).contains("timeout=1");
    }

    @Test
    void shouldAccumulateScoreAcrossFailureTypes() {
        var digest = DigestAlgorithm.DEFAULT.digest("multi-failure-member");
        var identifier = new SelfAddressingIdentifier(digest);

        // Record multiple failure types
        provider.recordValidationFailure(identifier, "Sig invalid");
        provider.recordQuorumFailure(identifier);
        provider.recordTimeout(identifier);

        var state = provider.getMemberState(identifier);
        assertThat(state).isPresent();

        // Score should be higher with multiple failures
        // (3 * 3 + 2 * 1 + 1 * 1) / 10 = 6/10 = 0.6
        assertThat(state.get().anomalyScore()).isCloseTo(0.6, within(0.01));
    }

    @Test
    void shouldCapScoreAtOne() {
        var digest = DigestAlgorithm.DEFAULT.digest("max-failure-member");
        var identifier = new SelfAddressingIdentifier(digest);

        // Record many validation failures (highest weight)
        for (int i = 0; i < 10; i++) {
            provider.recordValidationFailure(identifier, "Failure " + i);
        }

        var state = provider.getMemberState(identifier);
        assertThat(state).isPresent();
        assertThat(state.get().anomalyScore()).isEqualTo(1.0);
    }

    @Test
    void shouldReturnEmptyForUnknownMember() {
        var digest = DigestAlgorithm.DEFAULT.digest("unknown-member");
        var identifier = new SelfAddressingIdentifier(digest);

        var state = provider.getMemberState(identifier);
        assertThat(state).isEmpty();
    }

    @Test
    void shouldResetClearAllFailures() {
        var digest = DigestAlgorithm.DEFAULT.digest("reset-test-member");
        var identifier = new SelfAddressingIdentifier(digest);

        provider.recordValidationFailure(identifier, "Failure");
        assertThat(provider.getTrackedMemberCount()).isEqualTo(1);

        provider.reset();

        assertThat(provider.getTrackedMemberCount()).isEqualTo(0);
        assertThat(provider.getMemberState(identifier)).isEmpty();
    }

    @Test
    void shouldCountTrackedMembers() {
        var member1 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member1"));
        var member2 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member2"));
        var member3 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member3"));

        provider.recordValidationFailure(member1, "Failure 1");
        provider.recordQuorumFailure(member2);
        provider.recordTimeout(member3);

        assertThat(provider.getTrackedMemberCount()).isEqualTo(3);
    }

    @Test
    void shouldAcceptDigestConvenienceMethods() {
        var digest = DigestAlgorithm.DEFAULT.digest("digest-api-member");

        // Use Digest convenience methods
        provider.recordValidationFailure(digest, "Digest failure");
        provider.recordQuorumFailure(digest);
        provider.recordTimeout(digest);

        var identifier = new SelfAddressingIdentifier(digest);
        var state = provider.getMemberState(identifier);
        assertThat(state).isPresent();
        assertThat(state.get().evidenceSummary()).contains("validation=1", "quorum=1", "timeout=1");
    }

    @Test
    void shouldKeepRecentSignals() {
        var digest = DigestAlgorithm.DEFAULT.digest("signal-limit-member");
        var identifier = new SelfAddressingIdentifier(digest);

        // Record many failures to test signal limit
        for (int i = 0; i < 10; i++) {
            provider.recordValidationFailure(identifier, "Failure" + i);
        }

        var state = provider.getMemberState(identifier);
        assertThat(state).isPresent();

        // Should only keep recent signals (max 5)
        assertThat(state.get().activeSignals()).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void shouldExpireOldFailures() throws InterruptedException {
        // Use short expiry for testing
        var shortExpiryProvider = new ThothByzantineStateProvider(Duration.ofMillis(50));
        var digest = DigestAlgorithm.DEFAULT.digest("expiring-member");
        var identifier = new SelfAddressingIdentifier(digest);

        shortExpiryProvider.recordValidationFailure(identifier, "Expiring failure");
        assertThat(shortExpiryProvider.getTrackedMemberCount()).isEqualTo(1);

        // Wait for expiry
        Thread.sleep(100);

        // Should be expired now
        assertThat(shortExpiryProvider.getTrackedMemberCount()).isEqualTo(0);
        assertThat(shortExpiryProvider.getMemberState(identifier)).isEmpty();
    }

    @Test
    void shouldBeThreadSafe() throws InterruptedException {
        var threadCount = 4;
        var operationsPerThread = 100;
        var latch = new java.util.concurrent.CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            Thread.startVirtualThread(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        var digest = DigestAlgorithm.DEFAULT.digest("member-" + threadId + "-" + i);
                        var identifier = new SelfAddressingIdentifier(digest);

                        // Mix of operations
                        switch (i % 3) {
                            case 0 -> provider.recordValidationFailure(identifier, "Failure");
                            case 1 -> provider.recordQuorumFailure(identifier);
                            case 2 -> provider.recordTimeout(identifier);
                        }

                        // Read operations
                        provider.getMemberAnomalyStates();
                        provider.getTrackedMemberCount();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        var completed = latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Should have tracked members from all threads
        assertThat(provider.getTrackedMemberCount()).isEqualTo(threadCount * operationsPerThread);
    }

    @Test
    void shouldRejectNullExpiryDuration() {
        assertThatThrownBy(() -> new ThothByzantineStateProvider(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("failureExpiry");
    }
}
