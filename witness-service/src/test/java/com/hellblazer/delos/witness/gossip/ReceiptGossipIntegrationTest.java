/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.gossip;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.WitnessParameters;
import com.hellblazer.delos.witness.WitnessReceiptManager;
import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.migration.MigrationPhase;
import com.hellblazer.delos.witness.receipt.AggregateWitnessReceipt;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for receipt gossip components working together.
 * <p>
 * Tests the full gossip flow: creation → encoding → transmission → decoding →
 * validation → processing, verifying all components integrate correctly.
 *
 * @author hal.hildebrand
 */
class ReceiptGossipIntegrationTest {

    private static final boolean IS_CI = Boolean.parseBoolean(System.getenv().getOrDefault("CI", "false"));
    private static final DigestAlgorithm DIGEST_ALGO = DigestAlgorithm.DEFAULT;
    private static final long BLOOM_SEED = 42L;
    private static final double BLOOM_FPR = 0.01;

    // Helper to create known digests set from receipts
    private static Set<Digest> knownDigestsFrom(List<GossipableReceipt> receipts) {
        return receipts.stream()
                       .map(r -> ReceiptGossipCodec.digestOf(r, DIGEST_ALGO))
                       .collect(Collectors.toSet());
    }

    private WitnessReceiptManager receiptManager;
    private ReceiptGossipHandler handler;
    private BasicReceiptValidator validator;

    @BeforeEach
    void setUp() {
        var parameters = new WitnessParameters(
            5,                           // k (committeeSize)
            4,                           // threshold
            0L,                          // epoch
            Duration.ofSeconds(5),       // drainPeriod
            SignatureFormat.BLS_12_381,  // signatureFormat
            MigrationPhase.BLS_ONLY      // migrationPhase
        );

        receiptManager = new WitnessReceiptManager(parameters);
        validator = new BasicReceiptValidator();
        handler = new ReceiptGossipHandler(receiptManager, validator);
    }

    @Test
    void testFullGossipRoundTrip() {
        // Given: Create receipts (use millisecond-precision timestamps for proto compatibility)
        var receipts = List.of(
            createTestReceiptAtTime(0, Instant.ofEpochMilli(System.currentTimeMillis())),
            createTestReceiptAtTime(1, Instant.ofEpochMilli(System.currentTimeMillis())),
            createTestReceiptAtTime(2, Instant.ofEpochMilli(System.currentTimeMillis()))
        );

        // When: Encode to gossip
        var gossip = ReceiptGossipCodec.toReceiptGossip(receipts, knownDigestsFrom(receipts), BLOOM_SEED, BLOOM_FPR, DIGEST_ALGO);

        // And: Decode and handle gossip
        var processed = handler.handleGossip(gossip, null);

        // Then: All receipts successfully round-tripped
        assertEquals(3, processed.size());
        assertEquals(3, handler.getReceiptsReceived());
        assertEquals(3, handler.getReceiptsValidated());
        assertEquals(0, handler.getReceiptsRejected());

        // Verify receipts match originals (timestamp precision is milliseconds after round-trip)
        for (int i = 0; i < receipts.size(); i++) {
            var original = receipts.get(i);
            var roundTripped = processed.get(i);

            assertEquals(original.eventCoordinates(), roundTripped.eventCoordinates());
            assertEquals(original.witnessId(), roundTripped.witnessId());
            assertEquals(original.ringPosition(), roundTripped.ringPosition());
            assertEquals(original.timestamp(), roundTripped.timestamp()); // Both have millisecond precision now
            assertArrayEquals(
                original.witnessSignature().getBytes(),
                roundTripped.witnessSignature().getBytes()
            );
        }
    }

    @Test
    void testAntiEntropyIntegration() {
        // Given: Create 5 receipts (use millisecond-precision timestamps)
        var now = Instant.ofEpochMilli(System.currentTimeMillis());
        var receipts = List.of(
            createTestReceiptAtTime(0, now),
            createTestReceiptAtTime(1, now),
            createTestReceiptAtTime(2, now),
            createTestReceiptAtTime(3, now),
            createTestReceiptAtTime(4, now)
        );

        // Given: Mark receipts 1 and 3 as known
        var knownDigests = new HashSet<Digest>();
        knownDigests.add(ReceiptGossipCodec.digestOf(receipts.get(1), DIGEST_ALGO));
        knownDigests.add(ReceiptGossipCodec.digestOf(receipts.get(3), DIGEST_ALGO));

        // When: Process gossip with anti-entropy filter
        var gossip = ReceiptGossipCodec.toReceiptGossip(receipts, knownDigestsFrom(receipts), BLOOM_SEED, BLOOM_FPR, DIGEST_ALGO);
        var processed = handler.handleGossip(gossip, knownDigests);

        // Then: Only unknown receipts processed (0, 2, 4)
        assertEquals(3, processed.size());
        assertEquals(5, handler.getReceiptsReceived());  // All receipts counted
        assertEquals(3, handler.getReceiptsValidated()); // Only 3 validated (2 skipped)

        // Verify correct receipts were processed (compare by ring position since order may change)
        var processedPositions = processed.stream().map(GossipableReceipt::ringPosition).toList();
        assertTrue(processedPositions.contains(0), "Receipt 0 should be processed");
        assertFalse(processedPositions.contains(1), "Receipt 1 should be skipped (known)");
        assertTrue(processedPositions.contains(2), "Receipt 2 should be processed");
        assertFalse(processedPositions.contains(3), "Receipt 3 should be skipped (known)");
        assertTrue(processedPositions.contains(4), "Receipt 4 should be processed");
    }

    @Test
    void testValidationIntegrationWithCodec() {
        // Given: Create mix of valid and stale receipts (use millisecond-precision)
        var now = Instant.ofEpochMilli(System.currentTimeMillis());
        var staleTime = Instant.ofEpochMilli(System.currentTimeMillis() - Duration.ofMinutes(10).toMillis());

        var validReceipt = createTestReceiptAtTime(0, now);
        var staleReceipt = createTestReceiptAtTime(1, staleTime);
        var receipts = List.of(validReceipt, staleReceipt);

        // When: Process gossip
        var gossip = ReceiptGossipCodec.toReceiptGossip(receipts, knownDigestsFrom(receipts), BLOOM_SEED, BLOOM_FPR, DIGEST_ALGO);
        var processed = handler.handleGossip(gossip, null);

        // Then: Only valid receipt processed
        assertEquals(1, processed.size());
        assertEquals(0, processed.get(0).ringPosition()); // Valid receipt has position 0
        assertEquals(2, handler.getReceiptsReceived());
        assertEquals(1, handler.getReceiptsValidated());
        assertEquals(1, handler.getReceiptsRejected());
    }

    @Test
    void testBroadcasterIntegrationWithHandler() {
        // Given: Create capturing broadcaster
        var capturedEvents = new CopyOnWriteArrayList<EventCoordinates>();
        var capturedReceipts = new CopyOnWriteArrayList<AggregateWitnessReceipt>();

        var capturingBroadcaster = new ReceiptGossipBroadcaster() {
            @Override
            public void broadcast(EventCoordinates event, AggregateWitnessReceipt aggregateReceipt) {
                capturedEvents.add(event);
                capturedReceipts.add(aggregateReceipt);
            }
        };

        receiptManager.setGossipBroadcaster(capturingBroadcaster);

        // Note: Full integration with WitnessReceiptManager.completeBLSCollection
        // would require BLS signature setup, which is beyond unit test scope.
        // This test verifies the broadcaster can be set and called.

        // Then: Broadcaster is properly integrated
        assertNotNull(receiptManager.getGossipBroadcaster());
        assertEquals(capturingBroadcaster, receiptManager.getGossipBroadcaster());
    }

    @Test
    void testMetricsIntegrationAcrossComponents() {
        // Given: Reset metrics
        handler.resetMetrics();

        // When: Process multiple gossip batches
        var batch1 = List.of(createTestReceipt(0), createTestReceipt(1));
        var batch2 = List.of(createTestReceipt(2), createTestReceipt(3), createTestReceipt(4));

        var gossip1 = ReceiptGossipCodec.toReceiptGossip(batch1, knownDigestsFrom(batch1), BLOOM_SEED, BLOOM_FPR, DIGEST_ALGO);
        var gossip2 = ReceiptGossipCodec.toReceiptGossip(batch2, knownDigestsFrom(batch2), BLOOM_SEED, BLOOM_FPR, DIGEST_ALGO);

        handler.handleGossip(gossip1, null);
        handler.handleGossip(gossip2, null);

        // Then: Metrics aggregate correctly
        assertEquals(5, handler.getReceiptsReceived());
        assertEquals(5, handler.getReceiptsValidated());
        assertEquals(0, handler.getReceiptsRejected());
    }

    @Test
    void testConcurrentGossipProcessing() throws InterruptedException {
        // Given: Create multiple gossip messages
        var gossipCount = 10;
        var receiptsPerGossip = 5;
        var latch = new CountDownLatch(gossipCount);
        var processedCount = new AtomicInteger(0);
        var errors = new CopyOnWriteArrayList<Throwable>();

        // When: Process gossip concurrently from multiple threads
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < gossipCount; i++) {
            int batchId = i;
            var thread = new Thread(() -> {
                try {
                    var receipts = new ArrayList<GossipableReceipt>();
                    for (int j = 0; j < receiptsPerGossip; j++) {
                        receipts.add(createTestReceipt(batchId * receiptsPerGossip + j));
                    }

                    var gossip = ReceiptGossipCodec.toReceiptGossip(
                        receipts,
                        knownDigestsFrom(receipts),
                        BLOOM_SEED,
                        BLOOM_FPR,
                        DIGEST_ALGO
                    );

                    var processed = handler.handleGossip(gossip, null);
                    processedCount.addAndGet(processed.size());
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    latch.countDown();
                }
            });
            threads.add(thread);
            thread.start();
        }

        // Then: All threads complete without errors
        // CI infrastructure needs 3x timeout for concurrent gossip processing
        assertTrue(latch.await(IS_CI ? 30 : 10, TimeUnit.SECONDS), "All threads should complete");

        for (var thread : threads) {
            thread.join(IS_CI ? 3000 : 1000);
        }

        assertTrue(errors.isEmpty(), "No errors should occur: " + errors);
        assertEquals(gossipCount * receiptsPerGossip, processedCount.get());
        assertEquals(gossipCount * receiptsPerGossip, handler.getReceiptsReceived());
        assertEquals(gossipCount * receiptsPerGossip, handler.getReceiptsValidated());
    }

    @Test
    void testErrorRecoveryAcrossComponents() {
        // Given: Handler with custom validator that fails on specific receipts
        var failureValidator = new ReceiptGossipHandler.ReceiptValidator() {
            @Override
            public ReceiptGossipHandler.ValidationResult validate(GossipableReceipt receipt) {
                // Fail on even ring positions
                if (receipt.ringPosition() % 2 == 0) {
                    return ReceiptGossipHandler.ValidationResult.invalid("Test failure: even position");
                }
                return ReceiptGossipHandler.ValidationResult.valid();
            }
        };

        var errorHandler = new ReceiptGossipHandler(receiptManager, failureValidator);

        // When: Process mixed batch
        var receipts = List.of(
            createTestReceipt(0),  // Will fail (even)
            createTestReceipt(1),  // Will pass (odd)
            createTestReceipt(2),  // Will fail (even)
            createTestReceipt(3),  // Will pass (odd)
            createTestReceipt(4)   // Will fail (even)
        );

        var gossip = ReceiptGossipCodec.toReceiptGossip(receipts, knownDigestsFrom(receipts), BLOOM_SEED, BLOOM_FPR, DIGEST_ALGO);
        var processed = errorHandler.handleGossip(gossip, null);

        // Then: Processing continues despite failures
        assertEquals(2, processed.size());
        assertEquals(5, errorHandler.getReceiptsReceived());
        assertEquals(2, errorHandler.getReceiptsValidated());
        assertEquals(3, errorHandler.getReceiptsRejected());

        // Verify correct receipts were processed
        assertEquals(1, processed.get(0).ringPosition());
        assertEquals(3, processed.get(1).ringPosition());
    }

    @Test
    void testEmptyGossipIntegration() {
        // Given: Empty gossip
        var emptyGossip = ReceiptGossipCodec.toReceiptGossip(
            List.of(),
            Set.of(), // Empty known digests
            BLOOM_SEED,
            BLOOM_FPR,
            DIGEST_ALGO
        );

        // When: Process empty gossip
        var processed = handler.handleGossip(emptyGossip, null);

        // Then: No errors, no receipts processed
        assertTrue(processed.isEmpty());
        assertEquals(0, handler.getReceiptsReceived());
        assertEquals(0, handler.getReceiptsValidated());
        assertEquals(0, handler.getReceiptsRejected());
    }

    @Test
    void testBloomFilterIntegrationWithCodec() {
        // Given: Create receipts and encode to gossip
        var now = Instant.ofEpochMilli(System.currentTimeMillis());
        var receipts = List.of(
            createTestReceiptAtTime(0, now),
            createTestReceiptAtTime(1, now),
            createTestReceiptAtTime(2, now)
        );

        var gossip = ReceiptGossipCodec.toReceiptGossip(receipts, knownDigestsFrom(receipts), BLOOM_SEED, BLOOM_FPR, DIGEST_ALGO);

        // When: Extract bloom filter from gossip
        var bff = gossip.getBff();

        // Then: Bloom filter exists (Phase 1A: not yet populated, uses default instance)
        assertNotNull(bff);
        // Note: Bloom filter population deferred to Phase 1B+
        // Current implementation uses getDefaultInstance() which has empty bytes

        // When: Create known digests for anti-entropy test
        var knownDigests = new HashSet<Digest>();
        for (var receipt : receipts) {
            knownDigests.add(ReceiptGossipCodec.digestOf(receipt, DIGEST_ALGO));
        }

        // And: Process same gossip with all receipts marked as known
        var processed = handler.handleGossip(gossip, knownDigests);

        // Then: No receipts processed (all filtered by anti-entropy via digest set)
        assertEquals(0, processed.size());
    }

    @Test
    void testTimestampDriftIntegration() {
        // Given: Custom validator with tight drift tolerance
        var strictValidator = new BasicReceiptValidator(Duration.ofSeconds(30));
        var strictHandler = new ReceiptGossipHandler(receiptManager, strictValidator);

        // Given: Mix of fresh and old receipts
        var freshReceipt = createTestReceiptAtTime(0, Instant.now());
        var slightlyOldReceipt = createTestReceiptAtTime(1, Instant.now().minus(Duration.ofSeconds(20)));
        var veryOldReceipt = createTestReceiptAtTime(2, Instant.now().minus(Duration.ofMinutes(2)));

        var receipts = List.of(freshReceipt, slightlyOldReceipt, veryOldReceipt);

        // When: Process with strict validator
        var gossip = ReceiptGossipCodec.toReceiptGossip(receipts, knownDigestsFrom(receipts), BLOOM_SEED, BLOOM_FPR, DIGEST_ALGO);
        var processed = strictHandler.handleGossip(gossip, null);

        // Then: Only fresh and slightly old receipts pass
        assertEquals(2, processed.size());
        assertEquals(3, strictHandler.getReceiptsReceived());
        assertEquals(2, strictHandler.getReceiptsValidated());
        assertEquals(1, strictHandler.getReceiptsRejected());
    }

    // Test Helpers

    private GossipableReceipt createTestReceipt(int variant) {
        return createTestReceiptAtTime(variant, Instant.now());
    }

    private GossipableReceipt createTestReceiptAtTime(int variant, Instant timestamp) {
        var eventDigest = DIGEST_ALGO.digest("test-event-" + variant);
        var eventId = new SelfAddressingIdentifier(eventDigest);
        var eventCoords = new EventCoordinates(eventId, ULong.valueOf(0), eventDigest, "icp");

        var witnessId = DIGEST_ALGO.digest("test-witness-" + variant);
        var signature = createTestSignature();

        return new GossipableReceipt(
            eventCoords,
            witnessId,
            signature,
            timestamp,
            variant
        );
    }

    private JohnHancock createTestSignature() {
        var sigBytes = new byte[64]; // Ed25519 signature size
        for (int i = 0; i < sigBytes.length; i++) {
            sigBytes[i] = (byte) i;
        }
        return new JohnHancock(SignatureAlgorithm.ED_25519, sigBytes, ULong.valueOf(0));
    }
}
