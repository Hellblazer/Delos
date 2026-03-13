/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for NonceVerifier / InMemoryNonceVerifier.
 * Tests are written TDD-style: they define the contract, and all 7 tests
 * must pass once InMemoryNonceVerifier is implemented.
 *
 * @author hal.hildebrand
 */
public class NonceVerifierTest {

    private ScheduledExecutorService scheduler;
    private NonceVerifier             verifier;

    /** A stable member Digest used across tests. */
    private static Digest memberDigest(int seed) {
        var bytes = new byte[DigestAlgorithm.DEFAULT.digestLength()];
        bytes[0] = (byte) seed;
        return new Digest(DigestAlgorithm.DEFAULT, bytes);
    }

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        // Standard TTL: 5 seconds; large capacity for most tests
        verifier = new InMemoryNonceVerifier(Duration.ofSeconds(5), 100_000, scheduler);
    }

    @AfterEach
    void tearDown() {
        verifier.close();
        scheduler.shutdownNow();
    }

    // -----------------------------------------------------------------------
    // Test 1: generateNonce uniqueness
    // -----------------------------------------------------------------------

    /**
     * Generating 10,000 nonces should yield all distinct values.
     * The interface contract requires each call to return a distinct value.
     */
    @Test
    void testGenerateNonce_Uniqueness() {
        int count = 10_000;
        Set<String> nonces = Collections.synchronizedSet(new HashSet<>());
        for (int i = 0; i < count; i++) {
            nonces.add(verifier.generateNonce());
        }
        assertThat(nonces).hasSize(count);
    }

    // -----------------------------------------------------------------------
    // Test 2: first use accepted
    // -----------------------------------------------------------------------

    /**
     * The first time a nonce is presented it should be accepted (returns true).
     */
    @Test
    void testRecordAndVerify_FirstUse_Accepted() {
        var nonce = verifier.generateNonce();
        var result = verifier.recordAndVerify(nonce, memberDigest(1));
        assertThat(result).isTrue();
    }

    // -----------------------------------------------------------------------
    // Test 3: replay rejected
    // -----------------------------------------------------------------------

    /**
     * A nonce that is submitted a second time (same member) must be rejected (returns false).
     */
    @Test
    void testRecordAndVerify_Replay_Rejected() {
        var nonce = verifier.generateNonce();
        var member = memberDigest(2);

        assertThat(verifier.recordAndVerify(nonce, member)).isTrue();   // first use
        assertThat(verifier.recordAndVerify(nonce, member)).isFalse();  // replay
    }

    // -----------------------------------------------------------------------
    // Test 4: different members may share a nonce (same quorum request)
    // -----------------------------------------------------------------------

    /**
     * Multiple DHT quorum members legitimately respond to the same request carrying
     * the same nonce. Each member's response is keyed by (nonce, memberId), so
     * different members responding to the same request are all accepted.
     *
     * <p>Replay protection is per-member: the same (nonce, member) pair is rejected
     * on second use, but a different member using the same nonce is accepted —
     * representing a normal quorum response flow.</p>
     */
    @Test
    void testRecordAndVerify_DifferentMember_SameNonce_BothAccepted() {
        var nonce   = verifier.generateNonce();
        var memberA = memberDigest(3);
        var memberB = memberDigest(4);

        // Both members responding to the same request (same nonce) — both accepted
        assertThat(verifier.recordAndVerify(nonce, memberA)).isTrue();  // member A first use
        assertThat(verifier.recordAndVerify(nonce, memberB)).isTrue();  // member B first use (different key)

        // But if either member tries to replay their same (nonce, memberId) — rejected
        assertThat(verifier.recordAndVerify(nonce, memberA)).isFalse(); // member A replay
        assertThat(verifier.recordAndVerify(nonce, memberB)).isFalse(); // member B replay
    }

    // -----------------------------------------------------------------------
    // Test 5: TTL expiry — nonce reusable after TTL
    // -----------------------------------------------------------------------

    /**
     * After the TTL has elapsed, a nonce that was previously recorded should be
     * accepted again (the replay window has closed).
     *
     * <p>Uses a very short TTL (50 ms) so we don't slow down the test suite.</p>
     */
    @Test
    void testTTLExpiry_NonceReusableAfterExpiry() throws InterruptedException {
        // Create a short-lived verifier
        var shortTtl = Duration.ofMillis(50);
        var shortVerifier = new InMemoryNonceVerifier(shortTtl, 100_000, scheduler);
        try {
            var nonce  = shortVerifier.generateNonce();
            var member = memberDigest(5);

            assertThat(shortVerifier.recordAndVerify(nonce, member)).isTrue();  // first use
            assertThat(shortVerifier.recordAndVerify(nonce, member)).isFalse(); // immediate replay

            // Wait for TTL to expire
            Thread.sleep(shortTtl.toMillis() + 20);

            // After expiry the nonce slot is freed; fresh use accepted
            assertThat(shortVerifier.recordAndVerify(nonce, member)).isTrue();
        } finally {
            shortVerifier.close();
        }
    }

    // -----------------------------------------------------------------------
    // Test 6: capacity bounds — eviction occurs
    // -----------------------------------------------------------------------

    /**
     * When the verifier is at or beyond its capacity, it must not throw OOM.
     * The implementation should evict stale/oldest entries.
     * After eviction the verifier must continue to function correctly for new nonces.
     */
    @Test
    void testCapacityBounds_EvictionOccurs() {
        int smallCapacity = 100;
        var boundedVerifier = new InMemoryNonceVerifier(Duration.ofSeconds(30), smallCapacity, scheduler);
        try {
            var member = memberDigest(6);

            // Fill to capacity and slightly beyond
            for (int i = 0; i < smallCapacity + 50; i++) {
                var nonce = boundedVerifier.generateNonce();
                // Should not throw; eviction keeps size bounded
                boundedVerifier.recordAndVerify(nonce, member);
            }

            // Verifier must still be functional after eviction
            var freshNonce = boundedVerifier.generateNonce();
            assertThat(boundedVerifier.recordAndVerify(freshNonce, member)).isTrue();
        } finally {
            boundedVerifier.close();
        }
    }

    // -----------------------------------------------------------------------
    // Test 7: thread safety
    // -----------------------------------------------------------------------

    /**
     * 100 threads each call recordAndVerify with distinct nonces concurrently.
     * No exceptions should be thrown, no data should be corrupted, and each
     * thread's first-use nonce must be accepted.
     */
    @Test
    void testThreadSafety_ConcurrentAccess() throws InterruptedException {
        int threadCount = 100;
        var latch         = new CountDownLatch(threadCount);
        var successCount  = new AtomicInteger(0);
        var errorCount    = new AtomicInteger(0);
        var allNonces     = new String[threadCount];

        // Pre-generate nonces to avoid measuring generation time in the race
        for (int i = 0; i < threadCount; i++) {
            allNonces[i] = verifier.generateNonce();
        }

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            Thread.ofVirtual().start(() -> {
                try {
                    var member = memberDigest(idx % 10);
                    if (verifier.recordAndVerify(allNonces[idx], member)) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(errorCount.get()).isZero();
        // Each nonce is unique so every thread's first-use should succeed
        assertThat(successCount.get()).isEqualTo(threadCount);
    }
}
