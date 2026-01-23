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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for EpochLink sealed record and validation.
 *
 * @author hal.hildebrand
 */
class EpochLinkTest {

    @Test
    void testGenesisEpoch() {
        var timestamp = Instant.now();
        var epoch = EpochLink.genesis(0, 100, timestamp);

        assertThat(epoch.epochNumber()).isZero();
        assertThat(epoch.previousRootHash()).isEqualTo(DigestAlgorithm.DEFAULT.getOrigin());
        assertThat(epoch.totalSignerCount()).isEqualTo(100);
        assertThat(epoch.timestamp()).isEqualTo(timestamp);
        assertThat(epoch.isUnchanged()).isTrue();
        assertThat(epoch.getAggregatedSignature()).isEmpty();
        assertThat(epoch.estimatedBytes()).isEqualTo(44);
    }

    @Test
    void testGenesisEpoch_invalidEpochNumber() {
        assertThatThrownBy(() -> EpochLink.genesis(-1, 100, Instant.now()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("epochNumber must be non-negative");
    }

    @Test
    void testGenesisEpoch_invalidSignerCount() {
        assertThatThrownBy(() -> EpochLink.genesis(0, 0, Instant.now()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("totalSignerCount must be positive");
    }

    @Test
    void testGenesisEpoch_nullTimestamp() {
        assertThatThrownBy(() -> EpochLink.genesis(0, 100, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("timestamp cannot be null");
    }

    @Test
    void testUnchangedEpoch() {
        var hash = DigestAlgorithm.DEFAULT.digest("previous_hash".getBytes());
        var timestamp = Instant.now();
        var epoch = EpochLink.unchanged(1, hash, 100, timestamp);

        assertThat(epoch.epochNumber()).isOne();
        assertThat(epoch.previousRootHash()).isEqualTo(hash);
        assertThat(epoch.totalSignerCount()).isEqualTo(100);
        assertThat(epoch.timestamp()).isEqualTo(timestamp);
        assertThat(epoch.getAggregatedSignature()).isEmpty();
        assertThat(epoch.isUnchanged()).isTrue();
        assertThat(epoch.estimatedBytes()).isEqualTo(44);
    }

    @Test
    void testUnchangedEpoch_nullHash() {
        assertThatThrownBy(() -> EpochLink.unchanged(1, null, 100, Instant.now()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("previousRootHash cannot be null");
    }

    @Test
    void testChangedEpoch() {
        var sig = new BLSSignature(new byte[96]);
        var bitmap = new byte[8];
        var aggregate = new BLSAggregate(sig, bitmap);
        var hash = DigestAlgorithm.DEFAULT.digest("previous_hash".getBytes());
        var timestamp = Instant.now();
        var epoch = EpochLink.changed(2, hash, aggregate, bitmap, 100, timestamp);

        assertThat(epoch.epochNumber()).isEqualTo(2);
        assertThat(epoch.previousRootHash()).isEqualTo(hash);
        assertThat(epoch.totalSignerCount()).isEqualTo(100);
        assertThat(epoch.timestamp()).isEqualTo(timestamp);
        assertThat(epoch.getAggregatedSignature()).contains(aggregate);
        assertThat(epoch.isUnchanged()).isFalse();
        assertThat(epoch.estimatedBytes()).isGreaterThan(100);
        assertThat(epoch.estimatedBytes()).isEqualTo(160 + 8);
    }

    @Test
    void testChangedEpoch_nullSignature() {
        var bitmap = new byte[8];
        var hash = DigestAlgorithm.DEFAULT.digest("previous_hash".getBytes());

        assertThatThrownBy(() -> EpochLink.changed(2, hash, null, bitmap, 100, Instant.now()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("aggregatedSignature cannot be null");
    }

    @Test
    void testChangedEpoch_emptyBitmap() {
        var sig = new BLSSignature(new byte[96]);
        var bitmap = new byte[1];
        var aggregate = new BLSAggregate(sig, bitmap);
        var hash = DigestAlgorithm.DEFAULT.digest("previous_hash".getBytes());

        assertThatThrownBy(() -> EpochLink.changed(2, hash, aggregate, new byte[0], 100, Instant.now()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("committeeContributionBitmap cannot be empty");
    }

    @Test
    void testChangedEpoch_defensiveCopy() {
        var sig = new BLSSignature(new byte[96]);
        var bitmap = new byte[8];
        var aggregate = new BLSAggregate(sig, bitmap);
        var hash = DigestAlgorithm.DEFAULT.digest("previous_hash".getBytes());
        var timestamp = Instant.now();

        var epoch = EpochLink.changed(2, hash, aggregate, bitmap, 100, timestamp);

        // Modify original bitmap
        bitmap[0] = (byte) 0xFF;

        // Epoch's bitmap should be unchanged
        assertThat(((EpochLink.Changed) epoch).committeeContributionBitmap()[0]).isZero();
    }

    @Test
    void testProtoRoundTrip_unchanged() {
        var original = EpochLink.unchanged(
            1,
            DigestAlgorithm.DEFAULT.digest("hash".getBytes()),
            100,
            Instant.now()
        );

        var proto = original.toProto();
        var restored = EpochLink.fromProto(proto);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.isUnchanged()).isTrue();
    }

    @Test
    void testProtoRoundTrip_changed() {
        var sig = new BLSSignature(new byte[96]);
        var bitmap = new byte[8];
        var aggregate = new BLSAggregate(sig, bitmap);
        var hash = DigestAlgorithm.DEFAULT.digest("hash".getBytes());

        var original = EpochLink.changed(2, hash, aggregate, bitmap, 100, Instant.now());

        var proto = original.toProto();
        var restored = EpochLink.fromProto(proto);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.isUnchanged()).isFalse();
        assertThat(restored.getAggregatedSignature()).isPresent();
    }

    @Test
    void testProtoRoundTrip_genesis() {
        var original = EpochLink.genesis(0, 100, Instant.now());

        var proto = original.toProto();
        var restored = EpochLink.fromProto(proto);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.epochNumber()).isZero();
    }

    @Test
    void testFromProto_nullProto() {
        assertThatThrownBy(() -> EpochLink.fromProto(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("proto cannot be null");
    }

    @Test
    void testValidateAgainstPrevious_validSequence() {
        var epoch0 = EpochLink.genesis(0, 100, Instant.now());
        var epoch1 = EpochLink.unchanged(1, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now());

        var result = epoch1.validateAgainstPrevious(epoch0);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getFailureReason()).isEmpty();
    }

    @Test
    void testValidateAgainstPrevious_invalidSequence() {
        var epoch0 = EpochLink.genesis(0, 100, Instant.now());
        var epoch2 = EpochLink.unchanged(2, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now());

        var result = epoch2.validateAgainstPrevious(epoch0);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason()).isPresent();
        assertThat(result.getFailureReason().get()).contains("Non-sequential epochs");
    }

    @Test
    void testValidateAgainstPrevious_nullPrevious() {
        var epoch = EpochLink.genesis(0, 100, Instant.now());

        assertThatThrownBy(() -> epoch.validateAgainstPrevious(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("previous link cannot be null");
    }

    @Test
    void testEstimatedBytes_unchanged() {
        var epoch = EpochLink.unchanged(1, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now());

        assertThat(epoch.estimatedBytes()).isEqualTo(44);
    }

    @Test
    void testEstimatedBytes_changed() {
        var sig = new BLSSignature(new byte[96]);
        var bitmap = new byte[12];
        var aggregate = new BLSAggregate(sig, bitmap);
        var hash = DigestAlgorithm.DEFAULT.digest("hash".getBytes());

        var epoch = EpochLink.changed(2, hash, aggregate, bitmap, 100, Instant.now());

        assertThat(epoch.estimatedBytes()).isEqualTo(160 + 12);
    }

    @Test
    void testValidationResult_valid() {
        var result = ValidationResult.valid();

        assertThat(result.isValid()).isTrue();
        assertThat(result.getFailureReason()).isEmpty();
    }

    @Test
    void testValidationResult_invalid() {
        var result = ValidationResult.invalid("Test failure");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason()).contains("Test failure");
    }

    @Test
    void testValidationResult_invalidFormatted() {
        var result = ValidationResult.invalid("Error at epoch %d", 42);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason()).contains("Error at epoch 42");
    }

    @Test
    void testValidationResult_invalidNullReason() {
        assertThatThrownBy(() -> new ValidationResult.Invalid(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("reason cannot be null");
    }

    @Test
    void testValidationResult_invalidBlankReason() {
        assertThatThrownBy(() -> new ValidationResult.Invalid("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason cannot be blank");
    }

    @Test
    void testEpochLinkValidator_validateChain_emptyChain() {
        Map<Long, Digest> rootHashes = new HashMap<>();

        var result = EpochLinkValidator.validateChain(List.of(), rootHashes::get);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason()).contains("Empty epoch chain");
    }

    @Test
    void testEpochLinkValidator_validateChain_validChain() {
        var epoch0 = EpochLink.genesis(0, 100, Instant.now());
        var epoch1 = EpochLink.unchanged(1, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now());
        var epoch2 = EpochLink.unchanged(2, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now());

        Map<Long, Digest> rootHashes = Map.of(
            0L, DigestAlgorithm.DEFAULT.getOrigin(),
            1L, DigestAlgorithm.DEFAULT.getOrigin()
        );

        var result = EpochLinkValidator.validateChain(List.of(epoch0, epoch1, epoch2), rootHashes::get);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void testEpochLinkValidator_validateChain_nonSequentialEpochs() {
        var epoch0 = EpochLink.genesis(0, 100, Instant.now());
        var epoch2 = EpochLink.unchanged(2, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now());

        Map<Long, Digest> rootHashes = new HashMap<>();

        var result = EpochLinkValidator.validateChain(List.of(epoch0, epoch2), rootHashes::get);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason()).contains("Non-sequential epochs");
    }

    @Test
    void testEpochLinkValidator_validateChain_nonGenesisWithGenesisHash() {
        var epoch5 = EpochLink.unchanged(5, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now());

        Map<Long, Digest> rootHashes = new HashMap<>();

        var result = EpochLinkValidator.validateChain(List.of(epoch5), rootHashes::get);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason()).contains("Non-genesis epoch");
    }

    @Test
    void testEpochLinkValidator_validateSequence_valid() {
        var epoch0 = EpochLink.genesis(0, 100, Instant.now());
        var epoch1 = EpochLink.unchanged(1, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now());

        var result = EpochLinkValidator.validateSequence(epoch1, epoch0);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void testEpochLinkValidator_validateSequence_invalid() {
        var epoch0 = EpochLink.genesis(0, 100, Instant.now());
        var epoch3 = EpochLink.unchanged(3, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now());

        var result = EpochLinkValidator.validateSequence(epoch3, epoch0);

        assertThat(result.isValid()).isFalse();
    }

    @Test
    void testEpochLinkValidator_validateContiguous_valid() {
        var chain = List.of(
            EpochLink.genesis(0, 100, Instant.now()),
            EpochLink.unchanged(1, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now()),
            EpochLink.unchanged(2, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now())
        );

        var result = EpochLinkValidator.validateContiguous(chain);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void testEpochLinkValidator_validateContiguous_gap() {
        var chain = List.of(
            EpochLink.genesis(0, 100, Instant.now()),
            EpochLink.unchanged(2, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now())
        );

        var result = EpochLinkValidator.validateContiguous(chain);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason()).contains("Non-contiguous epochs");
    }

    @Test
    void testEpochLinkValidator_validateGenesisStart_valid() {
        var chain = List.of(
            EpochLink.genesis(0, 100, Instant.now()),
            EpochLink.unchanged(1, DigestAlgorithm.DEFAULT.getOrigin(), 100, Instant.now())
        );

        var result = EpochLinkValidator.validateGenesisStart(chain);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void testEpochLinkValidator_validateGenesisStart_notGenesis() {
        var hash = DigestAlgorithm.DEFAULT.digest("non-genesis".getBytes());
        var chain = List.of(
            EpochLink.unchanged(1, hash, 100, Instant.now())
        );

        var result = EpochLinkValidator.validateGenesisStart(chain);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getFailureReason()).contains("First epoch is not genesis");
    }

    @Test
    void testEpochLinkValidator_nullChain() {
        Map<Long, Digest> rootHashes = new HashMap<>();
        assertThatThrownBy(() -> EpochLinkValidator.validateChain(null, rootHashes::get))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("chain cannot be null");
    }

    @Test
    void testEpochLinkValidator_nullRootHashLookup() {
        var chain = List.of(EpochLink.genesis(0, 100, Instant.now()));

        assertThatThrownBy(() -> EpochLinkValidator.validateChain(chain, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("rootHashLookup cannot be null");
    }

    @Test
    void testChangedEpoch_equality() {
        var sig = new BLSSignature(new byte[96]);
        var bitmap = new byte[8];
        var aggregate = new BLSAggregate(sig, bitmap);
        var hash = DigestAlgorithm.DEFAULT.digest("hash".getBytes());
        var timestamp = Instant.now();

        var epoch1 = EpochLink.changed(2, hash, aggregate, bitmap, 100, timestamp);
        var epoch2 = EpochLink.changed(2, hash, aggregate, bitmap, 100, timestamp);

        assertThat(epoch1).isEqualTo(epoch2);
        assertThat(epoch1.hashCode()).isEqualTo(epoch2.hashCode());
    }

    @Test
    void testUnchangedEpoch_equality() {
        var hash = DigestAlgorithm.DEFAULT.digest("hash".getBytes());
        var timestamp = Instant.now();

        var epoch1 = EpochLink.unchanged(1, hash, 100, timestamp);
        var epoch2 = EpochLink.unchanged(1, hash, 100, timestamp);

        assertThat(epoch1).isEqualTo(epoch2);
        assertThat(epoch1.hashCode()).isEqualTo(epoch2.hashCode());
    }

    @Test
    void testMixedChain_validSequence() {
        var sig = new BLSSignature(new byte[96]);
        var bitmap = new byte[8];
        var aggregate = new BLSAggregate(sig, bitmap);
        var hash1 = DigestAlgorithm.DEFAULT.digest("hash1".getBytes());
        var hash2 = DigestAlgorithm.DEFAULT.digest("hash2".getBytes());

        var epoch0 = EpochLink.genesis(0, 100, Instant.now());
        var epoch1 = EpochLink.changed(1, DigestAlgorithm.DEFAULT.getOrigin(), aggregate, bitmap, 100, Instant.now());
        var epoch2 = EpochLink.unchanged(2, hash1, 100, Instant.now());
        var epoch3 = EpochLink.changed(3, hash1, aggregate, bitmap, 100, Instant.now());

        // Validate each link against previous
        assertThat(epoch1.validateAgainstPrevious(epoch0).isValid()).isTrue();
        assertThat(epoch2.validateAgainstPrevious(epoch1).isValid()).isTrue();
        assertThat(epoch3.validateAgainstPrevious(epoch2).isValid()).isTrue();
    }
}
