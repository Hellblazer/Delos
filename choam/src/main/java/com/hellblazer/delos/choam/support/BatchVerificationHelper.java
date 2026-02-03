/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.proto.Certification;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.membership.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Helper class for batch BLS signature verification in CHOAM.
 * <p>
 * Provides efficient batch verification for block certifications by:
 * - Detecting BLS-capable verifiers
 * - Batching BLS signatures for verification
 * - Falling back to individual verification for non-BLS or on failure
 * <p>
 * Thread-safe: All methods are reentrant.
 *
 * @author hal.hildebrand
 */
public class BatchVerificationHelper {
    private static final Logger log = LoggerFactory.getLogger(BatchVerificationHelper.class);

    // Minimum batch size for batch verification (below this, individual is faster)
    private static final int MIN_BATCH_SIZE = 3;

    private final BLSProvider provider;
    private final AtomicLong batchVerifications = new AtomicLong();
    private final AtomicLong individualVerifications = new AtomicLong();
    private final AtomicLong batchFailures = new AtomicLong();

    /**
     * Create batch verification helper with default BLS provider.
     */
    public BatchVerificationHelper() {
        this(BLSProvider.getDefault());
    }

    /**
     * Create batch verification helper with specified BLS provider.
     *
     * @param provider BLS cryptographic provider
     */
    public BatchVerificationHelper(BLSProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
    }

    /**
     * Check if a verifier supports BLS batch verification.
     * <p>
     * A verifier supports BLS if:
     * - It has a single public key (getKey() returns non-null)
     * - The key algorithm is BLS-12-381
     *
     * @param verifier The verifier to check
     * @return true if BLS batch verification is supported
     */
    public static boolean isBLSCapable(Verifier verifier) {
        if (verifier == null) {
            return false;
        }
        var key = verifier.getKey();
        if (key == null) {
            return false;
        }
        var algorithm = SignatureAlgorithm.lookup(key);
        return algorithm == SignatureAlgorithm.BLS_12_381;
    }

    /**
     * Verify block certifications using batch verification where possible.
     * <p>
     * Strategy:
     * 1. Separate certifications into BLS-capable and non-BLS
     * 2. Batch verify BLS certifications if count >= MIN_BATCH_SIZE
     * 3. Individually verify non-BLS certifications
     * 4. On batch failure, fall back to individual verification
     *
     * @param message    The message that was signed (block header bytes)
     * @param certifications List of certifications to verify
     * @param validators Map of member to verifier
     * @param memberId   Member ID for logging
     * @return Number of valid certifications
     */
    public int verifyCertifications(
        byte[] message,
        List<Certification> certifications,
        Map<Member, Verifier> validators,
        Digest memberId
    ) {
        if (certifications == null || certifications.isEmpty()) {
            return 0;
        }

        // Separate BLS-capable from non-BLS
        var blsCerts = new ArrayList<CertificationEntry>();
        var nonBlsCerts = new ArrayList<CertificationEntry>();

        for (var cert : certifications) {
            var wid = new Digest(cert.getId());
            var witness = findMember(wid, validators.keySet());
            if (witness == null) {
                log.debug("Witness not found: {} on: {}", wid, memberId);
                continue;
            }
            var verifier = validators.get(witness);
            if (verifier == null || verifier == Verifier.NO_VERIFIER) {
                log.debug("No verifier for witness: {} on: {}", wid, memberId);
                continue;
            }

            var entry = new CertificationEntry(cert, witness, verifier, wid);
            if (isBLSCapable(verifier)) {
                blsCerts.add(entry);
            } else {
                nonBlsCerts.add(entry);
            }
        }

        int validCount = 0;

        // Batch verify BLS certifications
        if (blsCerts.size() >= MIN_BATCH_SIZE) {
            validCount += batchVerifyBLS(message, blsCerts, memberId);
        } else {
            // Too few for batch, verify individually
            nonBlsCerts.addAll(blsCerts);
        }

        // Individually verify non-BLS (and BLS that didn't batch)
        for (var entry : nonBlsCerts) {
            if (verifyIndividual(message, entry, memberId)) {
                validCount++;
            }
        }

        return validCount;
    }

    /**
     * Batch verify BLS certifications.
     *
     * @return Number of valid certifications
     */
    private int batchVerifyBLS(byte[] message, List<CertificationEntry> entries, Digest memberId) {
        var publicKeys = new ArrayList<byte[]>();
        var messages = new ArrayList<byte[]>();
        var signatures = new ArrayList<byte[]>();

        for (var entry : entries) {
            var key = entry.verifier.getKey();
            if (key == null) {
                continue;
            }
            publicKeys.add(key.getEncoded());
            messages.add(message);

            var sig = new JohnHancock(entry.certification.getSignature());
            if (sig.getBytes().length > 0) {
                signatures.add(sig.getBytes()[0]);
            }
        }

        if (publicKeys.size() != signatures.size()) {
            log.warn("Batch size mismatch: keys={} signatures={} on: {}",
                     publicKeys.size(), signatures.size(), memberId);
            return verifyIndividually(message, entries, memberId);
        }

        try {
            boolean allValid = provider.batchVerify(publicKeys, messages, signatures);
            batchVerifications.incrementAndGet();

            if (allValid) {
                log.trace("Batch verified {} BLS certifications on: {}", entries.size(), memberId);
                return entries.size();
            } else {
                // Batch failed - identify individual failures
                log.debug("Batch verification failed, falling back to individual on: {}", memberId);
                batchFailures.incrementAndGet();
                return verifyIndividually(message, entries, memberId);
            }
        } catch (Exception e) {
            log.warn("Batch verification error, falling back to individual on: {}", memberId, e);
            batchFailures.incrementAndGet();
            return verifyIndividually(message, entries, memberId);
        }
    }

    /**
     * Verify certifications individually (fallback path).
     */
    private int verifyIndividually(byte[] message, List<CertificationEntry> entries, Digest memberId) {
        int valid = 0;
        for (var entry : entries) {
            if (verifyIndividual(message, entry, memberId)) {
                valid++;
            }
        }
        return valid;
    }

    /**
     * Verify a single certification.
     */
    private boolean verifyIndividual(byte[] message, CertificationEntry entry, Digest memberId) {
        individualVerifications.incrementAndGet();
        var sig = new JohnHancock(entry.certification.getSignature());
        boolean verified = entry.verifier.verify(sig, message);

        if (!verified) {
            log.debug("Verification failed for witness: {} on: {}", entry.witnessId, memberId);
        }
        return verified;
    }

    /**
     * Find a member by digest ID in the set.
     */
    private Member findMember(Digest id, Set<Member> members) {
        for (var m : members) {
            if (m.getId().equals(id)) {
                return m;
            }
        }
        return null;
    }

    // Metrics accessors

    public long getBatchVerifications() {
        return batchVerifications.get();
    }

    public long getIndividualVerifications() {
        return individualVerifications.get();
    }

    public long getBatchFailures() {
        return batchFailures.get();
    }

    public void resetMetrics() {
        batchVerifications.set(0);
        individualVerifications.set(0);
        batchFailures.set(0);
    }

    /**
     * Internal record for certification processing.
     */
    private record CertificationEntry(
        Certification certification,
        Member witness,
        Verifier verifier,
        Digest witnessId
    ) {}
}
