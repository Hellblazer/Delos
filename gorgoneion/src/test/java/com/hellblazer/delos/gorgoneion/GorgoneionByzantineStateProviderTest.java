/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.gorgoneion;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.byzantine.IntelligenceConfig;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for GorgoneionByzantineStateProvider.
 */
class GorgoneionByzantineStateProviderTest {

    private GorgoneionByzantineStateProvider provider;

    @BeforeEach
    void setUp() {
        provider = new GorgoneionByzantineStateProvider(Duration.ofMinutes(15));
    }

    @Test
    void shouldReturnGorgoneionLayerName() {
        assertThat(provider.getLayerName()).isEqualTo(IntelligenceConfig.LAYER_GORGONEION);
    }

    @Test
    void shouldReturnEmptyMapWhenNoAnomalies() {
        var states = provider.getMemberAnomalyStates();
        assertThat(states).isEmpty();
    }

    @Test
    void shouldTrackAttestationFailure() {
        var digest = DigestAlgorithm.DEFAULT.digest("failing-source");
        var identifier = new SelfAddressingIdentifier(digest);

        provider.recordAttestationFailure(identifier, "Invalid signature");

        var states = provider.getMemberAnomalyStates();
        assertThat(states).hasSize(1);
        assertThat(states).containsKey(identifier);

        var state = states.get(identifier);
        assertThat(state.anomalyScore()).isGreaterThan(0);
        assertThat(state.activeSignals()).anyMatch(s -> s.contains("ATTESTATION_FAILURE"));
        assertThat(state.evidenceSummary()).contains("attestation=1");
    }

    @Test
    void shouldTrackReplayAttempt() {
        var digest = DigestAlgorithm.DEFAULT.digest("replay-source");
        var identifier = new SelfAddressingIdentifier(digest);

        provider.recordReplayAttempt(identifier);

        var states = provider.getMemberAnomalyStates();
        assertThat(states).hasSize(1);

        var state = states.get(identifier);
        assertThat(state.activeSignals()).contains("REPLAY_ATTEMPT");
        assertThat(state.evidenceSummary()).contains("replay=1");
        // Replay has high weight (4)
        assertThat(state.anomalyScore()).isCloseTo(0.4, within(0.01));
    }

    @Test
    void shouldTrackIdentityAnomaly() {
        var digest = DigestAlgorithm.DEFAULT.digest("anomalous-source");
        var identifier = new SelfAddressingIdentifier(digest);

        provider.recordIdentityAnomaly(identifier, "RAPID_CYCLING");

        var states = provider.getMemberAnomalyStates();
        assertThat(states).hasSize(1);

        var state = states.get(identifier);
        assertThat(state.activeSignals()).anyMatch(s -> s.contains("IDENTITY_ANOMALY"));
        assertThat(state.evidenceSummary()).contains("identity=1");
    }

    @Test
    void shouldAccumulateScoreAcrossAnomalyTypes() {
        var digest = DigestAlgorithm.DEFAULT.digest("multi-anomaly-source");
        var identifier = new SelfAddressingIdentifier(digest);

        // Record multiple anomaly types
        provider.recordAttestationFailure(identifier, "Invalid sig");
        provider.recordReplayAttempt(identifier);
        provider.recordIdentityAnomaly(identifier, "SUSPICIOUS");

        var state = provider.getMemberState(identifier);
        assertThat(state).isPresent();

        // Score should be higher with multiple anomalies
        // (3 * 1 + 4 * 1 + 2 * 1) / 10 = 9/10 = 0.9
        assertThat(state.get().anomalyScore()).isCloseTo(0.9, within(0.01));
    }

    @Test
    void shouldCapScoreAtOne() {
        var digest = DigestAlgorithm.DEFAULT.digest("max-anomaly-source");
        var identifier = new SelfAddressingIdentifier(digest);

        // Record many replay attempts (highest weight = 4)
        for (int i = 0; i < 10; i++) {
            provider.recordReplayAttempt(identifier);
        }

        var state = provider.getMemberState(identifier);
        assertThat(state).isPresent();
        assertThat(state.get().anomalyScore()).isEqualTo(1.0);
    }

    @Test
    void shouldReturnEmptyForUnknownSource() {
        var digest = DigestAlgorithm.DEFAULT.digest("unknown-source");
        var identifier = new SelfAddressingIdentifier(digest);

        var state = provider.getMemberState(identifier);
        assertThat(state).isEmpty();
    }

    @Test
    void shouldResetClearAllAnomalies() {
        var digest = DigestAlgorithm.DEFAULT.digest("reset-test-source");
        var identifier = new SelfAddressingIdentifier(digest);

        provider.recordAttestationFailure(identifier, "Failure");
        assertThat(provider.getTrackedMemberCount()).isEqualTo(1);

        provider.reset();

        assertThat(provider.getTrackedMemberCount()).isEqualTo(0);
        assertThat(provider.getMemberState(identifier)).isEmpty();
    }

    @Test
    void shouldCountTrackedSources() {
        var source1 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("source1"));
        var source2 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("source2"));
        var source3 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("source3"));

        provider.recordAttestationFailure(source1, "Failure 1");
        provider.recordReplayAttempt(source2);
        provider.recordIdentityAnomaly(source3, "ANOMALY");

        assertThat(provider.getTrackedMemberCount()).isEqualTo(3);
    }

    @Test
    void shouldAcceptDigestConvenienceMethods() {
        var digest = DigestAlgorithm.DEFAULT.digest("digest-api-source");

        // Use Digest convenience methods
        provider.recordAttestationFailure(digest, "Digest failure");
        provider.recordReplayAttempt(digest);
        provider.recordIdentityAnomaly(digest, "TEST_ANOMALY");

        var identifier = new SelfAddressingIdentifier(digest);
        var state = provider.getMemberState(identifier);
        assertThat(state).isPresent();
        assertThat(state.get().evidenceSummary()).contains("attestation=1", "replay=1", "identity=1");
    }

    @Test
    void shouldKeepRecentSignals() {
        var digest = DigestAlgorithm.DEFAULT.digest("signal-limit-source");
        var identifier = new SelfAddressingIdentifier(digest);

        // Record many anomalies to test signal limit
        for (int i = 0; i < 10; i++) {
            provider.recordAttestationFailure(identifier, "Failure" + i);
        }

        var state = provider.getMemberState(identifier);
        assertThat(state).isPresent();

        // Should only keep recent signals (max 5)
        assertThat(state.get().activeSignals()).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void shouldExpireOldAnomalies() throws InterruptedException {
        // Use short expiry for testing
        var shortExpiryProvider = new GorgoneionByzantineStateProvider(Duration.ofMillis(50));
        var digest = DigestAlgorithm.DEFAULT.digest("expiring-source");
        var identifier = new SelfAddressingIdentifier(digest);

        shortExpiryProvider.recordAttestationFailure(identifier, "Expiring anomaly");
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
                        var digest = DigestAlgorithm.DEFAULT.digest("source-" + threadId + "-" + i);
                        var identifier = new SelfAddressingIdentifier(digest);

                        // Mix of operations
                        switch (i % 3) {
                            case 0 -> provider.recordAttestationFailure(identifier, "Failure");
                            case 1 -> provider.recordReplayAttempt(identifier);
                            case 2 -> provider.recordIdentityAnomaly(identifier, "ANOMALY");
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

        // Should have tracked sources from all threads
        assertThat(provider.getTrackedMemberCount()).isEqualTo(threadCount * operationsPerThread);
    }

    @Test
    void shouldRejectNullExpiryDuration() {
        assertThatThrownBy(() -> new GorgoneionByzantineStateProvider(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("anomalyExpiry");
    }

    @Test
    void shouldWeightReplayHigherThanOtherAnomalies() {
        var replaySource = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("replay-only"));
        var attestSource = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("attest-only"));

        // Single replay attempt
        provider.recordReplayAttempt(replaySource);
        // Single attestation failure
        provider.recordAttestationFailure(attestSource, "sig invalid");

        var replayState = provider.getMemberState(replaySource);
        var attestState = provider.getMemberState(attestSource);

        assertThat(replayState).isPresent();
        assertThat(attestState).isPresent();

        // Replay should have higher score (weight 4 vs 3)
        assertThat(replayState.get().anomalyScore())
            .isGreaterThan(attestState.get().anomalyScore());
    }
}
