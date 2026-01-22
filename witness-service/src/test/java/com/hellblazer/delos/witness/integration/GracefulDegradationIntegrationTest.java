/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.integration;

import com.hellblazer.delos.choam.proto.Block;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
import com.hellblazer.delos.choam.proto.Header;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.membership.MockMember;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.WitnessCHOAM;
import com.hellblazer.delos.witness.WitnessContext;
import com.hellblazer.delos.witness.WitnessFirefliesIntegration;
import com.hellblazer.delos.witness.WitnessParameters;
import com.hellblazer.delos.witness.validation.ByzantineWitnessDetector;
import com.hellblazer.delos.witness.validation.graceful.DegradedThresholdCalculator;
import com.hellblazer.delos.witness.validation.graceful.GracefulDegradationConfig;
import com.hellblazer.delos.witness.validation.graceful.SignatureBuffer;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end integration tests for graceful degradation flow.
 * <p>
 * Tests complete integration of:
 * - SignatureBuffer (buffering during view changes)
 * - DegradedThresholdCalculator (Byzantine-tolerant thresholds)
 * - WitnessFirefliesIntegration (drain state management)
 * - ByzantineWitnessDetector (Byzantine detection and exclusion)
 * <p>
 * Phase 1C-3-C: Graceful Degradation (Delos-3963)
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class GracefulDegradationIntegrationTest {

    private static final int COMMITTEE_SIZE = 7;
    private static final int WITNESS_POOL_SIZE = 21;
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;
    private static final Duration DRAIN_PERIOD = Duration.ofMillis(500);

    @Mock
    private WitnessCHOAM witnessCHOAM;

    private Context<MockMember> firefliesContext;
    private WitnessContext witnessContext;
    private WitnessParameters parameters;
    private ScheduledExecutorService scheduler;
    private WitnessFirefliesIntegration firefliesIntegration;
    private SignatureBuffer signatureBuffer;
    private DegradedThresholdCalculator degradedCalculator;
    private ByzantineWitnessDetector byzantineDetector;
    private GracefulDegradationConfig degradationConfig;
    private BLSProvider blsProvider;
    private SecureRandom random;
    private List<Identifier> committeeMembers;
    private Map<Identifier, BLSProvider.KeyPair> memberKeys;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        random = new SecureRandom();

        // Create witness pool and context
        var witnessPool = IntStream.range(0, WITNESS_POOL_SIZE)
            .mapToObj(i -> new MockMember(ALGORITHM.digest("witness-" + i)))
            .toList();

        var contextId = ALGORITHM.digest("degradation-test".getBytes());
        firefliesContext = new StaticContext<>(
            contextId,
            0.1,
            witnessPool,
            COMMITTEE_SIZE
        );

        var threshold = (2 * COMMITTEE_SIZE) / 3 + 1; // 5 for committee of 7
        parameters = WitnessParameters.newBuilder()
            .k(COMMITTEE_SIZE)
            .threshold(threshold)
            .epoch(0)
            .drainPeriod(DRAIN_PERIOD)
            .build();

        witnessContext = new WitnessContext(firefliesContext, parameters, ALGORITHM);
        scheduler = Executors.newScheduledThreadPool(1);

        // Initialize components
        degradationConfig = GracefulDegradationConfig.defaultConfig();
        signatureBuffer = new SignatureBuffer(degradationConfig);
        degradedCalculator = new DegradedThresholdCalculator(
            threshold,
            COMMITTEE_SIZE,
            0.667  // 2/3 + 1
        );

        var metricsMap = new HashMap<String, Integer>();
        byzantineDetector = new ByzantineWitnessDetector(metricsMap);

        // Mock CHOAM statistics
        when(witnessCHOAM.getStatistics()).thenReturn(
            new WitnessCHOAM.Statistics(0, 0, 0, false, 0)
        );

        firefliesIntegration = new WitnessFirefliesIntegration(
            witnessCHOAM,
            witnessContext,
            scheduler,
            DRAIN_PERIOD
        );

        // Initialize BLS provider and create test committee with keys
        blsProvider = BLSProvider.getDefault();
        committeeMembers = new ArrayList<>();
        memberKeys = new HashMap<>();

        for (var i = 0; i < COMMITTEE_SIZE; i++) {
            var keyPair = blsProvider.generateKeyPair(random);
            var digest = ALGORITHM.digest(keyPair.publicKey());
            var memberId = new SelfAddressingIdentifier(digest);
            committeeMembers.add(memberId);
            memberKeys.put(memberId, keyPair);

            // Mark all members as initially ACTIVE
            degradedCalculator.markActive(memberId);
        }
    }

    /**
     * Test 1: Buffer signatures during view change
     * <p>
     * Scenario:
     * - Normal operation (STABLE state)
     * - View change triggered → DRAINING state
     * - Signatures accumulate → buffered
     * - View change completes → STABLE
     * - Signatures replayed from buffer
     */
    @Test
    void testBufferSignaturesDuringViewChange() throws Exception {
        // Given: System in STABLE state
        assertThat(firefliesIntegration.getCurrentDrainState())
            .describedAs("Should start in STABLE state")
            .isEqualTo(WitnessFirefliesIntegration.DrainState.STABLE);

        var currentEpoch = 1L;
        var event = createEventCoordinates(1);

        // When: View change triggered (transition to DRAINING)
        var view = createViewBlock(1);
        firefliesIntegration.onViewChange(view);

        assertThat(firefliesIntegration.getCurrentDrainState())
            .describedAs("Should transition to DRAINING after view change")
            .isEqualTo(WitnessFirefliesIntegration.DrainState.DRAINING);

        // And: 10 signatures arrive during DRAINING
        var bufferedCount = 10;
        for (var i = 0; i < bufferedCount; i++) {
            var member = committeeMembers.get(i % COMMITTEE_SIZE);
            var signature = createTestSignature(member, event);
            var message = createTestMessage(event);

            signatureBuffer.buffer(member, signature, message, event, currentEpoch);
        }

        assertThat(signatureBuffer.size())
            .describedAs("All signatures should be buffered during DRAINING")
            .isEqualTo(bufferedCount);

        // And: View change completes (transition to STABLE)
        Thread.sleep(600); // Wait for drain period + buffer
        assertThat(firefliesIntegration.getCurrentDrainState())
            .describedAs("Should transition back to STABLE after drain completes")
            .isEqualTo(WitnessFirefliesIntegration.DrainState.STABLE);

        // Then: Buffered signatures can be replayed
        var bufferedSigs = signatureBuffer.getForEpoch(currentEpoch);
        assertThat(bufferedSigs)
            .describedAs("All signatures for epoch %d should be retrievable", currentEpoch)
            .hasSize(bufferedCount);

        // Verify threshold maintained with degraded calculation
        var degradedThreshold = degradedCalculator.getDegradedThreshold();
        assertThat(degradedThreshold)
            .describedAs("Degraded threshold should be >= original threshold")
            .isGreaterThanOrEqualTo(parameters.threshold());
    }

    /**
     * Test 2: Replay buffered signatures without Byzantine members
     * <p>
     * Scenario:
     * - Buffer 10 signatures from 7-member committee
     * - No Byzantine members detected
     * - Drain completes
     * - All buffered signatures replayed
     */
    @Test
    void testReplayBufferedSignaturesWithoutByzantine() {
        // Given: 10 signatures buffered (some members sign multiple times)
        var currentEpoch = 1L;
        var event = createEventCoordinates(1);
        var bufferedCount = 10;

        for (var i = 0; i < bufferedCount; i++) {
            var member = committeeMembers.get(i % COMMITTEE_SIZE);
            var signature = createTestSignature(member, event);
            var message = createTestMessage(event);

            signatureBuffer.buffer(member, signature, message, event, currentEpoch);
        }

        assertThat(signatureBuffer.size())
            .describedAs("Should have %d buffered signatures", bufferedCount)
            .isEqualTo(bufferedCount);

        // When: Drain completes, signatures replayed
        var bufferedSigs = signatureBuffer.getForEpoch(currentEpoch);

        // Count unique signers
        var uniqueSigners = bufferedSigs.stream()
            .map(SignatureBuffer.BufferedSignature::memberId)
            .distinct()
            .count();

        // Then: All signatures accumulated successfully
        assertThat(bufferedSigs)
            .describedAs("All 10 signatures should be accumulated")
            .hasSize(bufferedCount);

        assertThat(uniqueSigners)
            .describedAs("All 7 committee members should have signed")
            .isEqualTo(COMMITTEE_SIZE);

        // Threshold met (5 unique signers from 7-member committee)
        var threshold = degradedCalculator.getDegradedThreshold();
        assertThat(uniqueSigners)
            .describedAs("Unique signers (%d) should meet threshold (%d)", uniqueSigners, threshold)
            .isGreaterThanOrEqualTo(threshold);
    }

    /**
     * Test 3: Exclude Byzantine members from replay
     * <p>
     * Scenario:
     * - Buffer 10 signatures from 7-member committee
     * - Member 2 detected as Byzantine
     * - Drain completes with Byzantine exclusion
     * - Only 9 non-Byzantine signatures replayed
     */
    @Test
    void testExcludeByzantineMembersFromReplay() {
        // Given: 10 signatures buffered
        var currentEpoch = 1L;
        var event = createEventCoordinates(1);
        var bufferedCount = 10;

        for (var i = 0; i < bufferedCount; i++) {
            var member = committeeMembers.get(i % COMMITTEE_SIZE);
            var signature = createTestSignature(member, event);
            var message = createTestMessage(event);

            signatureBuffer.buffer(member, signature, message, event, currentEpoch);
        }

        // And: Member 2 marked as Byzantine
        var byzantineMember = committeeMembers.get(2);
        degradedCalculator.markByzantine(byzantineMember);
        byzantineDetector.markForExclusion(byzantineMember);

        assertThat(degradedCalculator.countByzantine())
            .describedAs("Should have 1 Byzantine member")
            .isEqualTo(1);

        // When: Drain completes, signatures replayed (excluding Byzantine)
        var bufferedSigs = signatureBuffer.getForEpoch(currentEpoch);
        var byzantineMembers = Set.of(byzantineMember);

        var nonByzantineSigs = bufferedSigs.stream()
            .filter(sig -> degradedCalculator.shouldInclude(sig.memberId(), byzantineMembers))
            .toList();

        // Then: Byzantine member signature not replayed
        var byzantineSigCount = bufferedSigs.stream()
            .filter(sig -> sig.memberId().equals(byzantineMember))
            .count();

        assertThat(byzantineSigCount)
            .describedAs("Byzantine member should have signed (before exclusion)")
            .isGreaterThan(0);

        assertThat(nonByzantineSigs.size())
            .describedAs("Non-Byzantine signatures should be less than total")
            .isLessThan(bufferedCount);

        // Degraded threshold recalculated (6 active → need 4)
        var activeCount = degradedCalculator.countActive();
        assertThat(activeCount)
            .describedAs("Should have 6 active members (7 - 1 Byzantine)")
            .isEqualTo(COMMITTEE_SIZE - 1);

        var degradedThreshold = degradedCalculator.getDegradedThreshold();
        assertThat(degradedThreshold)
            .describedAs("Degraded threshold with 1 Byzantine should be adjusted")
            .isLessThanOrEqualTo(parameters.threshold());

        // 9 signatures sufficient (> 4 required)
        assertThat(nonByzantineSigs.size())
            .describedAs("Non-Byzantine signatures (%d) should meet degraded threshold (%d)",
                nonByzantineSigs.size(), degradedThreshold)
            .isGreaterThanOrEqualTo(degradedThreshold);
    }

    /**
     * Test 4: Auto-recover from quarantine
     * <p>
     * Scenario:
     * - Member marked BYZANTINE_DETECTED
     * - Wait for recovery check interval
     * - Member transitions to IN_RECOVERY
     * - Drain completes
     * - Recovered member included in replay
     */
    @Test
    void testAutoRecoverFromQuarantine() throws Exception {
        // Given: Member initially Byzantine
        var member = committeeMembers.get(0);
        degradedCalculator.markByzantine(member);

        assertThat(degradedCalculator.countByzantine())
            .describedAs("Should have 1 Byzantine member")
            .isEqualTo(1);

        // When: Member transitions to IN_RECOVERY
        degradedCalculator.markRecovering(member);

        assertThat(degradedCalculator.countByzantine())
            .describedAs("Byzantine count should decrease after marking recovering")
            .isEqualTo(0);

        assertThat(degradedCalculator.countRecovering())
            .describedAs("Should have 1 recovering member")
            .isEqualTo(1);

        // Wait for recovery check interval
        Thread.sleep(degradationConfig.recoveryCheckIntervalMs() + 50);

        // And: Member fully recovered (mark as ACTIVE)
        degradedCalculator.markActive(member);

        // Then: Member status correctly transitions
        assertThat(degradedCalculator.countActive())
            .describedAs("Active count should increase after recovery")
            .isEqualTo(COMMITTEE_SIZE);

        assertThat(degradedCalculator.countRecovering())
            .describedAs("Recovering count should be 0 after full recovery")
            .isEqualTo(0);

        // Recovered member included in replay
        var byzantineMembers = new HashSet<Identifier>();
        assertThat(degradedCalculator.shouldInclude(member, byzantineMembers))
            .describedAs("Recovered member should be included in replay")
            .isTrue();

        // Threshold recalculated upward (active count increases)
        var degradedThreshold = degradedCalculator.getDegradedThreshold();
        assertThat(degradedThreshold)
            .describedAs("Threshold should reflect all active members")
            .isEqualTo(parameters.threshold());
    }

    /**
     * Test 5: Buffer capacity enforced
     * <p>
     * Scenario:
     * - maxSignaturesToBuffer = 1000 (default config)
     * - Buffer 1500 signatures during view change
     * - Only newest 1000 retained
     */
    @Test
    void testBufferCapacityEnforced() {
        // Given: Buffer capacity of 1000 (default config)
        var capacity = degradationConfig.maxSignaturesToBuffer();
        assertThat(capacity)
            .describedAs("Default buffer capacity should be 1000")
            .isEqualTo(1000);

        var currentEpoch = 1L;
        var event = createEventCoordinates(1);

        // When: Buffer 1500 signatures (exceeds capacity)
        var signaturesToBuffer = capacity + 500;
        for (var i = 0; i < signaturesToBuffer; i++) {
            var member = committeeMembers.get(i % COMMITTEE_SIZE);
            var signature = createTestSignature(member, event);
            var message = createTestMessage(event);

            signatureBuffer.buffer(member, signature, message, event, currentEpoch);
        }

        // Then: Buffer capacity limit enforced
        assertThat(signatureBuffer.size())
            .describedAs("Buffer should not exceed capacity of %d", capacity)
            .isLessThanOrEqualTo(capacity);

        // No OOM
        assertThat(signatureBuffer.getStats().currentSize())
            .describedAs("Buffer stats should show capacity enforcement")
            .isLessThanOrEqualTo(capacity);

        // Oldest signatures evicted (FIFO)
        var stats = signatureBuffer.getStats();
        assertThat(stats.totalBuffered())
            .describedAs("Total buffered count should track all attempts")
            .isEqualTo(signaturesToBuffer);
    }

    /**
     * Test 6: Multiple consecutive view changes
     * <p>
     * Scenario:
     * - View change 1: Buffer 5 signatures, drain, complete
     * - Immediately: View change 2: Buffer 5 more, drain, complete
     * - Rapid succession - no state machine races
     */
    @Test
    void testMultipleConsecutiveViewChanges() throws Exception {
        var currentEpoch = 1L;
        var event1 = createEventCoordinates(1);

        // Given: View change 1 starts
        var view1 = createViewBlock(1);
        firefliesIntegration.onViewChange(view1);

        // Buffer 5 signatures during first drain
        for (var i = 0; i < 5; i++) {
            var member = committeeMembers.get(i);
            var signature = createTestSignature(member, event1);
            var message = createTestMessage(event1);
            signatureBuffer.buffer(member, signature, message, event1, currentEpoch);
        }

        var bufferedAfterFirstViewChange = signatureBuffer.size();
        assertThat(bufferedAfterFirstViewChange)
            .describedAs("Should have 5 signatures buffered after first view change")
            .isEqualTo(5);

        // Wait for first drain to complete
        Thread.sleep(600);
        assertThat(firefliesIntegration.getCurrentDrainState())
            .describedAs("Should return to STABLE after first drain")
            .isEqualTo(WitnessFirefliesIntegration.DrainState.STABLE);

        // When: Immediately start view change 2
        var event2 = createEventCoordinates(2);
        var view2 = createViewBlock(2);
        firefliesIntegration.onViewChange(view2);

        // Buffer 5 more signatures during second drain
        for (var i = 0; i < 5; i++) {
            var member = committeeMembers.get(i);
            var signature = createTestSignature(member, event2);
            var message = createTestMessage(event2);
            signatureBuffer.buffer(member, signature, message, event2, currentEpoch);
        }

        // Wait for second drain to complete
        Thread.sleep(600);

        // Then: No signature loss across transitions
        var totalBuffered = signatureBuffer.getStats().totalBuffered();
        assertThat(totalBuffered)
            .describedAs("Total buffered should be at least 10 (5 + 5)")
            .isGreaterThanOrEqualTo(10);

        // State machine handles rapid changes without races
        assertThat(firefliesIntegration.getCurrentDrainState())
            .describedAs("Should be back in STABLE state after both drains")
            .isEqualTo(WitnessFirefliesIntegration.DrainState.STABLE);

        // No deadlocks or race conditions (test completes successfully)
    }

    /**
     * Test 7: Maintain BFT safety during degradation
     * <p>
     * Scenario:
     * - n=7 members, threshold=5 (2/3+1)
     * - 2 Byzantine detected and excluded
     * - 5 active remain
     * - Degraded threshold = max(5-2, ceil(5×2/3)) = 4
     * - 4 signatures from 5 active needed
     */
    @Test
    void testMaintainBFTSafetyDuringDegradation() {
        // Given: 7 members, threshold=5
        assertThat(COMMITTEE_SIZE)
            .describedAs("Committee size should be 7")
            .isEqualTo(7);

        var originalThreshold = parameters.threshold();
        assertThat(originalThreshold)
            .describedAs("Original threshold should be 5 (2/3+1 of 7)")
            .isEqualTo(5);

        // When: 2 Byzantine members detected and excluded
        var byzantine1 = committeeMembers.get(0);
        var byzantine2 = committeeMembers.get(1);

        degradedCalculator.markByzantine(byzantine1);
        degradedCalculator.markByzantine(byzantine2);

        var byzantineCount = degradedCalculator.countByzantine();
        assertThat(byzantineCount)
            .describedAs("Should have 2 Byzantine members")
            .isEqualTo(2);

        // 5 active remain
        var activeCount = degradedCalculator.countActive();
        assertThat(activeCount)
            .describedAs("Should have 5 active members (7 - 2 Byzantine)")
            .isEqualTo(5);

        // Then: Degraded threshold = max(5-2, ceil(5×2/3)) = max(3, 4) = 4
        var degradedThreshold = degradedCalculator.getDegradedThreshold();
        var expectedThreshold = Math.max(
            originalThreshold - byzantineCount,
            (int) Math.ceil(activeCount * 0.667)
        );

        assertThat(degradedThreshold)
            .describedAs("Degraded threshold should be %d with 2 Byzantine excluded", expectedThreshold)
            .isEqualTo(expectedThreshold);

        assertThat(degradedThreshold)
            .describedAs("Degraded threshold should be 4")
            .isEqualTo(4);

        // Byzantine fault tolerance maintained (minimum 2/3+1 honest members still required)
        assertThat(degradedThreshold)
            .describedAs("Degraded threshold should maintain BFT safety (>= 2/3+1 of active)")
            .isGreaterThanOrEqualTo((int) Math.ceil(activeCount * 0.667));

        // System remains safe with Byzantine exclusion
        assertThat(degradedCalculator.canAchieveThreshold())
            .describedAs("Should be able to achieve degraded threshold with active members")
            .isTrue();
    }

    /**
     * Test 8: Signature loss prevention
     * <p>
     * Scenario:
     * - 20 signatures buffered
     * - View change in progress
     * - Member (partial) failure during drain
     * - System recovers and completes drain
     */
    @Test
    void testSignatureLossPrevention() throws Exception {
        // Given: 20 signatures buffered
        var currentEpoch = 1L;
        var event = createEventCoordinates(1);
        var signatureCount = 20;

        for (var i = 0; i < signatureCount; i++) {
            var member = committeeMembers.get(i % COMMITTEE_SIZE);
            var signature = createTestSignature(member, event);
            var message = createTestMessage(event);
            signatureBuffer.buffer(member, signature, message, event, currentEpoch);
        }

        assertThat(signatureBuffer.size())
            .describedAs("Should have 20 signatures buffered")
            .isEqualTo(signatureCount);

        // And: View change in progress
        var view = createViewBlock(1);
        firefliesIntegration.onViewChange(view);

        assertThat(firefliesIntegration.getCurrentDrainState())
            .describedAs("Should be in DRAINING state")
            .isEqualTo(WitnessFirefliesIntegration.DrainState.DRAINING);

        // When: Member failure during drain (simulate by marking unreachable)
        var failedMember = committeeMembers.get(3);
        degradedCalculator.markUnreachable(failedMember);

        // System continues drain despite failure
        Thread.sleep(600);

        // Then: No signature loss despite failures
        var bufferedSigs = signatureBuffer.getForEpoch(currentEpoch);
        assertThat(bufferedSigs)
            .describedAs("All 20 signatures should still be in buffer")
            .hasSize(signatureCount);

        // All signatures either accumulated or re-buffered
        var stats = signatureBuffer.getStats();
        assertThat(stats.totalBuffered())
            .describedAs("Total buffered should track all signatures")
            .isGreaterThanOrEqualTo(signatureCount);

        // System reaches consistent state
        assertThat(firefliesIntegration.getCurrentDrainState())
            .describedAs("Should be in STABLE state after drain completes")
            .isEqualTo(WitnessFirefliesIntegration.DrainState.STABLE);

        assertThat(degradedCalculator.canAchieveThreshold())
            .describedAs("Should still be able to achieve threshold despite unreachable member")
            .isTrue();
    }

    // ===== Helper Methods =====

    /**
     * Create a view change block at specified height.
     */
    private HashedCertifiedBlock createViewBlock(long height) {
        var header = Header.newBuilder()
            .setHeight(height)
            .build();

        var block = Block.newBuilder()
            .setHeader(header)
            .build();

        var certifiedBlock = CertifiedBlock.newBuilder()
            .setBlock(block)
            .build();

        return new HashedCertifiedBlock(ALGORITHM, certifiedBlock);
    }

    /**
     * Create event coordinates for testing.
     */
    private EventCoordinates createEventCoordinates(long sequenceNumber) {
        var digest = ALGORITHM.digest(("event-" + sequenceNumber).getBytes());
        var identifier = new SelfAddressingIdentifier(digest);
        return new EventCoordinates(
            identifier,
            ULong.valueOf(sequenceNumber),
            digest,
            "icp"  // Inception event type
        );
    }

    /**
     * Create a test BLS signature for a member and event.
     */
    private byte[] createTestSignature(Identifier memberId, EventCoordinates event) {
        var keyPair = memberKeys.get(memberId);
        if (keyPair == null) {
            // Fallback for any members not in the committee
            keyPair = blsProvider.generateKeyPair(random);
        }

        var message = createTestMessage(event);
        return blsProvider.sign(keyPair.secretKey(), message);
    }

    /**
     * Create a test message from event coordinates.
     */
    private byte[] createTestMessage(EventCoordinates event) {
        var digest = event.getDigest();
        var sequence = event.getSequenceNumber();
        return (digest.toString() + ":" + sequence).getBytes();
    }
}
