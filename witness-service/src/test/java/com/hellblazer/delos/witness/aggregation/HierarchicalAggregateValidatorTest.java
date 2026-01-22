/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HierarchicalAggregateValidator.
 * <p>
 * Tests verification, Byzantine isolation, and O(log n) verification complexity.
 */
@DisplayName("HierarchicalAggregateValidator")
class HierarchicalAggregateValidatorTest {

    @Mock
    private BLSProvider mockProvider;

    private byte[] testMessage;
    private byte[] testSignature;
    private BLSSignature blsTestSignature;
    private byte[] testBitmap;
    private Map<Long, List<byte[]>> publicKeysPerCommittee;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);

        // Setup test data
        testMessage = "test-message".getBytes();
        testSignature = new byte[96];
        Arrays.fill(testSignature, (byte) 0xAA);
        blsTestSignature = new BLSSignature(testSignature);
        testBitmap = new byte[]{(byte) 0xFF};

        // Setup public keys per committee
        publicKeysPerCommittee = new HashMap<>();
        publicKeysPerCommittee.put(1L, List.of(createMockPublicKey(1)));
        publicKeysPerCommittee.put(2L, List.of(createMockPublicKey(2)));
    }

    @Test
    @DisplayName("should create validator with required parameters")
    void shouldCreateValidator() {
        var validator = new HierarchicalAggregateValidator(mockProvider, publicKeysPerCommittee, testMessage);

        assertThat(validator).isNotNull();
        assertThat(validator.getVerificationCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("should reject null provider")
    void shouldRejectNullProvider() {
        assertThatThrownBy(() -> new HierarchicalAggregateValidator(null, publicKeysPerCommittee, testMessage))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should reject null public keys map")
    void shouldRejectNullPublicKeysMap() {
        assertThatThrownBy(() -> new HierarchicalAggregateValidator(mockProvider, null, testMessage))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should reject null message")
    void shouldRejectNullMessage() {
        assertThatThrownBy(() -> new HierarchicalAggregateValidator(mockProvider, publicKeysPerCommittee, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should verify valid aggregate")
    void shouldVerifyValidAggregate() {
        // Setup: valid leaf node
        var leaf = new TreeNode.LeafNode(1L, blsTestSignature, 5, testBitmap, 1, 0, Optional.empty());
        var treeConfig = TreeConfiguration.create(1, 8);
        var aggregate = new HierarchicalAggregate(leaf, treeConfig, createTestEvent(), 5, 1);

        // Setup mock: verify succeeds
        when(mockProvider.verifyAggregate(
            anyList(), any(byte[].class), any(byte[].class)
        )).thenReturn(true);

        var validator = new HierarchicalAggregateValidator(mockProvider, publicKeysPerCommittee, testMessage);

        assertThat(validator.verify(aggregate)).isTrue();
        assertThat(validator.getVerificationCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("should reject invalid aggregate")
    void shouldRejectInvalidAggregate() {
        var leaf = new TreeNode.LeafNode(1L, blsTestSignature, 5, testBitmap, 1, 0, Optional.empty());
        var treeConfig = TreeConfiguration.create(1, 8);
        var aggregate = new HierarchicalAggregate(leaf, treeConfig, createTestEvent(), 5, 1);

        // Setup mock: verify fails
        when(mockProvider.verifyAggregate(
            anyList(), any(byte[].class), any(byte[].class)
        )).thenReturn(false);

        var validator = new HierarchicalAggregateValidator(mockProvider, publicKeysPerCommittee, testMessage);

        assertThat(validator.verify(aggregate)).isFalse();
    }

    @Test
    @DisplayName("should handle null aggregate")
    void shouldHandleNullAggregate() {
        var validator = new HierarchicalAggregateValidator(mockProvider, publicKeysPerCommittee, testMessage);

        assertThat(validator.verify(null)).isFalse();
    }

    @Test
    @DisplayName("should find Byzantine leaf node")
    void shouldFindByzantineLeafNode() {
        var leaf = new TreeNode.LeafNode(1L, blsTestSignature, 5, testBitmap, 1, 0, Optional.empty());
        var treeConfig = TreeConfiguration.create(1, 8);
        var aggregate = new HierarchicalAggregate(leaf, treeConfig, createTestEvent(), 5, 1);

        // Setup mock: verify fails for leaf
        when(mockProvider.verifyAggregate(
            anyList(), any(byte[].class), any(byte[].class)
        )).thenReturn(false);

        var validator = new HierarchicalAggregateValidator(mockProvider, publicKeysPerCommittee, testMessage);

        var byzantine = validator.findByzantineNode(aggregate);
        assertThat(byzantine).isPresent();
        assertThat(byzantine.get()).isInstanceOf(TreeNode.LeafNode.class);
    }

    @Test
    @DisplayName("should find Byzantine node in multi-level tree")
    void shouldFindByzantineNodeInMultiLevelTree() {
        // Create 2-level tree: one leaf is Byzantine
        var validLeaf = new TreeNode.LeafNode(2L, blsTestSignature, 5, testBitmap, 2, 0, Optional.empty());
        var byzantineLeaf = new TreeNode.LeafNode(1L, blsTestSignature, 5, testBitmap, 2, 1, Optional.empty());

        byte[] intermediateSignature = new byte[96];
        Arrays.fill(intermediateSignature, (byte) 0xBB);

        var intermediate = new TreeNode.IntermediateNode(
            List.of(validLeaf, byzantineLeaf),
            new BLSSignature(intermediateSignature),
            10,  // totalSignerCount
            1,   // depth
            0,
            Optional.empty()
        );

        var treeConfig = TreeConfiguration.create(2, 8);
        var aggregate = new HierarchicalAggregate(intermediate, treeConfig, createTestEvent(), 10, 2);

        // Setup mock: root fails verification (aggregate doesn't match children)
        when(mockProvider.aggregateSignatures(anyList()))
            .thenReturn(new byte[96]);  // Return different aggregate (mismatch)
        when(mockProvider.verifyAggregate(anyList(), any(byte[].class), any(byte[].class)))
            .thenReturn(false);  // All verify fail

        var validator = new HierarchicalAggregateValidator(mockProvider, publicKeysPerCommittee, testMessage);

        var byzantine = validator.findByzantineNode(aggregate);
        assertThat(byzantine).isPresent();
    }

    @Test
    @DisplayName("should return empty for valid aggregate during isolation")
    void shouldReturnEmptyForValidDuringIsolation() {
        var leaf = new TreeNode.LeafNode(1L, blsTestSignature, 5, testBitmap, 1, 0, Optional.empty());
        var treeConfig = TreeConfiguration.create(1, 8);
        var aggregate = new HierarchicalAggregate(leaf, treeConfig, createTestEvent(), 5, 1);

        // Setup mock: all verify
        when(mockProvider.verifyAggregate(
            anyList(), any(byte[].class), any(byte[].class)
        )).thenReturn(true);

        var validator = new HierarchicalAggregateValidator(mockProvider, publicKeysPerCommittee, testMessage);

        var byzantine = validator.findByzantineNode(aggregate);
        assertThat(byzantine).isEmpty();
    }

    @Test
    @DisplayName("should verify single subtree node")
    void shouldVerifySingleSubtreeNode() {
        var leaf = new TreeNode.LeafNode(1L, blsTestSignature, 5, testBitmap, 1, 0, Optional.empty());

        // Setup mock: verify succeeds
        when(mockProvider.verifyAggregate(
            anyList(), any(byte[].class), any(byte[].class)
        )).thenReturn(true);

        var validator = new HierarchicalAggregateValidator(mockProvider, publicKeysPerCommittee, testMessage);

        assertThat(validator.verifySubtree(leaf)).isTrue();
    }

    @Test
    @DisplayName("should reject null subtree")
    void shouldRejectNullSubtree() {
        var validator = new HierarchicalAggregateValidator(mockProvider, publicKeysPerCommittee, testMessage);

        assertThat(validator.verifySubtree(null)).isFalse();
    }

    @Test
    @DisplayName("should count verifications")
    void shouldCountVerifications() {
        var leaf = new TreeNode.LeafNode(1L, blsTestSignature, 5, testBitmap, 1, 0, Optional.empty());
        var treeConfig = TreeConfiguration.create(1, 8);
        var aggregate = new HierarchicalAggregate(leaf, treeConfig, createTestEvent(), 5, 1);

        when(mockProvider.verifyAggregate(
            anyList(), any(byte[].class), any(byte[].class)
        )).thenReturn(true);

        var validator = new HierarchicalAggregateValidator(mockProvider, publicKeysPerCommittee, testMessage);

        assertThat(validator.getVerificationCount()).isEqualTo(0);
        validator.verify(aggregate);
        assertThat(validator.getVerificationCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("should reset verification counter")
    void shouldResetVerificationCounter() {
        var leaf = new TreeNode.LeafNode(1L, blsTestSignature, 5, testBitmap, 1, 0, Optional.empty());
        var treeConfig = TreeConfiguration.create(1, 8);
        var aggregate = new HierarchicalAggregate(leaf, treeConfig, createTestEvent(), 5, 1);

        when(mockProvider.verifyAggregate(
            anyList(), any(byte[].class), any(byte[].class)
        )).thenReturn(true);

        var validator = new HierarchicalAggregateValidator(mockProvider, publicKeysPerCommittee, testMessage);

        validator.verify(aggregate);
        long firstCount = validator.getVerificationCount();
        assertThat(firstCount).isGreaterThan(0);

        validator.resetVerificationCount();
        assertThat(validator.getVerificationCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("should handle missing public keys for committee")
    void shouldHandleMissingPublicKeysForCommittee() {
        // Create leaf for committee with no public keys
        var leaf = new TreeNode.LeafNode(999L, blsTestSignature, 5, testBitmap, 1, 0, Optional.empty());
        var treeConfig = TreeConfiguration.create(1, 8);
        var aggregate = new HierarchicalAggregate(leaf, treeConfig, createTestEvent(), 5, 1);

        var validator = new HierarchicalAggregateValidator(mockProvider, publicKeysPerCommittee, testMessage);

        // Should return false because public keys not found
        assertThat(validator.verify(aggregate)).isFalse();
    }

    @Test
    @DisplayName("should verify multiple children in intermediate node")
    void shouldVerifyMultipleChildrenInIntermediateNode() {
        var leaf1 = new TreeNode.LeafNode(1L, blsTestSignature, 3, testBitmap, 2, 0, Optional.empty());
        var leaf2 = new TreeNode.LeafNode(2L, blsTestSignature, 2, testBitmap, 2, 1, Optional.empty());

        byte[] intermediateSignature = new byte[96];
        Arrays.fill(intermediateSignature, (byte) 0xBB);

        var intermediate = new TreeNode.IntermediateNode(
            List.of(leaf1, leaf2),
            new BLSSignature(intermediateSignature),
            5,
            1,
            0,
            Optional.empty()
        );

        // Setup mock: intermediate sig matches children agg
        when(mockProvider.aggregateSignatures(anyList()))
            .thenReturn(intermediateSignature);
        when(mockProvider.verifyAggregate(
            anyList(), any(byte[].class), any(byte[].class)
        )).thenReturn(true);

        var validator = new HierarchicalAggregateValidator(mockProvider, publicKeysPerCommittee, testMessage);

        assertThat(validator.verifySubtree(intermediate)).isTrue();
    }

    // Helper methods

    private byte[] createMockPublicKey(int index) {
        byte[] key = new byte[48];
        Arrays.fill(key, (byte) index);
        return key;
    }

    private com.hellblazer.delos.stereotomy.EventCoordinates createTestEvent() {
        var digestAlgorithm = com.hellblazer.delos.cryptography.DigestAlgorithm.DEFAULT;
        var identifier = new com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier(
            digestAlgorithm.digest("test".getBytes())
        );
        var digest = digestAlgorithm.digest("event-test".getBytes());
        return new com.hellblazer.delos.stereotomy.EventCoordinates(
            identifier,
            org.joou.ULong.valueOf(1L),
            digest,
            "test"
        );
    }
}
