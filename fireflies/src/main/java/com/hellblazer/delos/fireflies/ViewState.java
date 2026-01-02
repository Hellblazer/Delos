/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

/**
 * Explicit membership lifecycle states for state machine validation.
 * Transitions are validated to prevent invalid state changes.
 *
 * @author hal.hildebrand
 */
public enum ViewState {
    /** Initial state before start() is called */
    INITIAL,
    /** Connecting to seeds and establishing initial contacts */
    SEEDING,
    /** Waiting for join to complete after seeding */
    JOINING,
    /** Fully joined and operational */
    JOINED,
    /** Stop has been requested, draining operations */
    STOPPING,
    /** Fully stopped */
    STOPPED;

    /**
     * Validate if transition to target state is allowed from this state.
     *
     * @return true if transition is valid
     */
    public boolean canTransitionTo(ViewState target) {
        return switch (this) {
            case INITIAL -> target == SEEDING;
            case SEEDING -> target == JOINING || target == JOINED || target == STOPPING;
            case JOINING -> target == JOINED || target == STOPPING;
            case JOINED -> target == STOPPING;
            case STOPPING -> target == STOPPED;
            case STOPPED -> target == SEEDING; // Allow restart after stop
        };
    }
}
