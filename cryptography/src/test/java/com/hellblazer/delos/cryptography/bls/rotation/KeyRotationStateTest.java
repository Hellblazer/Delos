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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for KeyRotationState.
 * Validates thread-safe state management and lifecycle transitions.
 * <p>
 * Phase 1C-3-A-3: Key rotation testing - State management
 *
 * @author hal.hildebrand
 */
class KeyRotationStateTest {

    private static final BLSProvider PROVIDER = BLSProvider.getDefault();
    private static final Random RANDOM = new Random(42);

    private KeyRotationState state;

    @BeforeEach
    void setUp() {
        state = new KeyRotationState();
    }

    @Test
    void shouldAddKeyVersion() {
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var version = new KeyVersion(
            1,
            Instant.now(),
            null,
            KeyStatus.GENERATED,
            "rotation-1",
            keyPair.publicKey().proofOfPossession()
        );

        state.addKeyVersion(version);

        assertThat(state.keyVersions()).containsKey(1);
        assertThat(state.keyVersions().get(1)).isEqualTo(version);
    }

    @Test
    void shouldRejectDuplicateVersionNumber() {
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var version1 = new KeyVersion(
            1,
            Instant.now(),
            null,
            KeyStatus.GENERATED,
            "rotation-1",
            keyPair.publicKey().proofOfPossession()
        );

        state.addKeyVersion(version1);

        // Attempt to add another version with same number
        var version2 = new KeyVersion(
            1,
            Instant.now(),
            null,
            KeyStatus.ACTIVE,
            "rotation-2",
            keyPair.publicKey().proofOfPossession()
        );

        assertThatThrownBy(() -> state.addKeyVersion(version2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void shouldUpdateKeyVersion() {
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var now = Instant.now();
        var version = new KeyVersion(
            1,
            now,
            null,
            KeyStatus.GENERATED,
            "rotation-1",
            keyPair.publicKey().proofOfPossession()
        );

        state.addKeyVersion(version);

        // Update to ACTIVE status
        var updatedVersion = new KeyVersion(
            1,
            now,
            null,
            KeyStatus.ACTIVE,
            "rotation-1",
            keyPair.publicKey().proofOfPossession()
        );

        state.updateKeyVersion(updatedVersion);

        assertThat(state.keyVersions().get(1).status()).isEqualTo(KeyStatus.ACTIVE);
    }

    @Test
    void shouldRejectUpdateOfNonexistentVersion() {
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var version = new KeyVersion(
            99,
            Instant.now(),
            null,
            KeyStatus.ACTIVE,
            "rotation-99",
            keyPair.publicKey().proofOfPossession()
        );

        assertThatThrownBy(() -> state.updateKeyVersion(version))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not exist");
    }

    @Test
    void shouldActivateKeyVersion() {
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var version = new KeyVersion(
            1,
            Instant.now(),
            null,
            KeyStatus.GENERATED,
            "rotation-1",
            keyPair.publicKey().proofOfPossession()
        );

        state.addKeyVersion(version);
        state.activateKeyVersion(1);

        var activeKey = state.getActiveKey();
        assertThat(activeKey).isPresent();
        assertThat(activeKey.get().versionNumber()).isEqualTo(1);
    }

    @Test
    void shouldTrackPreviousKeyDuringActivation() {
        var keyPair1 = BLSKeyPair.generate(RANDOM, PROVIDER);
        var version1 = new KeyVersion(
            1,
            Instant.now(),
            null,
            KeyStatus.ACTIVE,
            "rotation-1",
            keyPair1.publicKey().proofOfPossession()
        );

        state.addKeyVersion(version1);
        state.activateKeyVersion(1);

        // Activate second key
        var keyPair2 = BLSKeyPair.generate(RANDOM, PROVIDER);
        var version2 = new KeyVersion(
            2,
            Instant.now(),
            null,
            KeyStatus.ACTIVE,
            "rotation-2",
            keyPair2.publicKey().proofOfPossession()
        );

        state.addKeyVersion(version2);
        state.activateKeyVersion(2);

        // Previous key should now be version 1
        var previousKey = state.getPreviousKey();
        assertThat(previousKey).isPresent();
        assertThat(previousKey.get().versionNumber()).isEqualTo(1);
    }

    @Test
    void shouldReturnValidKeysIncludingGracePeriod() {
        var now = Instant.now();
        var gracePeriod = Duration.ofMinutes(5);

        // Active key
        var keyPair1 = BLSKeyPair.generate(RANDOM, PROVIDER);
        var activeVersion = new KeyVersion(
            2,
            now,
            null,
            KeyStatus.ACTIVE,
            "rotation-2",
            keyPair1.publicKey().proofOfPossession()
        );

        // Deprecated key within grace period
        var keyPair2 = BLSKeyPair.generate(RANDOM, PROVIDER);
        var deprecatedVersion = new KeyVersion(
            1,
            now.minus(Duration.ofDays(1)),
            now.minus(Duration.ofMinutes(2)),  // Deprecated 2 minutes ago
            KeyStatus.DEPRECATED,
            "rotation-1",
            keyPair2.publicKey().proofOfPossession()
        );

        state.addKeyVersion(activeVersion);
        state.addKeyVersion(deprecatedVersion);

        var validKeys = state.getValidKeys(now, gracePeriod);

        // Both should be valid (active + deprecated within grace period)
        assertThat(validKeys).hasSize(2);
        assertThat(validKeys).containsKeys(1, 2);
    }

    @Test
    void shouldExcludeExpiredKeysAfterGracePeriod() {
        var now = Instant.now();
        var gracePeriod = Duration.ofMinutes(5);

        // Active key
        var keyPair1 = BLSKeyPair.generate(RANDOM, PROVIDER);
        var activeVersion = new KeyVersion(
            2,
            now,
            null,
            KeyStatus.ACTIVE,
            "rotation-2",
            keyPair1.publicKey().proofOfPossession()
        );

        // Deprecated key past grace period
        var keyPair2 = BLSKeyPair.generate(RANDOM, PROVIDER);
        var expiredVersion = new KeyVersion(
            1,
            now.minus(Duration.ofDays(1)),
            now.minus(Duration.ofMinutes(10)),  // Deprecated 10 minutes ago
            KeyStatus.DEPRECATED,
            "rotation-1",
            keyPair2.publicKey().proofOfPossession()
        );

        state.addKeyVersion(activeVersion);
        state.addKeyVersion(expiredVersion);

        var validKeys = state.getValidKeys(now, gracePeriod);

        // Only active key should be valid
        assertThat(validKeys).hasSize(1);
        assertThat(validKeys).containsKey(2);
    }

    @Test
    void shouldArchiveOldVersions() {
        var now = Instant.now();

        // Add 5 deprecated keys
        for (int i = 1; i <= 5; i++) {
            var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
            var version = new KeyVersion(
                i,
                now.minus(Duration.ofDays(i)),
                now.minus(Duration.ofDays(i - 1)),
                KeyStatus.DEPRECATED,
                "rotation-" + i,
                keyPair.publicKey().proofOfPossession()
            );
            state.addKeyVersion(version);
        }

        // Archive, keeping only 2 versions
        state.archiveOldVersions(2);

        // First 3 should be archived
        assertThat(state.keyVersions().get(1).status()).isEqualTo(KeyStatus.ARCHIVED);
        assertThat(state.keyVersions().get(2).status()).isEqualTo(KeyStatus.ARCHIVED);
        assertThat(state.keyVersions().get(3).status()).isEqualTo(KeyStatus.ARCHIVED);

        // Last 2 should still be deprecated
        assertThat(state.keyVersions().get(4).status()).isEqualTo(KeyStatus.DEPRECATED);
        assertThat(state.keyVersions().get(5).status()).isEqualTo(KeyStatus.DEPRECATED);
    }

    @Test
    void shouldHandleConcurrentReads() throws InterruptedException, ExecutionException {
        // Add some key versions
        for (int i = 1; i <= 10; i++) {
            var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
            var version = new KeyVersion(
                i,
                Instant.now(),
                null,
                KeyStatus.ACTIVE,
                "rotation-" + i,
                keyPair.publicKey().proofOfPossession()
            );
            state.addKeyVersion(version);
        }

        // 32 threads reading concurrently
        int threadCount = 32;
        var executor = Executors.newFixedThreadPool(threadCount);
        var futures = new ArrayList<Future<?>>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                for (int j = 0; j < 100; j++) {
                    var versions = state.keyVersions();
                    assertThat(versions).hasSize(10);
                }
            }));
        }

        // Wait for all threads
        for (var future : futures) {
            future.get();
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shouldHandleConcurrentWrites() throws InterruptedException, ExecutionException {
        int threadCount = 16;
        int operationsPerThread = 10;
        var executor = Executors.newFixedThreadPool(threadCount);
        var futures = new ArrayList<Future<?>>();
        var versionCounter = new AtomicInteger(1);

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    int versionNumber = versionCounter.getAndIncrement();
                    var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
                    var version = new KeyVersion(
                        versionNumber,
                        Instant.now(),
                        null,
                        KeyStatus.GENERATED,
                        "rotation-" + versionNumber,
                        keyPair.publicKey().proofOfPossession()
                    );
                    state.addKeyVersion(version);
                }
            }));
        }

        // Wait for all threads
        for (var future : futures) {
            future.get();
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // Should have all versions
        int expectedVersions = threadCount * operationsPerThread;
        assertThat(state.keyVersions()).hasSize(expectedVersions);
    }

    @Test
    void shouldHandleMixedConcurrentOperations() throws InterruptedException, ExecutionException {
        int threadCount = 32;
        int operationsPerThread = 100;
        var executor = Executors.newFixedThreadPool(threadCount);
        var futures = new ArrayList<Future<?>>();
        var versionCounter = new AtomicInteger(1);

        // Add initial version
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var version = new KeyVersion(
            versionCounter.getAndIncrement(),
            Instant.now(),
            null,
            KeyStatus.ACTIVE,
            "rotation-0",
            keyPair.publicKey().proofOfPossession()
        );
        state.addKeyVersion(version);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            futures.add(executor.submit(() -> {
                var random = new Random(threadId);
                for (int j = 0; j < operationsPerThread; j++) {
                    int operation = random.nextInt(3);
                    switch (operation) {
                        case 0 -> {
                            // Read
                            var versions = state.keyVersions();
                            assertThat(versions).isNotEmpty();
                        }
                        case 1 -> {
                            // Write
                            int versionNumber = versionCounter.getAndIncrement();
                            var kp = BLSKeyPair.generate(RANDOM, PROVIDER);
                            var v = new KeyVersion(
                                versionNumber,
                                Instant.now(),
                                null,
                                KeyStatus.GENERATED,
                                "rotation-" + versionNumber,
                                kp.publicKey().proofOfPossession()
                            );
                            state.addKeyVersion(v);
                        }
                        case 2 -> {
                            // Get active key
                            state.getActiveKey();
                        }
                    }
                }
            }));
        }

        // Wait for all threads
        for (var future : futures) {
            future.get();
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // State should be consistent
        assertThat(state.keyVersions()).isNotEmpty();
    }
}
