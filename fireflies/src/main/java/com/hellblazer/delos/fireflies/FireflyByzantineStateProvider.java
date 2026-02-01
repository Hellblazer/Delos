/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.membership.byzantine.ByzantineStateProvider;
import com.hellblazer.delos.membership.byzantine.IntelligenceConfig;
import com.hellblazer.delos.membership.byzantine.LayerAnomalyState;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ByzantineStateProvider implementation for the Fireflies membership layer.
 * <p>
 * Exposes accused and shunned member state as Byzantine anomaly signals.
 * This provider uses a pull-based model where the coordinator polls for state.
 * </p>
 * <p>
 * <b>Anomaly Scoring</b>:
 * <ul>
 *   <li>Shunned members: score = 1.0 (maximum, permanent exclusion)</li>
 *   <li>Accused members: score = min(1.0, accusations / ringCount)</li>
 * </ul>
 * </p>
 * <p>
 * <b>Thread Safety</b>:
 * This implementation is thread-safe for concurrent polling by the coordinator.
 * All returned maps are snapshots that don't reflect subsequent changes.
 * </p>
 *
 * @author hal.hildebrand
 * @see ByzantineStateProvider
 * @see View
 */
public class FireflyByzantineStateProvider implements ByzantineStateProvider {

    private static final Logger log = LoggerFactory.getLogger(FireflyByzantineStateProvider.class);

    private final View view;
    private final short ringCount;

    // Cache of member states for efficiency - refreshed on each poll
    private final Map<Digest, LayerAnomalyState> stateCache = new ConcurrentHashMap<>();

    /**
     * Create a provider wrapping the given View.
     *
     * @param view The Fireflies view to monitor
     */
    public FireflyByzantineStateProvider(View view) {
        this.view = Objects.requireNonNull(view, "view cannot be null");
        this.ringCount = view.getContext().getRingCount();
        log.debug("Created FireflyByzantineStateProvider with {} rings", ringCount);
    }

    @Override
    public String getLayerName() {
        return IntelligenceConfig.LAYER_FIREFLIES;
    }

    @Override
    public Map<Identifier, LayerAnomalyState> getMemberAnomalyStates() {
        var now = Instant.now();
        var result = new HashMap<Identifier, LayerAnomalyState>();

        // Process shunned members (highest severity)
        for (var digest : view.getShunnedMembers()) {
            var identifier = new SelfAddressingIdentifier(digest);
            var state = createShunnedState(digest, now);
            result.put(identifier, state);
            stateCache.put(digest, state);
        }

        // Process accused members (variable severity based on accusation count)
        view.getAccusedMembers().forEach(participant -> {
            var digest = participant.getId();
            // Skip if already in shunned set (higher priority)
            if (!view.getShunnedMembers().contains(digest)) {
                var identifier = participant.getIdentifier();
                var state = createAccusedState(participant, now);
                result.put(identifier, state);
                stateCache.put(digest, state);
            }
        });

        log.trace("getMemberAnomalyStates returning {} entries", result.size());
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Optional<LayerAnomalyState> getMemberState(Identifier memberId) {
        if (!(memberId instanceof SelfAddressingIdentifier sai)) {
            return Optional.empty();
        }

        var digest = sai.getDigest();
        var now = Instant.now();

        // Check if shunned
        if (view.getShunnedMembers().contains(digest)) {
            return Optional.of(createShunnedState(digest, now));
        }

        // Check if accused
        var member = view.getContext().getMember(digest);
        if (member instanceof Participant participant) {
            int accusations = participant.getAccusationCount();
            if (accusations > 0) {
                return Optional.of(createAccusedState(participant, now));
            }
        }

        return Optional.empty();
    }

    @Override
    public int getTrackedMemberCount() {
        return view.getShunnedMembers().size() +
               (int) view.getAccusedMembers().count();
    }

    @Override
    public void reset() {
        stateCache.clear();
        log.debug("Reset FireflyByzantineStateProvider state cache");
    }

    /**
     * Create anomaly state for a shunned member.
     * Shunned = maximum anomaly score (1.0).
     */
    private LayerAnomalyState createShunnedState(Digest memberId, Instant timestamp) {
        return new LayerAnomalyState(
            IntelligenceConfig.LAYER_FIREFLIES,
            1.0,  // Maximum score for shunned members
            timestamp,
            List.of("SHUNNED"),
            String.format("Member %s is permanently shunned", memberId)
        );
    }

    /**
     * Create anomaly state for an accused member.
     * Score scales with number of accusations relative to ring count.
     */
    private LayerAnomalyState createAccusedState(Participant participant, Instant timestamp) {
        int accusations = participant.getAccusationCount();
        // Score proportional to accusations across rings
        // More accusations = higher anomaly score
        double score = Math.min(1.0, (double) accusations / ringCount);

        var signals = new ArrayList<String>();
        signals.add("ACCUSED");
        if (accusations > 1) {
            signals.add("MULTIPLE_ACCUSATIONS:" + accusations);
        }

        return new LayerAnomalyState(
            IntelligenceConfig.LAYER_FIREFLIES,
            score,
            timestamp,
            signals,
            String.format("Member %s accused on %d/%d rings",
                         participant.getId(), accusations, ringCount)
        );
    }
}
