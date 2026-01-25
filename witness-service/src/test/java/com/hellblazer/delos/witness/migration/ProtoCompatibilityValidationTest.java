/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.migration;

import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.proto.WitnessReceipt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.hellblazer.delos.witness.migration.ProtoTestHelpers.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive proto compatibility validation test suite.
 * <p>
 * Validates backward compatibility between Ed25519 (Phase 1A) and BLS (Phase 1B) signatures:
 * - Field 3 (signatures): Ed25519 individual signatures - PRESERVED
 * - Field 11 (blsSig): BLS aggregate signature - NEW in Phase 1B
 * - Field 12 (signerBitmap): BLS signer bitmap - NEW in Phase 1B
 * <p>
 * Test Categories:
 * <ul>
 *   <li>Category A: Format Detection (4 tests) - Validate format identification logic</li>
 *   <li>Category B: Round-Trip Serialization (3 tests) - Verify proto preservation</li>
 *   <li>Category C: Phase Rules (3 tests) - Validate phase-based acceptance rules</li>
 *   <li>Category D: Edge Cases (4 tests) - Test boundary conditions and malformed data</li>
 * </ul>
 * <p>
 * Design Principles:
 * - Test-first: All tests written before production code changes
 * - No production code modifications: Uses existing implementations
 * - Realistic mocks: ProtoTestHelpers provides valid proto structures
 * - Exhaustive coverage: All success and failure paths tested
 * <p>
 * Success Criteria:
 * - All 14 tests pass using existing implementations
 * - No regressions in existing 38+ migration tests
 * - Build succeeds without warnings
 *
 * @author hal.hildebrand
 */
@DisplayName("Proto Compatibility Validation Tests")
class ProtoCompatibilityValidationTest {

    // ========== Category A: Format Detection Tests (4 tests) ==========

    @Test
    @DisplayName("A1: detectFormat identifies BLS-only receipts (field 11)")
    void detectFormat_BlsOnlyReceipt() {
        // GIVEN: Receipt with only BLS signature (field 11)
        var receipt = createBlsReceipt(List.of(0, 1, 2));

        // WHEN: Detecting format
        var format = ReceiptCompatibilityLayer.detectFormat(receipt);

        // THEN: Format is BLS_12_381
        assertThat(format).isEqualTo(SignatureFormat.BLS_12_381);
        assertThat(receipt.hasBlsSig()).isTrue();
        assertThat(receipt.getSignaturesCount()).isEqualTo(0); // Field 3 empty
    }

    @Test
    @DisplayName("A2: detectFormat identifies Ed25519-only receipts (field 3)")
    void detectFormat_Ed25519OnlyReceipt() {
        // GIVEN: Receipt with only Ed25519 signatures (field 3)
        var receipt = createEd25519Receipt(3);

        // WHEN: Detecting format
        var format = ReceiptCompatibilityLayer.detectFormat(receipt);

        // THEN: Format is ED25519
        assertThat(format).isEqualTo(SignatureFormat.ED25519);
        assertThat(receipt.hasBlsSig()).isFalse(); // Field 11 unset
        assertThat(receipt.getSignaturesCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("A3: detectFormat rejects mixed-format receipts (both fields)")
    void detectFormat_MixedFormatReceipt() {
        // GIVEN: Receipt with both BLS and Ed25519 signatures
        var receipt = createMixedFormatReceipt();

        // WHEN: Detecting format
        var format = ReceiptCompatibilityLayer.detectFormat(receipt);

        // THEN: Format is null (ambiguous)
        assertThat(format).isNull();
        assertThat(receipt.hasBlsSig()).isTrue();
        assertThat(receipt.getSignaturesCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("A4: detectFormat handles empty receipts (neither field)")
    void detectFormat_EmptyReceipt() {
        // GIVEN: Receipt with no signatures
        var receipt = createEmptyReceipt();

        // WHEN: Detecting format
        var format = ReceiptCompatibilityLayer.detectFormat(receipt);

        // THEN: Format is null (unknown)
        assertThat(format).isNull();
        assertThat(receipt.hasBlsSig()).isFalse();
        assertThat(receipt.getSignaturesCount()).isEqualTo(0);
    }

    // ========== Category B: Round-Trip Serialization Tests (3 tests) ==========

    @Test
    @DisplayName("B1: BLS signature survives serialization round-trip")
    void roundTrip_BlsSignaturePreservation() throws Exception {
        // GIVEN: BLS receipt with specific signer indices
        var original = createBlsReceipt(List.of(0, 5, 10));

        // WHEN: Serialize to bytes and deserialize
        var serialized = original.toByteArray();
        var deserialized = WitnessReceipt.parseFrom(serialized);

        // THEN: BLS signature preserved exactly
        assertThat(deserialized.hasBlsSig()).isTrue();
        assertThat(deserialized.getBlsSig().getSignerIndicesList())
            .containsExactly(0, 5, 10);
        assertThat(deserialized.getBlsSig().getSignature())
            .isEqualTo(original.getBlsSig().getSignature());

        // AND: Field 3 remains empty
        assertThat(deserialized.getSignaturesCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("B2: Ed25519 signatures survive serialization (field 3 preserved)")
    void roundTrip_Ed25519Compatibility() throws Exception {
        // GIVEN: Ed25519 receipt with 4 signatures
        var original = createEd25519Receipt(4);

        // WHEN: Serialize to bytes and deserialize
        var serialized = original.toByteArray();
        var deserialized = WitnessReceipt.parseFrom(serialized);

        // THEN: Ed25519 signatures preserved
        assertThat(deserialized.getSignaturesCount()).isEqualTo(4);
        for (int i = 0; i < 4; i++) {
            assertThat(deserialized.getSignatures(i).getCode())
                .isEqualTo(original.getSignatures(i).getCode());
            assertThat(deserialized.getSignatures(i).getSignaturesList())
                .hasSize(1);
        }

        // AND: Field 11 remains unset
        assertThat(deserialized.hasBlsSig()).isFalse();
    }

    @Test
    @DisplayName("B3: Signer indices preserved in correct order")
    void roundTrip_SignerIndicesPreservation() throws Exception {
        // GIVEN: BLS receipt with non-sequential indices
        var indices = List.of(15, 3, 7, 1, 22);
        var original = createBlsReceipt(indices);

        // WHEN: Serialize and deserialize
        var serialized = original.toByteArray();
        var deserialized = WitnessReceipt.parseFrom(serialized);

        // THEN: Indices preserved in exact order (not sorted)
        assertThat(deserialized.getBlsSig().getSignerIndicesList())
            .containsExactlyElementsOf(indices);
    }

    // ========== Category C: Phase Rules Tests (3 tests) ==========

    @Test
    @DisplayName("C1: INIT phase rejects BLS receipts (pre-migration)")
    void phaseRule_InitRejectsBls() {
        // GIVEN: Migration tracker in INIT phase
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
        var layer = new ReceiptCompatibilityLayer(tracker);
        var blsReceipt = createBlsReceipt(List.of(0, 1));

        // WHEN: Validating BLS receipt in INIT phase
        var result = layer.validateReceipt(blsReceipt, MigrationPhase.INIT);

        // THEN: Validation fails with FormatNotSupported
        assertThat(result).isInstanceOf(CompatibilityResult.FormatNotSupported.class);
        var notSupported = (CompatibilityResult.FormatNotSupported) result;
        assertThat(notSupported.format()).isEqualTo(SignatureFormat.BLS_12_381);
        assertThat(notSupported.currentPhase()).isEqualTo(MigrationPhase.INIT);
    }

    @Test
    @DisplayName("C2: DUAL phase accepts both formats")
    void phaseRule_DualAcceptsBoth() {
        // GIVEN: Migration tracker in DUAL phase
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
        tracker.manualAdvance(MigrationPhase.DUAL);
        var layer = new ReceiptCompatibilityLayer(tracker);

        // WHEN: Validating BLS receipt
        var blsReceipt = createBlsReceipt(List.of(0, 1));
        var blsResult = layer.validateReceipt(blsReceipt, MigrationPhase.DUAL);

        // THEN: BLS format accepted (validation may fail due to dummy data)
        assertThat(blsResult).isNotInstanceOf(CompatibilityResult.FormatNotSupported.class);

        // WHEN: Validating Ed25519 receipt
        var ed25519Receipt = createEd25519Receipt(3);
        var ed25519Result = layer.validateReceipt(ed25519Receipt, MigrationPhase.DUAL);

        // THEN: Ed25519 format also accepted
        assertThat(ed25519Result).isNotInstanceOf(CompatibilityResult.FormatNotSupported.class);
    }

    @Test
    @DisplayName("C3: BLS_ONLY phase rejects Ed25519 receipts (post-migration)")
    void phaseRule_BlsOnlyRejectsEd25519() {
        // GIVEN: Migration tracker in BLS_ONLY phase
        var tracker = new MigrationStateTracker(MigrationPhase.INIT, 0L);
        tracker.manualAdvance(MigrationPhase.DUAL);
        tracker.manualAdvance(MigrationPhase.BLS_ONLY);
        var layer = new ReceiptCompatibilityLayer(tracker);
        var ed25519Receipt = createEd25519Receipt(3);

        // WHEN: Validating Ed25519 receipt in BLS_ONLY phase
        var result = layer.validateReceipt(ed25519Receipt, MigrationPhase.BLS_ONLY);

        // THEN: Validation fails with FormatNotSupported
        assertThat(result).isInstanceOf(CompatibilityResult.FormatNotSupported.class);
        var notSupported = (CompatibilityResult.FormatNotSupported) result;
        assertThat(notSupported.format()).isEqualTo(SignatureFormat.ED25519);
        assertThat(notSupported.currentPhase()).isEqualTo(MigrationPhase.BLS_ONLY);
    }

    // ========== Category D: Edge Cases Tests (4 tests) ==========

    @Test
    @DisplayName("D1: Corrupted BLS signature bytes handled gracefully")
    void edge_CorruptedBlsSignatureBytes() {
        // GIVEN: BLS receipt with wrong signature length (32 bytes not 96)
        var receipt = createCorruptedBlsReceipt();

        // WHEN: Detecting format
        var format = ReceiptCompatibilityLayer.detectFormat(receipt);

        // THEN: Still detects as BLS (field presence, not content validation)
        assertThat(format).isEqualTo(SignatureFormat.BLS_12_381);
        assertThat(receipt.getBlsSig().getSignature().size()).isEqualTo(32);

        // AND: Can serialize/deserialize corrupted data
        assertThatCode(() -> {
            var bytes = receipt.toByteArray();
            WitnessReceipt.parseFrom(bytes);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("D2: Empty signer bitmap handled without crash")
    void edge_EmptySignerBitmap() {
        // GIVEN: BLS receipt with no signer indices or bitmap
        var receipt = createEmptySignerBitmapReceipt();

        // WHEN: Detecting format
        var format = ReceiptCompatibilityLayer.detectFormat(receipt);

        // THEN: Detects as BLS format
        assertThat(format).isEqualTo(SignatureFormat.BLS_12_381);
        assertThat(receipt.getBlsSig().getSignerIndicesCount()).isEqualTo(0);
        assertThat(receipt.getSignerBitmap()).isEmpty();

        // AND: Validation handles empty indices gracefully
        var tracker = new MigrationStateTracker(MigrationPhase.DUAL, 0L);
        var layer = new ReceiptCompatibilityLayer(tracker);

        assertThatCode(() -> layer.validateReceipt(receipt, MigrationPhase.DUAL))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("D3: Zero epoch receipt handled correctly")
    void edge_ZeroEpochReceipt() {
        // GIVEN: Receipt with epoch 0
        var receipt = createZeroEpochReceipt();

        // WHEN: Validating
        var tracker = new MigrationStateTracker(MigrationPhase.DUAL, 0L);
        var layer = new ReceiptCompatibilityLayer(tracker);
        var result = layer.validateReceipt(receipt, MigrationPhase.DUAL);

        // THEN: Format detected correctly despite zero epoch
        var format = ReceiptCompatibilityLayer.detectFormat(receipt);
        assertThat(format).isEqualTo(SignatureFormat.BLS_12_381);
        assertThat(receipt.getEpoch()).isEqualTo(0L);

        // AND: Validation completes without exception
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("D4: Maximum signer indices (1000) handled without overflow")
    void edge_MaximumSignerIndices() {
        // GIVEN: BLS receipt with 1000 signer indices
        var receipt = createMaximumSignerIndicesReceipt();

        // WHEN: Detecting format and serializing
        var format = ReceiptCompatibilityLayer.detectFormat(receipt);

        // THEN: Format detected correctly
        assertThat(format).isEqualTo(SignatureFormat.BLS_12_381);
        assertThat(receipt.getBlsSig().getSignerIndicesCount()).isEqualTo(1000);

        // AND: Serialization handles large repeated field
        assertThatCode(() -> {
            var bytes = receipt.toByteArray();
            var deserialized = WitnessReceipt.parseFrom(bytes);
            assertThat(deserialized.getBlsSig().getSignerIndicesCount()).isEqualTo(1000);
        }).doesNotThrowAnyException();

        // AND: No integer overflow on index values
        var indices = receipt.getBlsSig().getSignerIndicesList();
        assertThat(indices).containsExactly(
            java.util.stream.IntStream.range(0, 1000)
                .boxed()
                .toArray(Integer[]::new)
        );
    }
}
