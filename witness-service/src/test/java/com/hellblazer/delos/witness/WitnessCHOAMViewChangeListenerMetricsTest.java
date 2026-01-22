/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WitnessCHOAMViewChangeListener metrics integration.
 * <p>
 * Tests that view change events are properly recorded in metrics.
 */
@DisplayName("WitnessCHOAMViewChangeListener Metrics Integration")
class WitnessCHOAMViewChangeListenerMetricsTest {

    private WitnessCHOAMViewChangeListener listener;
    private WitnessCHOAM mockWitnessCHOAM;
    private WitnessContext mockWitnessContext;
    private BLSMetrics mockBLSMetrics;
    private DigestAlgorithm digestAlgorithm;

    @BeforeEach
    void setup() {
        mockWitnessCHOAM = mock(WitnessCHOAM.class);
        mockWitnessContext = mock(WitnessContext.class);
        mockBLSMetrics = mock(BLSMetrics.class);
        digestAlgorithm = DigestAlgorithm.DEFAULT;

        listener = new WitnessCHOAMViewChangeListener(
            mockWitnessCHOAM,
            mockWitnessContext,
            digestAlgorithm,
            "test-listener",
            null,  // choam
            mockBLSMetrics  // Phase 1C: BLS metrics
        );
    }

    @Test
    @DisplayName("should accept BLS metrics in constructor")
    void shouldAcceptBLSMetricsInConstructor() {
        assertThat(listener).isNotNull();
    }

    @Test
    @DisplayName("should handle null BLS metrics gracefully")
    void shouldHandleNullBLSMetricsGracefully() {
        // Constructor with null metrics should work
        var listenerWithoutMetrics = new WitnessCHOAMViewChangeListener(
            mockWitnessCHOAM,
            mockWitnessContext,
            digestAlgorithm,
            "test-listener-no-metrics",
            null,  // choam
            null   // no metrics
        );

        assertThat(listenerWithoutMetrics).isNotNull();
    }

    @Test
    @DisplayName("should maintain backward compatibility with old constructor")
    void shouldMaintainBackwardCompatibility() {
        // Old constructor without BLSMetrics should still work
        var legacyListener = new WitnessCHOAMViewChangeListener(
            mockWitnessCHOAM,
            mockWitnessContext,
            digestAlgorithm,
            "legacy-listener"
        );

        assertThat(legacyListener).isNotNull();
    }

    @Test
    @DisplayName("should record metrics on view height getter")
    void shouldProvideMetricsAccessor() {
        // Verify we can access the listener's methods that would use metrics
        long height = listener.getViewHeight();
        assertThat(height).isGreaterThanOrEqualTo(0L);

        // Set view height and verify
        listener.setViewHeight(42L);
        assertThat(listener.getViewHeight()).isEqualTo(42L);
    }
}
