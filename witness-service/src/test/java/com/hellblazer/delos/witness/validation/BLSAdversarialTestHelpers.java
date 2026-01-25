/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import com.hellblazer.delos.cryptography.bls.*;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Helper utilities for generating adversarial BLS test scenarios.
 * <p>
 * Provides methods to create invalid/corrupted BLS signatures, malformed aggregates,
 * adversarial bitmaps, and Byzantine committee attack scenarios for comprehensive
 * validation testing.
 * <p>
 * Used by {@link ByzantineBLSValidationTest} to simulate Byzantine attacks:
 * - Signature corruption and random garbage
 * - Bitmap manipulation (oversized indices, inconsistent counts)
 * - Threshold bypass attempts (empty aggregates, duplicate signatures)
 * - Coordinated Byzantine behavior (partial failures, equivocation)
 * - Rogue key attacks (without proof of possession)
 *
 * @author hal.hildebrand
 */
public final class BLSAdversarialTestHelpers {

    private BLSAdversarialTestHelpers() {
        // Static utility class
    }

    /**
     * Generate a completely random BLS signature (96 random bytes).
     * <p>
     * This will not verify against any legitimate public key or message.
     * Used to test rejection of garbage signatures.
     *
     * @param entropy Random source
     * @return BLSSignature with random bytes
     */
    public static BLSSignature generateRandomSignature(Random entropy) {
        var randomBytes = new byte[BLSSignature.COMPRESSED_SIZE];
        entropy.nextBytes(randomBytes);
        // Ensure at least one non-zero byte to pass format check
        randomBytes[0] |= 0x01;
        return new BLSSignature(randomBytes);
    }

    /**
     * Flip random bits in a valid BLS signature to corrupt it.
     * <p>
     * Corrupts between 1-8 random bits in the signature, making it cryptographically
     * invalid while maintaining proper size/format.
     *
     * @param signature Valid signature to corrupt
     * @param entropy   Random source
     * @return Corrupted BLSSignature
     */
    public static BLSSignature flipRandomBits(BLSSignature signature, Random entropy) {
        var bytes = signature.toBytes();
        var bitFlipCount = 1 + entropy.nextInt(8); // Flip 1-8 bits

        for (int i = 0; i < bitFlipCount; i++) {
            var byteIndex = entropy.nextInt(bytes.length);
            var bitIndex = entropy.nextInt(8);
            bytes[byteIndex] ^= (byte) (1 << bitIndex);
        }

        return new BLSSignature(bytes);
    }

    /**
     * Create an aggregate with valid signature for a different message.
     * <p>
     * Signs a different message than what will be validated, simulating
     * message substitution attack.
     *
     * @param committeeKeys Keys to sign with
     * @param signerIndices Which committee members sign
     * @param wrongMessage  Different message to sign
     * @return Aggregate for wrong message
     */
    public static BLSAggregate createWrongMessageAggregate(
        List<BLSKeyPair> committeeKeys,
        List<Integer> signerIndices,
        byte[] wrongMessage
    ) {
        var signatures = new ArrayList<BLSSignature>();
        for (var index : signerIndices) {
            var signature = committeeKeys.get(index).sign(wrongMessage);
            signatures.add(signature);
        }
        return BLSAggregate.aggregate(signatures, signerIndices);
    }

    /**
     * Create an aggregate with empty bitmap (all bits zero).
     * <p>
     * Should be rejected as invalid - no signers claimed.
     *
     * @param validSignature A valid signature (to satisfy constructor)
     * @return Aggregate with empty bitmap
     */
    public static BLSAggregate createEmptyBitmapAggregate(BLSSignature validSignature) {
        var emptyBitmap = new byte[1]; // All zeros
        return new BLSAggregate(validSignature, emptyBitmap);
    }

    /**
     * Create bitmap with indices beyond committee bounds.
     * <p>
     * Sets bits for indices 0-4 (valid) plus index 100 (invalid for 7-member committee).
     *
     * @return Bitmap with out-of-bounds index set
     */
    public static byte[] createOversizedBitmap() {
        // Need at least 13 bytes to represent bit 100 (100/8 = 12, so byte index 12)
        var bitmap = new byte[13];

        // Set bits 0-4 (valid signers)
        bitmap[0] = (byte) 0x1F; // 0001 1111

        // Set bit 100 (index 12, bit 4)
        bitmap[12] = (byte) 0x10; // 0001 0000

        return bitmap;
    }

    /**
     * Create bitmap claiming more signers than actual signatures.
     * <p>
     * Bitmap claims 7 signers, but only 5 signatures provided.
     * Should be rejected for inconsistency.
     *
     * @return Bitmap with 7 bits set
     */
    public static byte[] createBitmapWithExtraBits() {
        var bitmap = new byte[1];
        bitmap[0] = (byte) 0x7F; // 0111 1111 (7 bits set for indices 0-6)
        return bitmap;
    }

    /**
     * Create bitmap claiming fewer signers than actual signatures.
     * <p>
     * Bitmap claims 5 signers, but 7 signatures provided.
     * Should be rejected for inconsistency.
     *
     * @return Bitmap with 5 bits set
     */
    public static byte[] createBitmapWithClearedBits() {
        var bitmap = new byte[1];
        bitmap[0] = (byte) 0x1F; // 0001 1111 (5 bits set for indices 0-4)
        return bitmap;
    }

    /**
     * Create an aggregate with duplicate signatures.
     * <p>
     * Takes the same signature twice and aggregates it, violating
     * uniqueness assumption.
     *
     * @param signature     Single signature to duplicate
     * @param signerIndices Indices claiming multiple signers
     * @return Aggregate with duplicated signature
     */
    public static BLSAggregate createDuplicateSignatureAggregate(
        BLSSignature signature,
        List<Integer> signerIndices
    ) {
        var signatures = new ArrayList<BLSSignature>();
        // Add same signature multiple times
        for (int i = 0; i < signerIndices.size(); i++) {
            signatures.add(signature);
        }
        return BLSAggregate.aggregate(signatures, signerIndices);
    }

    /**
     * Create Byzantine (invalid) signatures for coordinated attack scenarios.
     * <p>
     * Generates random garbage signatures for Byzantine committee members.
     * Used to test that honest majority can still reach consensus despite
     * Byzantine nodes submitting invalid signatures.
     *
     * @param count   Number of Byzantine signatures to generate
     * @param entropy Random source
     * @return List of garbage signatures
     */
    public static List<BLSSignature> createByzantineSignatures(int count, Random entropy) {
        var byzantineSignatures = new ArrayList<BLSSignature>();
        for (int i = 0; i < count; i++) {
            byzantineSignatures.add(generateRandomSignature(entropy));
        }
        return byzantineSignatures;
    }

    /**
     * Create a mixed aggregate with both honest and Byzantine signatures.
     * <p>
     * Simulates coordinated attack where f Byzantine nodes submit garbage
     * while honest majority submits valid signatures.
     *
     * @param committeeKeys      All committee key pairs
     * @param honestIndices      Indices of honest signers
     * @param byzantineIndices   Indices of Byzantine signers
     * @param message            Message to sign (honest nodes)
     * @param entropy            Random source
     * @return Aggregate with mixed valid/invalid signatures
     */
    public static BLSAggregate createMixedByzantineAggregate(
        List<BLSKeyPair> committeeKeys,
        List<Integer> honestIndices,
        List<Integer> byzantineIndices,
        byte[] message,
        Random entropy
    ) {
        var allSignatures = new ArrayList<BLSSignature>();
        var allIndices = new ArrayList<Integer>();

        // Add honest signatures
        for (var index : honestIndices) {
            allSignatures.add(committeeKeys.get(index).sign(message));
            allIndices.add(index);
        }

        // Add Byzantine garbage signatures
        for (var index : byzantineIndices) {
            allSignatures.add(generateRandomSignature(entropy));
            allIndices.add(index);
        }

        return BLSAggregate.aggregate(allSignatures, allIndices);
    }

    /**
     * Create a BLS key pair without generating proof of possession.
     * <p>
     * Used to test rogue key attack prevention. In production systems,
     * BLS keys should always have a proof of possession to prevent
     * cancellation attacks.
     *
     * @param entropy Random source
     * @param provider BLS provider
     * @return Key pair without PoP validation
     */
    public static BLSKeyPair createKeyWithoutProofOfPossession(Random entropy, BLSProvider provider) {
        // Generate key normally - the test will simulate missing PoP validation
        // In a real attack, adversary would craft public key to cancel honest keys
        return BLSKeyPair.generate(entropy, provider);
    }

    /**
     * Create a "cancellation" public key for rogue key attack.
     * <p>
     * Crafts a public key designed to cancel out honest aggregated public keys.
     * This demonstrates why proof of possession is required in BLS multi-signatures.
     * <p>
     * Simplified simulation: returns a random key. Real attack would involve
     * computing: rogue_pk = adversary_pk - aggregate(honest_pks)
     *
     * @param entropy Random source
     * @param provider BLS provider
     * @return Crafted rogue public key
     */
    public static BLSPublicKey createRogueKeyForCancellation(Random entropy, BLSProvider provider) {
        // Simplified: generate random key
        // Real rogue key attack: pk_rogue = pk_adversary - sum(pk_honest)
        var rogueKeyPair = BLSKeyPair.generate(entropy, provider);
        return rogueKeyPair.publicKey();
    }

    /**
     * Create an aggregate that appears valid but has threshold bypass attempt.
     * <p>
     * Only 4 signatures provided, but tries to bypass 5-signature threshold
     * by claiming additional signers in bitmap without their actual signatures.
     *
     * @param committeeKeys Valid committee keys
     * @param actualSigners Indices of real signers (below threshold)
     * @param claimedSigners Indices claimed in bitmap (meets threshold)
     * @param message Message to sign
     * @return Aggregate attempting threshold bypass
     */
    public static BLSAggregate createThresholdBypassAggregate(
        List<BLSKeyPair> committeeKeys,
        List<Integer> actualSigners,
        List<Integer> claimedSigners,
        byte[] message
    ) {
        // Create signatures from actual signers only
        var signatures = new ArrayList<BLSSignature>();
        for (var index : actualSigners) {
            signatures.add(committeeKeys.get(index).sign(message));
        }

        // Aggregate with CLAIMED indices (which includes non-signers)
        // This will fail verification because bitmap doesn't match actual signers
        return BLSAggregate.aggregate(signatures, claimedSigners);
    }

    /**
     * Simulate delayed Byzantine response (timeout scenario).
     * <p>
     * In practice, this would involve Thread.sleep() to simulate network delay.
     * For testing, we return a marker value (null) to indicate "no response yet".
     *
     * @param delayMs Milliseconds to delay
     * @return null to simulate timeout
     */
    public static BLSAggregate simulateByzantineTimeout(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null; // Simulate no response
    }

    /**
     * Create equivocating signatures for the same event.
     * <p>
     * Byzantine node signs two different messages for the same event,
     * attempting to create conflicting receipts.
     *
     * @param keyPair Key pair of equivocating node
     * @param message1 First message
     * @param message2 Second (conflicting) message
     * @return List containing both equivocating signatures
     */
    public static List<BLSSignature> createEquivocatingSignatures(
        BLSKeyPair keyPair,
        byte[] message1,
        byte[] message2
    ) {
        var sig1 = keyPair.sign(message1);
        var sig2 = keyPair.sign(message2);
        return List.of(sig1, sig2);
    }

    /**
     * Validate that a signature is cryptographically garbage.
     * <p>
     * Helper to verify that generated adversarial signatures are actually invalid.
     *
     * @param signature Signature to check
     * @param provider BLS provider
     * @param publicKey Public key to verify against
     * @param message Message to verify
     * @return true if signature is invalid (fails verification)
     */
    public static boolean isInvalidSignature(
        BLSSignature signature,
        BLSProvider provider,
        BLSPublicKey publicKey,
        byte[] message
    ) {
        try {
            return !provider.verify(publicKey.toBytesCompressed(), message, signature.toBytes());
        } catch (Exception e) {
            return true; // Exception means invalid
        }
    }
}
