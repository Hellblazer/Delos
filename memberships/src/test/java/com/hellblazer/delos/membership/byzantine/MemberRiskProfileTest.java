/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for MemberRiskProfile.
 */
class MemberRiskProfileTest {

    private IntelligenceConfig config;
    private Identifier memberId;

    @BeforeEach
    void setUp() {
        config = IntelligenceConfig.defaults();
        memberId = Identifier.NONE;
    }

    @Test
    void shouldCreateWithZeroScore() {
        var profile = new MemberRiskProfile(memberId, config);

        assertThat(profile.getMemberId()).isEqualTo(memberId);
        assertThat(profile.getAggregatedScore()).isEqualTo(0.0);
        assertThat(profile.getAllLayerStates()).isEmpty();
    }

    @Test
    void shouldUpdateLayerState() {
        var profile = new MemberRiskProfile(memberId, config);
        var state = new LayerAnomalyState(
            "FIREFLIES", 0.6, Instant.now(), List.of("ACCUSED"), "test"
        );

        profile.updateLayerState(state);

        assertThat(profile.getLayerState("FIREFLIES")).isPresent();
        assertThat(profile.getAggregatedScore()).isGreaterThan(0);
    }

    @Test
    void shouldAggregateMultipleLayers() {
        var profile = new MemberRiskProfile(memberId, config);

        // Add Fireflies state (weight 0.4)
        profile.updateLayerState(new LayerAnomalyState(
            IntelligenceConfig.LAYER_FIREFLIES, 0.8, Instant.now(), List.of("SIG"), "test"
        ));

        // Add Ethereal state (weight 0.3)
        profile.updateLayerState(new LayerAnomalyState(
            IntelligenceConfig.LAYER_ETHEREAL, 0.6, Instant.now(), List.of("SIG"), "test"
        ));

        // Weighted average: (0.8 * 0.4 + 0.6 * 0.3) / (0.4 + 0.3) = 0.50 / 0.70 ≈ 0.714
        assertThat(profile.getAggregatedScore())
            .isCloseTo(0.714, within(0.01));
    }

    @Test
    void shouldTrackActiveSignalSources() {
        var profile = new MemberRiskProfile(memberId, config);

        profile.updateLayerState(new LayerAnomalyState(
            "LAYER1", 0.5, Instant.now(), List.of("SIG1"), "test"
        ));
        profile.updateLayerState(new LayerAnomalyState(
            "LAYER2", 0.5, Instant.now(), List.of(), "no signals"
        ));

        var sources = profile.getActiveSignalSources();

        assertThat(sources).containsExactly("LAYER1");
    }

    @Test
    void shouldApplyDecay() {
        var profile = new MemberRiskProfile(memberId, config);
        profile.updateLayerState(new LayerAnomalyState(
            "FIREFLIES", 0.8, Instant.now(), List.of("SIG"), "test"
        ));

        var initialScore = profile.getAggregatedScore();
        profile.applyDecay();

        assertThat(profile.getAggregatedScore())
            .isLessThan(initialScore);
    }

    @Test
    void shouldCheckThresholds() {
        var profile = new MemberRiskProfile(memberId, config);

        // Below warning (0.5)
        profile.updateLayerState(new LayerAnomalyState(
            "FIREFLIES", 0.3, Instant.now(), List.of(), "test"
        ));
        assertThat(profile.isWarning()).isFalse();
        assertThat(profile.isCritical()).isFalse();

        // Above warning, below critical (0.8)
        profile.updateLayerState(new LayerAnomalyState(
            "FIREFLIES", 0.7, Instant.now(), List.of(), "test"
        ));
        assertThat(profile.isWarning()).isTrue();
        assertThat(profile.isCritical()).isFalse();

        // Above critical
        profile.updateLayerState(new LayerAnomalyState(
            "FIREFLIES", 1.0, Instant.now(), List.of(), "test"
        ));
        assertThat(profile.isCritical()).isTrue();
    }

    @Test
    void shouldBeThreadSafe() throws InterruptedException {
        var profile = new MemberRiskProfile(memberId, config);
        var threadCount = 4;
        var updatesPerThread = 1000;
        var latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            var layerName = "LAYER" + t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < updatesPerThread; i++) {
                        var state = new LayerAnomalyState(
                            layerName,
                            Math.random(),
                            Instant.now(),
                            List.of("SIG"),
                            "test"
                        );
                        profile.updateLayerState(state);

                        // Also read concurrently
                        profile.getAggregatedScore();
                        profile.getAllLayerStates();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        var completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(profile.getAllLayerStates()).hasSize(threadCount);
    }

    @Test
    void shouldCheckNegligible() {
        var profile = new MemberRiskProfile(memberId, config);
        profile.updateLayerState(new LayerAnomalyState(
            "FIREFLIES", 0.05, Instant.now(), List.of(), "test"
        ));

        assertThat(profile.isNegligible(0.01)).isFalse();
        assertThat(profile.isNegligible(0.1)).isTrue();
    }
}
