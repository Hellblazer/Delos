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
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.HierarchicalAggregate;
import com.hellblazer.delos.witness.aggregation.TreeConfiguration;
import com.hellblazer.delos.witness.aggregation.TreeNode;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for RecursiveAggregateReceipt record.
 * <p>
 * Tests receipt creation, validation, proto conversion, and chain integrity checks.
 *
 * @author hal.hildebrand
 * @since Phase 1C-2-B
 */
@DisplayName("RecursiveAggregateReceipt")
class RecursiveAggregateReceiptTest {

    private HierarchicalAggregate baseAggregate;
    private EventCoordinates event;
    private BLSSignature testSignature;
    private byte[] testBitmap;

    @BeforeEach
    void setup() {
        // Create test fixtures
        testBitmap = new byte[]{(byte) 0xFF, (byte) 0xF0};
        var sigBytes = new byte[96];
        Arrays.fill(sigBytes, (byte) 0xAA);
        testSignature = new BLSSignature(sigBytes);

        // Create test event
        var digestAlgorithm = DigestAlgorithm.DEFAULT;
        var identifier = new SelfAddressingIdentifier(digestAlgorithm.digest("test".getBytes()));
        var digest = digestAlgorithm.digest("event-test".getBytes());
        event = new EventCoordinates(identifier, ULong.valueOf(1L), digest, "test");

        // Create test hierarchical aggregate
        var treeConfig = TreeConfiguration.create(10, 8);
        var leaf = new TreeNode.LeafNode(1L, testSignature, 100, testBitmap, 1, 0, Optional.empty());
        baseAggregate = new HierarchicalAggregate(leaf, treeConfig, event, 100, 10);
    }

    @Test
    @DisplayName("should create single-epoch receipt")
    void testSingleEpochReceipt() {
        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochs(0, 0)
            .event(event)
            .totalUniqueSigners(100)
            .build();

        assertThat(receipt.epochCount()).isOne();
        assertThat(receipt.startEpoch()).isZero();
        assertThat(receipt.endEpoch()).isZero();
        assertThat(receipt.uniqueSignerCount()).isEqualTo(100);
        assertThat(receipt.getEpochChain()).isEmpty();
        assertThat(receipt.estimatedBytes()).isGreaterThan(100);  // Single leaf ~110 + metadata 60
        assertThat(receipt.compressionCodec()).isEqualTo(CompressionCodec.NONE);
    }

    @Test
    @DisplayName("should create multi-epoch receipt with chain")
    void testMultiEpochReceipt() {
        var hash1 = DigestAlgorithm.DEFAULT.digest("epoch1".getBytes());
        var hash2 = DigestAlgorithm.DEFAULT.digest("epoch2".getBytes());
        var sig = new BLSSignature(new byte[96]);
        var bitmap = new byte[8];
        var aggregate = new BLSAggregate(sig, bitmap);

        var chain = List.of(
            EpochLink.unchanged(0, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now()),
            EpochLink.unchanged(1, hash1, 100, Instant.now()),
            EpochLink.changed(2, hash2, aggregate, bitmap, 105, Instant.now())
        );

        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochs(0, 2)
            .epochChain(chain)
            .event(event)
            .totalUniqueSigners(150)
            .build();

        assertThat(receipt.epochCount()).isEqualTo(3);
        assertThat(receipt.getEpochChain()).hasSize(3);
        assertThat(receipt.getAggregateForEpoch(2)).isPresent();
        assertThat(receipt.getAggregateForEpoch(1)).isEmpty();
        assertThat(receipt.uniqueSignerCount()).isEqualTo(150);
    }

    @Test
    @DisplayName("should auto-infer epoch range from chain")
    void testAutoInferEpochRange() {
        var chain = List.of(
            EpochLink.genesis(0, 100, Instant.now()),
            EpochLink.unchanged(1, DigestAlgorithm.DEFAULT.digest("hash".getBytes()), 100, Instant.now())
        );

        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(chain)
            .event(event)
            .totalUniqueSigners(150)
            .build();

        assertThat(receipt.startEpoch()).isZero();
        assertThat(receipt.endEpoch()).isOne();
        assertThat(receipt.epochCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("should validate chain integrity for valid sequence")
    void testChainValidation_validSequence() {
        var chain = List.of(
            EpochLink.unchanged(0, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now()),
            EpochLink.unchanged(1, DigestAlgorithm.DEFAULT.digest("hash".getBytes()), 100, Instant.now()),
            EpochLink.unchanged(2, DigestAlgorithm.DEFAULT.digest("hash2".getBytes()), 100, Instant.now())
        );

        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochs(0, 2)
            .epochChain(chain)
            .event(event)
            .totalUniqueSigners(150)
            .build();

        var validation = receipt.validateChainIntegrity();
        assertThat(validation.isValid()).isTrue();
        assertThat(validation.getFailureReason()).isEmpty();
    }

    @Test
    @DisplayName("should detect invalid chain sequence")
    void testChainValidation_invalidSequence() {
        // Gap in epoch sequence: 0, 2 (missing 1)
        var chain = List.of(
            EpochLink.unchanged(0, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now()),
            EpochLink.unchanged(2, DigestAlgorithm.DEFAULT.digest("hash".getBytes()), 100, Instant.now())
        );

        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochs(0, 2)
            .epochChain(chain)
            .event(event)
            .totalUniqueSigners(150)
            .build();

        var validation = receipt.validateChainIntegrity();
        assertThat(validation.isValid()).isFalse();
        assertThat(validation.getFailureReason())
            .isPresent()
            .get()
            .asString()
            .contains("Epoch sequence broken");
    }

    @Test
    @DisplayName("should accept empty chain for single epoch")
    void testChainValidation_emptyChainValid() {
        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochs(0, 0)
            .event(event)
            .totalUniqueSigners(100)
            .build();

        var validation = receipt.validateChainIntegrity();
        assertThat(validation.isValid()).isTrue();
    }

    @Test
    @DisplayName("should reject empty chain for multi-epoch range")
    void testChainValidation_emptyChainInvalid() {
        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochs(0, 2)
            .event(event)
            .totalUniqueSigners(100)
            .build();

        var validation = receipt.validateChainIntegrity();
        assertThat(validation.isValid()).isFalse();
        assertThat(validation.getFailureReason())
            .isPresent()
            .get()
            .asString()
            .contains("Non-empty epoch range but empty chain");
    }

    @Test
    @DisplayName("should calculate estimated bytes correctly")
    void testEstimatedBytes() {
        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochs(0, 0)
            .event(event)
            .totalUniqueSigners(100)
            .build();

        var bytes = receipt.estimatedBytes();
        // Single leaf: ~110 bytes (epoch 8 + sig 96 + count 4 + bitmap 2)
        // Metadata: ~60 bytes
        // Total: ~170 bytes
        assertThat(bytes).isBetween(150, 200);
    }

    @Test
    @DisplayName("should calculate estimated bytes with chain")
    void testEstimatedBytesWithChain() {
        var chain = List.of(
            EpochLink.unchanged(0, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now()),
            EpochLink.unchanged(1, DigestAlgorithm.DEFAULT.digest("h1".getBytes()), 100, Instant.now()),
            EpochLink.changed(2, DigestAlgorithm.DEFAULT.digest("h2".getBytes()),
                new BLSAggregate(new BLSSignature(new byte[96]), new byte[8]),
                new byte[8], 105, Instant.now())
        );

        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochs(0, 2)
            .epochChain(chain)
            .event(event)
            .totalUniqueSigners(150)
            .build();

        var bytes = receipt.estimatedBytes();
        // baseAggregate (single leaf ~110) + 2 unchanged (44 each) + 1 changed (~168) + metadata (60)
        // 110 + 88 + 168 + 60 = ~426
        assertThat(bytes).isBetween(400, 500);
    }

    @Test
    @DisplayName("should get root signature hash")
    void testGetRootSignatureHash() {
        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochs(0, 0)
            .event(event)
            .totalUniqueSigners(100)
            .build();

        var rootHash = receipt.getRootSignatureHash();
        assertThat(rootHash).isNotNull();
        assertThat(rootHash.getAlgorithm()).isEqualTo(DigestAlgorithm.DEFAULT);
    }

    @Test
    @DisplayName("should reject null baseAggregate")
    void testInvalidConstruction_nullBaseAggregate() {
        assertThatThrownBy(() ->
            new RecursiveAggregateReceipt(null, List.of(), 0, 0, event, 100, CompressionCodec.NONE))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("baseAggregate required");
    }

    @Test
    @DisplayName("should reject null epochChain")
    void testInvalidConstruction_nullEpochChain() {
        assertThatThrownBy(() ->
            new RecursiveAggregateReceipt(baseAggregate, null, 0, 0, event, 100, CompressionCodec.NONE))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("epochChain required");
    }

    @Test
    @DisplayName("should reject null event")
    void testInvalidConstruction_nullEvent() {
        assertThatThrownBy(() ->
            new RecursiveAggregateReceipt(baseAggregate, List.of(), 0, 0, null, 100, CompressionCodec.NONE))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("event required");
    }

    @Test
    @DisplayName("should reject null compressionCodec")
    void testInvalidConstruction_nullCompressionCodec() {
        assertThatThrownBy(() ->
            new RecursiveAggregateReceipt(baseAggregate, List.of(), 0, 0, event, 100, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("compressionCodec required");
    }

    @Test
    @DisplayName("should reject negative epoch")
    void testInvalidConstruction_negativeEpoch() {
        assertThatThrownBy(() ->
            new RecursiveAggregateReceipt(baseAggregate, List.of(), -1, 0, event, 100, CompressionCodec.NONE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Epochs must be non-negative");
    }

    @Test
    @DisplayName("should reject endEpoch < startEpoch")
    void testInvalidConstruction_endBeforeStart() {
        assertThatThrownBy(() ->
            new RecursiveAggregateReceipt(baseAggregate, List.of(), 5, 3, event, 100, CompressionCodec.NONE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("endEpoch must be >= startEpoch");
    }

    @Test
    @DisplayName("should reject negative totalUniqueSigners")
    void testInvalidConstruction_negativeSigners() {
        assertThatThrownBy(() ->
            new RecursiveAggregateReceipt(baseAggregate, List.of(), 0, 0, event, -1, CompressionCodec.NONE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("totalUniqueSigners must be non-negative");
    }

    @Test
    @DisplayName("should reject chain not matching startEpoch")
    void testInvalidConstruction_chainMismatchStart() {
        var chain = List.of(
            EpochLink.unchanged(1, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now())
        );

        assertThatThrownBy(() ->
            new RecursiveAggregateReceipt(baseAggregate, chain, 0, 1, event, 100, CompressionCodec.NONE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Chain does not match startEpoch");
    }

    @Test
    @DisplayName("should reject chain not matching endEpoch")
    void testInvalidConstruction_chainMismatchEnd() {
        var chain = List.of(
            EpochLink.unchanged(0, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now())
        );

        assertThatThrownBy(() ->
            new RecursiveAggregateReceipt(baseAggregate, chain, 0, 2, event, 100, CompressionCodec.NONE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Chain does not match endEpoch");
    }

    @Test
    @DisplayName("should create defensive copy of epoch chain")
    void testDefensiveCopy() {
        var mutableList = new ArrayList<EpochLink>();
        mutableList.add(EpochLink.genesis(0, 100, Instant.now()));

        var receipt = new RecursiveAggregateReceipt(
            baseAggregate, mutableList, 0, 0, event, 100, CompressionCodec.NONE);

        // Mutate original list (should not affect receipt)
        mutableList.add(EpochLink.unchanged(1, DigestAlgorithm.DEFAULT.digest("hash".getBytes()), 100, Instant.now()));

        assertThat(receipt.epochChain()).hasSize(1);
    }

    @Test
    @DisplayName("should return immutable epoch chain from getter")
    void testImmutableEpochChain() {
        var chain = List.of(
            EpochLink.genesis(0, 100, Instant.now())
        );

        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochChain(chain)
            .event(event)
            .totalUniqueSigners(100)
            .build();

        assertThatThrownBy(() ->
            receipt.getEpochChain().add(EpochLink.unchanged(1, DigestAlgorithm.DEFAULT.digest("hash".getBytes()), 100, Instant.now())))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("should build with fluent builder pattern")
    void testBuilderFluentAPI() {
        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochs(0, 0)
            .event(event)
            .totalUniqueSigners(100)
            .compressionCodec(CompressionCodec.NONE)
            .build();

        assertThat(receipt.baseAggregate()).isEqualTo(baseAggregate);
        assertThat(receipt.event()).isEqualTo(event);
        assertThat(receipt.compressionCodec()).isEqualTo(CompressionCodec.NONE);
    }

    @Test
    @DisplayName("should add epoch links individually via builder")
    void testBuilderAddEpochLink() {
        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .addEpochLink(EpochLink.genesis(0, 100, Instant.now()))
            .addEpochLink(EpochLink.unchanged(1, DigestAlgorithm.DEFAULT.digest("hash".getBytes()), 100, Instant.now()))
            .event(event)
            .totalUniqueSigners(150)
            .build();

        assertThat(receipt.getEpochChain()).hasSize(2);
        assertThat(receipt.startEpoch()).isZero();
        assertThat(receipt.endEpoch()).isOne();
    }

    @Test
    @DisplayName("should validate totalUniqueSigners >= base aggregate signers")
    void testValidation_signerCountConsistency() {
        var chain = List.of(
            EpochLink.genesis(0, 100, Instant.now())
        );

        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)  // has 100 signers
            .epochChain(chain)
            .event(event)
            .totalUniqueSigners(50)  // Less than base!
            .build();

        var validation = receipt.validateChainIntegrity();
        assertThat(validation.isValid()).isFalse();
        assertThat(validation.getFailureReason())
            .isPresent()
            .get()
            .asString()
            .contains("totalUniqueSigners")
            .contains("base aggregate signers");
    }

    @Test
    @DisplayName("should throw UnsupportedOperationException on toProto")
    void testProtoConversion_notYetImplemented() {
        var receipt = RecursiveAggregateReceipt.builder()
            .baseAggregate(baseAggregate)
            .epochs(0, 0)
            .event(event)
            .totalUniqueSigners(100)
            .build();

        // toProto() should throw because HierarchicalAggregate.toProto() is not yet implemented
        assertThatThrownBy(receipt::toProto)
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("HierarchicalAggregate.toProto() not yet implemented");
    }
}
