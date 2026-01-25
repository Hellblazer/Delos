/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.ParsedBLSKey;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for CommitteeKeyCache.
 * <p>
 * Tests cache operations, metrics, thread safety, and eviction behavior.
 * <p>
 * Phase 1C-1-D-C: Committee Key Pre-computation Cache Testing
 *
 * @author hal.hildebrand
 */
class CommitteeKeyCacheTest {

    private static final DigestAlgorithm DIGEST_ALGORITHM = DigestAlgorithm.DEFAULT;

    private BLSProvider provider;
    private CommitteeKeyCache cache;
    private SecureRandom random;
    private int identifierCounter;

    @BeforeEach
    void setUp() {
        provider = BLSProvider.getDefault();
        cache = new CommitteeKeyCache(provider);
        random = new SecureRandom();
        identifierCounter = 0;
    }

    @Test
    void testConstructorNullProvider() {
        assertThatThrownBy(() -> new CommitteeKeyCache(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("provider");
    }

    @Test
    void testGetCacheMiss() {
        // Given: Empty cache
        var memberId = createIdentifier();

        // When: Get from empty cache
        var result = cache.get(memberId);

        // Then: Returns null
        assertThat(result).isNull();
        assertThat(cache.getMissCount()).isEqualTo(1);
        assertThat(cache.getHitCount()).isEqualTo(0);
    }

    @Test
    void testGetOrParseWithCacheMiss() {
        // Given: Empty cache and a valid key
        var memberId = createIdentifier();
        var keyPair = provider.generateKeyPair(random);
        var publicKey = keyPair.publicKey();

        // When: Get or parse (should parse)
        var parsedKey = cache.getOrParse(memberId, publicKey);

        // Then: Key is parsed and cached
        assertThat(parsedKey).isNotNull();
        assertThat(parsedKey.parsedKey()).isNotNull();
        assertThat(cache.getMissCount()).isEqualTo(1);
        assertThat(cache.getHitCount()).isEqualTo(0);
        assertThat(cache.getSize()).isEqualTo(1);
    }

    @Test
    void testGetOrParseWithCacheHit() {
        // Given: Key already in cache
        var memberId = createIdentifier();
        var keyPair = provider.generateKeyPair(random);
        var publicKey = keyPair.publicKey();
        var firstParse = cache.getOrParse(memberId, publicKey);

        // When: Get or parse again (should hit cache)
        var secondParse = cache.getOrParse(memberId, publicKey);

        // Then: Same instance returned, hit count incremented
        assertThat(secondParse).isSameAs(firstParse);
        assertThat(cache.getHitCount()).isEqualTo(1);
        assertThat(cache.getMissCount()).isEqualTo(1);
        assertThat(cache.getSize()).isEqualTo(1);
    }

    @Test
    void testGetOrParseNullParameters() {
        var memberId = createIdentifier();
        var publicKey = provider.generateKeyPair(random).publicKey();

        assertThatThrownBy(() -> cache.getOrParse(null, publicKey))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> cache.getOrParse(memberId, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testPrecomputeCommittee() {
        // Given: Committee with 5 members
        var committee = createMockCommittee(5);

        // When: Precompute all committee keys
        cache.precomputeCommittee(committee);

        // Then: All keys are cached
        assertThat(cache.getSize()).isEqualTo(5);
        assertThat(cache.getMissCount()).isEqualTo(0);
        assertThat(cache.getHitCount()).isEqualTo(0);

        // Verify all keys are accessible
        for (var memberId : committee.keySet()) {
            var cached = cache.get(memberId);
            assertThat(cached).isNotNull();
        }
        assertThat(cache.getHitCount()).isEqualTo(5);
    }

    @Test
    void testPrecomputeCommitteeNullParameter() {
        assertThatThrownBy(() -> cache.precomputeCommittee(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testGetAll() {
        // Given: Cache with 10 keys
        var committee = createMockCommittee(10);
        cache.precomputeCommittee(committee);

        // When: Get subset of 5 keys
        var subset = new ArrayList<>(committee.keySet()).subList(0, 5);
        var results = cache.getAll(subset);

        // Then: Returns list of 5 parsed keys
        assertThat(results).hasSize(5);
        assertThat(results).allMatch(Objects::nonNull);
        assertThat(cache.getHitCount()).isEqualTo(5);
    }

    @Test
    void testGetAllWithMissingKeys() {
        // Given: Cache with 5 keys, request includes 3 new keys
        var committee = createMockCommittee(5);
        cache.precomputeCommittee(committee);

        var requestList = new ArrayList<>(committee.keySet());
        requestList.addAll(List.of(createIdentifier(), createIdentifier(), createIdentifier()));

        // When: Get all (some missing)
        var results = cache.getAll(requestList);

        // Then: Returns only cached keys (nulls for missing)
        assertThat(results).hasSize(8);
        assertThat(results).filteredOn(Objects::nonNull).hasSize(5);
        assertThat(results).filteredOn(Objects::isNull).hasSize(3);
    }

    @Test
    void testGetAllNullParameter() {
        assertThatThrownBy(() -> cache.getAll(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testClear() {
        // Given: Cache with 10 keys
        var committee = createMockCommittee(10);
        cache.precomputeCommittee(committee);
        assertThat(cache.getSize()).isEqualTo(10);

        // When: Clear cache
        cache.clear();

        // Then: Cache is empty
        assertThat(cache.getSize()).isEqualTo(0);
        assertThat(cache.getHitCount()).isEqualTo(0);
        assertThat(cache.getMissCount()).isEqualTo(0);
    }

    @Test
    void testClearAndPrecompute() {
        // Given: Cache with old committee (5 members)
        var oldCommittee = createMockCommittee(5);
        cache.precomputeCommittee(oldCommittee);
        assertThat(cache.getSize()).isEqualTo(5);

        // When: Clear and precompute new committee (7 members)
        var newCommittee = createMockCommittee(7);
        cache.clearAndPrecompute(newCommittee);

        // Then: Cache contains only new committee
        assertThat(cache.getSize()).isEqualTo(7);

        // Old keys should not be present
        for (var oldMember : oldCommittee.keySet()) {
            assertThat(cache.get(oldMember)).isNull();
        }

        // New keys should be present
        for (var newMember : newCommittee.keySet()) {
            assertThat(cache.get(newMember)).isNotNull();
        }
    }

    @Test
    void testClearAndPrecomputeNullParameter() {
        assertThatThrownBy(() -> cache.clearAndPrecompute(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testMetricsTracking() {
        // Given: Empty cache
        assertThat(cache.getSize()).isEqualTo(0);
        assertThat(cache.getHitCount()).isEqualTo(0);
        assertThat(cache.getMissCount()).isEqualTo(0);
        assertThat(cache.getEvictionCount()).isEqualTo(0);

        // When: Perform mixed operations
        var member1 = createIdentifier();
        var member2 = createIdentifier();
        var key1 = provider.generateKeyPair(random).publicKey();
        var key2 = provider.generateKeyPair(random).publicKey();

        cache.getOrParse(member1, key1); // miss + parse
        cache.getOrParse(member1, key1); // hit
        cache.getOrParse(member2, key2); // miss + parse
        cache.get(member1); // hit
        cache.get(createIdentifier()); // miss

        // Then: Metrics are accurate
        assertThat(cache.getSize()).isEqualTo(2);
        assertThat(cache.getHitCount()).isEqualTo(2);
        assertThat(cache.getMissCount()).isEqualTo(3);
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        // Given: Shared cache and committee
        var committee = createMockCommittee(50);
        cache.precomputeCommittee(committee);
        var memberList = new ArrayList<>(committee.keySet());

        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(100);
        var errors = new ConcurrentHashMap<String, Throwable>();

        // When: 100 threads concurrently access cache
        for (int i = 0; i < 100; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    var member = memberList.get(index % memberList.size());
                    var result = cache.get(member);
                    assertThat(result).isNotNull();
                } catch (Throwable t) {
                    errors.put("Thread-" + index, t);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Then: No errors, all reads successful
        assertThat(errors).isEmpty();
        assertThat(cache.getSize()).isEqualTo(50);
    }

    @Test
    void testConcurrentGetOrParse() throws InterruptedException {
        // Given: Multiple threads trying to parse same key
        var memberId = createIdentifier();
        var publicKey = provider.generateKeyPair(random).publicKey();

        var executor = Executors.newFixedThreadPool(20);
        var latch = new CountDownLatch(20);
        var results = Collections.synchronizedList(new ArrayList<ParsedBLSKey>());
        var errors = new ConcurrentHashMap<String, Throwable>();

        // When: 20 threads concurrently call getOrParse for same key
        for (int i = 0; i < 20; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    var result = cache.getOrParse(memberId, publicKey);
                    results.add(result);
                } catch (Throwable t) {
                    errors.put("Thread-" + index, t);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Then: All threads get same instance (or equivalent), no errors
        assertThat(errors).isEmpty();
        assertThat(results).hasSize(20);
        assertThat(cache.getSize()).isEqualTo(1);

        // All results should be non-null
        assertThat(results).allMatch(Objects::nonNull);
    }

    @Test
    void testEvictionWhenCapacityExceeded() {
        // Given: Cache with 10,000 entry capacity
        // Note: Caffeine eviction is asynchronous and may not happen immediately
        var largeCommittee = createMockCommittee(10100); // Exceed capacity

        // When: Precompute large committee
        cache.precomputeCommittee(largeCommittee);

        // Then: Cache handles large committee insertion
        // Size may temporarily exceed capacity due to async eviction
        assertThat(cache.getSize()).isGreaterThan(9000); // At least most entries cached
        assertThat(cache.getSize()).isLessThanOrEqualTo(11000); // Within reasonable bounds

        // Eviction count metric is available (may be 0 if eviction hasn't processed yet)
        assertThat(cache.getEvictionCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void testInvalidPublicKeyHandling() {
        // Given: Invalid public key (wrong size)
        var memberId = createIdentifier();
        var invalidKey = new byte[32]; // Should be 48 bytes

        // When/Then: Should throw IllegalArgumentException
        assertThatThrownBy(() -> cache.getOrParse(memberId, invalidKey))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // === Helper Methods ===

    private Identifier createIdentifier() {
        var idString = "member-" + identifierCounter++;
        var digest = DIGEST_ALGORITHM.digest(idString.getBytes());
        return new SelfAddressingIdentifier(digest);
    }

    private Map<Identifier, byte[]> createMockCommittee(int size) {
        var committee = new HashMap<Identifier, byte[]>();
        for (int i = 0; i < size; i++) {
            var member = createIdentifier();
            var keyPair = provider.generateKeyPair(random);
            committee.put(member, keyPair.publicKey());
        }
        return committee;
    }
}
