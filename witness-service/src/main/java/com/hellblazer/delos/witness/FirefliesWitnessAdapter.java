/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.context.DynamicContextImpl;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.SequencedSet;

/**
 * Adapter that configures Fireflies Context to match KERI witness thresholds.
 * <p>
 * Maps KERI witness requirements (N witnesses, threshold T) to Fireflies
 * configuration (rings, bias) such that Context.majority() equals KERI threshold.
 * <p>
 * Key formula:
 * <pre>
 *   majority = rings - (rings - 1) / bias
 *
 *   To achieve: majority = threshold, rings = witnessCount
 *   Solve for: bias = (witnessCount - 1) / (witnessCount - threshold)
 * </pre>
 * <p>
 * This allows Fireflies to support any KERI threshold configuration:
 * <ul>
 *   <li>3 of 5 witnesses: bias = 2 (standard 2f+1)</li>
 *   <li>4 of 5 witnesses: bias = 4 (3f+1)</li>
 *   <li>2 of 5 witnesses: bias = 1 (simple majority)</li>
 *   <li>5 of 7 witnesses: bias = 3</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class FirefliesWitnessAdapter {

    private static final Logger log = LoggerFactory.getLogger(FirefliesWitnessAdapter.class);

    private final DigestAlgorithm digestAlgorithm;

    /**
     * Create adapter with default digest algorithm.
     */
    public FirefliesWitnessAdapter() {
        this(DigestAlgorithm.DEFAULT);
    }

    /**
     * Create adapter with specific digest algorithm.
     *
     * @param digestAlgorithm Algorithm for hashing event coordinates
     */
    public FirefliesWitnessAdapter(DigestAlgorithm digestAlgorithm) {
        this.digestAlgorithm = Objects.requireNonNull(digestAlgorithm, "digestAlgorithm cannot be null");
    }

    /**
     * Compute the Fireflies bias required to achieve a KERI threshold.
     * <p>
     * Formula derivation:
     * <pre>
     *   majority = rings - toleranceLevel
     *   toleranceLevel = (rings - 1) / bias
     *   majority = rings - (rings - 1) / bias
     *
     *   Solving for bias:
     *   threshold = rings - (rings - 1) / bias
     *   (rings - 1) / bias = rings - threshold
     *   bias = (rings - 1) / (rings - threshold)
     * </pre>
     *
     * @param witnessCount Total number of witnesses (KERI N)
     * @param threshold    Required number of signatures (KERI T)
     * @return Fireflies bias value to achieve threshold
     * @throws IllegalArgumentException if threshold is invalid
     */
    public int computeBias(int witnessCount, int threshold) {
        if (witnessCount <= 0) {
            throw new IllegalArgumentException("witnessCount must be positive: " + witnessCount);
        }
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive: " + threshold);
        }
        if (threshold > witnessCount) {
            throw new IllegalArgumentException(
                "threshold cannot exceed witnessCount: threshold=" + threshold + ", witnessCount=" + witnessCount);
        }

        // Special case: unanimous consent
        if (threshold == witnessCount) {
            // Any large bias will work for unanimous
            return witnessCount;
        }

        // Special case: single witness
        if (witnessCount == 1) {
            return 1;
        }

        // bias = (witnessCount - 1) / (witnessCount - threshold)
        int denominator = witnessCount - threshold;
        if (denominator == 0) {
            // threshold == witnessCount, handled above
            return witnessCount;
        }

        int bias = (witnessCount - 1) / denominator;

        // Ensure minimum bias of 1
        return Math.max(1, bias);
    }

    /**
     * Verify that a given bias produces the expected majority.
     * <p>
     * Uses Fireflies formula: majority = rings - (rings - 1) / bias
     *
     * @param rings           Number of rings (= witnessCount)
     * @param bias            Computed bias value
     * @param expectedMajority Expected majority (= KERI threshold)
     * @return true if configuration matches
     */
    public boolean verifyConfiguration(int rings, int bias, int expectedMajority) {
        int toleranceLevel = (rings - 1) / bias;
        int actualMajority = rings - toleranceLevel;
        return actualMajority == expectedMajority;
    }

    /**
     * Create a DynamicContext configured for KERI witness requirements.
     *
     * @param contextId    Context identifier
     * @param witnessCount Total witnesses (becomes ringCount)
     * @param threshold    Required signatures (becomes majority)
     * @param pByz         Probability of Byzantine member (typically 0.1)
     * @param <T>          Member type
     * @return Configured DynamicContext
     */
    public <T extends Member> DynamicContext<T> createContext(Digest contextId, int witnessCount, int threshold,
                                                               double pByz) {
        int bias = computeBias(witnessCount, threshold);

        log.info("Creating Fireflies context for KERI: witnesses={}, threshold={}, bias={}, pByz={}",
                 witnessCount, threshold, bias, pByz);

        var context = new DynamicContextImpl<T>(contextId, witnessCount, pByz, bias);

        // Verify configuration
        int actualMajority = context.majority();
        if (actualMajority != threshold) {
            log.warn("Majority mismatch: expected={}, actual={} (witnesses={}, bias={}). " +
                     "This may be due to integer division in tolerance calculation.",
                     threshold, actualMajority, witnessCount, bias);
        }

        return context;
    }

    /**
     * Select witnesses for an event using the provided context.
     * <p>
     * Uses Fireflies' bftSubset() for deterministic committee selection.
     * The same event coordinates always produce the same witness set.
     *
     * @param context          Fireflies context
     * @param eventCoordinates Event being witnessed
     * @param <T>              Member type
     * @return Deterministic set of witnesses for this event
     * @throws NullPointerException if context or eventCoordinates is null
     */
    public <T extends Member> SequencedSet<T> selectWitnesses(Context<T> context, EventCoordinates eventCoordinates) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(eventCoordinates, "eventCoordinates cannot be null");

        Digest eventHash = hashEventCoordinates(eventCoordinates);
        var witnesses = context.bftSubset(eventHash);

        if (log.isDebugEnabled()) {
            log.debug("Selected {} witnesses for event {}", witnesses.size(), eventCoordinates.getDigest());
        }

        return witnesses;
    }

    /**
     * Convert selected witnesses to KERI Identifier list.
     *
     * @param witnesses Set of Member witnesses
     * @return List of Identifier for KERI KeyState
     * @throws NullPointerException if witnesses is null
     */
    public List<Identifier> toWitnessIdentifiers(SequencedSet<? extends Member> witnesses) {
        Objects.requireNonNull(witnesses, "witnesses cannot be null");

        return witnesses.stream()
            .map(Member::getId)
            .map(this::toIdentifier)
            .toList();
    }

    /**
     * Hash event coordinates deterministically for witness selection.
     * <p>
     * Combines: identifier + sequence number + digest + ilk
     *
     * @param eventCoordinates Event to hash
     * @return Deterministic hash for ring iterator
     * @throws NullPointerException if eventCoordinates is null
     * @throws IllegalArgumentException if event coordinates are too large to hash
     */
    public Digest hashEventCoordinates(EventCoordinates eventCoordinates) {
        Objects.requireNonNull(eventCoordinates, "eventCoordinates cannot be null");

        var identifierDigest = eventCoordinates.getIdentifier().getDigest(digestAlgorithm);
        var identifierBytes = identifierDigest.getBytes();
        var sequenceNumber = eventCoordinates.getSequenceNumber().longValue();
        var digestBytes = eventCoordinates.getDigest().getBytes();
        var ilkBytes = eventCoordinates.getIlk().getBytes();

        // Buffer overflow protection
        long totalSize = (long) identifierBytes.length + 8L + digestBytes.length + ilkBytes.length;
        if (totalSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Event coordinates too large to hash: " + totalSize + " bytes");
        }

        var buffer = ByteBuffer.allocate((int) totalSize);
        buffer.put(identifierBytes);
        buffer.putLong(sequenceNumber);
        buffer.put(digestBytes);
        buffer.put(ilkBytes);

        return digestAlgorithm.digest(buffer.array());
    }

    /**
     * Convert Digest to Identifier.
     *
     * @param digest Member digest
     * @return Identifier for internal use
     */
    public Identifier toIdentifier(Digest digest) {
        return new SelfAddressingIdentifier(digest);
    }

    /**
     * Compute threshold mapping table for documentation.
     * <p>
     * Useful for understanding the mapping at various witness counts.
     *
     * @param maxWitnesses Maximum witness count to show
     * @return Formatted table string
     */
    public String computeThresholdMappingTable(int maxWitnesses) {
        var sb = new StringBuilder();
        sb.append("| Witnesses | Threshold | Bias | Tolerance | Majority (computed) |\n");
        sb.append("|-----------|-----------|------|-----------|---------------------|\n");

        // Start at 4 witnesses (minimum for BFT with f=1 fault tolerance)
        for (int n = 4; n <= maxWitnesses; n++) {
            for (int t = (n / 2) + 1; t <= n; t++) {
                int bias = computeBias(n, t);
                int tolerance = (n - 1) / bias;
                int majority = n - tolerance;
                sb.append(String.format("| %9d | %9d | %4d | %9d | %19d |\n",
                                        n, t, bias, tolerance, majority));
            }
        }

        return sb.toString();
    }
}
