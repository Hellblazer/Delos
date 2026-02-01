/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.Map;
import java.util.Optional;

/**
 * Provider of Byzantine-relevant state for cross-layer intelligence.
 * <p>
 * Each layer (Fireflies, Thoth, Gorgoneion, etc.) implements this interface
 * to expose its local Byzantine detection state. The coordinator polls
 * implementations to correlate signals across layers.
 * </p>
 * <p>
 * <b>Thread Safety (Fix 3)</b><br>
 * Implementations <b>MUST</b> be thread-safe for concurrent polling by the
 * coordinator. The coordinator may poll multiple layers concurrently
 * (Amendment 6) from different scheduled tasks. Specifically:
 * <ul>
 *   <li>{@link #getMemberAnomalyStates()} may be called from any thread</li>
 *   <li>{@link #getMemberState(Identifier)} may be called concurrently</li>
 *   <li>Internal state updates must be synchronized with reads</li>
 *   <li>Returned maps should be snapshots or unmodifiable views</li>
 * </ul>
 * </p>
 * <p>
 * <b>Design Rationale</b><br>
 * This is a PULL-based interface (coordinator polls) rather than PUSH-based
 * (layer emits events). This prevents feedback loops where detection events
 * trigger more detection events in a cascade.
 * </p>
 *
 * @author hal.hildebrand
 * @see LayerAnomalyState
 * @see MemberRiskProfile
 */
public interface ByzantineStateProvider {

    /**
     * Get the layer identifier.
     * <p>
     * Used for logging, metrics, and weight lookup in configuration.
     * Should match constants in {@link IntelligenceConfig} (e.g., "FIREFLIES").
     * </p>
     *
     * @return Layer name (must be non-null and stable)
     */
    String getLayerName();

    /**
     * Get current Byzantine-relevant state for all members with anomalies.
     * <p>
     * Implementations should only return members with non-zero suspicion to
     * minimize coordinator overhead. Members with no anomalies should be
     * excluded from the returned map.
     * </p>
     * <p>
     * <b>Thread Safety</b>: Must return a thread-safe snapshot or copy.
     * The returned map should not reflect changes made after the call.
     * </p>
     *
     * @return Map of member ID to their current anomaly state (never null)
     */
    Map<Identifier, LayerAnomalyState> getMemberAnomalyStates();

    /**
     * Get anomaly state for a specific member.
     * <p>
     * More efficient than {@link #getMemberAnomalyStates()} when querying
     * a single member.
     * </p>
     * <p>
     * <b>Thread Safety</b>: Must be safe for concurrent calls.
     * </p>
     *
     * @param memberId Member to query
     * @return State if member has anomalies, empty otherwise
     */
    Optional<LayerAnomalyState> getMemberState(Identifier memberId);

    /**
     * Get the number of members currently being tracked.
     * <p>
     * Useful for metrics and capacity planning.
     * </p>
     *
     * @return Number of members with non-zero anomaly state
     */
    default int getTrackedMemberCount() {
        return getMemberAnomalyStates().size();
    }

    /**
     * Reset all tracked state.
     * <p>
     * Called during view changes or coordinator reset.
     * Implementations should clear all accumulated anomaly data.
     * </p>
     * <p>
     * <b>Thread Safety</b>: Must be safe to call while polling is active.
     * </p>
     */
    void reset();
}
