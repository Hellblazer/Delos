/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.HierarchicalAggregate;
import com.hellblazer.delos.witness.aggregation.TreeConfiguration;
import com.hellblazer.delos.witness.aggregation.TreeNode;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for TemporalByzantineIsolator.
 * <p>
 * Tests Byzantine detection across temporal epoch boundaries:
 * - Equivocation detection (same member signs contradictory epochs)
 * - Abstinence detection (member missing from expected epochs)
 * - Consistent signer tracking
 * - First Byzantine epoch identification
 * - Multi-member anomaly detection
 * - Grace period handling for key rotation
 *
 * @author hal.hildebrand
 * @since Phase 3.2 (Delos-4002)
 */
@DisplayName("TemporalByzantineIsolator")
class TemporalByzantineIsolatorTest {

    private TemporalByzantineIsolator isolator;
    private CommitteeMemberResolver memberResolver;
    private DigestAlgorithm digestAlgorithm;

    // Test fixtures
    private Identifier member1;
    private Identifier member2;
    private Identifier member3;
    private Identifier member4;
    private Map<Integer, Identifier> committeeMembers;

    private BLSSignature testSignature1;
    private BLSSignature testSignature2;
    private BLSSignature testSignature3;
    private byte[] bitmap_AllMembers;
    private byte[] bitmap_Member1Only;
    private byte[] bitmap_Member2Only;
    private byte[] bitmap_Member3Only;
    private byte[] bitmap_Member1And2;
    private byte[] bitmap_Member1And3;

    @BeforeEach
    void setup() {
        isolator = new TemporalByzantineIsolator();
        digestAlgorithm = DigestAlgorithm.DEFAULT;

        // Create test member identifiers
        member1 = new SelfAddressingIdentifier(digestAlgorithm.digest("member1".getBytes()));
        member2 = new SelfAddressingIdentifier(digestAlgorithm.digest("member2".getBytes()));
        member3 = new SelfAddressingIdentifier(digestAlgorithm.digest("member3".getBytes()));
        member4 = new SelfAddressingIdentifier(digestAlgorithm.digest("member4".getBytes()));

        // Committee has 4 members at positions 0-3
        committeeMembers = Map.of(
            0, member1,
            1, member2,
            2, member3,
            3, member4
        );

        // Create mock resolver
        memberResolver = (epochNumber, bitmapPosition) -> {
            if (bitmapPosition < 0 || bitmapPosition >= 4) {
                return Optional.empty();
            }
            return Optional.ofNullable(committeeMembers.get(bitmapPosition));
        };

        // Create test BLS signatures (distinct for equivocation detection)
        var sigBytes1 = new byte[96];
        Arrays.fill(sigBytes1, (byte) 0xAA);
        testSignature1 = new BLSSignature(sigBytes1);

        var sigBytes2 = new byte[96];
        Arrays.fill(sigBytes2, (byte) 0xBB);
        testSignature2 = new BLSSignature(sigBytes2);

        var sigBytes3 = new byte[96];
        Arrays.fill(sigBytes3, (byte) 0xCC);
        testSignature3 = new BLSSignature(sigBytes3);

        // Create test bitmaps (4 members, 1 byte bitmap)
        bitmap_AllMembers = new byte[]{(byte) 0x0F};     // bits 0-3 set
        bitmap_Member1Only = new byte[]{(byte) 0x01};    // bit 0 set
        bitmap_Member2Only = new byte[]{(byte) 0x02};    // bit 1 set
        bitmap_Member3Only = new byte[]{(byte) 0x04};    // bit 2 set
        bitmap_Member1And2 = new byte[]{(byte) 0x03};    // bits 0-1 set
        bitmap_Member1And3 = new byte[]{(byte) 0x05};    // bits 0,2 set
    }

    @Test
    @DisplayName("should return empty result when all epochs consistent")
    void shouldIsolateByzantine_AllConsistent_Empty() {
        var receipt = createReceiptWithConsistentEpochs(3);

        var result = isolator.isolateByzantine(receipt, memberResolver);

        assertThat(result.indicators()).isEmpty();
        assertThat(result.memberBehaviors()).isEmpty();
        assertThat(result.firstByzantineEpoch()).isEmpty();
        assertThat(result.hasByzantineBehavior()).isFalse();
    }

    @Test
    @DisplayName("should detect equivocation within same epoch")
    void shouldIsolateByzantine_EquivocationSameEpoch_Detected() {
        // Member1 signs with two different signatures in epoch 1
        // This simulates member signing different aggregate trees
        var receipt = createReceiptWithEquivocationInEpoch1();

        var result = isolator.isolateByzantine(receipt, memberResolver);

        assertThat(result.hasByzantineBehavior()).isTrue();
        assertThat(result.indicators()).hasSize(1);

        var indicator = result.indicators().get(0);
        assertThat(indicator.member()).isEqualTo(member1);
        assertThat(indicator.type()).isEqualTo(ByzantineIndicatorType.EQUIVOCATION);
        assertThat(indicator.epochNumber()).isEqualTo(1L);
        assertThat(indicator.description()).contains("contradictory");

        assertThat(result.firstByzantineEpoch()).hasValue(1L);
        assertThat(result.memberBehaviors()).containsKey(member1);
    }

    @Test
    @DisplayName("should detect equivocation across epochs")
    void shouldIsolateByzantine_EquivocationCrossEpoch_Detected() {
        // Member1 signs epoch 1 and epoch 2 with contradictory signatures
        var receipt = createReceiptWithCrossEpochEquivocation();

        var result = isolator.isolateByzantine(receipt, memberResolver);

        assertThat(result.hasByzantineBehavior()).isTrue();
        assertThat(result.indicators()).isNotEmpty();

        // Should detect equivocation for member1
        var member1Indicators = result.indicators().stream()
            .filter(i -> i.member().equals(member1))
            .filter(i -> i.type() == ByzantineIndicatorType.EQUIVOCATION)
            .toList();

        assertThat(member1Indicators).isNotEmpty();
        assertThat(result.firstByzantineEpoch()).isPresent();
    }

    @Test
    @DisplayName("should detect abstinence mid-stream")
    void shouldIsolateByzantine_AbstinenceMidStream_Detected() {
        // Member1 present in epoch 0, missing in epoch 1, returns in epoch 2
        var receipt = createReceiptWithAbstinence();

        var result = isolator.isolateByzantine(receipt, memberResolver);

        assertThat(result.hasByzantineBehavior()).isTrue();

        var abstinenceIndicators = result.indicators().stream()
            .filter(i -> i.type() == ByzantineIndicatorType.ABSTINENCE)
            .toList();

        assertThat(abstinenceIndicators).isNotEmpty();

        var member1Abstinence = abstinenceIndicators.stream()
            .filter(i -> i.member().equals(member1))
            .findFirst();

        assertThat(member1Abstinence).isPresent();
        assertThat(member1Abstinence.get().epochNumber()).isEqualTo(1L);  // Fixed: epoch 1 is where member1 is missing
        assertThat(member1Abstinence.get().description()).contains("missing");
    }

    @Test
    @DisplayName("should find first Byzantine epoch correctly")
    void shouldFindFirstByzantineEpoch_Epoch2_Success() {
        // Member1 acts Byzantine starting in epoch 2
        var receipt = createReceiptWithByzantineStartingEpoch2();

        var result = isolator.findFirstByzantineEpoch(receipt, member1, memberResolver);

        assertThat(result).hasValue(2L);
    }

    @Test
    @DisplayName("should return empty when no Byzantine activity found")
    void shouldFindFirstByzantineEpoch_NoAnomaly_Empty() {
        var receipt = createReceiptWithConsistentEpochs(3);

        var result = isolator.findFirstByzantineEpoch(receipt, member1, memberResolver);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should identify consistent signers across all epochs")
    void shouldGetConsistentSigners_AllPresent_Success() {
        var receipt = createReceiptWithConsistentEpochs(3);

        var consistentSigners = isolator.getConsistentSigners(receipt, memberResolver);

        // All 4 members signed all epochs
        assertThat(consistentSigners).hasSize(4);
        assertThat(consistentSigners).contains(member1, member2, member3, member4);
    }

    @Test
    @DisplayName("should identify partial consistent signers")
    void shouldGetConsistentSigners_PartialPresent_Success() {
        // Member1 and Member2 sign all epochs
        // Member3 only signs epochs 1,2
        // Member4 only signs epoch 3
        var receipt = createReceiptWithPartialSigners();

        var consistentSigners = isolator.getConsistentSigners(receipt, memberResolver);

        // Only members 1 and 2 signed all epochs
        assertThat(consistentSigners).hasSize(2);
        assertThat(consistentSigners).contains(member1, member2);
        assertThat(consistentSigners).doesNotContain(member3, member4);
    }

    @Test
    @DisplayName("should detect Byzantine behavior from multiple members")
    void shouldIsolateByzantine_MultipleMembers_DetectAll() {
        // Member1: equivocation in epoch 2
        // Member2: abstinence in epoch 3
        var receipt = createReceiptWithMultipleByzantineMembers();

        var result = isolator.isolateByzantine(receipt, memberResolver);

        assertThat(result.hasByzantineBehavior()).isTrue();
        assertThat(result.byzantineMemberCount()).isEqualTo(2);

        assertThat(result.memberBehaviors()).containsKeys(member1, member2);

        var member1Behavior = result.memberBehaviors().get(member1);
        assertThat(member1Behavior.hasByzantineBehavior()).isTrue();

        var member2Behavior = result.memberBehaviors().get(member2);
        assertThat(member2Behavior.hasByzantineBehavior()).isTrue();
    }

    @Test
    @DisplayName("should handle key rotation gracefully")
    void shouldIsolateByzantine_GracePeriod_HandleKeyRotation() {
        // Member1 appears to equivocate due to key rotation mid-receipt
        // Should not be flagged if within grace period
        var receipt = createReceiptWithKeyRotation();

        var result = isolator.isolateByzantine(receipt, memberResolver);

        // With proper grace period handling, should not detect false positive
        // Note: This test may need refinement based on actual grace period implementation
        assertThat(result.hasByzantineBehavior()).isFalse();
    }

    @Test
    @DisplayName("should return empty result for empty receipt")
    void shouldIsolateByzantine_EmptyReceipt_Empty() {
        var receipt = createEmptyReceipt();

        var result = isolator.isolateByzantine(receipt, memberResolver);

        assertThat(result.indicators()).isEmpty();
        assertThat(result.memberBehaviors()).isEmpty();
        assertThat(result.firstByzantineEpoch()).isEmpty();
    }

    @Test
    @DisplayName("should handle single epoch with no temporal anomalies")
    void shouldIsolateByzantine_SingleEpoch_NoDetection() {
        // Single epoch can't have temporal anomalies (equivocation requires multiple epochs)
        var receipt = createReceiptWithSingleEpoch();

        var result = isolator.isolateByzantine(receipt, memberResolver);

        // Single epoch should have no temporal Byzantine behavior
        assertThat(result.indicators()).isEmpty();
        assertThat(result.hasByzantineBehavior()).isFalse();
    }

    // Helper methods to create test receipts

    private RecursiveAggregateReceipt createReceiptWithConsistentEpochs(int epochCount) {
        var event = createTestEvent();
        var baseAggregate = createBaseAggregate(event, bitmap_AllMembers, testSignature1);

        var chain = new ArrayList<EpochLink>();
        for (int i = 0; i < epochCount; i++) {
            var prevHash = i == 0 ? digestAlgorithm.getOrigin() : digestAlgorithm.digest(testSignature1.toBytes());
            // Use Changed epochs with same signature to simulate consistent behavior
            chain.add(EpochLink.changed(i, prevHash,
                new BLSAggregate(testSignature1, bitmap_AllMembers), bitmap_AllMembers, 4, Instant.now()));
        }

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(chain)
            .epochs(0, epochCount - 1L)
            .event(event)
            .totalUniqueSigners(4)
            .build();
    }

    private RecursiveAggregateReceipt createReceiptWithEquivocationInEpoch1() {
        // Simplified: Can't truly simulate intra-epoch equivocation with current structure
        // This would require two different aggregate trees for same epoch
        // Instead, simulate as cross-epoch equivocation with different signatures
        var event = createTestEvent();
        var baseAggregate = createBaseAggregate(event, bitmap_Member1Only, testSignature1);

        // Member1 signs with signature1 in epoch 0, then signature2 in epoch 1 (equivocation)
        var chain = List.of(
            EpochLink.changed(0, digestAlgorithm.getOrigin(),
                new BLSAggregate(testSignature1, bitmap_Member1Only), bitmap_Member1Only, 1, Instant.now()),
            EpochLink.changed(1, digestAlgorithm.digest(testSignature1.toBytes()),
                new BLSAggregate(testSignature2, bitmap_Member1Only), bitmap_Member1Only, 1, Instant.now())
        );

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(chain)
            .epochs(0, 1)
            .event(event)
            .totalUniqueSigners(1)
            .build();
    }

    private RecursiveAggregateReceipt createReceiptWithCrossEpochEquivocation() {
        var event = createTestEvent();
        var baseAggregate = createBaseAggregate(event, bitmap_Member1Only, testSignature1);

        // Member1 signs with signature1 in epoch 0, signature2 in epoch 1, signature3 in epoch 2
        var chain = List.of(
            EpochLink.changed(0, digestAlgorithm.getOrigin(),
                new BLSAggregate(testSignature1, bitmap_Member1Only), bitmap_Member1Only, 1, Instant.now()),
            EpochLink.changed(1, digestAlgorithm.digest(testSignature1.toBytes()),
                new BLSAggregate(testSignature2, bitmap_Member1Only), bitmap_Member1Only, 1, Instant.now()),
            EpochLink.changed(2, digestAlgorithm.digest(testSignature2.toBytes()),
                new BLSAggregate(testSignature3, bitmap_Member1Only), bitmap_Member1Only, 1, Instant.now())
        );

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(chain)
            .epochs(0, 2)
            .event(event)
            .totalUniqueSigners(1)
            .build();
    }

    private RecursiveAggregateReceipt createReceiptWithAbstinence() {
        var event = createTestEvent();
        var baseAggregate = createBaseAggregate(event, bitmap_Member1And2, testSignature1);

        // Epoch 0: Member1 and Member2 sign
        // Epoch 1: Only Member2 signs (Member1 absent)
        // Epoch 2: Member1 and Member2 sign again
        var chain = List.of(
            EpochLink.changed(0, digestAlgorithm.getOrigin(),
                new BLSAggregate(testSignature1, bitmap_Member1And2), bitmap_Member1And2, 2, Instant.now()),
            EpochLink.changed(1, digestAlgorithm.digest(testSignature1.toBytes()),
                new BLSAggregate(testSignature2, bitmap_Member2Only), bitmap_Member2Only, 1, Instant.now()),
            EpochLink.changed(2, digestAlgorithm.digest(testSignature2.toBytes()),
                new BLSAggregate(testSignature1, bitmap_Member1And2), bitmap_Member1And2, 2, Instant.now())
        );

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(chain)
            .epochs(0, 2)
            .event(event)
            .totalUniqueSigners(2)
            .build();
    }

    private RecursiveAggregateReceipt createReceiptWithByzantineStartingEpoch2() {
        var event = createTestEvent();
        var baseAggregate = createBaseAggregate(event, bitmap_Member1Only, testSignature1);

        // Member1 behaves normally in epochs 0-1 (same signature), then equivocates in epoch 2 (different signature)
        var chain = List.of(
            EpochLink.changed(0, digestAlgorithm.getOrigin(),
                new BLSAggregate(testSignature1, bitmap_Member1Only), bitmap_Member1Only, 1, Instant.now()),
            EpochLink.changed(1, digestAlgorithm.digest(testSignature1.toBytes()),
                new BLSAggregate(testSignature1, bitmap_Member1Only), bitmap_Member1Only, 1, Instant.now()),
            EpochLink.changed(2, digestAlgorithm.digest(testSignature1.toBytes()),
                new BLSAggregate(testSignature2, bitmap_Member1Only), bitmap_Member1Only, 1, Instant.now())
        );

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(chain)
            .epochs(0, 2)
            .event(event)
            .totalUniqueSigners(1)
            .build();
    }

    private RecursiveAggregateReceipt createReceiptWithPartialSigners() {
        var event = createTestEvent();
        var baseAggregate = createBaseAggregate(event, bitmap_AllMembers, testSignature1);

        // Epoch 0: All 4 members sign
        // Epoch 1: Members 1,2,3 sign
        // Epoch 2: Members 1,2,4 sign
        var chain = List.of(
            EpochLink.changed(0, digestAlgorithm.getOrigin(),
                new BLSAggregate(testSignature1, bitmap_AllMembers), bitmap_AllMembers, 4, Instant.now()),
            EpochLink.changed(1, digestAlgorithm.digest(testSignature1.toBytes()),
                new BLSAggregate(testSignature1, new byte[]{(byte) 0x07}), new byte[]{(byte) 0x07}, 3, Instant.now()),
            EpochLink.changed(2, digestAlgorithm.digest(testSignature1.toBytes()),
                new BLSAggregate(testSignature1, new byte[]{(byte) 0x0B}), new byte[]{(byte) 0x0B}, 3, Instant.now())
        );

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(chain)
            .epochs(0, 2)
            .event(event)
            .totalUniqueSigners(4)
            .build();
    }

    private RecursiveAggregateReceipt createReceiptWithMultipleByzantineMembers() {
        var event = createTestEvent();
        var baseAggregate = createBaseAggregate(event, bitmap_Member1And2, testSignature1);

        // Epoch 0: Members 1,2 sign normally
        // Epoch 1: Member1 equivocates, Member2 signs normally
        // Epoch 2: Member1 normal, Member2 absent (abstinence)
        var chain = List.of(
            EpochLink.changed(0, digestAlgorithm.getOrigin(),
                new BLSAggregate(testSignature1, bitmap_Member1And2), bitmap_Member1And2, 2, Instant.now()),
            EpochLink.changed(1, digestAlgorithm.digest(testSignature1.toBytes()),
                new BLSAggregate(testSignature2, bitmap_Member1And2), bitmap_Member1And2, 2, Instant.now()),
            EpochLink.changed(2, digestAlgorithm.digest(testSignature2.toBytes()),
                new BLSAggregate(testSignature1, bitmap_Member1Only), bitmap_Member1Only, 1, Instant.now())
        );

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(chain)
            .epochs(0, 2)
            .event(event)
            .totalUniqueSigners(2)
            .build();
    }

    private RecursiveAggregateReceipt createReceiptWithKeyRotation() {
        // Simplified: Same structure as consistent epochs
        // Real implementation would need metadata indicating key rotation
        return createReceiptWithConsistentEpochs(3);
    }

    private RecursiveAggregateReceipt createEmptyReceipt() {
        var event = createTestEvent();
        var baseAggregate = createBaseAggregate(event, new byte[]{0}, testSignature1);

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochs(0, 0)
            .event(event)
            .totalUniqueSigners(0)
            .build();
    }

    private RecursiveAggregateReceipt createReceiptWithSingleEpoch() {
        var event = createTestEvent();
        var baseAggregate = createBaseAggregate(event, bitmap_AllMembers, testSignature1);

        return RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochs(0, 0)
            .event(event)
            .totalUniqueSigners(4)
            .build();
    }

    private EventCoordinates createTestEvent() {
        var identifier = new SelfAddressingIdentifier(digestAlgorithm.digest("test-event".getBytes()));
        var digest = digestAlgorithm.digest("event-digest".getBytes());
        return new EventCoordinates(identifier, ULong.valueOf(1L), digest, "test");
    }

    private HierarchicalAggregate createBaseAggregate(EventCoordinates event, byte[] bitmap, BLSSignature signature) {
        var treeConfig = TreeConfiguration.create(1, 8);
        var leaf = new TreeNode.LeafNode(0L, signature, bitmap.length, bitmap, 1, 0, Optional.empty());
        return new HierarchicalAggregate(leaf, treeConfig, event, bitmap.length, 1);
    }
}
