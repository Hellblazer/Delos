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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive test suite for EpochTransitionValidator.
 * Tests state continuity, chain integrity, and Byzantine safety rules.
 *
 * @author hal.hildebrand
 */
@DisplayName("EpochTransitionValidator")
class EpochTransitionValidatorTest {

    private EpochTransitionValidator validator;
    private EpochTransitionConfig defaultConfig;
    private EventCoordinates event;
    private TreeConfiguration treeConfig;
    private BLSSignature testSignature;
    private byte[] testBitmap;

    @BeforeEach
    void setup() {
        validator = new EpochTransitionValidator();
        defaultConfig = EpochTransitionConfig.defaults();

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

        // Create test tree configuration
        treeConfig = TreeConfiguration.create(10, 3);
    }

    // ===== Category A: validateTransition Success Cases =====

    @Test
    @DisplayName("should validate identical aggregates with same metrics")
    void testValidateTransition_IdenticalAggregates_Success() {
        var from = createTestAggregate(100, 10, 1);
        var to = createTestAggregate(100, 10, 1);

        var result = validator.validateTransition(from, to, defaultConfig);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("should validate transition with increasing signers within threshold")
    void testValidateTransition_IncreasingSigners_Success() {
        var from = createTestAggregate(100, 10, 1);
        var to = createTestAggregate(150, 10, 1);  // 50% increase

        var result = validator.validateTransition(from, to, defaultConfig);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("should validate transition with decreasing signers within threshold")
    void testValidateTransition_DecreasingSigners_Success() {
        var from = createTestAggregate(100, 10, 1);
        var to = createTestAggregate(60, 10, 1);   // 40% decrease

        var result = validator.validateTransition(from, to, defaultConfig);

        assertThat(result.isValid()).isTrue();
    }

    // ===== Category B: validateTransition Failure Cases =====

    @Test
    @DisplayName("should reject transition with signers exceeding max threshold")
    void testValidateTransition_ExcessiveSignerChange_Failure() {
        var from = createTestAggregate(100, 10, 1);
        var to = createTestAggregate(200, 10, 1);  // 100% increase, threshold is 50%

        var result = validator.validateTransition(from, to, defaultConfig);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason())
            .isPresent()
            .get()
            .asString()
            .contains("Signer count change");
    }

    @Test
    @DisplayName("should reject transition with committee change exceeding threshold")
    void testValidateTransition_ExcessiveCommitteeChange_Failure() {
        var from = createTestAggregate(100, 10, 1);
        var to = createTestAggregate(100, 50, 1);  // 400% increase, threshold is 33%

        var result = validator.validateTransition(from, to, defaultConfig);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason())
            .isPresent()
            .get()
            .asString()
            .contains("Committee count change");
    }

    @Test
    @DisplayName("should reject transition with incompatible tree config")
    void testValidateTransition_IncompatibleTreeConfig_Failure() {
        var from = createTestAggregate(100, 10, 1);
        var to = createTestAggregateWithConfig(100, 10, 1, TreeConfiguration.create(10, 4));

        var result = validator.validateTransition(from, to, defaultConfig);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason())
            .isPresent()
            .get()
            .asString()
            .contains("tree config");
    }

    @Test
    @DisplayName("should reject transition with null from aggregate")
    void testValidateTransition_NullFrom_Failure() {
        var to = createTestAggregate(100, 10, 1);

        assertThatThrownBy(() -> validator.validateTransition(null, to, defaultConfig))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should reject transition with null to aggregate")
    void testValidateTransition_NullTo_Failure() {
        var from = createTestAggregate(100, 10, 1);

        assertThatThrownBy(() -> validator.validateTransition(from, null, defaultConfig))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should reject transition with null config")
    void testValidateTransition_NullConfig_Failure() {
        var from = createTestAggregate(100, 10, 1);
        var to = createTestAggregate(100, 10, 1);

        assertThatThrownBy(() -> validator.validateTransition(from, to, null))
            .isInstanceOf(NullPointerException.class);
    }

    // ===== Category C: verifyChainIntegrity Success Cases =====

    @Test
    @DisplayName("should validate single-element chain")
    void testVerifyChainIntegrity_SingleElement_Success() {
        var chain = List.of(createTestAggregate(100, 10, 1));

        var result = validator.verifyChainIntegrity(chain, defaultConfig);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("should validate multi-element chain with valid transitions")
    void testVerifyChainIntegrity_MultiElement_Success() {
        var chain = List.of(
            createTestAggregate(100, 10, 1),
            createTestAggregate(110, 10, 1),
            createTestAggregate(120, 11, 1)
        );

        var result = validator.verifyChainIntegrity(chain, defaultConfig);

        assertThat(result.isValid()).isTrue();
    }

    // ===== Category D: verifyChainIntegrity Failure Cases =====

    @Test
    @DisplayName("should reject empty chain")
    void testVerifyChainIntegrity_EmptyChain_Failure() {
        var result = validator.verifyChainIntegrity(List.of(), defaultConfig);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason())
            .isPresent()
            .get()
            .asString()
            .contains("Empty chain");
    }

    @Test
    @DisplayName("should reject null chain")
    void testVerifyChainIntegrity_NullChain_Failure() {
        assertThatThrownBy(() -> validator.verifyChainIntegrity(null, defaultConfig))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should reject chain with invalid transition")
    void testVerifyChainIntegrity_InvalidTransition_Failure() {
        var chain = List.of(
            createTestAggregate(100, 10, 1),
            createTestAggregate(500, 50, 1)  // Excessive changes
        );

        var result = validator.verifyChainIntegrity(chain, defaultConfig);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason())
            .isPresent()
            .get()
            .asString()
            .contains("Transition failed");
    }

    // ===== Category E: checkCommitteeConsistency Success Cases =====

    @Test
    @DisplayName("should validate Byzantine safety for chain with f tolerance")
    void testCheckCommitteeConsistency_ByzantineSafe_Success() {
        var chain = List.of(
            createTestAggregate(100, 10, 1),
            createTestAggregate(100, 11, 1)   // 1 committee change, f=3, OK
        );

        var result = validator.checkCommitteeConsistency(chain, defaultConfig);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("should validate quorum maintenance for chain")
    void testCheckCommitteeConsistency_QuorumMaintained_Success() {
        var chain = List.of(
            createTestAggregate(100, 10, 1),
            createTestAggregate(100, 9, 1)    // Still above 2f+1
        );

        var result = validator.checkCommitteeConsistency(chain, defaultConfig);

        assertThat(result.isValid()).isTrue();
    }

    // ===== Category F: checkCommitteeConsistency Failure Cases =====

    @Test
    @DisplayName("should reject chain violating Byzantine safety threshold")
    void testCheckCommitteeConsistency_ByzantineThresholdExceeded_Failure() {
        var chain = List.of(
            createTestAggregate(100, 12, 1),
            createTestAggregate(100, 2, 1)    // 10 committee changes, f=3, FAIL
        );

        var result = validator.checkCommitteeConsistency(chain, defaultConfig);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason())
            .isPresent()
            .get()
            .asString()
            .contains("Byzantine");
    }

    @Test
    @DisplayName("should reject chain losing quorum")
    void testCheckCommitteeConsistency_QuorumLost_Failure() {
        // With 10 committees, f = (10-1)/3 = 3, so 2f+1 = 7 signers required
        var chain = List.of(
            createTestAggregate(100, 10, 1),
            createTestAggregate(6, 10, 1)     // Only 6 signers, need 7 for quorum
        );

        var result = validator.checkCommitteeConsistency(chain, defaultConfig);

        assertThat(result.isValid()).isFalse();
    }

    @Test
    @DisplayName("should reject empty committee chain")
    void testCheckCommitteeConsistency_EmptyChain_Failure() {
        var result = validator.checkCommitteeConsistency(List.of(), defaultConfig);

        assertThat(result.isValid()).isFalse();
    }

    // ===== Category G: Configuration Flexibility =====

    @Test
    @DisplayName("should allow signer changes exceeding strict thresholds with lenient config")
    void testValidateTransition_LenientConfig_Success() {
        // Strict config only allows 50% signer change; lenient allows 100%
        var strictConfig = EpochTransitionConfig.defaults();
        var lenientConfig = EpochTransitionConfig.lenient();
        var from = createTestAggregate(100, 10, 1);
        var to = createTestAggregate(250, 10, 1);  // 150% increase

        // Should fail with strict config
        assertThat(validator.validateTransition(from, to, strictConfig).isValid()).isFalse();

        // Should pass with lenient config (100% threshold)
        assertThat(validator.validateTransition(from, to, lenientConfig).isValid()).isTrue();
    }

    @Test
    @DisplayName("should allow minimal signer changes when configured")
    void testValidateTransition_MinimalChanges_Success() {
        var config = new EpochTransitionConfig(33, 50, true, true, true);
        var from = createTestAggregate(100, 10, 1);
        var to = createTestAggregate(50, 10, 1);  // 50% decrease, at threshold

        var result = validator.validateTransition(from, to, config);

        assertThat(result.isValid()).isTrue();
    }

    // ===== Category H: Integration Tests =====

    @Test
    @DisplayName("should validate full chain through all validation stages")
    void testFullChainValidation_AllStages_Success() {
        var chain = List.of(
            createTestAggregate(100, 10, 1),
            createTestAggregate(110, 11, 1),
            createTestAggregate(120, 12, 1),
            createTestAggregate(130, 13, 1)
        );

        // All three validators should pass
        assertThat(validator.verifyChainIntegrity(chain, defaultConfig).isValid()).isTrue();
        assertThat(validator.checkCommitteeConsistency(chain, defaultConfig).isValid()).isTrue();
    }

    // ===== Helper Methods =====

    private HierarchicalAggregate createTestAggregate(int totalSigners, int leafCommittees, int depth) {
        // Create a tree configuration that matches the leaf committee count
        var config = TreeConfiguration.create(leafCommittees, 3);
        var leaf = new TreeNode.LeafNode(1L, testSignature, totalSigners, testBitmap, depth, 0, Optional.empty());
        return new HierarchicalAggregate(leaf, config, event, totalSigners, leafCommittees);
    }

    private HierarchicalAggregate createTestAggregateWithConfig(
        int totalSigners, int leafCommittees, int depth,
        TreeConfiguration config
    ) {
        // Verify that the config supports the desired leaf committee count
        if (config.committeeCount() != leafCommittees) {
            config = TreeConfiguration.create(leafCommittees, config.maxDepth());
        }
        var leaf = new TreeNode.LeafNode(1L, testSignature, totalSigners, testBitmap, depth, 0, Optional.empty());
        return new HierarchicalAggregate(leaf, config, event, totalSigners, leafCommittees);
    }
}
