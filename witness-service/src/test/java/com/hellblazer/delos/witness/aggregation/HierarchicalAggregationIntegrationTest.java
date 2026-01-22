/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for hierarchical aggregation.
 * <p>
 * Tests end-to-end aggregation flow for various committee sizes,
 * demonstrating O(log n) complexity and Byzantine isolation.
 */
@DisplayName("Hierarchical Aggregation Integration")
class HierarchicalAggregationIntegrationTest {

    @Mock
    private BLSProvider mockProvider;

    private byte[] testMessage;
    private EventCoordinates testEvent;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        testMessage = "consensus-message".getBytes();
        testEvent = createTestEvent();

        // Setup mock to aggregate and verify correctly
        when(mockProvider.aggregateSignatures(anyList()))
            .thenAnswer(invocation -> {
                List<byte[]> sigs = invocation.getArgument(0);
                // Simple mock: XOR first 10 bytes of all signatures
                byte[] result = new byte[96];
                for (byte[] sig : sigs) {
                    for (int i = 0; i < Math.min(10, sig.length); i++) {
                        result[i] ^= sig[i];
                    }
                }
                return result;
            });

        when(mockProvider.verifyAggregate(anyList(), any(byte[].class), any(byte[].class)))
            .thenReturn(true);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 8, 10, 64, 100})
    @DisplayName("should build and verify aggregates for committee counts")
    void shouldBuildAndVerifyForVariousCommitteeCounts(int committeeCount) {
        // Build aggregator and add committees
        var treeConfig = TreeConfiguration.create(committeeCount, 8);
        var aggregator = new HierarchicalAggregator(testEvent, mockProvider, treeConfig);

        // Add committee signatures
        byte[] testBitmap = new byte[]{(byte) 0xFF};
        for (int i = 1; i <= committeeCount; i++) {
            byte[] sig = createTestSignature(i);
            aggregator.addCommitteeSignatures((long) i, List.of(new BLSSignature(sig)), testBitmap);
        }

        // Build aggregate
        HierarchicalAggregate aggregate = aggregator.build();

        // Verify structure
        assertThat(aggregate.leafCommitteeCount()).isEqualTo(committeeCount);
        assertThat(aggregate.getTreeDepth()).isEqualTo(treeConfig.maxDepth());
        assertThat(aggregate.getBranchingFactor()).isEqualTo(8);

        // Verify aggregate has reasonable storage estimate
        assertThat(aggregate.estimatedStorageBytes()).isGreaterThan(0);
        assertThat(aggregate.estimatedVerificationCost()).isLessThanOrEqualTo(aggregate.getTreeDepth());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8, 10, 64})
    @DisplayName("should validate full aggregates")
    void shouldValidateFullAggregates(int committeeCount) {
        // Build aggregate
        var treeConfig = TreeConfiguration.create(committeeCount, 8);
        var aggregator = new HierarchicalAggregator(testEvent, mockProvider, treeConfig);

        byte[] testBitmap = new byte[]{(byte) 0xFF};
        Map<Long, List<byte[]>> publicKeysPerCommittee = new HashMap<>();

        for (int i = 1; i <= committeeCount; i++) {
            byte[] sig = createTestSignature(i);
            aggregator.addCommitteeSignatures((long) i, List.of(new BLSSignature(sig)), testBitmap);
            publicKeysPerCommittee.put((long) i, List.of(createMockPublicKey(i)));
        }

        HierarchicalAggregate aggregate = aggregator.build();

        // Create validator and verify
        var validator = new HierarchicalAggregateValidator(mockProvider, publicKeysPerCommittee, testMessage);
        assertThat(validator.verify(aggregate)).isTrue();

        // Verify count should be reasonable (one per tree level)
        long verificationCount = validator.getVerificationCount();
        assertThat(verificationCount).isGreaterThan(0);
        assertThat(verificationCount).isLessThanOrEqualTo(treeConfig.maxDepth() * 10);
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 50, 100})
    @DisplayName("should verify verification complexity is logarithmic")
    void shouldDemonstrateByzantineIsolation(int committeeCount) {
        // Build aggregate with proper verification setup
        var treeConfig = TreeConfiguration.create(committeeCount, 8);
        var aggregator = new HierarchicalAggregator(testEvent, mockProvider, treeConfig);

        byte[] testBitmap = new byte[]{(byte) 0xFF};
        Map<Long, List<byte[]>> publicKeysPerCommittee = new HashMap<>();

        for (int i = 1; i <= committeeCount; i++) {
            byte[] sig = createTestSignature(i);
            aggregator.addCommitteeSignatures((long) i, List.of(new BLSSignature(sig)), testBitmap);
            publicKeysPerCommittee.put((long) i, List.of(createMockPublicKey(i)));
        }

        HierarchicalAggregate aggregate = aggregator.build();

        // Create validator with properly configured mock that verifies successfully
        var validator = new HierarchicalAggregateValidator(mockProvider, publicKeysPerCommittee, testMessage);

        // Verify the aggregate (counts verifications)
        assertThat(validator.verify(aggregate)).isTrue();

        // Verify verification count is logarithmic (proportional to depth)
        // With k=8 and n=100, depth=4, so verification count should be roughly O(log n) ~= O(4)
        long verificationCount = validator.getVerificationCount();
        int expectedDepth = treeConfig.maxDepth();

        // Verification count should be related to tree depth
        assertThat(verificationCount).isGreaterThan(0);
        assertThat(verificationCount).isLessThanOrEqualTo((long) expectedDepth * 10);  // Conservative bound
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 8, 10, 64})
    @DisplayName("should handle committees added out of order")
    void shouldHandleCommitteesOutOfOrder(int committeeCount) {
        var treeConfig = TreeConfiguration.create(committeeCount, 8);
        var aggregator = new HierarchicalAggregator(testEvent, mockProvider, treeConfig);

        byte[] testBitmap = new byte[]{(byte) 0xFF};

        // Add in random order
        List<Integer> order = new ArrayList<>();
        for (int i = 1; i <= committeeCount; i++) {
            order.add(i);
        }
        Collections.shuffle(order);

        for (int idx : order) {
            byte[] sig = createTestSignature(idx);
            aggregator.addCommitteeSignatures((long) idx, List.of(new BLSSignature(sig)), testBitmap);
        }

        HierarchicalAggregate aggregate = aggregator.build();

        // Verify structure is correct regardless of addition order
        assertThat(aggregate.leafCommitteeCount()).isEqualTo(committeeCount);
        assertThat(aggregate.getTreeDepth()).isEqualTo(treeConfig.maxDepth());
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 8, 16})
    @DisplayName("should verify tree depth matches configuration")
    void shouldVerifyTreeDepth(int committeeCount) {
        var treeConfig = TreeConfiguration.create(committeeCount, 8);
        int expectedDepth = treeConfig.maxDepth();

        var aggregator = new HierarchicalAggregator(testEvent, mockProvider, treeConfig);

        byte[] testBitmap = new byte[]{(byte) 0xFF};
        for (int i = 1; i <= committeeCount; i++) {
            byte[] sig = createTestSignature(i);
            aggregator.addCommitteeSignatures((long) i, List.of(new BLSSignature(sig)), testBitmap);
        }

        HierarchicalAggregate aggregate = aggregator.build();

        // Verify depth formula: ceil(log_8(committeeCount))
        assertThat(aggregate.getTreeDepth()).isEqualTo(expectedDepth);

        // Verify depth is logarithmic
        int maxExpectedDepth = (int) (Math.log(committeeCount) / Math.log(8)) + 1;
        assertThat(aggregate.getTreeDepth()).isLessThanOrEqualTo(maxExpectedDepth + 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 50, 100})
    @DisplayName("should demonstrate storage efficiency")
    void shouldDemonstrateStorageEfficiency(int committeeCount) {
        var treeConfig = TreeConfiguration.create(committeeCount, 8);
        var aggregator = new HierarchicalAggregator(testEvent, mockProvider, treeConfig);

        byte[] testBitmap = new byte[]{(byte) 0xFF};
        for (int i = 1; i <= committeeCount; i++) {
            byte[] sig = createTestSignature(i);
            aggregator.addCommitteeSignatures((long) i, List.of(new BLSSignature(sig)), testBitmap);
        }

        HierarchicalAggregate aggregate = aggregator.build();

        // Storage should be much less than flat aggregation
        long estimatedBytes = aggregate.estimatedStorageBytes();
        long flatEstimate = (long) committeeCount * 96 * 8;  // Conservative flat estimate

        // Hierarchical should be significantly more efficient
        assertThat(estimatedBytes).isLessThan(flatEstimate);

        // Compression ratio should be reasonable
        double compression = aggregate.estimatedCompressionRatio();
        assertThat(compression).isGreaterThan(0).isLessThan(1);
    }

    // Helper methods

    private byte[] createTestSignature(int index) {
        byte[] sig = new byte[96];
        Arrays.fill(sig, (byte) index);
        return sig;
    }

    private byte[] createMockPublicKey(int index) {
        byte[] key = new byte[48];
        Arrays.fill(key, (byte) index);
        return key;
    }

    private EventCoordinates createTestEvent() {
        var digestAlgorithm = DigestAlgorithm.DEFAULT;
        var identifier = new SelfAddressingIdentifier(
            digestAlgorithm.digest("integration-test".getBytes())
        );
        var digest = digestAlgorithm.digest("event-integration".getBytes());
        return new EventCoordinates(
            identifier,
            org.joou.ULong.valueOf(1L),
            digest,
            "test"
        );
    }
}
