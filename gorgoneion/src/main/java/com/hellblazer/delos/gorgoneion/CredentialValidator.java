/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.proto.Digeste;
import com.hellblazer.delos.gorgoneion.proto.MemberSignature;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.event.proto.Ident;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory.digestOf;

/**
 * Centralized validation logic for Gorgoneion credential operations.
 * <p>
 * Provides consistent validation methods for timestamps, signatures,
 * BFT majority requirements, and membership verification. This class
 * consolidates common validation patterns used across Gorgoneion's
 * admission and endorsement services.
 * </p>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>All timestamp validations use clock skew tolerance for future timestamps
 *       and maxDuration for past timestamps</li>
 *   <li>Signature verification requires member to be in expected BFT subset</li>
 *   <li>Majority calculation accounts for single-member contexts (returns 1)</li>
 *   <li>Methods return primitives (boolean, int) - callers handle logging</li>
 *   <li>Thread-safe: all methods are stateless after construction</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * var validator = new CredentialValidator(context, parameters, member.getId());
 *
 * // Timestamp validation
 * if (!validator.isTimestampValid(nonce.getTimestamp())) {
 *     log.warn("Invalid timestamp");
 *     return false;
 * }
 *
 * // Signature verification with BFT subset enforcement
 * var expectedSigners = validator.expectedBftSigners(ident);
 * int validCount = validator.countValidSignatures(signatures, expectedSigners, data);
 * if (!validator.hasMajority(validCount)) {
 *     log.warn("No majority");
 *     return false;
 * }
 * }</pre>
 *
 * @author hal.hildebrand
 * @see Gorgoneion
 * @see Parameters
 */
public class CredentialValidator {

    private final Context<Member> context;
    private final Parameters      parameters;
    private final Digest          localMemberId;

    /**
     * Creates a new CredentialValidator.
     *
     * @param context       the membership context for BFT operations
     * @param parameters    configuration parameters including clock and tolerances
     * @param localMemberId the digest ID of the local member
     * @throws NullPointerException if any parameter is null
     */
    public CredentialValidator(Context<Member> context, Parameters parameters, Digest localMemberId) {
        this.context = Objects.requireNonNull(context, "context cannot be null");
        this.parameters = Objects.requireNonNull(parameters, "parameters cannot be null");
        this.localMemberId = Objects.requireNonNull(localMemberId, "localMemberId cannot be null");
    }

    // ==================== Timestamp Validation ====================

    /**
     * Validates a protobuf timestamp against current time with clock skew tolerance.
     * <p>
     * A timestamp is valid if:
     * <ul>
     *   <li>It is not more than {@code clockSkewTolerance} in the future</li>
     *   <li>It is not more than {@code maxDuration} in the past</li>
     * </ul>
     *
     * @param timestamp the protobuf timestamp to validate
     * @return true if the timestamp is within acceptable bounds
     */
    public boolean isTimestampValid(Timestamp timestamp) {
        return isTimestampValid(toInstant(timestamp));
    }

    /**
     * Validates an instant against current time with clock skew tolerance.
     * <p>
     * A timestamp is valid if:
     * <ul>
     *   <li>It is not more than {@code clockSkewTolerance} in the future</li>
     *   <li>It is not more than {@code maxDuration} in the past</li>
     * </ul>
     *
     * @param instant the instant to validate
     * @return true if the timestamp is within acceptable bounds
     */
    public boolean isTimestampValid(Instant instant) {
        var now = parameters.clock().instant();
        var tolerance = parameters.clockSkewTolerance();
        var maxAge = parameters.maxDuration();

        // Check not too far in future (beyond tolerance)
        if (now.plus(tolerance).isBefore(instant)) {
            return false;
        }
        // Check not too old (beyond maxDuration)
        return !instant.plus(maxAge).isBefore(now);
    }

    /**
     * Checks if a timestamp is at or after another timestamp.
     * <p>
     * Used to ensure attestation timestamp is not before the nonce timestamp.
     *
     * @param timestamp the timestamp to check
     * @param reference the reference timestamp (must be at or before)
     * @return true if timestamp is at or after reference
     */
    public boolean isTimestampAtOrAfter(Timestamp timestamp, Timestamp reference) {
        var ts = toInstant(timestamp);
        var ref = toInstant(reference);
        return !ts.isBefore(ref);
    }

    /**
     * Converts a protobuf Timestamp to a Java Instant.
     *
     * @param timestamp the protobuf timestamp
     * @return the equivalent Java Instant
     */
    public static Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    // ==================== Signature Verification ====================

    /**
     * Counts valid signatures from expected BFT subset members.
     * <p>
     * For each signature, this method:
     * <ol>
     *   <li>Resolves the signer ID to a context member</li>
     *   <li>Verifies the signer is in the expected BFT subset</li>
     *   <li>Cryptographically verifies the signature</li>
     * </ol>
     * <p>
     * Invalid signatures (unknown member, not in subset, bad signature) are
     * silently skipped. Callers should log details using the callback variant
     * if detailed logging is needed.
     *
     * @param signatures      list of member signatures to verify
     * @param expectedSigners set of digest IDs for expected BFT subset members
     * @param signedData      the data that was signed
     * @return count of valid signatures
     */
    public int countValidSignatures(List<MemberSignature> signatures, Set<Digest> expectedSigners,
                                    ByteString signedData) {
        int count = 0;
        for (var sig : signatures) {
            var id = Digest.from(sig.getId());
            var member = context.getMember(id);
            if (member == null) {
                continue;
            }
            if (!expectedSigners.contains(id)) {
                continue;
            }
            if (!member.verify(JohnHancock.from(sig.getSignature()), signedData)) {
                continue;
            }
            count++;
        }
        return count;
    }

    /**
     * Verifies a single member's signature.
     *
     * @param memberId   the digest ID of the signing member
     * @param signature  the signature to verify
     * @param signedData the data that was signed
     * @return true if the member exists and the signature is valid
     */
    public boolean verifySignature(Digest memberId, JohnHancock signature, ByteString signedData) {
        var member = context.getMember(memberId);
        if (member == null) {
            return false;
        }
        return member.verify(signature, signedData);
    }

    // ==================== BFT Helpers ====================

    /**
     * Gets the required majority for BFT consensus.
     * <p>
     * For single-member contexts, returns 1.
     * For multi-member contexts, returns {@code context.majority()}.
     *
     * @return the required number of signatures for majority
     */
    public int getRequiredMajority() {
        return context.size() == 1 ? 1 : context.majority();
    }

    /**
     * Checks if a count meets or exceeds the required BFT majority.
     *
     * @param count the number of valid signatures or validations
     * @return true if count >= required majority
     */
    public boolean hasMajority(int count) {
        return count >= getRequiredMajority();
    }

    /**
     * Computes the expected BFT subset signers for an identifier.
     * <p>
     * For single-member contexts, returns only the local member.
     * For multi-member contexts, computes the deterministic BFT subset
     * based on the identifier's digest.
     *
     * @param ident the identifier to compute subset for
     * @return set of digest IDs for expected signers
     */
    public Set<Digest> expectedBftSigners(Ident ident) {
        if (context.size() == 1) {
            return Set.of(localMemberId);
        }
        return context.bftSubset(digestOf(ident, parameters.digestAlgorithm()))
                      .stream()
                      .map(Member::getId)
                      .collect(Collectors.toSet());
    }

    // ==================== Membership Helpers ====================

    /**
     * Checks if a digest ID represents a member of the context.
     *
     * @param id the member ID to check
     * @return true if the ID is a context member
     */
    public boolean isMember(Digest id) {
        return context.isMember(id);
    }

    /**
     * Gets a member by their digest ID.
     *
     * @param id the member ID to look up
     * @return Optional containing the member if found, empty otherwise
     */
    public Optional<Member> getMember(Digest id) {
        return Optional.ofNullable(context.getMember(id));
    }

    // ==================== Value Validators ====================

    /**
     * Checks if a noise digest is valid (not default/empty).
     * <p>
     * Nonces must contain random noise to prevent replay attacks.
     *
     * @param noise the noise digest to check
     * @return true if noise is not the default instance
     */
    public boolean hasValidNoise(Digeste noise) {
        return !noise.equals(Digeste.getDefaultInstance());
    }

    /**
     * Checks if a member identifier is valid (not default/empty).
     *
     * @param member the member identifier to check
     * @return true if member is not the default instance
     */
    public boolean hasValidMember(Ident member) {
        return !member.equals(Ident.getDefaultInstance());
    }

    // ==================== Accessors ====================

    /**
     * Gets the configuration parameters.
     *
     * @return the parameters
     */
    public Parameters parameters() {
        return parameters;
    }

    /**
     * Gets the membership context.
     *
     * @return the context
     */
    public Context<Member> context() {
        return context;
    }

    /**
     * Gets the local member ID.
     *
     * @return the local member's digest ID
     */
    public Digest localMemberId() {
        return localMemberId;
    }
}
