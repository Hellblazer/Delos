/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSKeyPair;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSPublicKey;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.joou.ULong;
import com.hellblazer.delos.witness.migration.MigrationPhase;
import com.hellblazer.delos.witness.migration.MigrationStateTracker;
import com.hellblazer.delos.witness.migration.ReceiptCompatibilityLayer;
import com.hellblazer.delos.witness.proto.BLSAggregateSignature;
import com.hellblazer.delos.witness.proto.ReceiptResponse;
import com.hellblazer.delos.witness.proto.ValidationStatus;
import com.hellblazer.delos.witness.proto.WitnessReceipt;
import com.hellblazer.delos.witness.receipt.AggregateWitnessReceipt;
import com.hellblazer.delos.witness.validation.AggregateValidator;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Phase 1B-2-D-1: Full BLS flow integration tests.
 * <p>
 * Validates end-to-end BLS receipt workflow through WitnessServiceImpl.validateReceipt().
 * Uses real BLS cryptography (not mocked) with mock committee setup.
 * <p>
 * Test Scope:
 * - Valid BLS aggregate validation (single and multiple signers)
 * - Invalid signature detection
 * - Threshold enforcement
 * - Fallback to Ed25519 in DUAL phase
 * - Phase transition behavior
 * - Metrics tracking accuracy
 * - End-to-end integration with ReceiptCompatibilityLayer
 * <p>
 * Architecture Flow:
 * <pre>
 * WitnessServiceImpl.validateReceipt()
 *   → ReceiptCompatibilityLayer.validateReceipt()
 *     → Detects format (BLS vs Ed25519)
 *     → Routes to validateBlsReceipt()
 *       → AggregateValidator.validate()
 *         → BLSProvider.verifyAggregateWithBitmap()
 *   → Maps CompatibilityResult to ValidationStatus
 * </pre>
 *
 * @author hal.hildebrand
 */
@DisplayName("BLS Integration Flow Tests (Phase 1B-2-D-1)")
class BLSIntegrationFlowTest {

    // Test infrastructure
    private BLSProvider blsProvider;
    private Random entropy;
    private DigestAlgorithm digestAlgorithm;

    // Service components
    private WitnessServiceImpl witnessService;
    private MigrationStateTracker migrationTracker;
    private ReceiptCompatibilityLayer compatibilityLayer;
    private AggregateValidator aggregateValidator;

    // Mock dependencies
    private WitnessCHOAM mockWitnessCHOAM;
    private WitnessContext mockWitnessContext;
    private WitnessReceiptManager mockReceiptManager;

    // Test fixtures
    private WitnessParameters parameters;
    private List<BLSKeyPair> committeeKeys;
    private List<BLSPublicKey> committeePublicKeys;
    private Set<Identifier> committeeMembers;
    private byte[] testMessage;
    private EventCoordinates testEvent;

    @BeforeEach
    void setUp() {
        // Initialize BLS provider with real cryptography
        blsProvider = BLSProvider.getDefault();
        entropy = new SecureRandom();
        digestAlgorithm = DigestAlgorithm.DEFAULT;

        // Create test message (event digest)
        testMessage = new byte[32];
        entropy.nextBytes(testMessage);

        // Create test event coordinates
        var testIdentifier = Identifier.NONE;
        var testDigest = digestAlgorithm.digest(testMessage);
        testEvent = new EventCoordinates(testIdentifier, ULong.valueOf(1), testDigest, "icp");

        // Create committee of 7 members (Byzantine threshold: 5 = ceil((7+5)/2))
        var committeeSize = 7;
        committeeKeys = new ArrayList<>();
        committeePublicKeys = new ArrayList<>();
        committeeMembers = new HashSet<>();

        for (int i = 0; i < committeeSize; i++) {
            var keyPair = BLSKeyPair.generate(entropy, blsProvider);
            committeeKeys.add(keyPair);
            committeePublicKeys.add(keyPair.publicKey());

            // Create unique identifier for each committee member
            var memberDigest = digestAlgorithm.digest(("committee-member-" + i).getBytes());
            var memberId = new com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier(memberDigest);
            committeeMembers.add(memberId);
        }

        // Create parameters (threshold = 5 for Byzantine fault tolerance)
        parameters = WitnessParameters.newBuilder()
            .k(7)
            .threshold(5)
            .epoch(1L)
            .drainPeriod(java.time.Duration.ofSeconds(10))
            .build();

        // Initialize migration tracker (start in DUAL phase for most tests)
        migrationTracker = new MigrationStateTracker(MigrationPhase.DUAL, 0L);

        // Create real aggregate validator with BLS provider
        aggregateValidator = new AggregateValidator(blsProvider);

        // Mock dependencies
        mockWitnessCHOAM = mock(WitnessCHOAM.class);
        mockWitnessContext = mock(WitnessContext.class);
        mockReceiptManager = mock(WitnessReceiptManager.class);

        // Configure mock witness context to return committee and BLS keys
        when(mockWitnessContext.selectCommittee(any(EventCoordinates.class)))
            .thenReturn(committeeMembers);

        // Mock the committee BLS key store to return public keys
        var mockKeyStore = mock(com.hellblazer.delos.witness.committee.CommitteeBLSKeyStore.class);
        when(mockWitnessContext.getCommitteeBLSKeys()).thenReturn(mockKeyStore);

        // Configure the key store to return committee public keys
        when(mockKeyStore.getPublicKeys(any())).thenReturn(committeePublicKeys);

        // Create compatibility layer with real validator
        compatibilityLayer = new ReceiptCompatibilityLayer(
            migrationTracker,
            aggregateValidator,
            mockWitnessContext,
            parameters
        );

        // Create witness service
        witnessService = new WitnessServiceImpl(
            mockWitnessCHOAM,
            mockWitnessContext,
            mockReceiptManager,
            parameters,
            digestAlgorithm,
            migrationTracker,
            compatibilityLayer
        );
    }

    // ========== Valid Receipt Tests ==========

    @Test
    @DisplayName("testValidReceipt_SingleSigner: Valid BLS aggregate from 1 signer")
    void testValidReceipt_SingleSigner() throws Exception {
        // Given: A valid BLS receipt from single signer
        var signers = List.of(0);
        var receipt = createValidBLSReceipt(signers);

        // When: Validating through service
        var response = validateReceiptSync(receipt);

        // Then: Validation should fail (insufficient signers)
        assertThat(response.getStatus())
            .as("Single signer BLS receipt should fail threshold (need 5, got 1)")
            .isEqualTo(ValidationStatus.INVALID);
        assertThat(response.getSignatureCount())
            .as("Should report actual signer count for BLS aggregate")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("testValidReceipt_MultipleSigners: Valid aggregate from 7 signers")
    void testValidReceipt_MultipleSigners() throws Exception {
        // Given: A valid BLS receipt from all 7 signers (exceeds threshold of 5)
        var signers = List.of(0, 1, 2, 3, 4, 5, 6);
        var receipt = createValidBLSReceipt(signers);

        // When: Validating through service
        var response = validateReceiptSync(receipt);

        // Then: Validation should succeed
        assertThat(response.getStatus())
            .as("All signers BLS receipt should pass validation and threshold")
            .isEqualTo(ValidationStatus.THRESHOLD_MET);
        assertThat(response.getSignatureCount())
            .as("Should report actual signer count for BLS aggregate")
            .isEqualTo(7);
    }

    @Test
    @DisplayName("testValidReceipt_ThresholdMet: Exactly 5 signers meets threshold")
    void testValidReceipt_ThresholdMet() throws Exception {
        // Given: A valid BLS receipt from exactly threshold signers (5)
        var signers = List.of(0, 1, 2, 3, 4);
        var receipt = createValidBLSReceipt(signers);

        // When: Validating through service
        var response = validateReceiptSync(receipt);

        // Then: Validation should succeed
        assertThat(response.getStatus())
            .as("Threshold-exact BLS receipt should pass validation")
            .isEqualTo(ValidationStatus.THRESHOLD_MET);
        assertThat(response.getRequiredThreshold())
            .as("Should report configured threshold")
            .isEqualTo(5);
    }

    // ========== Invalid Signature Tests ==========

    @Test
    @DisplayName("testInvalidReceipt_BadSignature: Invalid BLS aggregate rejected")
    void testInvalidReceipt_BadSignature() throws Exception {
        // Given: A BLS receipt with corrupted signature
        var signers = List.of(0, 1, 2, 3, 4);
        var receipt = createInvalidBLSReceipt(signers);

        // When: Validating through service
        var response = validateReceiptSync(receipt);

        // Then: Validation should fail
        assertThat(response.getStatus())
            .as("Corrupted BLS signature should be rejected")
            .isEqualTo(ValidationStatus.INVALID);

        // And: Metrics should track failure
        assertThat(compatibilityLayer.getBlsValidationFailures())
            .as("Should increment BLS validation failure counter")
            .isGreaterThan(0L);
    }

    @Test
    @DisplayName("testInvalidReceipt_InsufficientSigners: Below threshold rejected")
    void testInvalidReceipt_InsufficientSigners() throws Exception {
        // Given: A valid BLS receipt but with only 2 signers (below threshold of 5)
        var signers = List.of(0, 1);
        var receipt = createValidBLSReceipt(signers);

        // When: Validating through service
        var response = validateReceiptSync(receipt);

        // Then: Validation should fail due to insufficient signers
        assertThat(response.getStatus())
            .as("Receipt with 2 signers should fail threshold check (need 5)")
            .isEqualTo(ValidationStatus.INVALID);
        assertThat(response.getSignatureCount())
            .as("Should report actual signer count for BLS aggregate")
            .isEqualTo(2);
    }

    // ========== Fallback Tests ==========

    @Test
    @DisplayName("testFallback_BlsFailureToEd25519_DualPhase: Fallback attempts Ed25519 in DUAL")
    void testFallback_BlsFailureToEd25519_DualPhase() throws Exception {
        // Given: DUAL phase with fallback enabled (already in DUAL from setUp)
        compatibilityLayer.setFallbackPolicy(ReceiptCompatibilityLayer.FallbackPolicy.MONITORED);

        // And: A BLS receipt that will fail validation (corrupted signature)
        var signers = List.of(0, 1, 2, 3, 4);
        var receipt = createInvalidBLSReceipt(signers);

        // When: Validating through service
        var response = validateReceiptSync(receipt);

        // Then: Validation should fail (no Ed25519 signature to fallback to)
        assertThat(response.getStatus())
            .as("Invalid BLS signature should fail without Ed25519 fallback")
            .isEqualTo(ValidationStatus.INVALID);

        // And: Metrics should show BLS failure was attempted
        assertThat(compatibilityLayer.getBlsValidationFailures())
            .as("Should increment BLS validation failure counter")
            .isGreaterThan(0L);
    }

    @Test
    @DisplayName("testNoFallback_BlsFailureToEd25519_BlsOnlyPhase: No fallback in BLS_ONLY")
    void testNoFallback_BlsFailureToEd25519_BlsOnlyPhase() throws Exception {
        // Given: BLS_ONLY phase (no Ed25519 allowed) - advance from DUAL to BLS_ONLY
        migrationTracker.manualAdvance(MigrationPhase.BLS_ONLY);

        // And: An invalid BLS receipt
        var signers = List.of(0, 1, 2, 3, 4);
        var receipt = createInvalidBLSReceipt(signers);

        // When: Validating through service
        var response = validateReceiptSync(receipt);

        // Then: Validation should fail (no fallback in BLS_ONLY phase)
        assertThat(response.getStatus())
            .as("Invalid BLS signature should fail in BLS_ONLY phase")
            .isEqualTo(ValidationStatus.INVALID);

        // And: No fallback should be attempted
        assertThat(compatibilityLayer.getFormatFallbackAttempts())
            .as("No fallback should be attempted in BLS_ONLY phase")
            .isEqualTo(0L);
    }

    // ========== Metrics Tests ==========

    @Test
    @DisplayName("testThresholdMetrics: Validates metric counters increment correctly")
    void testThresholdMetrics() throws Exception {
        // Given: Initial metric state
        var initialBlsSuccess = compatibilityLayer.getBlsReceiptsValidated();
        var initialBlsFailure = compatibilityLayer.getBlsValidationFailures();

        // When: Validating a valid receipt
        var validReceipt = createValidBLSReceipt(List.of(0, 1, 2, 3, 4, 5, 6));
        validateReceiptSync(validReceipt);

        // Then: Success counter should increment
        assertThat(compatibilityLayer.getBlsReceiptsValidated())
            .as("Valid BLS receipt should increment success counter")
            .isEqualTo(initialBlsSuccess + 1);

        // When: Validating an invalid receipt
        var invalidReceipt = createInvalidBLSReceipt(List.of(0, 1, 2, 3, 4));
        validateReceiptSync(invalidReceipt);

        // Then: Failure counter should increment
        assertThat(compatibilityLayer.getBlsValidationFailures())
            .as("Invalid BLS receipt should increment failure counter")
            .isEqualTo(initialBlsFailure + 1);
    }

    // ========== Phase Transition Tests ==========

    @Test
    @DisplayName("testPhaseTransition: Receipt validation changes behavior on phase transition")
    void testPhaseTransition() throws Exception {
        // Given: Start in INIT phase
        migrationTracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
        compatibilityLayer = new ReceiptCompatibilityLayer(
            migrationTracker,
            aggregateValidator,
            mockWitnessContext,
            parameters
        );
        witnessService = new WitnessServiceImpl(
            mockWitnessCHOAM,
            mockWitnessContext,
            mockReceiptManager,
            parameters,
            digestAlgorithm,
            migrationTracker,
            compatibilityLayer
        );

        // When: Validating BLS receipt in INIT phase
        var receipt = createValidBLSReceipt(List.of(0, 1, 2, 3, 4, 5, 6));
        var responseInit = validateReceiptSync(receipt);

        // Then: Should be rejected (BLS not supported in INIT)
        assertThat(responseInit.getStatus())
            .as("BLS receipt should be rejected in INIT phase")
            .isEqualTo(ValidationStatus.INVALID);

        // When: Transitioning to DUAL phase
        migrationTracker.manualAdvance(MigrationPhase.DUAL);

        // And: Validating same receipt again
        var responseDual = validateReceiptSync(receipt);

        // Then: Should be accepted (BLS supported in DUAL)
        assertThat(responseDual.getStatus())
            .as("BLS receipt should be accepted in DUAL phase")
            .isEqualTo(ValidationStatus.THRESHOLD_MET);
    }

    // ========== End-to-End Integration Tests ==========

    @Test
    @DisplayName("testReceiptFromRealWitness: End-to-end from WitnessServiceImpl.validateReceipt()")
    void testReceiptFromRealWitness() throws Exception {
        // Given: A complete BLS aggregate receipt (simulating real witness service output)
        var signers = List.of(0, 1, 2, 3, 4, 5, 6);
        var aggregate = createBLSAggregate(signers);
        var aggregateReceipt = new AggregateWitnessReceipt(
            testEvent,
            aggregate,
            signers,
            com.hellblazer.delos.witness.aggregation.SignatureFormat.BLS_12_381,
            System.currentTimeMillis(),
            1
        );

        // When: Converting to proto and validating through service
        var protoReceipt = aggregateReceipt.toProto();
        var response = validateReceiptSync(protoReceipt);

        // Then: Full flow should validate successfully
        assertThat(response.getStatus())
            .as("End-to-end BLS receipt should validate successfully")
            .isEqualTo(ValidationStatus.THRESHOLD_MET);
        assertThat(response.getReceipt())
            .as("Response should contain the original receipt")
            .isNotNull();
        assertThat(response.getReceipt().hasBlsSig())
            .as("Response receipt should contain BLS signature")
            .isTrue();
    }

    @Test
    @DisplayName("testConcurrentValidation: Multiple concurrent validations are thread-safe")
    void testConcurrentValidation() throws Exception {
        // Given: Multiple receipts to validate concurrently
        var receiptCount = 10;
        var latch = new CountDownLatch(receiptCount);
        var errors = Collections.synchronizedList(new ArrayList<Throwable>());
        var successCount = new java.util.concurrent.atomic.AtomicInteger(0);

        // When: Validating receipts concurrently
        IntStream.range(0, receiptCount).parallel().forEach(i -> {
            try {
                var signers = List.of(0, 1, 2, 3, 4, 5, 6);
                var receipt = createValidBLSReceipt(signers);
                var response = validateReceiptSync(receipt);

                if (response.getStatus() == ValidationStatus.THRESHOLD_MET) {
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                errors.add(e);
            } finally {
                latch.countDown();
            }
        });

        // Then: All validations should complete successfully
        assertThat(latch.await(10, TimeUnit.SECONDS))
            .as("All concurrent validations should complete")
            .isTrue();
        assertThat(errors)
            .as("No errors should occur during concurrent validation")
            .isEmpty();
        assertThat(successCount.get())
            .as("All receipts should validate successfully")
            .isEqualTo(receiptCount);
    }

    // ========== Helper Methods ==========

    /**
     * Create a valid BLS receipt with real cryptographic signatures.
     *
     * @param signers List of signer indices (0-6)
     * @return WitnessReceipt with valid BLS aggregate
     */
    private WitnessReceipt createValidBLSReceipt(List<Integer> signers) {
        var aggregate = createBLSAggregate(signers);
        var digest = digestAlgorithm.digest(testMessage);

        return WitnessReceipt.newBuilder()
            .setEventCoordinates(testEvent.toEventCoords())
            .setEventDigest(digest.toDigeste())
            .setEpoch(1L)
            .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(System.currentTimeMillis() / 1000)
                .build())
            .setBlsSig(BLSAggregateSignature.newBuilder()
                .setSignature(ByteString.copyFrom(aggregate.aggregatedSignature().toBytes()))
                .addAllSignerIndices(signers)
                .build())
            .setSignerBitmap(ByteString.copyFrom(aggregate.signerBitmap()))
            .build();
    }

    /**
     * Create an invalid BLS receipt (corrupted signature).
     *
     * @param signers List of signer indices
     * @return WitnessReceipt with invalid signature
     */
    private WitnessReceipt createInvalidBLSReceipt(List<Integer> signers) {
        var aggregate = createBLSAggregate(signers);
        var digest = digestAlgorithm.digest(testMessage);

        // Corrupt the signature
        var corruptedSigBytes = aggregate.aggregatedSignature().toBytes();
        corruptedSigBytes[0] ^= (byte) 0xFF; // Flip bits

        return WitnessReceipt.newBuilder()
            .setEventCoordinates(testEvent.toEventCoords())
            .setEventDigest(digest.toDigeste())
            .setEpoch(1L)
            .setTimestamp(com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(System.currentTimeMillis() / 1000)
                .build())
            .setBlsSig(BLSAggregateSignature.newBuilder()
                .setSignature(ByteString.copyFrom(corruptedSigBytes))
                .addAllSignerIndices(signers)
                .build())
            .setSignerBitmap(ByteString.copyFrom(aggregate.signerBitmap()))
            .build();
    }

    /**
     * Create a BLS aggregate from committee members using real cryptography.
     *
     * @param signers List of signer indices (0-6)
     * @return BLS aggregate signature
     */
    private BLSAggregate createBLSAggregate(List<Integer> signers) {
        // Create individual signatures from each signer
        var signatures = new ArrayList<com.hellblazer.delos.cryptography.bls.BLSSignature>();

        // IMPORTANT: Sign the digest bytes, not testMessage!
        // The receipt stores eventDigest = digest(testMessage), so signatures must be of the digest bytes
        var eventDigest = digestAlgorithm.digest(testMessage);
        byte[] messageToSign = eventDigest.getBytes();

        for (var index : signers) {
            var keyPair = committeeKeys.get(index);
            var signature = keyPair.sign(messageToSign);
            signatures.add(signature);
        }

        // Use BLSAggregate.aggregate() to create the aggregate
        return BLSAggregate.aggregate(signatures, signers);
    }

    /**
     * Synchronously validate a receipt and return the response.
     *
     * @param receipt WitnessReceipt to validate
     * @return ReceiptResponse from validation
     * @throws Exception if validation fails or times out
     */
    private ReceiptResponse validateReceiptSync(WitnessReceipt receipt) throws Exception {
        var latch = new CountDownLatch(1);
        var responseRef = new AtomicReference<ReceiptResponse>();
        var errorRef = new AtomicReference<Throwable>();

        var observer = new StreamObserver<ReceiptResponse>() {
            @Override
            public void onNext(ReceiptResponse value) {
                responseRef.set(value);
            }

            @Override
            public void onError(Throwable t) {
                errorRef.set(t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        };

        witnessService.validateReceipt(receipt, observer);

        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw new RuntimeException("Validation timed out");
        }

        if (errorRef.get() != null) {
            throw new RuntimeException("Validation error", errorRef.get());
        }

        return responseRef.get();
    }
}
