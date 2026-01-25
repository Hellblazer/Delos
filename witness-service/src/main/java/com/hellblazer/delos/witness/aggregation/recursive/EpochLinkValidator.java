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

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Utility for validating EpochLink chains.
 * <p>
 * Provides static methods for validating sequential epoch links,
 * checking epoch number sequences, and hash binding integrity.
 * <p>
 * Thread-safe: All methods are stateless and safe for concurrent access.
 *
 * @author hal.hildebrand
 */
public final class EpochLinkValidator {

    /**
     * Private constructor to prevent instantiation.
     */
    private EpochLinkValidator() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Validate sequential epoch links.
     * Checks: epoch number sequence, hash binding integrity
     * <p>
     * Validation rules:
     * 1. Chain must not be empty
     * 2. First epoch must be genesis (epoch 0 with zero hash) or have explicit previous hash
     * 3. Each subsequent epoch must be sequential (epoch_n = epoch_(n-1) + 1)
     * 4. Hash binding is validated via rootHashLookup function
     *
     * @param chain          The list of EpochLinks to validate (must be sequential)
     * @param rootHashLookup Function to get root hash for a given epoch number
     *                       (used for cryptographic binding validation)
     * @return ValidationResult indicating success or failure with reason
     * @throws NullPointerException if chain or rootHashLookup is null
     */
    public static ValidationResult validateChain(
        List<EpochLink> chain,
        Function<Long, Digest> rootHashLookup
    ) {
        Objects.requireNonNull(chain, "chain cannot be null");
        Objects.requireNonNull(rootHashLookup, "rootHashLookup cannot be null");

        if (chain.isEmpty()) {
            return ValidationResult.invalid("Empty epoch chain");
        }

        // First link should be genesis or have explicit previous hash
        var first = chain.get(0);
        var genesisHash = DigestAlgorithm.DEFAULT.getOrigin();

        if (first.epochNumber() != 0 && first.previousRootHash().equals(genesisHash)) {
            return ValidationResult.invalid(
                "Non-genesis epoch %d has genesis hash as previous_root_hash".formatted(first.epochNumber())
            );
        }

        // Validate sequential links
        for (int i = 1; i < chain.size(); i++) {
            var current = chain.get(i);
            var previous = chain.get(i - 1);

            // Check epoch sequence
            var result = current.validateAgainstPrevious(previous);
            if (!result.isValid()) {
                return result;
            }

            // Validate cryptographic linking
            // The current link's previousRootHash should match the computed root hash of the previous epoch
            var expectedPrevHash = computeEpochRootHash(previous, rootHashLookup);
            if (!current.previousRootHash().equals(expectedPrevHash)) {
                return ValidationResult.invalid(
                    "Epoch %d has invalid previous_root_hash: expected %s, got %s".formatted(
                        current.epochNumber(),
                        expectedPrevHash,
                        current.previousRootHash()
                    )
                );
            }
        }

        return ValidationResult.valid();
    }

    /**
     * Validate a single epoch link against its previous link.
     * <p>
     * This is a simpler validation that only checks sequential epoch numbers.
     * For full cryptographic validation, use validateChain.
     *
     * @param current  The current EpochLink
     * @param previous The previous EpochLink
     * @return ValidationResult indicating success or failure
     * @throws NullPointerException if current or previous is null
     */
    public static ValidationResult validateSequence(EpochLink current, EpochLink previous) {
        Objects.requireNonNull(current, "current link cannot be null");
        Objects.requireNonNull(previous, "previous link cannot be null");

        return current.validateAgainstPrevious(previous);
    }

    /**
     * Compute the root hash for an epoch.
     * <p>
     * For Changed epochs, uses the aggregated signature.
     * For Unchanged epochs, uses the previous epoch's root hash via lookup.
     * <p>
     * Hash computation: Hash(epoch_number || aggregated_signature_bytes)
     *
     * @param link           The EpochLink to compute hash for
     * @param rootHashLookup Function to get root hash for previous epochs (for Unchanged links)
     * @return The computed root hash for this epoch
     * @throws NullPointerException if link or rootHashLookup is null
     */
    private static Digest computeEpochRootHash(EpochLink link, Function<Long, Digest> rootHashLookup) {
        Objects.requireNonNull(link, "link cannot be null");
        Objects.requireNonNull(rootHashLookup, "rootHashLookup cannot be null");

        return switch (link) {
            case EpochLink.Changed changed -> {
                // Hash the aggregated signature for chain linking
                var sigBytes = changed.aggregatedSignature().aggregatedSignature().toBytes();
                yield DigestAlgorithm.DEFAULT.digest(sigBytes);
            }
            case EpochLink.Unchanged unchanged -> {
                // For unchanged epochs, use the previous epoch's root hash
                // This effectively chains the hash forward
                if (unchanged.epochNumber() == 0) {
                    yield DigestAlgorithm.DEFAULT.getOrigin();
                } else {
                    yield rootHashLookup.apply(unchanged.epochNumber() - 1);
                }
            }
        };
    }

    /**
     * Validate that an epoch chain is contiguous (no gaps in epoch numbers).
     *
     * @param chain The list of EpochLinks to validate
     * @return ValidationResult indicating success or failure
     * @throws NullPointerException if chain is null
     */
    public static ValidationResult validateContiguous(List<EpochLink> chain) {
        Objects.requireNonNull(chain, "chain cannot be null");

        if (chain.isEmpty()) {
            return ValidationResult.invalid("Empty epoch chain");
        }

        if (chain.size() == 1) {
            return ValidationResult.valid();
        }

        for (int i = 1; i < chain.size(); i++) {
            var current = chain.get(i);
            var previous = chain.get(i - 1);

            if (current.epochNumber() != previous.epochNumber() + 1) {
                return ValidationResult.invalid(
                    "Non-contiguous epochs: gap between %d and %d".formatted(
                        previous.epochNumber(), current.epochNumber()
                    )
                );
            }
        }

        return ValidationResult.valid();
    }

    /**
     * Validate that the first epoch in a chain is genesis (epoch 0).
     *
     * @param chain The list of EpochLinks to validate
     * @return ValidationResult indicating success or failure
     * @throws NullPointerException if chain is null
     */
    public static ValidationResult validateGenesisStart(List<EpochLink> chain) {
        Objects.requireNonNull(chain, "chain cannot be null");

        if (chain.isEmpty()) {
            return ValidationResult.invalid("Empty epoch chain");
        }

        var first = chain.get(0);
        if (first.epochNumber() != 0) {
            return ValidationResult.invalid(
                "First epoch is not genesis: epoch %d".formatted(first.epochNumber())
            );
        }

        var genesisHash = DigestAlgorithm.DEFAULT.getOrigin();
        if (!first.previousRootHash().equals(genesisHash)) {
            return ValidationResult.invalid(
                "Genesis epoch does not have genesis hash as previous_root_hash"
            );
        }

        return ValidationResult.valid();
    }
}
