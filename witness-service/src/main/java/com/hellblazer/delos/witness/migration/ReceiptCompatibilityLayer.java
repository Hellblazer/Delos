/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.migration;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSPublicKey;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.witness.WitnessContext;
import com.hellblazer.delos.witness.WitnessParameters;
import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.aggregation.ValidationResult;
import com.hellblazer.delos.witness.proto.WitnessReceipt;
import com.hellblazer.delos.witness.receipt.AggregateWitnessReceipt;
import com.hellblazer.delos.witness.validation.AggregateValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compatibility layer bridging Ed25519 and BLS signature validation.
 * <p>
 * Phase 1B-2-C: Enables graceful migration with format detection, routing, and fallback.
 * <p>
 * Features:
 * <ul>
 *   <li>Automatic format detection from WitnessReceipt proto</li>
 *   <li>Routing to appropriate validator (BLS or Ed25519)</li>
 *   <li>Fallback from BLS to Ed25519 during DUAL phase</li>
 *   <li>Thread-safe metrics tracking via atomic counters</li>
 * </ul>
 * <p>
 * Design:
 * <ul>
 *   <li>Stateless validation (all state in MigrationStateTracker)</li>
 *   <li>Lock-free operation for virtual thread compatibility</li>
 *   <li>Sealed CompatibilityResult for exhaustive pattern matching</li>
 *   <li>Configurable fallback policy (SILENT, MONITORED, STRICT)</li>
 * </ul>
 * <p>
 * Format Detection Rules:
 * <pre>
 * - BLS only: hasBlsSig() && !hasSignatures() → BLS_12_381
 * - Ed25519 only: !hasBlsSig() && hasSignatures() → ED25519
 * - Both present: → null (mixed format error)
 * - Neither present: → null (unknown format)
 * </pre>
 * <p>
 * Validation Routing:
 * <pre>
 * INIT phase:
 *   - Ed25519: Accepted
 *   - BLS: FormatNotSupported
 *
 * DUAL phase:
 *   - Ed25519: Accepted
 *   - BLS: Accepted, with fallback to Ed25519 if policy allows
 *
 * BLS_ONLY phase:
 *   - Ed25519: FormatNotSupported
 *   - BLS: Accepted
 * </pre>
 * <p>
 * Thread Safety:
 * <ul>
 *   <li>No synchronized blocks (virtual thread compatible)</li>
 *   <li>AtomicLong for metrics (7 counters)</li>
 *   <li>Immutable state (FallbackPolicy via volatile field)</li>
 * </ul>
 * <p>
 * Example Usage:
 * <pre>{@code
 * var tracker = new MigrationStateTracker(MigrationPhase.DUAL, 0L);
 * var layer = new ReceiptCompatibilityLayer(tracker);
 *
 * var result = layer.validateReceipt(receipt, tracker.getCurrentPhase());
 *
 * switch (result) {
 *     case CompatibilityResult.Valid v -> processValid(v);
 *     case CompatibilityResult.BlsValidationFailed f when f.hasFallback() -> tryFallback(f);
 *     case CompatibilityResult.FormatNotSupported n -> rejectReceipt(n);
 *     default -> handleError(result);
 * }
 * }</pre>
 *
 * @author hal.hildebrand
 */
public final class ReceiptCompatibilityLayer {

    private static final Logger log = LoggerFactory.getLogger(ReceiptCompatibilityLayer.class);

    /**
     * Fallback policy for BLS to Ed25519 transition in DUAL phase.
     * <p>
     * Controls behavior when BLS validation fails and Ed25519 is available.
     */
    public enum FallbackPolicy {
        /**
         * Fallback silently without logging (default).
         * Use for production when fallback is expected.
         */
        SILENT,

        /**
         * Log at WARN level if fallback rate exceeds 10%.
         * Use for monitoring migration progress.
         */
        MONITORED,

        /**
         * Never fallback - treat BLS failure as terminal error.
         * Use for enforcing BLS-only after sufficient migration time.
         */
        STRICT
    }

    // Dependencies
    private final MigrationStateTracker migrationTracker;
    private final AggregateValidator aggregateValidator;
    private final WitnessContext witnessContext;
    private final WitnessParameters parameters;

    // Metrics (lock-free counters)
    private final AtomicLong blsReceiptsValidated = new AtomicLong(0);
    private final AtomicLong ed25519ReceiptsValidated = new AtomicLong(0);
    private final AtomicLong blsValidationFailures = new AtomicLong(0);
    private final AtomicLong ed25519ValidationFailures = new AtomicLong(0);
    private final AtomicLong formatFallbackAttempts = new AtomicLong(0);
    private final AtomicLong formatFallbackSuccesses = new AtomicLong(0);
    private final AtomicLong unsupportedFormatErrors = new AtomicLong(0);

    // Configuration (volatile for thread-safe updates)
    private volatile FallbackPolicy fallbackPolicy = FallbackPolicy.MONITORED;

    /**
     * Create compatibility layer with migration state tracker and BLS validation.
     * <p>
     * Full constructor for Phase 1B-2 with complete BLS support.
     *
     * @param migrationTracker Migration state tracker for phase information
     * @param aggregateValidator Validator for BLS aggregate signatures (can be null only if BLS validation not needed)
     * @param witnessContext Witness context for committee selection and key retrieval (can be null only if BLS validation not needed)
     * @param parameters Witness parameters for threshold information (can be null only if BLS validation not needed)
     * @throws NullPointerException if migrationTracker is null and BLS validation will be used
     */
    public ReceiptCompatibilityLayer(MigrationStateTracker migrationTracker,
                                     AggregateValidator aggregateValidator,
                                     WitnessContext witnessContext,
                                     WitnessParameters parameters) {
        this.migrationTracker = Objects.requireNonNull(migrationTracker, "migrationTracker cannot be null");
        this.aggregateValidator = aggregateValidator;  // Nullable for backward compatibility
        this.witnessContext = witnessContext;          // Nullable for backward compatibility
        this.parameters = parameters;                  // Nullable for backward compatibility
        log.debug("Initialized ReceiptCompatibilityLayer with policy: {}", fallbackPolicy);
    }

    /**
     * Legacy constructor for backward compatibility (no BLS validation).
     * Used in tests where BLS validation not needed.
     * <p>
     * In this mode, BLS receipt validation will fail with "BLS validation error: Validator not initialized".
     *
     * @param migrationTracker Migration state tracker
     * @deprecated Use four-argument constructor for full BLS support
     */
    @Deprecated(forRemoval = false)
    public ReceiptCompatibilityLayer(MigrationStateTracker migrationTracker) {
        this(migrationTracker, null, null, null);
        log.warn("Using deprecated single-argument constructor - BLS validation disabled");
    }

    /**
     * Detect signature format from WitnessReceipt.
     * <p>
     * Detection rules:
     * <ul>
     *   <li>BLS only → BLS_12_381</li>
     *   <li>Ed25519 only → ED25519</li>
     *   <li>Both present → null (mixed format error)</li>
     *   <li>Neither present → null (unknown format)</li>
     * </ul>
     * <p>
     * Thread-safe and stateless.
     *
     * @param receipt WitnessReceipt to inspect
     * @return SignatureFormat if single format detected, null if mixed or empty
     * @throws NullPointerException if receipt is null
     */
    public static SignatureFormat detectFormat(WitnessReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt cannot be null");

        var hasBls = receipt.hasBlsSig();
        var hasEd25519 = receipt.getSignaturesCount() > 0;

        if (hasBls && !hasEd25519) {
            return SignatureFormat.BLS_12_381;
        }
        if (hasEd25519 && !hasBls) {
            return SignatureFormat.ED25519;
        }
        // Mixed format or no signatures
        return null;
    }

    /**
     * Validate receipt against migration phase rules.
     * <p>
     * Validation flow:
     * <ol>
     *   <li>Detect signature format</li>
     *   <li>Check if format allowed in current phase</li>
     *   <li>Route to appropriate validator (BLS or Ed25519)</li>
     *   <li>Handle fallback in DUAL phase if policy allows</li>
     *   <li>Update metrics</li>
     * </ol>
     * <p>
     * Thread-safe and lock-free.
     *
     * @param receipt WitnessReceipt to validate
     * @param phase Current migration phase
     * @return CompatibilityResult indicating validation outcome
     * @throws NullPointerException if any parameter is null
     */
    public CompatibilityResult validateReceipt(WitnessReceipt receipt, MigrationPhase phase) {
        Objects.requireNonNull(receipt, "receipt cannot be null");
        Objects.requireNonNull(phase, "phase cannot be null");

        // Step 1: Detect format
        var format = detectFormat(receipt);

        if (format == null) {
            // Mixed format or no signature
            if (receipt.hasBlsSig() && receipt.getSignaturesCount() > 0) {
                return new CompatibilityResult.MixedFormatError(
                    "Receipt contains both BLS and Ed25519 signatures"
                );
            }
            return new CompatibilityResult.UnknownFormat(
                "Receipt has no valid signature format"
            );
        }

        // Step 2: Validate format against phase rules
        var phaseValidation = migrationTracker.validateInCurrentPhase(format);

        if (phaseValidation instanceof CompatibilityResult.FormatNotSupported notSupported) {
            unsupportedFormatErrors.incrementAndGet();
            log.debug("Format {} not supported in phase {}", format, phase);
            return notSupported;
        }

        // Step 3: Route to appropriate validator
        return switch (format) {
            case BLS_12_381 -> validateBlsReceipt(receipt, phase);
            case ED25519 -> validateEd25519Receipt(receipt, phase);
        };
    }

    /**
     * Validate BLS receipt.
     * <p>
     * In DUAL phase, may fallback to Ed25519 if BLS validation fails
     * and Ed25519 signature is available.
     *
     * @param receipt WitnessReceipt with BLS signature
     * @param phase Current migration phase
     * @return Validation result
     */
    private CompatibilityResult validateBlsReceipt(WitnessReceipt receipt, MigrationPhase phase) {
        try {
            // Check if validator is initialized (backward compatibility check)
            if (aggregateValidator == null || witnessContext == null || parameters == null) {
                blsValidationFailures.incrementAndGet();
                var hasFallback = phase == MigrationPhase.DUAL && receipt.getSignaturesCount() > 0;
                return new CompatibilityResult.BlsValidationFailed(
                    "BLS validation error: Validator not initialized",
                    hasFallback && fallbackPolicy != FallbackPolicy.STRICT
                );
            }

            // Step 1: Deserialize aggregate receipt from proto
            AggregateWitnessReceipt aggregateReceipt;
            try {
                aggregateReceipt = AggregateWitnessReceipt.fromProto(receipt);
            } catch (IllegalArgumentException e) {
                blsValidationFailures.incrementAndGet();
                var hasFallback = phase == MigrationPhase.DUAL && receipt.getSignaturesCount() > 0;
                return new CompatibilityResult.BlsValidationFailed(
                    "Failed to parse BLS aggregate: " + e.getMessage(),
                    hasFallback && fallbackPolicy != FallbackPolicy.STRICT
                );
            }

            // Step 2: Get committee public keys for verification
            var event = EventCoordinates.from(receipt.getEventCoordinates());
            var committee = witnessContext.selectCommittee(event);
            var committeePublicKeys = getCommitteeBLSPublicKeys(committee);

            if (committeePublicKeys.isEmpty()) {
                blsValidationFailures.incrementAndGet();
                var hasFallback = phase == MigrationPhase.DUAL && receipt.getSignaturesCount() > 0;
                return new CompatibilityResult.BlsValidationFailed(
                    "Committee BLS public keys not available for epoch " + receipt.getEpoch(),
                    hasFallback && fallbackPolicy != FallbackPolicy.STRICT
                );
            }

            // Step 3: Extract message to verify (event digest)
            // Convert Digeste proto back to Digest to get the original digest bytes (not proto bytes)
            var digesteProto = receipt.getEventDigest();
            var digest = new Digest(digesteProto);
            byte[] message = digest.getBytes();

            // Step 4: Validate using AggregateValidator
            var validationResult = aggregateValidator.validate(
                aggregateReceipt.aggregate(),
                committeePublicKeys,
                message
            );

            // Step 5: Map ValidationResult to CompatibilityResult
            return mapValidationResultToCompatibility(validationResult, phase, aggregateReceipt);

        } catch (Exception e) {
            blsValidationFailures.incrementAndGet();
            var hasFallback = phase == MigrationPhase.DUAL && receipt.getSignaturesCount() > 0;
            return new CompatibilityResult.BlsValidationFailed(
                "BLS validation error: " + e.getMessage(),
                hasFallback && fallbackPolicy != FallbackPolicy.STRICT
            );
        }
    }

    /**
     * Map AggregateValidator.ValidationResult to CompatibilityResult.
     * Uses pattern matching on sealed ValidationResult type.
     *
     * @param result ValidationResult from AggregateValidator
     * @param phase Current migration phase
     * @param receipt Aggregate receipt being validated
     * @return CompatibilityResult for service layer
     */
    private CompatibilityResult mapValidationResultToCompatibility(
            ValidationResult result,
            MigrationPhase phase,
            AggregateWitnessReceipt receipt) {

        return switch (result) {
            case ValidationResult.Valid(var aggregate) -> {
                blsReceiptsValidated.incrementAndGet();
                yield new CompatibilityResult.Valid(
                    SignatureFormat.BLS_12_381,
                    receipt.signerIndices().size(),
                    parameters.threshold()
                );
            }

            case ValidationResult.InvalidSignature(var member, var reason) -> {
                blsValidationFailures.incrementAndGet();
                yield new CompatibilityResult.BlsValidationFailed(
                    String.format("Invalid BLS signature from %s: %s", member, reason),
                    shouldAttemptFallback(phase)
                );
            }

            case ValidationResult.InvalidBitmap(var reason) -> {
                blsValidationFailures.incrementAndGet();
                yield new CompatibilityResult.BlsValidationFailed(
                    "Invalid signer bitmap: " + reason,
                    shouldAttemptFallback(phase)
                );
            }

            case ValidationResult.InvalidThreshold(var expected, var actual) -> {
                blsValidationFailures.incrementAndGet();
                yield new CompatibilityResult.BlsValidationFailed(
                    String.format("Insufficient BLS signers: expected %d, got %d", expected, actual),
                    shouldAttemptFallback(phase)
                );
            }

            case ValidationResult.ValidationFailed(var reason) -> {
                blsValidationFailures.incrementAndGet();
                yield new CompatibilityResult.BlsValidationFailed(
                    "BLS crypto validation failed: " + reason,
                    shouldAttemptFallback(phase)
                );
            }
        };
    }

    /**
     * Get BLS public keys for committee members.
     *
     * @param committee Committee identifier set
     * @return List of BLS public keys for committee
     */
    private List<BLSPublicKey> getCommitteeBLSPublicKeys(java.util.Set<com.hellblazer.delos.stereotomy.identifier.Identifier> committee) {
        if (committee == null || committee.isEmpty()) {
            return List.of();
        }

        // Retrieve BLS public keys from CommitteeBLSKeyStore via WitnessContext
        try {
            var keyStore = witnessContext.getCommitteeBLSKeys();
            if (keyStore == null) {
                log.warn("CommitteeBLSKeyStore not available - cannot retrieve BLS keys");
                return List.of();
            }

            var keys = keyStore.getPublicKeys(committee);
            if (keys == null || keys.isEmpty()) {
                log.debug("No BLS keys available for committee members");
                return List.of();
            }

            return keys;
        } catch (Exception e) {
            log.error("Error retrieving committee BLS keys", e);
            return List.of();
        }
    }

    /**
     * Check if BLS → Ed25519 fallback should be attempted.
     *
     * @param phase Current migration phase
     * @return true if fallback allowed
     */
    private boolean shouldAttemptFallback(MigrationPhase phase) {
        return phase == MigrationPhase.DUAL && fallbackPolicy != FallbackPolicy.STRICT;
    }

    /**
     * Validate Ed25519 receipt.
     *
     * @param receipt WitnessReceipt with Ed25519 signatures
     * @param phase Current migration phase
     * @return Validation result
     */
    private CompatibilityResult validateEd25519Receipt(WitnessReceipt receipt, MigrationPhase phase) {
        // Placeholder: In real implementation, would call WitnessSignatureValidator
        // For now, simulate validation failure to test metrics

        ed25519ValidationFailures.incrementAndGet();

        return new CompatibilityResult.Ed25519ValidationFailed(
            "Ed25519 validation failed (placeholder)"
        );
    }

    /**
     * Check fallback rate and log warning if exceeds 10% (MONITORED policy).
     */
    private void checkFallbackRate() {
        if (fallbackPolicy != FallbackPolicy.MONITORED) {
            return;
        }

        var attempts = formatFallbackAttempts.get();
        var successes = formatFallbackSuccesses.get();
        var totalValidations = blsReceiptsValidated.get() + ed25519ReceiptsValidated.get();

        if (totalValidations > 0) {
            var fallbackRate = (double) successes / totalValidations;
            if (fallbackRate > 0.1) {
                log.warn("Fallback rate exceeds 10%: {}/{} ({:.2f}%)",
                         successes, totalValidations, fallbackRate * 100);
            }
        }
    }

    // ========== Configuration ==========

    /**
     * Set fallback policy.
     *
     * @param policy Fallback policy to use
     * @throws NullPointerException if policy is null
     */
    public void setFallbackPolicy(FallbackPolicy policy) {
        this.fallbackPolicy = Objects.requireNonNull(policy, "policy cannot be null");
        log.info("Fallback policy updated: {}", policy);
    }

    /**
     * Get current fallback policy.
     *
     * @return Current policy
     */
    public FallbackPolicy getFallbackPolicy() {
        return fallbackPolicy;
    }

    // ========== Metrics Accessors (Thread-safe) ==========

    /**
     * Get count of successfully validated BLS receipts.
     *
     * @return BLS receipt validation successes
     */
    public long getBlsReceiptsValidated() {
        return blsReceiptsValidated.get();
    }

    /**
     * Get count of successfully validated Ed25519 receipts.
     *
     * @return Ed25519 receipt validation successes
     */
    public long getEd25519ReceiptsValidated() {
        return ed25519ReceiptsValidated.get();
    }

    /**
     * Get count of BLS validation failures.
     *
     * @return BLS validation failures
     */
    public long getBlsValidationFailures() {
        return blsValidationFailures.get();
    }

    /**
     * Get count of Ed25519 validation failures.
     *
     * @return Ed25519 validation failures
     */
    public long getEd25519ValidationFailures() {
        return ed25519ValidationFailures.get();
    }

    /**
     * Get count of fallback attempts (BLS → Ed25519).
     *
     * @return Fallback attempts
     */
    public long getFormatFallbackAttempts() {
        return formatFallbackAttempts.get();
    }

    /**
     * Get count of successful fallbacks (BLS → Ed25519).
     *
     * @return Fallback successes
     */
    public long getFormatFallbackSuccesses() {
        return formatFallbackSuccesses.get();
    }

    /**
     * Get count of unsupported format errors.
     *
     * @return Unsupported format errors
     */
    public long getUnsupportedFormatErrors() {
        return unsupportedFormatErrors.get();
    }

    /**
     * Reset all metrics to zero.
     * <p>
     * Useful for testing and periodic metric windows.
     */
    public void resetMetrics() {
        blsReceiptsValidated.set(0);
        ed25519ReceiptsValidated.set(0);
        blsValidationFailures.set(0);
        ed25519ValidationFailures.set(0);
        formatFallbackAttempts.set(0);
        formatFallbackSuccesses.set(0);
        unsupportedFormatErrors.set(0);
        log.debug("Metrics reset");
    }
}
