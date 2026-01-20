/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.migration;

import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for CompatibilityResult sealed interface and record implementations.
 * Validates sealed interface exhaustiveness, record instantiation, and compact constructor validation.
 *
 * @author hal.hildebrand
 */
class CompatibilityResultTest {

    // Valid record tests

    @Test
    void validShouldCreateSuccessfully() {
        var result = new CompatibilityResult.Valid(
            SignatureFormat.BLS_12_381,
            10,
            7
        );

        assertThat(result.format()).isEqualTo(SignatureFormat.BLS_12_381);
        assertThat(result.signerCount()).isEqualTo(10);
        assertThat(result.threshold()).isEqualTo(7);
        assertThat(result.thresholdMet()).isTrue();
    }

    @Test
    void validShouldDetectThresholdNotMet() {
        var result = new CompatibilityResult.Valid(
            SignatureFormat.ED25519,
            5,
            7
        );

        assertThat(result.thresholdMet()).isFalse();
    }

    @Test
    void validShouldRejectNullFormat() {
        assertThatThrownBy(() ->
            new CompatibilityResult.Valid(null, 10, 7)
        )
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("format cannot be null");
    }

    @Test
    void validShouldRejectNegativeSignerCount() {
        assertThatThrownBy(() ->
            new CompatibilityResult.Valid(SignatureFormat.BLS_12_381, -1, 7)
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("signerCount must be >= 0");
    }

    @Test
    void validShouldRejectZeroThreshold() {
        assertThatThrownBy(() ->
            new CompatibilityResult.Valid(SignatureFormat.BLS_12_381, 10, 0)
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("threshold must be >= 1");
    }

    @Test
    void validShouldAllowZeroSigners() {
        // Edge case: zero signers is valid (represents empty receipt)
        var result = new CompatibilityResult.Valid(
            SignatureFormat.BLS_12_381,
            0,
            1
        );

        assertThat(result.signerCount()).isEqualTo(0);
        assertThat(result.thresholdMet()).isFalse();
    }

    // BlsValidationFailed record tests

    @Test
    void blsValidationFailedShouldCreateSuccessfully() {
        var result = new CompatibilityResult.BlsValidationFailed(
            "Aggregate signature mismatch",
            true
        );

        assertThat(result.reason()).isEqualTo("Aggregate signature mismatch");
        assertThat(result.hasFallback()).isTrue();
    }

    @Test
    void blsValidationFailedShouldRejectNullReason() {
        assertThatThrownBy(() ->
            new CompatibilityResult.BlsValidationFailed(null, true)
        )
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("reason cannot be null");
    }

    @Test
    void blsValidationFailedShouldSupportNoFallback() {
        var result = new CompatibilityResult.BlsValidationFailed(
            "Aggregation failed",
            false
        );

        assertThat(result.hasFallback()).isFalse();
    }

    // Ed25519ValidationFailed record tests

    @Test
    void ed25519ValidationFailedShouldCreateSuccessfully() {
        var result = new CompatibilityResult.Ed25519ValidationFailed(
            "Individual signature verification failed"
        );

        assertThat(result.reason()).isEqualTo("Individual signature verification failed");
    }

    @Test
    void ed25519ValidationFailedShouldRejectNullReason() {
        assertThatThrownBy(() ->
            new CompatibilityResult.Ed25519ValidationFailed(null)
        )
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("reason cannot be null");
    }

    // FormatNotSupported record tests

    @Test
    void formatNotSupportedShouldCreateSuccessfully() {
        var result = new CompatibilityResult.FormatNotSupported(
            SignatureFormat.BLS_12_381,
            MigrationPhase.INIT,
            "BLS not supported in INIT phase"
        );

        assertThat(result.format()).isEqualTo(SignatureFormat.BLS_12_381);
        assertThat(result.currentPhase()).isEqualTo(MigrationPhase.INIT);
        assertThat(result.message()).isEqualTo("BLS not supported in INIT phase");
    }

    @Test
    void formatNotSupportedShouldRejectNullFormat() {
        assertThatThrownBy(() ->
            new CompatibilityResult.FormatNotSupported(
                null,
                MigrationPhase.INIT,
                "test"
            )
        )
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("format cannot be null");
    }

    @Test
    void formatNotSupportedShouldRejectNullPhase() {
        assertThatThrownBy(() ->
            new CompatibilityResult.FormatNotSupported(
                SignatureFormat.BLS_12_381,
                null,
                "test"
            )
        )
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("currentPhase cannot be null");
    }

    @Test
    void formatNotSupportedShouldRejectNullMessage() {
        assertThatThrownBy(() ->
            new CompatibilityResult.FormatNotSupported(
                SignatureFormat.BLS_12_381,
                MigrationPhase.INIT,
                null
            )
        )
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("message cannot be null");
    }

    // MixedFormatError record tests

    @Test
    void mixedFormatErrorShouldCreateSuccessfully() {
        var result = new CompatibilityResult.MixedFormatError(
            "Receipt contains both Ed25519 and BLS signatures"
        );

        assertThat(result.reason()).isEqualTo("Receipt contains both Ed25519 and BLS signatures");
    }

    @Test
    void mixedFormatErrorShouldRejectNullReason() {
        assertThatThrownBy(() ->
            new CompatibilityResult.MixedFormatError(null)
        )
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("reason cannot be null");
    }

    // UnknownFormat record tests

    @Test
    void unknownFormatShouldCreateSuccessfully() {
        var result = new CompatibilityResult.UnknownFormat(
            "Unable to detect signature format from receipt"
        );

        assertThat(result.reason()).isEqualTo("Unable to detect signature format from receipt");
    }

    @Test
    void unknownFormatShouldRejectNullReason() {
        assertThatThrownBy(() ->
            new CompatibilityResult.UnknownFormat(null)
        )
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("reason cannot be null");
    }

    // Sealed interface tests

    @Test
    void shouldSupportExhaustivePatternMatching() {
        CompatibilityResult result = new CompatibilityResult.Valid(
            SignatureFormat.BLS_12_381,
            10,
            7
        );

        // Exhaustive switch with sealed interface
        String description = switch (result) {
            case CompatibilityResult.Valid v -> "Valid: " + v.format();
            case CompatibilityResult.BlsValidationFailed f -> "BLS failed: " + f.reason();
            case CompatibilityResult.Ed25519ValidationFailed f -> "Ed25519 failed: " + f.reason();
            case CompatibilityResult.FormatNotSupported f -> "Format not supported: " + f.format();
            case CompatibilityResult.MixedFormatError e -> "Mixed format: " + e.reason();
            case CompatibilityResult.UnknownFormat u -> "Unknown: " + u.reason();
        };

        assertThat(description).isEqualTo("Valid: BLS_12_381");
    }

    @Test
    void shouldSupportInstanceofPatternMatching() {
        CompatibilityResult result = new CompatibilityResult.BlsValidationFailed(
            "test failure",
            true
        );

        if (result instanceof CompatibilityResult.BlsValidationFailed failed) {
            assertThat(failed.reason()).isEqualTo("test failure");
            assertThat(failed.hasFallback()).isTrue();
        } else {
            fail("Expected BlsValidationFailed instance");
        }
    }

    @Test
    void recordsShouldHaveValueEquality() {
        var result1 = new CompatibilityResult.Valid(SignatureFormat.BLS_12_381, 10, 7);
        var result2 = new CompatibilityResult.Valid(SignatureFormat.BLS_12_381, 10, 7);

        assertThat(result1).isEqualTo(result2);
        assertThat(result1.hashCode()).isEqualTo(result2.hashCode());
    }

    @Test
    void recordsShouldHaveProperToString() {
        var result = new CompatibilityResult.Valid(SignatureFormat.BLS_12_381, 10, 7);

        assertThat(result.toString())
            .contains("Valid")
            .contains("BLS_12_381")
            .contains("10")
            .contains("7");
    }
}
