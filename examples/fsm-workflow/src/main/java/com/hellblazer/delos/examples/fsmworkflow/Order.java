/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.examples.fsmworkflow;

import java.time.Instant;

/**
 * Represents an order in the workflow system.
 * Uses OrderState FSM to manage state transitions and validate operations.
 *
 * Properties:
 * - orderId: Unique identifier
 * - customerId: Associated customer
 * - amount: Order total amount
 * - state: Current FSM state (PENDING, CONFIRMED, SHIPPED, DELIVERED, or CANCELLED)
 * - createdAt: Order creation timestamp
 * - updatedAt: Last update timestamp
 *
 * Usage:
 * <pre>
 *   Order order = new Order("order-123", "customer-456", 99.99);
 *   order.confirm();       // PENDING → CONFIRMED
 *   order.ship();          // CONFIRMED → SHIPPED
 *   order.deliver();       // SHIPPED → DELIVERED
 * </pre>
 *
 * @author hal.hildebrand
 */
public class Order {
    private final String orderId;
    private final String customerId;
    private final double amount;

    private OrderState state;
    private final Instant createdAt;
    private Instant updatedAt;

    /**
     * Create a new order in PENDING state.
     *
     * @param orderId Unique order identifier
     * @param customerId Customer ID
     * @param amount Order total amount
     */
    public Order(String orderId, String customerId, double amount) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.state = OrderState.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Get order ID.
     */
    public String getOrderId() {
        return orderId;
    }

    /**
     * Get customer ID.
     */
    public String getCustomerId() {
        return customerId;
    }

    /**
     * Get order amount.
     */
    public double getAmount() {
        return amount;
    }

    /**
     * Get current order state.
     */
    public OrderState getState() {
        return state;
    }

    /**
     * Get creation timestamp.
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Get last update timestamp.
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Confirm order (PENDING → CONFIRMED).
     * Throws exception if not in PENDING state.
     */
    public void confirm() {
        try {
            state = state.confirm();
            updatedAt = Instant.now();
        } catch (UnsupportedOperationException e) {
            throw new OrderStateException(
                String.format("Cannot confirm order %s from state %s", orderId, state), e);
        }
    }

    /**
     * Ship order (CONFIRMED → SHIPPED).
     * Throws exception if not in CONFIRMED state.
     */
    public void ship() {
        try {
            state = state.ship();
            updatedAt = Instant.now();
        } catch (UnsupportedOperationException e) {
            throw new OrderStateException(
                String.format("Cannot ship order %s from state %s", orderId, state), e);
        }
    }

    /**
     * Deliver order (SHIPPED → DELIVERED).
     * Throws exception if not in SHIPPED state.
     */
    public void deliver() {
        try {
            state = state.deliver();
            updatedAt = Instant.now();
        } catch (UnsupportedOperationException e) {
            throw new OrderStateException(
                String.format("Cannot deliver order %s from state %s", orderId, state), e);
        }
    }

    /**
     * Cancel order (any non-terminal state → CANCELLED).
     * Throws exception if already in terminal state.
     */
    public void cancel() {
        try {
            if (state.isTerminal()) {
                throw new OrderStateException(
                    String.format("Cannot cancel order %s from terminal state %s", orderId, state));
            }
            state = state.cancel();
            updatedAt = Instant.now();
        } catch (UnsupportedOperationException e) {
            throw new OrderStateException(
                String.format("Cannot cancel order %s from state %s", orderId, state), e);
        }
    }

    /**
     * Check if order is in terminal state (DELIVERED or CANCELLED).
     */
    public boolean isTerminal() {
        return state.isTerminal();
    }

    /**
     * Get order summary string.
     */
    @Override
    public String toString() {
        return String.format("Order{id=%s, customer=%s, amount=%.2f, state=%s, created=%s, updated=%s}",
                           orderId, customerId, amount, state, createdAt, updatedAt);
    }

    /**
     * Exception for invalid order state transitions.
     */
    public static class OrderStateException extends RuntimeException {
        public OrderStateException(String message) {
            super(message);
        }

        public OrderStateException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
