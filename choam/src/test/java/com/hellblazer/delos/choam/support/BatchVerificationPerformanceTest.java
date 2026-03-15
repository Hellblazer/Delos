/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.proto.Certification;
import com.hellblazer.delos.cryptography.*;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.MockMember;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for BatchVerificationHelper.
 * <p>
 * Tests include:
 * - Edge cases (empty, single, boundary conditions)
 * - Byzantine batches (mixed valid/forged signatures)
 * - Concurrency (multiple threads)
 * - Circuit breaker behavior
 * - Metrics recording
 *
 * @author hal.hildebrand
 */
@Tag("performance")
class BatchVerificationPerformanceTest {

    private static final byte[] TEST_MESSAGE = "Test block header for batch verification".getBytes();
    private static Random random;

    @BeforeAll
    static void setup() {
        random = new Random(0x42434445L);
    }

    // ========== Edge Case Tests ==========

    @Test
    void emptyCertifications_returnsZero() {
        var helper = new BatchVerificationHelper();

        int valid = helper.verifyCertifications(TEST_MESSAGE, Collections.emptyList(), Collections.emptyMap(), Digest.NONE);
        assertEquals(0, valid);

        valid = helper.verifyCertifications(TEST_MESSAGE, null, Collections.emptyMap(), Digest.NONE);
        assertEquals(0, valid);
    }

    @Test
    void singleCertification_verifiedIndividually() {
        var testData = createMockTestData(1, true);
        var helper = new BatchVerificationHelper();

        int valid = helper.verifyCertifications(TEST_MESSAGE, testData.certifications, testData.validators, testData.memberId);

        assertEquals(1, valid);
        // Single cert goes to individual verification (batch requires MIN_BATCH_SIZE)
        assertEquals(1, helper.getIndividualVerifications());
        assertEquals(0, helper.getBatchVerifications());
    }

    @Test
    void twoCertifications_verifiedIndividually() {
        var testData = createMockTestData(2, true);
        var helper = new BatchVerificationHelper();

        int valid = helper.verifyCertifications(TEST_MESSAGE, testData.certifications, testData.validators, testData.memberId);

        assertEquals(2, valid);
        // Two certs go to individual (below MIN_BATCH_SIZE=3)
        assertEquals(2, helper.getIndividualVerifications());
        assertEquals(0, helper.getBatchVerifications());
    }

    @Test
    void threeCertifications_nonBLS_verifiedIndividually() {
        // Non-BLS verifiers fall through to individual
        var testData = createMockTestData(3, true);
        var helper = new BatchVerificationHelper();

        int valid = helper.verifyCertifications(TEST_MESSAGE, testData.certifications, testData.validators, testData.memberId);

        assertEquals(3, valid);
        // Non-BLS certs always go to individual verification
        assertEquals(3, helper.getIndividualVerifications());
    }

    // ========== Invalid Signature Tests ==========

    @Test
    void allInvalidSignatures_returnsZero() {
        var testData = createMockTestData(5, false); // All fail verification

        var helper = new BatchVerificationHelper();

        int valid = helper.verifyCertifications(TEST_MESSAGE, testData.certifications, testData.validators, testData.memberId);

        assertEquals(0, valid);
    }

    @Test
    void mixedValidAndInvalid_countsOnlyValid() {
        // Create test data with some valid, some invalid
        var certifications = new ArrayList<Certification>();
        var validators = new HashMap<Member, Verifier>();

        // 3 valid
        for (int i = 0; i < 3; i++) {
            var entry = createMockEntry(i, true);
            certifications.add(entry.certification);
            validators.put(entry.member, entry.verifier);
        }

        // 2 invalid
        for (int i = 3; i < 5; i++) {
            var entry = createMockEntry(i, false);
            certifications.add(entry.certification);
            validators.put(entry.member, entry.verifier);
        }

        var helper = new BatchVerificationHelper();
        int valid = helper.verifyCertifications(TEST_MESSAGE, certifications, validators, Digest.NONE);

        assertEquals(3, valid);
    }

    @Test
    void unknownWitnesses_ignored() {
        var testData = createMockTestData(5, true);

        // Create certifications from unknown members (not in validators)
        var unknownCerts = new ArrayList<Certification>();
        for (int i = 0; i < 3; i++) {
            var unknownId = Digest.normalized(DigestAlgorithm.DEFAULT, ("unknown-" + i).getBytes());
            var cert = Certification.newBuilder()
                .setId(unknownId.toDigeste())
                .setSignature(testData.certifications.get(0).getSignature())
                .build();
            unknownCerts.add(cert);
        }

        var allCerts = new ArrayList<>(testData.certifications);
        allCerts.addAll(unknownCerts);

        var helper = new BatchVerificationHelper();
        int valid = helper.verifyCertifications(TEST_MESSAGE, allCerts, testData.validators, testData.memberId);

        // Only the 5 known valid witnesses should count
        assertEquals(5, valid);
    }

    // ========== Concurrency Tests ==========

    @Test
    void concurrentVerification_threadSafe() throws Exception {
        var testData = createMockTestData(7, true);
        var helper = new BatchVerificationHelper();

        int numThreads = 8;
        int operationsPerThread = 50;
        var executor = Executors.newFixedThreadPool(numThreads);
        var errors = new AtomicInteger(0);
        var latch = new CountDownLatch(numThreads);

        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        int valid = helper.verifyCertifications(
                            TEST_MESSAGE, testData.certifications, testData.validators, testData.memberId);
                        if (valid != 7) {
                            errors.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed);
        assertEquals(0, errors.get());

        // Verify total operations match expected
        long totalVerifications = helper.getBatchVerifications() + helper.getIndividualVerifications();
        assertTrue(totalVerifications >= numThreads * operationsPerThread);
    }

    // ========== Circuit Breaker Tests ==========

    @Test
    void circuitBreaker_disablesBatchOnHighFailureRate() {
        var config = BatchVerificationConfig.newBuilder()
            .setEnableBatchVerification(true)
            .setBatchVerificationPercentage(100)
            .setCircuitBreakerThreshold(0.1)  // 10%
            .setCircuitBreakerWindowSize(50)
            .setCircuitBreakerMinOperations(5)
            .build();

        var helper = new BatchVerificationHelper(
            com.hellblazer.delos.cryptography.bls.BLSProvider.getDefault(),
            BatchVerificationMetrics.NOOP,
            config
        );

        // Simulate many failures
        for (int i = 0; i < 10; i++) {
            config.recordOperation(false);
        }

        assertTrue(config.isCircuitOpen());
        assertFalse(helper.isBatchEnabled());
    }

    @Test
    void featureFlag_disabled_usesIndividual() {
        var testData = createMockTestData(5, true);
        var helper = new BatchVerificationHelper(
            com.hellblazer.delos.cryptography.bls.BLSProvider.getDefault(),
            BatchVerificationMetrics.NOOP,
            BatchVerificationConfig.DISABLED
        );

        int valid = helper.verifyCertifications(TEST_MESSAGE, testData.certifications, testData.validators, testData.memberId);

        assertEquals(5, valid);
        // Should all be individual since batch is disabled
        assertEquals(5, helper.getIndividualVerifications());
        assertEquals(0, helper.getBatchVerifications());
    }

    // ========== Metrics Tests ==========

    @Test
    void metricsRecorded_individual() {
        var testData = createMockTestData(2, true);
        var metrics = new TestMetrics();
        var helper = new BatchVerificationHelper(
            com.hellblazer.delos.cryptography.bls.BLSProvider.getDefault(),
            metrics,
            BatchVerificationConfig.ENABLED
        );

        helper.verifyCertifications(TEST_MESSAGE, testData.certifications, testData.validators, testData.memberId);

        assertEquals(2, metrics.individualCount);
    }

    // ========== Helper Methods ==========

    private MockTestData createMockTestData(int count, boolean allValid) {
        var certifications = new ArrayList<Certification>();
        var validators = new HashMap<Member, Verifier>();

        for (int i = 0; i < count; i++) {
            var entry = createMockEntry(i, allValid);
            certifications.add(entry.certification);
            validators.put(entry.member, entry.verifier);
        }

        return new MockTestData(certifications, validators, Digest.NONE);
    }

    private MockEntry createMockEntry(int index, boolean valid) {
        var memberId = Digest.normalized(DigestAlgorithm.DEFAULT, ("member-" + index).getBytes());
        var member = new MockMember(memberId);

        // Create a mock verifier that returns the specified validity
        var verifier = new MockVerifier(valid);

        // Create a certification with a simple signature
        var sigBytes = new byte[64];
        random.nextBytes(sigBytes);
        var hancock = new JohnHancock(SignatureAlgorithm.ED_25519, sigBytes, ULong.valueOf(0));
        var cert = Certification.newBuilder()
            .setId(memberId.toDigeste())
            .setSignature(hancock.toSig())
            .build();

        return new MockEntry(cert, member, verifier);
    }

    record MockTestData(List<Certification> certifications, Map<Member, Verifier> validators, Digest memberId) {}
    record MockEntry(Certification certification, Member member, Verifier verifier) {}

    /**
     * Mock verifier that returns a predetermined result.
     */
    static class MockVerifier implements Verifier {
        private final boolean valid;

        MockVerifier(boolean valid) {
            this.valid = valid;
        }

        @Override
        public boolean verify(JohnHancock signature, java.io.InputStream message) {
            return valid;
        }

        @Override
        public boolean verify(SigningThreshold threshold, JohnHancock signature, java.io.InputStream message) {
            return valid;
        }

        @Override
        public PublicKey getKey() {
            // Return null to indicate non-BLS (forces individual verification)
            return null;
        }
    }

    static class TestMetrics implements BatchVerificationMetrics {
        int batchCount = 0;
        int individualCount = 0;
        int lastBatchSize = 0;
        boolean lastBatchSuccess = false;

        @Override
        public void recordBatchVerification(int batchSize, long latencyNanos, boolean success) {
            batchCount++;
            lastBatchSize = batchSize;
            lastBatchSuccess = success;
        }

        @Override
        public void recordIndividualVerification(long latencyNanos, boolean success) {
            individualCount++;
        }

        @Override
        public void recordBatchFallback(int batchSize, String reason) {}

        @Override
        public long getBatchVerifications() { return batchCount; }

        @Override
        public long getIndividualVerifications() { return individualCount; }

        @Override
        public long getBatchFailures() { return 0; }

        @Override
        public double getAverageLatencyMs() { return 0; }

        @Override
        public double getFailureRate() { return 0; }

        @Override
        public boolean isHealthy() { return true; }
    }
}
