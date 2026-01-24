/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

/**
 * Types of Byzantine behavior indicators detected across temporal epoch analysis.
 * <p>
 * These indicator types represent different classes of Byzantine anomalies that can be
 * detected by analyzing member behavior patterns across multiple epochs in a
 * RecursiveAggregateReceipt chain.
 * <p>
 * <strong>Detection Patterns</strong>:
 * <ul>
 *   <li><strong>EQUIVOCATION</strong>: Member signs contradictory messages in same or different epochs.
 *       Example: Member M signs both aggregate A1 and conflicting aggregate A2 in epoch 5.</li>
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
     * Member signs contradictory messages in same or different epochs.
     * Severity: CRITICAL - direct protocol violation.
     */
    EQUIVOCATION,

    /**
     * Member fails to sign when they should participate (missing from signer set).
     * Severity: MODERATE - potential liveness attack or key compromise.
     */
    ABSTINENCE,

    /**
     * Member signs at suspicious times relative to others.
     * Severity: MODERATE - potential timing-based attack.
     */
    TIMING_ATTACK,

    /**
     * Member signs different aggregate chains in same epoch.
     * Severity: CRITICAL - direct fork attempt.
     */
    FORK_ATTACK,

    /**
     * Member joins committee mid-stream without proper rotation.
     * Severity: HIGH - potential Sybil or infiltration attack.
     */
    LATE_JOINER
}
