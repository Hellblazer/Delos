/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for LayerAnomalyState record.
 */
class LayerAnomalyStateTest {

    @Test
    void shouldCreateValidState() {
        var state = new LayerAnomalyState(
            "FIREFLIES",
            0.75,
            Instant.now(),
            List.of("ACCUSED", "TIMEOUT"),
            "Member accused by 3 peers"
        );

        assertThat(state.layerName()).isEqualTo("FIREFLIES");
        assertThat(state.anomalyScore()).isEqualTo(0.75);
        assertThat(state.activeSignals()).containsExactly("ACCUSED", "TIMEOUT");
    }

    @Test
    void shouldRejectNullLayerName() {
        assertThatThrownBy(() -> new LayerAnomalyState(
            null, 0.5, Instant.now(), List.of(), "test"
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullEvidenceSummary() {
        assertThatThrownBy(() -> new LayerAnomalyState(
            "TEST", 0.5, Instant.now(), List.of(), null
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectInvalidScore() {
        assertThatThrownBy(() -> new LayerAnomalyState(
            "TEST", -0.1, Instant.now(), List.of(), "test"
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new LayerAnomalyState(
            "TEST", 1.1, Instant.now(), List.of(), "test"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptBoundaryScores() {
        var zeroState = new LayerAnomalyState("TEST", 0.0, Instant.now(), List.of(), "test");
        var oneState = new LayerAnomalyState("TEST", 1.0, Instant.now(), List.of(), "test");

        assertThat(zeroState.anomalyScore()).isEqualTo(0.0);
        assertThat(oneState.anomalyScore()).isEqualTo(1.0);
    }

    @Test
    void shouldCreateNoAnomalyState() {
        var state = LayerAnomalyState.noAnomaly("THOTH");

        assertThat(state.layerName()).isEqualTo("THOTH");
        assertThat(state.anomalyScore()).isEqualTo(0.0);
        assertThat(state.activeSignals()).isEmpty();
        assertThat(state.hasActiveSignals()).isFalse();
    }

    @Test
    void shouldCreateNoAnomalyStateWithTimestamp() {
        var timestamp = Instant.parse("2026-01-31T12:00:00Z");
        var state = LayerAnomalyState.noAnomaly("THOTH", timestamp);

        assertThat(state.layerName()).isEqualTo("THOTH");
        assertThat(state.anomalyScore()).isEqualTo(0.0);
        assertThat(state.lastUpdated()).isEqualTo(timestamp);
        assertThat(state.activeSignals()).isEmpty();
    }

    @Test
    void shouldCheckSignificance() {
        var state = new LayerAnomalyState("TEST", 0.6, Instant.now(), List.of("SIG"), "test");

        assertThat(state.isSignificant(0.5)).isTrue();
        assertThat(state.isSignificant(0.6)).isTrue();
        assertThat(state.isSignificant(0.7)).isFalse();
    }

    @Test
    void shouldCheckActiveSignals() {
        var withSignals = new LayerAnomalyState("TEST", 0.5, Instant.now(), List.of("SIG"), "test");
        var noSignals = new LayerAnomalyState("TEST", 0.5, Instant.now(), List.of(), "test");

        assertThat(withSignals.hasActiveSignals()).isTrue();
        assertThat(noSignals.hasActiveSignals()).isFalse();
    }

    @Test
    void shouldMakeDefensiveCopyOfSignals() {
        var mutableList = new java.util.ArrayList<>(List.of("SIG1", "SIG2"));
        var state = new LayerAnomalyState("TEST", 0.5, Instant.now(), mutableList, "test");

        mutableList.add("SIG3");

        assertThat(state.activeSignals()).hasSize(2);
        assertThat(state.activeSignals()).containsExactly("SIG1", "SIG2");
    }
}
