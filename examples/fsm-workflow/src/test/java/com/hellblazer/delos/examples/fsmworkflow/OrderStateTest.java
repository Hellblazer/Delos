/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.examples.fsmworkflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Order FSM demonstrating state transitions and validation.
 */
class OrderStateTest {

    private Order order;

    @BeforeEach
    void setUp() {
        order = new Order("order-123", "customer-456", 99.99);
    }

    @Test
    void shouldStartInPendingState() {
        assertEquals(OrderState.PENDING, order.getState());
        assertFalse(order.isTerminal());
    }

    @Test
    void shouldTransitionPendingToConfirmed() {
        order.confirm();
        assertEquals(OrderState.CONFIRMED, order.getState());
        assertFalse(order.isTerminal());
    }

    @Test
    void shouldTransitionConfirmedToShipped() {
        order.confirm();
        order.ship();
        assertEquals(OrderState.SHIPPED, order.getState());
        assertFalse(order.isTerminal());
    }

    @Test
    void shouldTransitionShippedToDelivered() {
        order.confirm();
        order.ship();
        order.deliver();
        assertEquals(OrderState.DELIVERED, order.getState());
        assertTrue(order.isTerminal());
    }

    @Test
    void shouldAllowHappyPathFlow() {
        // Complete workflow: PENDING → CONFIRMED → SHIPPED → DELIVERED
        assertEquals(OrderState.PENDING, order.getState());
        order.confirm();
        assertEquals(OrderState.CONFIRMED, order.getState());
        order.ship();
        assertEquals(OrderState.SHIPPED, order.getState());
        order.deliver();
        assertEquals(OrderState.DELIVERED, order.getState());
        assertTrue(order.isTerminal());
    }

    @Test
    void shouldAllowCancelFromPending() {
        order.cancel();
        assertEquals(OrderState.CANCELLED, order.getState());
        assertTrue(order.isTerminal());
    }

    @Test
    void shouldAllowCancelFromConfirmed() {
        order.confirm();
        order.cancel();
        assertEquals(OrderState.CANCELLED, order.getState());
        assertTrue(order.isTerminal());
    }

    @Test
    void shouldAllowCancelFromShipped() {
        order.confirm();
        order.ship();
        order.cancel();
        assertEquals(OrderState.CANCELLED, order.getState());
        assertTrue(order.isTerminal());
    }

    @Test
    void shouldThrowWhenConfirmingNonPendingOrder() {
        order.confirm();
        assertThrows(Order.OrderStateException.class, order::confirm);
    }

    @Test
    void shouldThrowWhenShippingNonConfirmedOrder() {
        assertThrows(Order.OrderStateException.class, order::ship);
    }

    @Test
    void shouldThrowWhenDeliveringNonShippedOrder() {
        assertThrows(Order.OrderStateException.class, order::deliver);
    }

    @Test
    void shouldThrowWhenCancellingDeliveredOrder() {
        order.confirm();
        order.ship();
        order.deliver();
        assertThrows(Order.OrderStateException.class, order::cancel);
    }

    @Test
    void shouldThrowWhenCancellingCancelledOrder() {
        order.cancel();
        assertThrows(Order.OrderStateException.class, order::cancel);
    }

    @Test
    void shouldNotAllowShipAfterCancel() {
        order.cancel();
        assertThrows(Order.OrderStateException.class, order::ship);
    }

    @Test
    void shouldHaveOrderProperties() {
        assertEquals("order-123", order.getOrderId());
        assertEquals("customer-456", order.getCustomerId());
        assertEquals(99.99, order.getAmount());
        assertNotNull(order.getCreatedAt());
        assertNotNull(order.getUpdatedAt());
    }

    @Test
    void shouldUpdateTimestampOnTransition() {
        var createdAt = order.getCreatedAt();
        var initialUpdatedAt = order.getUpdatedAt();

        try {
            Thread.sleep(10); // Ensure time passes
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        order.confirm();
        var newUpdatedAt = order.getUpdatedAt();

        assertEquals(createdAt, order.getCreatedAt());
        assertTrue(newUpdatedAt.isAfter(initialUpdatedAt));
    }

    @Test
    void shouldProvideBoundaryStateDescriptions() {
        assertEquals(OrderState.PENDING.getDescription(), "Order received, awaiting confirmation");
        assertEquals(OrderState.CONFIRMED.getDescription(), "Payment confirmed, ready for shipment");
        assertEquals(OrderState.SHIPPED.getDescription(), "Item shipped, in transit");
        assertEquals(OrderState.DELIVERED.getDescription(), "Item delivered to customer");
        assertEquals(OrderState.CANCELLED.getDescription(), "Order cancelled");
    }

    @Test
    void demonstratesStateMachineAPI() {
        // This test demonstrates the typical FSM workflow

        // 1. Order starts in PENDING state
        assertEquals(OrderState.PENDING, order.getState());

        // 2. Confirm order (PENDING → CONFIRMED)
        order.confirm();
        assertEquals(OrderState.CONFIRMED, order.getState());

        // 3. Ship order (CONFIRMED → SHIPPED)
        order.ship();
        assertEquals(OrderState.SHIPPED, order.getState());

        // 4. Deliver order (SHIPPED → DELIVERED) - Terminal state
        order.deliver();
        assertEquals(OrderState.DELIVERED, order.getState());
        assertTrue(order.isTerminal());

        // 5. Cannot transition from terminal state
        assertThrows(Order.OrderStateException.class, order::ship);
        assertThrows(Order.OrderStateException.class, order::cancel);
    }

    @Test
    void demonstratesCancellationPath() {
        // This test shows the cancellation workflow

        // 1. Order starts in PENDING
        assertEquals(OrderState.PENDING, order.getState());

        // 2. Confirm order
        order.confirm();
        assertEquals(OrderState.CONFIRMED, order.getState());

        // 3. Can still cancel before shipping
        order.cancel();
        assertEquals(OrderState.CANCELLED, order.getState());
        assertTrue(order.isTerminal());

        // 4. Cannot transition from cancelled (terminal state)
        assertThrows(Order.OrderStateException.class, order::ship);
    }
}
