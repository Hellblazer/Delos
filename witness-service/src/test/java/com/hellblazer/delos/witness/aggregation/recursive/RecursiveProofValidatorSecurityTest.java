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
import com.hellblazer.delos.cryptography.bls.BLSKeyPair;
import com.hellblazer.delos.cryptography.bls.BLSOperations;
import com.hellblazer.delos.cryptography.bls.BLSPublicKey;
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

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Security-focused tests for RecursiveProofValidator.
 * <p>
 * Tests cryptographic verification correctness including:
 * - Forged intermediate node rejection (security regression tests)
 * - Epoch message construction determinism and domain separation
 * <p>
 * These tests verify that the validator actually performs cryptographic checks
 * rather than returning valid() unconditionally.
 *
 * @author hal.hildebrand
 * @since Phase 1 P0 Security (Delos-izm.1.5)
 */
@DisplayName("RecursiveProofValidator Security Tests")
class RecursiveProofValidatorSecurityTest {

    private RecursiveProofValidator validator;
    private EventCoordinates event;

    @BeforeEach
    void setup() {
        validator = new RecursiveProofValidator();

        var digestAlgorithm = DigestAlgorithm.DEFAULT;
        var identifier = new SelfAddressingIdentifier(digestAlgorithm.digest("test-security".getBytes()));
        var digest = digestAlgorithm.digest("event-security-test".getBytes());
        event = new EventCoordinates(identifier, ULong.valueOf(1L), digest, "test");
    }

    /**
     * Regression test: a forged intermediate node with random/invalid BLS signature bytes
     * must be REJECTED by verifyHierarchicalAggregateBLS, not silently accepted.
     * <p>
     * Prior to fix: the IntermediateNode branch returned ValidationResult.valid() after
     * recursing children, ignoring the intermediate node's own signature entirely.
     * <p>
     * This test constructs a tree where:
     * - The leaf node has a VALID BLS signature (correctly signed with real key pair)
     * - The intermediate (root) node has a FORGED signature (random bytes)
     * <p>
     * The leaf is signed with the placeholder message ({@code new byte[32]}) that the
     * current implementation uses — so the leaf passes. The intermediate signature is
     * random and cannot be a valid BLS aggregate. A secure implementation must reject it.
     */
    @Test
    @DisplayName("forged intermediate node with random signature bytes must be rejected")
    void testForgedIntermediateNode_Rejected() {
        var random = new Random(12345L); // seeded for reproducibility

        // Create a real key pair for the leaf committee
        var leafKeyPair = BLSOperations.generateKeyPair(random);

        // The current leaf verification uses new byte[32] as the placeholder message.
        // Sign that placeholder so the leaf PASSES verification.
        var leafPlaceholderMessage = new byte[32];
        var leafSig = BLSOperations.sign(leafKeyPair, leafPlaceholderMessage);

        // Valid bitmap: 1 signer at index 0
        var bitmap = new byte[]{(byte) 0x01};

        // Leaf node with real, correctly signed signature
        var leaf = new TreeNode.LeafNode(
            1L, // committeeEpoch = epoch 1
            leafSig,
            1, // signerCount
            bitmap,
            2, // depth 2 (leaf)
            0, // index
            Optional.empty()
        );

        // Intermediate (root) node with FORGED signature (random bytes)
        var forgedSigBytes = new byte[96];
        random.nextBytes(forgedSigBytes);
        var forgedSig = new BLSSignature(forgedSigBytes);

        var intermediate = new TreeNode.IntermediateNode(
            List.of(leaf),
            forgedSig,   // FORGED — this is not a valid BLS aggregate
            1,           // totalSignerCount
            1,           // depth 1 (root)
            0,           // index
            Optional.empty()
        );

        var treeConfig = TreeConfiguration.create(1, 2);
        var aggregate = new HierarchicalAggregate(intermediate, treeConfig, event, 1, 1);

        // Key resolver: provide real keys for all lookups
        final var lkp = leafKeyPair;
        RecursiveKeyResolver keyResolver = new RecursiveKeyResolver() {
            @Override
            public List<BLSPublicKey> getCommitteeKeys(long epochNumber, int committeeIndex) {
                return List.of(lkp.publicKey());
            }
            @Override
            public List<BLSPublicKey> getDeprecatedKeys(long epochNumber, int committeeIndex) {
                return List.of();
            }
            @Override
            public boolean isKeyValid(BLSPublicKey key, long epochNumber) {
                return true;
            }
        };
        GracePeriodKeyLookup graceLookup = (epochNumber, committeeIndex) -> List.of();
        // Root hash lookup: always return a fixed hash for testing
        java.util.function.Function<Long, Digest> rootHashLookup =
            epoch -> DigestAlgorithm.DEFAULT.digest("test-root-hash".getBytes());

        var result = validator.verifyHierarchicalAggregateBLS(aggregate, keyResolver, rootHashLookup, graceLookup);

        // The forged intermediate signature MUST be rejected.
        // If this fails (result.isValid() == true), it confirms the security vulnerability:
        // intermediate signatures are not being checked.
        assertThat(result.isValid())
            .as("Forged intermediate node with random signature bytes must be REJECTED, not silently accepted")
            .isFalse();
    }

    /**
     * constructEpochMessage must produce the same output for the same inputs (determinism).
     */
    @Test
    @DisplayName("constructEpochMessage produces same bytes for same epoch and root hash")
    void testConstructEpochMessage_Deterministic() {
        var rootHash = DigestAlgorithm.DEFAULT.digest("some-root-hash".getBytes());

        var msg1 = invokeConstructEpochMessage(5L, rootHash);
        var msg2 = invokeConstructEpochMessage(5L, rootHash);

        assertThat(msg1).isEqualTo(msg2);
    }

    /**
     * Different epoch numbers must produce different messages (prevents epoch replay).
     */
    @Test
    @DisplayName("constructEpochMessage produces different bytes for different epoch numbers")
    void testConstructEpochMessage_DifferentEpochs_DifferentMessages() {
        var rootHash = DigestAlgorithm.DEFAULT.digest("same-root".getBytes());

        var msg1 = invokeConstructEpochMessage(1L, rootHash);
        var msg2 = invokeConstructEpochMessage(2L, rootHash);

        assertThat(msg1).isNotEqualTo(msg2);
    }

    /**
     * Different root hashes must produce different messages (prevents cross-chain replay).
     */
    @Test
    @DisplayName("constructEpochMessage produces different bytes for different root hashes")
    void testConstructEpochMessage_DifferentRootHashes_DifferentMessages() {
        var rootHash1 = DigestAlgorithm.DEFAULT.digest("root-hash-1".getBytes());
        var rootHash2 = DigestAlgorithm.DEFAULT.digest("root-hash-2".getBytes());

        var msg1 = invokeConstructEpochMessage(1L, rootHash1);
        var msg2 = invokeConstructEpochMessage(1L, rootHash2);

        assertThat(msg1).isNotEqualTo(msg2);
    }

    /**
     * The epoch message must not be all zeros (placeholder sentinel value).
     * A real implementation produces a non-trivial hash output.
     */
    @Test
    @DisplayName("constructEpochMessage returns non-zero array (not placeholder)")
    void testConstructEpochMessage_NotZeroArray() {
        var rootHash = DigestAlgorithm.DEFAULT.digest("non-zero-root".getBytes());
        var zeroArray = new byte[32]; // default placeholder

        var msg = invokeConstructEpochMessage(1L, rootHash);

        assertThat(msg).isNotNull();
        assertThat(msg.length).isGreaterThan(0);
        assertThat(msg).isNotEqualTo(zeroArray);
    }

    /**
     * Epoch messages must have correct length (BLAKE2B_256 = 32 bytes).
     */
    @Test
    @DisplayName("constructEpochMessage returns 32-byte BLAKE2B_256 hash")
    void testConstructEpochMessage_CorrectLength() {
        var rootHash = DigestAlgorithm.DEFAULT.digest("length-check".getBytes());
        var msg = invokeConstructEpochMessage(42L, rootHash);

        assertThat(msg).hasSize(32); // BLAKE2B_256 digest length
    }

    /**
     * Epoch 0 with different root hashes must still produce different messages.
     * Guard against edge case where epoch=0 collapses to zero-like behavior.
     */
    @Test
    @DisplayName("epoch 0 produces distinct messages for different root hashes")
    void testConstructEpochMessage_EpochZero_Distinct() {
        var rootHash1 = DigestAlgorithm.DEFAULT.digest("root-A".getBytes());
        var rootHash2 = DigestAlgorithm.DEFAULT.digest("root-B".getBytes());

        var msg1 = invokeConstructEpochMessage(0L, rootHash1);
        var msg2 = invokeConstructEpochMessage(0L, rootHash2);

        assertThat(msg1).isNotEqualTo(msg2);
    }

    // ---- Helper: access private constructEpochMessage via reflection ----

    private byte[] invokeConstructEpochMessage(long epochNumber, Digest previousRootHash) {
        try {
            var method = RecursiveProofValidator.class.getDeclaredMethod(
                "constructEpochMessage", long.class, Digest.class);
            method.setAccessible(true);
            return (byte[]) method.invoke(validator, epochNumber, previousRootHash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke constructEpochMessage via reflection", e);
        }
    }
}
