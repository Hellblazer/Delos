/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.witness.committee.CommitteeBLSKeyStore;
import com.hellblazer.delos.witness.committee.GenesisTransitionCoordinator;
import com.hellblazer.delos.witness.committee.TransitionReadinessChecker;
import com.hellblazer.delos.witness.committee.TransitionStatus;
import com.hellblazer.delos.witness.migration.MigrationPhase;
import com.hellblazer.delos.witness.migration.MigrationStateTracker;
import com.hellblazer.delos.witness.validation.ByzantineWitnessDetector;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test-first implementation for Phase1B3HealthProvider.
 * Tests non-blocking health aggregation from all Phase 1B-3 components.
 */
class HealthCheckPhase1B3Test {

    @Test
    void shouldAggregateHealthFromAllComponents() {
        // Setup real instances where possible
        var stateTracker = new MigrationStateTracker(MigrationPhase.BLS_ONLY, 1L);

        // Use simple mock for keyStore
        var keyStore = mock(CommitteeBLSKeyStore.class);
        when(keyStore.keyCount()).thenReturn(7);

        var parameters = new WitnessParameters(10, 7, 1L, Duration.ofSeconds(30));
        var readinessChecker = new TransitionReadinessChecker(keyStore, parameters);

        var coordinator = new GenesisTransitionCoordinator(readinessChecker, stateTracker, parameters);

        var detector = new ByzantineWitnessDetector(new ConcurrentHashMap<>());

        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);

        // Create health provider
        var provider = new Phase1B3HealthProviderImpl(
            stateTracker,
            readinessChecker,
            coordinator,
            detector,
            metrics
        );

        // Verify health aggregation
        var health = provider.getHealth();
        assertThat(health).isNotNull();
        assertThat(health.currentPhase()).isEqualTo(MigrationPhase.BLS_ONLY);
        assertThat(health.transitionStatus()).isEqualTo(TransitionStatus.WAITING_FOR_READINESS);
        assertThat(health.registeredKeyCount()).isEqualTo(7);
        assertThat(health.totalMemberCount()).isEqualTo(10);
        assertThat(health.shunnedMemberCount()).isEqualTo(0);
        assertThat(health.transitionInProgress()).isFalse(); // NOT_STARTED by default
    }

    @Test
    void shouldBeThreadSafeAndNonBlocking() throws InterruptedException {
        // Setup real instances with minimal blocking behavior
        var stateTracker = new MigrationStateTracker(MigrationPhase.DUAL, 1L);

        var keyStore = mock(CommitteeBLSKeyStore.class);
        when(keyStore.keyCount()).thenReturn(5);

        var parameters = new WitnessParameters(10, 7, 1L, Duration.ofSeconds(30));
        var readinessChecker = new TransitionReadinessChecker(keyStore, parameters);

        var coordinator = new GenesisTransitionCoordinator(readinessChecker, stateTracker, parameters);

        var detector = new ByzantineWitnessDetector(new ConcurrentHashMap<>());

        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);
        metrics.setBlsKeysRegistered(5);
        metrics.setBlsKeysCoverage(50.0);

        var provider = new Phase1B3HealthProviderImpl(
            stateTracker,
            readinessChecker,
            coordinator,
            detector,
            metrics
        );

        // Concurrent access test
        var thread1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                var health = provider.getHealth();
                assertThat(health).isNotNull();
            }
        });

        var thread2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                var health = provider.getHealth();
                assertThat(health).isNotNull();
            }
        });

        thread1.start();
        thread2.start();
        thread1.join(1000); // Should complete quickly (non-blocking)
        thread2.join(1000);

        assertThat(thread1.isAlive()).isFalse();
        assertThat(thread2.isAlive()).isFalse();
    }
}
