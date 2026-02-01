/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine.testing;

import com.hellblazer.delos.membership.byzantine.IntelligenceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ByzantineTestHarness.
 */
class ByzantineTestHarnessTest {

    private ByzantineTestHarness harness;

    @BeforeEach
    void setUp() {
        harness = ByzantineTestHarness.builder()
                                       .withDeterministicEntropy()
                                       .withFixedClock()
                                       .withStandardLayers()
                                       .build();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void shouldBuildWithDefaults() {
        try (var h = ByzantineTestHarness.builder().build()) {
            assertThat(h.getConfig()).isNotNull();
            assertThat(h.getMetrics()).isNotNull();
            assertThat(h.getResponseHandler()).isNotNull();
        }
    }

    @Test
    void shouldBuildWithStandardLayers() {
        assertThat(harness.getInjector(IntelligenceConfig.LAYER_FIREFLIES)).isNotNull();
        assertThat(harness.getInjector(IntelligenceConfig.LAYER_THOTH)).isNotNull();
        assertThat(harness.getInjector(IntelligenceConfig.LAYER_GORGONEION)).isNotNull();
    }

    @Test
    void shouldCreateDeterministicMember() {
        var m1 = harness.createMember("same-seed");
        var m2 = harness.createMember("same-seed");
        var m3 = harness.createMember("different-seed");

        assertThat(m1).isEqualTo(m2);
        assertThat(m1).isNotEqualTo(m3);
    }

    @Test
    void shouldInjectFaultOnSpecificLayer() {
        var member = harness.createMember("layer-test");

        var handle = harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.CRASH);

        assertThat(handle).isNotNull();
        assertThat(harness.getInjector(IntelligenceConfig.LAYER_FIREFLIES).hasFault(member)).isTrue();
        assertThat(harness.getInjector(IntelligenceConfig.LAYER_THOTH).hasFault(member)).isFalse();
    }

    @Test
    void shouldRejectUnknownLayer() {
        var member = harness.createMember("unknown-test");

        assertThatThrownBy(() -> harness.injectFault("nonexistent", member, FaultType.CRASH))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown layer");
    }

    @Test
    void shouldInjectMultiLayerFaults() {
        var member = harness.createMember("multi-layer");

        var handles = harness.injectMultiLayerFaults(member, java.util.Map.of(
            IntelligenceConfig.LAYER_FIREFLIES, FaultType.EQUIVOCATION,
            IntelligenceConfig.LAYER_THOTH, FaultType.TIMING_ATTACK
        ));

        assertThat(handles).hasSize(2);
        assertThat(harness.getInjector(IntelligenceConfig.LAYER_FIREFLIES).hasFault(member)).isTrue();
        assertThat(harness.getInjector(IntelligenceConfig.LAYER_THOTH).hasFault(member)).isTrue();

        // Cleanup
        handles.forEach(FaultInjectionHandle::restore);
    }

    @Test
    void shouldEvaluateMemberAndRecordMetrics() {
        var member = harness.createMember("eval-test");

        harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.EQUIVOCATION);
        var result = harness.evaluate(member);

        assertThat(result).isNotNull();
        assertThat(result.isAnomalous()).isTrue();
        assertThat(harness.getMetrics().getTruePositives()).isEqualTo(1);
    }

    @Test
    void shouldRunPollingCycle() {
        var member = harness.createMember("poll-test");
        harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.EQUIVOCATION);

        // Should not throw
        harness.runPollingCycle();
    }

    @Test
    void shouldTrackResponsesInHandler() {
        var member = harness.createMember("response-test");

        harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.EQUIVOCATION);
        harness.runPollingCycle();

        var handler = harness.getResponseHandler();
        // Equivocation should trigger critical response
        assertThat(handler.hadCriticalResponse(member)).isTrue();
    }

    @Test
    void shouldRestoreAllFaults() {
        var m1 = harness.createMember("restore-1");
        var m2 = harness.createMember("restore-2");

        harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, m1, FaultType.CRASH);
        harness.injectFault(IntelligenceConfig.LAYER_THOTH, m2, FaultType.DELAY);

        harness.restoreAllFaults();

        assertThat(harness.getInjector(IntelligenceConfig.LAYER_FIREFLIES).hasFault(m1)).isFalse();
        assertThat(harness.getInjector(IntelligenceConfig.LAYER_THOTH).hasFault(m2)).isFalse();
    }

    @Test
    void shouldReset() {
        var member = harness.createMember("reset-test");

        harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.CRASH);
        harness.evaluate(member);

        harness.reset();

        assertThat(harness.getInjector(IntelligenceConfig.LAYER_FIREFLIES).getTrackedMemberCount()).isEqualTo(0);
        assertThat(harness.getResponseHandler().getCriticalResponses()).isEmpty();
    }

    @Test
    void shouldProvideClockAndEntropy() {
        assertThat(harness.getClock()).isNotNull();
        assertThat(harness.getEntropy()).isNotNull();
    }

    @Test
    void shouldAutoCloseCleanly() {
        var h = ByzantineTestHarness.builder().build();
        var member = h.createMember("close-test");
        h.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.CRASH);

        h.close();  // Should not throw
    }

    @Test
    void shouldBuildWithCustomConfig() {
        var config = IntelligenceConfig.builder()
                                        .criticalThreshold(0.9)
                                        .warningThreshold(0.5)
                                        .build();

        try (var h = ByzantineTestHarness.builder()
                                          .withConfig(config)
                                          .build()) {
            // Should use custom config
            assertThat(h.getConfig()).isNotNull();
            assertThat(h.getConfig().criticalThreshold()).isEqualTo(0.9);
        }
    }

    @Test
    void shouldBuildWithCustomLayers() {
        try (var h = ByzantineTestHarness.builder()
                                          .withLayers("custom-layer-1", "custom-layer-2")
                                          .build()) {
            assertThat(h.getInjector("custom-layer-1")).isNotNull();
            assertThat(h.getInjector("custom-layer-2")).isNotNull();
            assertThat(h.getInjector(IntelligenceConfig.LAYER_FIREFLIES)).isNull();
        }
    }
}
