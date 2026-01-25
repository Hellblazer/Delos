/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stress and performance tests for BLS key rotation.
 * Tests scalability, concurrency, and performance under load.
 * <p>
 * Phase 1C-3-A-3: Key rotation testing - Stress and performance
 *
 * @author hal.hildebrand
 */
@Tag("stress")
class KeyRotationStressTest extends KeyRotationTestBase {

    @Test
    @Timeout(60)
    void shouldHandleRapidConsecutiveRotations() {
        var manager = createManager("stress-member");

        // 100 rapid rotations
        for (int i = 1; i <= 100; i++) {
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            manager.activateKeyVersion(i);
        }

        // Verify final state
        var activeKey = manager.getState().getActiveKey();
        assertThat(activeKey).isPresent();
        assertThat(activeKey.get().versionNumber()).isEqualTo(100);

        // All 100 versions should exist
        assertThat(manager.getState().keyVersions()).hasSize(100);
    }

    @Test
    @Timeout(60)
    void shouldHandleLargeCommitteeRotation() {
        var committee = createCommittee(COMMITTEE_SIZE_LARGE);

        // Rotate entire large committee (n=13)
        var start = System.nanoTime();

        initiateRotationOnCommittee(committee);
        activateOnCommittee(committee, 1);

        var elapsed = Duration.ofNanos(System.nanoTime() - start);

        // All members should have active keys
        for (var manager : committee) {
            assertThat(manager.getState().getActiveKey()).isPresent();
        }

        // Performance check: should complete in reasonable time
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @Timeout(60)
    void shouldHandleHighConcurrencyOperations() throws InterruptedException, ExecutionException {
        var manager = createManager("concurrent-member");

        // 32 threads, 1000 total operations
        int threadCount = 32;
        int operationsPerThread = 1000 / threadCount;
        var executor = Executors.newFixedThreadPool(threadCount);
        var futures = new ArrayList<Future<?>>();
        var versionCounter = new AtomicInteger(1);
        var errorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            futures.add(executor.submit(() -> {
                var random = new Random(threadId);
                for (int j = 0; j < operationsPerThread; j++) {
                    try {
                        int operation = random.nextInt(3);
                        switch (operation) {
                            case 0 -> {
                                // Generate key pair
                                manager.generateNewKeyPair();
                            }
                            case 1 -> {
                                // Read state
                                var state = manager.getState();
                                state.keyVersions();
                            }
                            case 2 -> {
                                // Get valid keys
                                manager.getValidKeys(Instant.now());
                            }
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                }
            }));
        }

        // Wait for completion
        for (var future : futures) {
            future.get();
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // No errors should occur
        assertThat(errorCount.get()).isEqualTo(0);
    }

    @Test
    @Timeout(120)
    void shouldHandleConcurrentRotationsOnCommittee() throws InterruptedException, ExecutionException {
        var committee = createCommittee(COMMITTEE_SIZE_MEDIUM);
        int rotationCount = 10;
        var executor = Executors.newFixedThreadPool(committee.size());
        var futures = new ArrayList<Future<?>>();

        for (var manager : committee) {
            futures.add(executor.submit(() -> {
                for (int i = 1; i <= rotationCount; i++) {
                    var keyPair = manager.generateNewKeyPair();
                    manager.initiateRotation(keyPair);
                    manager.activateKeyVersion(i);
                }
            }));
        }

        // Wait for all members to complete
        for (var future : futures) {
            future.get();
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        // All members should be on version 10
        for (var manager : committee) {
            var activeKey = manager.getState().getActiveKey();
            assertThat(activeKey).isPresent();
            assertThat(activeKey.get().versionNumber()).isEqualTo(rotationCount);
        }
    }

    @Test
    @Timeout(60)
    void shouldHandleMemoryPressureWithManyVersions() {
        var manager = createManager("memory-test");

        // Create and activate 1000 key versions
        // This creates DEPRECATED versions that can be archived
        for (int i = 1; i <= 1000; i++) {
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            manager.activateKeyVersion(i);
        }

        // Verify all versions tracked
        assertThat(manager.getState().keyVersions()).hasSize(1000);

        // Count deprecated (archivable) keys before archiving
        int deprecatedCount = 0;
        for (var version : manager.getState().keyVersions().values()) {
            if (version.status() == KeyStatus.DEPRECATED) {
                deprecatedCount++;
            }
        }
        assertThat(deprecatedCount).isGreaterThan(0);

        // Archive old versions (keep only recent 10)
        manager.getState().archiveOldVersions(10);

        // Should still have 1000 versions, but old ones archived
        assertThat(manager.getState().keyVersions()).hasSize(1000);

        // Count archived
        int archivedCount = 0;
        for (var version : manager.getState().keyVersions().values()) {
            if (version.status() == KeyStatus.ARCHIVED) {
                archivedCount++;
            }
        }

        assertThat(archivedCount).isGreaterThan(0);
    }

    @Test
    @Timeout(60)
    void shouldMaintainPerformanceUnderLoad() {
        var manager = createManager("performance-test");

        // Warm up
        for (int i = 0; i < 10; i++) {
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
        }

        // Measure rotation performance
        var rotationTimes = new ArrayList<Duration>();
        for (int i = 0; i < 100; i++) {
            var start = System.nanoTime();
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            var elapsed = Duration.ofNanos(System.nanoTime() - start);
            rotationTimes.add(elapsed);
        }

        // Calculate average
        var averageNanos = rotationTimes.stream()
            .mapToLong(Duration::toNanos)
            .average()
            .orElse(0);

        var averageRotation = Duration.ofNanos((long) averageNanos);

        // Performance target: <10ms per rotation
        assertThat(averageRotation).isLessThan(Duration.ofMillis(10));
    }

    @Test
    @Timeout(60)
    void shouldHandleValidKeysQueryPerformance() {
        var manager = createManager("query-performance");

        // Create 100 key versions with varying states
        for (int i = 1; i <= 100; i++) {
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            if (i % 10 == 0) {
                manager.activateKeyVersion(i);
            }
        }

        // Measure getValidKeys performance
        var queryTimes = new ArrayList<Duration>();
        var now = Instant.now();

        for (int i = 0; i < 1000; i++) {
            var start = System.nanoTime();
            manager.getValidKeys(now);
            var elapsed = Duration.ofNanos(System.nanoTime() - start);
            queryTimes.add(elapsed);
        }

        // Calculate average
        var averageNanos = queryTimes.stream()
            .mapToLong(Duration::toNanos)
            .average()
            .orElse(0);

        var averageQuery = Duration.ofNanos((long) averageNanos);

        // Performance target: <2ms for query with grace period
        assertThat(averageQuery).isLessThan(Duration.ofMillis(2));
    }

    @Test
    @Timeout(120)
    void shouldHandleThreadContentionOnState() throws InterruptedException, ExecutionException {
        var manager = createManager("contention-test");

        // Pre-populate with some versions
        for (int i = 1; i <= 10; i++) {
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
            manager.activateKeyVersion(i);
        }

        // 64 threads hammering the state
        int threadCount = 64;
        int readsPerThread = 1000;
        var executor = Executors.newFixedThreadPool(threadCount);
        var futures = new ArrayList<Future<?>>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                for (int j = 0; j < readsPerThread; j++) {
                    // Read operations (high contention)
                    manager.getState().getActiveKey();
                    manager.getState().getPreviousKey();
                    manager.getState().keyVersions();
                    manager.getValidKeys(Instant.now());
                }
            }));
        }

        // Wait for completion
        for (var future : futures) {
            future.get();
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        // State should remain consistent
        var activeKey = manager.getState().getActiveKey();
        assertThat(activeKey).isPresent();
        assertThat(activeKey.get().versionNumber()).isEqualTo(10);
    }

    @Test
    @Timeout(60)
    void shouldHandleActivationPerformance() {
        var manager = createManager("activation-perf");

        // Create 100 versions
        for (int i = 1; i <= 100; i++) {
            var keyPair = manager.generateNewKeyPair();
            manager.initiateRotation(keyPair);
        }

        // Measure activation performance
        var activationTimes = new ArrayList<Duration>();

        for (int i = 1; i <= 100; i++) {
            var start = System.nanoTime();
            manager.activateKeyVersion(i);
            var elapsed = Duration.ofNanos(System.nanoTime() - start);
            activationTimes.add(elapsed);
        }

        // Calculate average
        var averageNanos = activationTimes.stream()
            .mapToLong(Duration::toNanos)
            .average()
            .orElse(0);

        var averageActivation = Duration.ofNanos((long) averageNanos);

        // Performance target: <5ms per activation
        assertThat(averageActivation).isLessThan(Duration.ofMillis(5));
    }
}
