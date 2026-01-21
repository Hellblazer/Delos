/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.migration;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.witness.proto.BLSAggregateSignature;
import com.hellblazer.delos.witness.proto.WitnessReceipt;

import java.util.List;

/**
 * Test helper utilities for creating mock WitnessReceipt proto messages.
 * <p>
 * Provides factory methods for creating receipts with:
 * - BLS-only signatures (field 11)
 * - Ed25519-only signatures (field 3)
 * - Mixed format receipts (both fields)
 * - Edge cases (empty, corrupted, maximum sizes)
 * <p>
 * Used by ProtoCompatibilityValidationTest to verify backward compatibility
 * between Ed25519 (Phase 1A) and BLS (Phase 1B) signature formats.
 *
 * @author hal.hildebrand
 */
public final class ProtoTestHelpers {

    private static final DigestAlgorithm DIGEST_ALGO = DigestAlgorithm.DEFAULT;

    private ProtoTestHelpers() {
        // Utility class - no instantiation
    }

    /**
     * Create a BLS-only receipt with specified signer indices.
     * <p>
     * Uses field 11 (blsSig) with realistic BLS12-381 signature structure:
     * - 96-byte signature field (all zeros for test)
     * - Signer indices matching provided list
     * <p>
     * Field 3 (signatures) remains empty.
     *
     * @param signerIndices List of committee member indices (0-based)
     * @return WitnessReceipt with BLS aggregate signature
     */
    public static WitnessReceipt createBlsReceipt(List<Integer> signerIndices) {
        var blsSig = BLSAggregateSignature.newBuilder()
                                          .setSignature(ByteString.copyFrom(new byte[96])) // BLS12-381 G1 signature
                                          .addAllSignerIndices(signerIndices)
                                          .build();

        return WitnessReceipt.newBuilder()
                             .setBlsSig(blsSig)
                             .setEpoch(1L)
                             .setEventDigest(DIGEST_ALGO.digest("test-event".getBytes()).toDigeste())
                             .setViewRef(DIGEST_ALGO.digest("test-view".getBytes()).toDigeste())
                             .build();
    }

    /**
     * Create an Ed25519-only receipt with specified signature count.
     * <p>
     * Uses field 3 (signatures) with individual Ed25519 signatures:
     * - Each signature is 64 bytes (Ed25519 standard)
     * - code=1 (Ed25519 signature algorithm code)
     * <p>
     * Field 11 (blsSig) remains unset.
     *
     * @param signatureCount Number of individual Ed25519 signatures to include
     * @return WitnessReceipt with Ed25519 signatures
     */
    public static WitnessReceipt createEd25519Receipt(int signatureCount) {
        var builder = WitnessReceipt.newBuilder()
                                    .setEpoch(1L)
                                    .setEventDigest(DIGEST_ALGO.digest("test-event".getBytes()).toDigeste())
                                    .setViewRef(DIGEST_ALGO.digest("test-view".getBytes()).toDigeste());

        for (int i = 0; i < signatureCount; i++) {
            var sig = com.hellblazer.delos.cryptography.proto.Sig.newBuilder()
                                                                  .setCode(1) // Ed25519 code
                                                                  .addSignatures(ByteString.copyFrom(new byte[64])) // Ed25519 signature
                                                                  .build();
            builder.addSignatures(sig);
        }

        return builder.build();
    }

    /**
     * Create a mixed-format receipt (both BLS and Ed25519).
     * <p>
     * Intentionally creates an invalid state for testing:
     * - Field 3 (signatures): 1 Ed25519 signature
     * - Field 11 (blsSig): 1 BLS aggregate signature
     * <p>
     * Should be detected as mixed format and rejected.
     *
     * @return WitnessReceipt with both signature types
     */
    public static WitnessReceipt createMixedFormatReceipt() {
        var blsSig = BLSAggregateSignature.newBuilder()
                                          .setSignature(ByteString.copyFrom(new byte[96]))
                                          .addSignerIndices(0)
                                          .build();

        var ed25519Sig = com.hellblazer.delos.cryptography.proto.Sig.newBuilder()
                                                                     .setCode(1)
                                                                     .addSignatures(ByteString.copyFrom(new byte[64]))
                                                                     .build();

        return WitnessReceipt.newBuilder()
                             .setBlsSig(blsSig)
                             .addSignatures(ed25519Sig)
                             .setEpoch(1L)
                             .setEventDigest(DIGEST_ALGO.digest("test-event".getBytes()).toDigeste())
                             .setViewRef(DIGEST_ALGO.digest("test-view".getBytes()).toDigeste())
                             .build();
    }

    /**
     * Create an empty receipt (no signatures).
     * <p>
     * Neither field 3 nor field 11 is set.
     * Should be detected as unknown format.
     *
     * @return WitnessReceipt with no signatures
     */
    public static WitnessReceipt createEmptyReceipt() {
        return WitnessReceipt.newBuilder()
                             .setEpoch(1L)
                             .setEventDigest(DIGEST_ALGO.digest("test-event".getBytes()).toDigeste())
                             .setViewRef(DIGEST_ALGO.digest("test-view".getBytes()).toDigeste())
                             .build();
    }

    /**
     * Create a corrupted BLS receipt with invalid signature bytes.
     * <p>
     * BLS signature field is set but with wrong length (32 bytes instead of 96).
     * Tests resilience to malformed proto data.
     *
     * @return WitnessReceipt with corrupted BLS signature
     */
    public static WitnessReceipt createCorruptedBlsReceipt() {
        var blsSig = BLSAggregateSignature.newBuilder()
                                          .setSignature(ByteString.copyFrom(new byte[32])) // Wrong length
                                          .addSignerIndices(0)
                                          .build();

        return WitnessReceipt.newBuilder()
                             .setBlsSig(blsSig)
                             .setEpoch(1L)
                             .setEventDigest(DIGEST_ALGO.digest("test-event".getBytes()).toDigeste())
                             .setViewRef(DIGEST_ALGO.digest("test-view".getBytes()).toDigeste())
                             .build();
    }

    /**
     * Create a BLS receipt with empty signer bitmap.
     * <p>
     * Tests edge case where BLS signature exists but no signers indicated.
     * Field 12 (signerBitmap) is empty.
     *
     * @return WitnessReceipt with BLS signature but no signer bitmap
     */
    public static WitnessReceipt createEmptySignerBitmapReceipt() {
        var blsSig = BLSAggregateSignature.newBuilder()
                                          .setSignature(ByteString.copyFrom(new byte[96]))
                                          // No signer indices
                                          .build();

        return WitnessReceipt.newBuilder()
                             .setBlsSig(blsSig)
                             .setSignerBitmap(ByteString.EMPTY)
                             .setEpoch(1L)
                             .setEventDigest(DIGEST_ALGO.digest("test-event".getBytes()).toDigeste())
                             .setViewRef(DIGEST_ALGO.digest("test-view".getBytes()).toDigeste())
                             .build();
    }

    /**
     * Create a receipt with epoch 0.
     * <p>
     * Tests handling of zero-valued required fields.
     * Epoch 0 may indicate uninitialized or invalid state.
     *
     * @return WitnessReceipt with epoch 0
     */
    public static WitnessReceipt createZeroEpochReceipt() {
        var blsSig = BLSAggregateSignature.newBuilder()
                                          .setSignature(ByteString.copyFrom(new byte[96]))
                                          .addSignerIndices(0)
                                          .build();

        return WitnessReceipt.newBuilder()
                             .setBlsSig(blsSig)
                             .setEpoch(0L) // Zero epoch
                             .setEventDigest(DIGEST_ALGO.digest("test-event".getBytes()).toDigeste())
                             .setViewRef(DIGEST_ALGO.digest("test-view".getBytes()).toDigeste())
                             .build();
    }

    /**
     * Create a BLS receipt with maximum signer indices.
     * <p>
     * Tests handling of large committee sizes:
     * - 1000 signer indices (stress test)
     * - Validates proto field size limits
     *
     * @return WitnessReceipt with maximum signer indices
     */
    public static WitnessReceipt createMaximumSignerIndicesReceipt() {
        var builder = BLSAggregateSignature.newBuilder()
                                           .setSignature(ByteString.copyFrom(new byte[96]));

        // Add 1000 signer indices (0-999)
        for (int i = 0; i < 1000; i++) {
            builder.addSignerIndices(i);
        }

        return WitnessReceipt.newBuilder()
                             .setBlsSig(builder.build())
                             .setEpoch(1L)
                             .setEventDigest(DIGEST_ALGO.digest("test-event".getBytes()).toDigeste())
                             .setViewRef(DIGEST_ALGO.digest("test-view".getBytes()).toDigeste())
                             .build();
    }

    /**
     * Create a BLS receipt with default (empty) BLS signature.
     * <p>
     * Tests handling of proto default instances:
     * - blsSig field is set but uses getDefaultInstance()
     * - Should still detect as BLS format (field presence)
     *
     * @return WitnessReceipt with default BLS signature
     */
    public static WitnessReceipt createDefaultBlsSignatureReceipt() {
        return WitnessReceipt.newBuilder()
                             .setBlsSig(BLSAggregateSignature.getDefaultInstance())
                             .setEpoch(1L)
                             .setEventDigest(DIGEST_ALGO.digest("test-event".getBytes()).toDigeste())
                             .setViewRef(DIGEST_ALGO.digest("test-view".getBytes()).toDigeste())
                             .build();
    }

    /**
     * Create an Ed25519 receipt with empty signature bytes.
     * <p>
     * Tests handling of present but empty signatures:
     * - signatures field has entries
     * - but signature bytes are empty
     *
     * @return WitnessReceipt with empty Ed25519 signature bytes
     */
    public static WitnessReceipt createEmptyEd25519SignatureReceipt() {
        var sig = com.hellblazer.delos.cryptography.proto.Sig.newBuilder()
                                                              .setCode(1)
                                                              .addSignatures(ByteString.EMPTY) // Empty bytes
                                                              .build();

        return WitnessReceipt.newBuilder()
                             .addSignatures(sig)
                             .setEpoch(1L)
                             .setEventDigest(DIGEST_ALGO.digest("test-event".getBytes()).toDigeste())
                             .setViewRef(DIGEST_ALGO.digest("test-view".getBytes()).toDigeste())
                             .build();
    }
}
