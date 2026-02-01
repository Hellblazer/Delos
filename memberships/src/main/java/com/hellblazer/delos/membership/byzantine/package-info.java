/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

/**
 * Cross-Layer Byzantine Detection Framework.
 * <p>
 * This package provides infrastructure for correlating Byzantine detection
 * signals across multiple system layers (Fireflies, Thoth, Gorgoneion, etc.)
 * to achieve higher detection accuracy with lower false positive rates.
 * </p>
 *
 * <h2>Architecture Overview</h2>
 * <p>
 * The framework uses a <b>PULL-BASED</b> architecture where a central
 * coordinator polls layer state providers on configurable intervals,
 * rather than a push-based event-driven model. This design prevents
 * feedback loops where detection events trigger more detection events.
 * </p>
 *
 * <h2>Core Interfaces</h2>
 * <ul>
 *   <li>{@link com.hellblazer.delos.membership.byzantine.ByzantineStateProvider} -
 *       Interface for layers to expose their Byzantine detection state</li>
 *   <li>{@link com.hellblazer.delos.membership.byzantine.ResponseHandler} -
 *       Interface for handling detection responses (e.g., shunning)</li>
 * </ul>
 *
 * <h2>Data Types</h2>
 * <ul>
 *   <li>{@link com.hellblazer.delos.membership.byzantine.LayerAnomalyState} -
 *       Per-member anomaly state from a single layer</li>
 *   <li>{@link com.hellblazer.delos.membership.byzantine.MemberRiskProfile} -
 *       Aggregated risk profile across all layers</li>
 *   <li>{@link com.hellblazer.delos.membership.byzantine.IntelligenceConfig} -
 *       Configuration for polling, thresholds, and weights</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * All implementations of {@link com.hellblazer.delos.membership.byzantine.ByzantineStateProvider}
 * must be thread-safe for concurrent polling. The coordinator polls multiple
 * layers concurrently using per-layer scheduled tasks (Amendment 6).
 * </p>
 *
 * <h2>Layer Weights</h2>
 * <p>
 * Different layers contribute different weights to the aggregated score
 * based on their signal reliability:
 * </p>
 * <table border="1">
 *   <tr><th>Layer</th><th>Default Weight</th><th>Rationale</th></tr>
 *   <tr><td>FIREFLIES</td><td>0.4</td><td>Core membership, high signal quality</td></tr>
 *   <tr><td>ETHEREAL</td><td>0.3</td><td>Consensus, strong equivocation detection</td></tr>
 *   <tr><td>THOTH</td><td>0.2</td><td>DHT layer, quorum failures</td></tr>
 *   <tr><td>GORGONEION</td><td>0.1</td><td>Identity layer, attestation failures</td></tr>
 * </table>
 *
 * <h2>References</h2>
 * <ul>
 *   <li>Bead: Delos-f0v3 (Feature: Cross-Layer Byzantine Detection)</li>
 *   <li>Plan: Memory Bank Delos_active/cross-layer-byzantine-detection-plan-v3.md</li>
 * </ul>
 *
 * @author hal.hildebrand
 * @see com.hellblazer.delos.membership.byzantine.ByzantineStateProvider
 * @see com.hellblazer.delos.membership.byzantine.ResponseHandler
 */
package com.hellblazer.delos.membership.byzantine;
