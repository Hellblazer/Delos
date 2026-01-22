/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates ResponseState transitions to enforce state machine integrity.
 * <p>
 * Valid transitions:
 * <pre>
 * NORMAL → WARNED, QUARANTINED, SHUNNED (equivocation fast-path)
 * WARNED → NORMAL (recovery), QUARANTINED (escalation)
 * QUARANTINED → NORMAL (recovery), KEY_ROTATING, ESCALATING
 * KEY_ROTATING → NORMAL (success), SHUNNED (failure)
 * ESCALATING → SHUNNED (view change complete)
 * SHUNNED → (terminal, no transitions)
 * </pre>
 * </p>
 *
 * @author hal.hildebrand
 */
public class ResponseStateTransitions {

    private static final Map<ResponseState, Set<ResponseState>> VALID_TRANSITIONS = Map.of(
        ResponseState.NORMAL, EnumSet.of(
            ResponseState.WARNED,
            ResponseState.QUARANTINED,
            ResponseState.SHUNNED  // Equivocation fast-path
        ),
        ResponseState.WARNED, EnumSet.of(
            ResponseState.NORMAL,       // Recovery
            ResponseState.QUARANTINED   // Escalation
        ),
        ResponseState.QUARANTINED, EnumSet.of(
            ResponseState.NORMAL,        // Recovery
            ResponseState.KEY_ROTATING,  // Key rotation escalation
            ResponseState.ESCALATING     // View change escalation
        ),
        ResponseState.KEY_ROTATING, EnumSet.of(
            ResponseState.NORMAL,   // Successful rotation
            ResponseState.SHUNNED   // Failed rotation
        ),
        ResponseState.ESCALATING, EnumSet.of(
            ResponseState.SHUNNED   // View change complete
        ),
        ResponseState.SHUNNED, EnumSet.noneOf(ResponseState.class)  // Terminal state
    );

    /**
     * Check if state transition is valid.
     *
     * @param from Source state
     * @param to   Target state
     * @return true if transition is allowed
     * @throws IllegalArgumentException if from or to is null
     */
    public static boolean isValid(ResponseState from, ResponseState to) {
        if (from == null) {
            throw new IllegalArgumentException("from state cannot be null");
        }
        if (to == null) {
            throw new IllegalArgumentException("to state cannot be null");
        }

        // Same-state transitions always allowed (idempotent)
        if (from == to) {
            return true;
        }

        return VALID_TRANSITIONS.get(from).contains(to);
    }

    /**
     * Get all valid target states for given source state.
     *
     * @param from Source state
     * @return Set of valid target states
     * @throws IllegalArgumentException if from is null
     */
    public static Set<ResponseState> getValidTargets(ResponseState from) {
        if (from == null) {
            throw new IllegalArgumentException("from state cannot be null");
        }

        return EnumSet.copyOf(VALID_TRANSITIONS.get(from));
    }

    /**
     * Validate transition and throw if invalid.
     *
     * @param from Source state
     * @param to   Target state
     * @throws IllegalStateException if transition is invalid
     */
    public static void validate(ResponseState from, ResponseState to) {
        if (!isValid(from, to)) {
            throw new IllegalStateException(
                String.format("Invalid state transition: %s → %s", from, to)
            );
        }
    }
}
