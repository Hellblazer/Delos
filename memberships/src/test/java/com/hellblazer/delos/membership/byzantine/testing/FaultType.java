/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine.testing;

/**
 * Types of Byzantine faults that can be injected for testing.
 *
 * @author hal.hildebrand
 */
public enum FaultType {
    /**
     * Node stops responding entirely (crash failure).
     */
    CRASH,

    /**
     * Node responds with artificial delay.
     */
    DELAY,

    /**
     * Node sends conflicting messages to different nodes.
     */
    EQUIVOCATION,

    /**
     * Node produces invalid signatures.
     */
    SIGNATURE_FORGERY,

    /**
     * Node responds to some nodes but not others.
     */
    SELECTIVE_RESPONSE,

    /**
     * Node manipulates timestamps.
     */
    TIMING_ATTACK,

    /**
     * Node sends messages at excessive rate.
     */
    RATE_ANOMALY,

    /**
     * Node omits required data from responses.
     */
    OMISSION
}
