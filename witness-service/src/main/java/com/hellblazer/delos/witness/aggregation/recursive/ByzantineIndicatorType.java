/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

/**
 * Types of Byzantine behavior indicators detected during temporal analysis.
 * <p>
 * <strong>Implementation Status</strong>:
 * <ul>
 *   <li>{@link #SIGNATURE_INCONSISTENCY}: ✅ Phase 3.2 - Cross-epoch signature changes</li>
 *   <li>{@link #ABSTINENCE}: ✅ Phase 3.2 - Missing participation in epochs</li>
 *   <li>{@link #TIMING_ATTACK}: ⏳ Phase 3.3 - Suspiciously timed signatures</li>
 *   <li>{@link #FORK_ATTACK}: ⏳ Phase 3.3 - Different aggregate chains</li>
 *   <li>{@link #LATE_JOINER}: ⏳ Phase 3.3 - Member joins partway through</li>
 * </ul>
 * <p>
 * <strong>Detection Patterns</strong>:
 * <ul>
 *   <li><strong>SIGNATURE_INCONSISTENCY</strong>: Member's signatures change across epochs.
 *       This detects cross-epoch signature changes which may indicate legitimate state transitions
 *       or Byzantine behavior. True intra-epoch equivocation (same member signs two different
 *       aggregates for the same epoch) is reserved for Phase 3.3.
 *       Example: Member M signs epoch 1 with signature S1, epoch 2 with signature S2.</li>
 *   <li><strong>ABSTINENCE</strong>: Member fails to sign when they should participate (missing from signer set).
 *       Example: Member M present in epochs 1,2,4,5 but missing in epoch 3 without rotation.</li>
 *   <li><strong>TIMING_ATTACK</strong>: Member signs at suspicious times relative to others.
 *       Example: Member M consistently signs last across epochs, suggesting strategic delay.</li>
 *   <li><strong>FORK_ATTACK</strong>: Member signs different aggregate chains in same epoch.
 *       Example: Member M signs both chain A→B and chain A→C at epoch 3.</li>
 *   <li><strong>LATE_JOINER</strong>: Member joins committee mid-stream without proper rotation.
 *       Example: Member M absent in epochs 1-3, suddenly appears in epoch 4 without key event.</li>
 * </ul>
 * <p>
 * Thread-safety: Immutable enum, thread-safe.
 *
 * @author hal.hildebrand
 * @since Phase 3.2 (Delos-4002)
 */
public enum ByzantineIndicatorType {
    /**
     * Cross-epoch signature inconsistency. May indicate state transitions or Byzantine behavior.
     * <p>
     * Phase 3.2: Detects when member's signatures change across epochs. This may be a legitimate
     * state transition or indicate Byzantine behavior. May produce false positives during normal
     * operation.
     * <p>
     * Phase 3.3: Will be enhanced to detect true intra-epoch equivocation (same member signs
     * two different aggregates for the same epoch).
     * <p>
     * Severity: MODERATE - requires additional context to determine if malicious.
     */
    SIGNATURE_INCONSISTENCY,

    /**
     * Missing or inconsistent participation across epochs.
     * <p>
     * Phase 3.2: Detects when member is present in some epochs but missing in others,
     * indicating potential liveness attack or key compromise.
     * <p>
     * Severity: MODERATE - potential liveness attack or key compromise.
     */
    ABSTINENCE,

    /**
     * Reserved for Phase 3.3: Timing analysis of signatures.
     * <p>
     * Will detect when members sign at suspicious times relative to others,
     * suggesting strategic delay or timing-based attacks.
     * <p>
     * Severity: MODERATE - potential timing-based attack.
     */
    TIMING_ATTACK,

    /**
     * Reserved for Phase 3.3: Detection of contradictory aggregate chains.
     * <p>
     * Will detect when member signs different aggregate chains in the same epoch,
     * indicating a direct fork attempt.
     * <p>
     * Severity: CRITICAL - direct fork attempt.
     */
    FORK_ATTACK,

    /**
     * Reserved for Phase 3.3: Members joining committee mid-stream.
     * <p>
     * Will detect when member appears without proper rotation event,
     * suggesting potential Sybil or infiltration attack.
     * <p>
     * Severity: HIGH - potential Sybil or infiltration attack.
     */
    LATE_JOINER
}
