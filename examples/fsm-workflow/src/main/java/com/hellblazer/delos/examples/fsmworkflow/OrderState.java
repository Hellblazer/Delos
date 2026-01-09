/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.examples.fsmworkflow;

/**
 * Order state machine demonstrating event-driven workflow using a simple Enum-based FSM pattern.
 *
 * States:
 * - PENDING: Order received, awaiting confirmation
 * - CONFIRMED: Payment confirmed, ready for shipment
 * - SHIPPED: Item shipped, in transit
 * - DELIVERED: Item delivered to customer
 * - CANCELLED: Order cancelled
 *
 * Transitions:
 * - PENDING → CONFIRMED (confirm)
 * - PENDING → CANCELLED (cancel)
 * - CONFIRMED → SHIPPED (ship)
 * - CONFIRMED → CANCELLED (cancel)
 * - SHIPPED → DELIVERED (deliver)
 * - SHIPPED → CANCELLED (cancel)
 *
 * @author hal.hildebrand
 */
/**
 * Simple FSM pattern: each state defines its possible transitions.
 * This is more lightweight than the full Tron framework, suitable for simple workflows.
 */
public enum OrderState {
    /** Order received, awaiting confirmation */
    PENDING {
        @Override
        public OrderState confirm() {
            return CONFIRMED;
        }

        @Override
        public OrderState cancel() {
            return CANCELLED;
        }
    },

    /** Payment confirmed, ready for shipment */
    CONFIRMED {
        @Override
        public OrderState ship() {
            return SHIPPED;
        }

        @Override
        public OrderState cancel() {
            return CANCELLED;
        }
    },

    /** Item shipped, in transit */
    SHIPPED {
        @Override
        public OrderState deliver() {
            return DELIVERED;
        }

        @Override
        public OrderState cancel() {
            return CANCELLED;
        }
    },

    /** Item delivered to customer - Terminal State */
    DELIVERED {
        // Terminal state - no transitions possible
    },

    /** Order cancelled - Terminal State */
    CANCELLED {
        // Terminal state - no transitions possible
    };

    /**
     * Confirm pending order (PENDING → CONFIRMED).
     * Default: unsupported transition
     */
    public OrderState confirm() {
        throw new UnsupportedOperationException("Cannot confirm from state: " + this);
    }

    /**
     * Cancel order at any non-terminal state.
     * Default: unsupported transition
     */
    public OrderState cancel() {
        throw new UnsupportedOperationException("Cannot cancel from state: " + this);
    }

    /**
     * Ship confirmed order (CONFIRMED → SHIPPED).
     * Default: unsupported transition
     */
    public OrderState ship() {
        throw new UnsupportedOperationException("Cannot ship from state: " + this);
    }

    /**
     * Deliver shipped order (SHIPPED → DELIVERED).
     * Default: unsupported transition
     */
    public OrderState deliver() {
        throw new UnsupportedOperationException("Cannot deliver from state: " + this);
    }

    /**
     * Check if this is a terminal state (no further transitions possible).
     */
    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED;
    }

    /**
     * Get human-readable description of state.
     */
    public String getDescription() {
        return switch (this) {
            case PENDING -> "Order received, awaiting confirmation";
            case CONFIRMED -> "Payment confirmed, ready for shipment";
            case SHIPPED -> "Item shipped, in transit";
            case DELIVERED -> "Item delivered to customer";
            case CANCELLED -> "Order cancelled";
        };
    }
}
