/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.ethereal;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConsumerErrorHandler circuit breaker functionality.
 * <p>
 * Fast unit tests that don't require full Ethereal cluster setup.
 *
 * @author hal.hildebrand
 */
public class ConsumerErrorHandlerUnitTest {

    @Test
    public void testFailureCounterIncrementsOnError() {
        var errorHandler = new ConsumerErrorHandler.Builder().setMaxConsecutiveFailures(5).build();

        var exception = new RuntimeException("Test failure");
        var preBlock = Arrays.asList(ByteString.copyFromUtf8("test"));

        var action1 = errorHandler.handleError(exception, preBlock, false);
        assertEquals(ConsumerErrorHandler.ErrorAction.CONTINUE, action1);

        var action2 = errorHandler.handleError(exception, preBlock, false);
        assertEquals(ConsumerErrorHandler.ErrorAction.CONTINUE, action2);

        var action3 = errorHandler.handleError(exception, preBlock, false);
        assertEquals(ConsumerErrorHandler.ErrorAction.CONTINUE, action3);
    }

    @Test
    public void testFailureCounterResetsOnSuccess() {
        var errorHandler = new ConsumerErrorHandler.Builder().setMaxConsecutiveFailures(5).build();

        var exception = new RuntimeException("Test failure");
        var preBlock = Arrays.asList(ByteString.copyFromUtf8("test"));

        // Fail twice
        errorHandler.handleError(exception, preBlock, false);
        errorHandler.handleError(exception, preBlock, false);

        // Success resets counter
        errorHandler.recordSuccess();

        // Fail three more times - should not trigger circuit breaker
        errorHandler.handleError(exception, preBlock, false);
        errorHandler.handleError(exception, preBlock, false);
        var action = errorHandler.handleError(exception, preBlock, false);

        // Should still CONTINUE since we haven't hit 5 consecutive
        assertEquals(ConsumerErrorHandler.ErrorAction.CONTINUE, action);
    }

    @Test
    public void testCircuitBreakerTriggersAfterThreshold() {
        var errorHandler = new ConsumerErrorHandler.Builder().setMaxConsecutiveFailures(3).build();

        var exception = new RuntimeException("Test failure");
        var preBlock = Arrays.asList(ByteString.copyFromUtf8("test"));

        // Fail 3 times to trigger circuit breaker
        errorHandler.handleError(exception, preBlock, false);
        errorHandler.handleError(exception, preBlock, false);
        var action = errorHandler.handleError(exception, preBlock, false);

        // Circuit breaker should trigger HALT
        assertEquals(ConsumerErrorHandler.ErrorAction.HALT, action);
    }

    @Test
    public void testCustomErrorHandler() {
        var callCount = new AtomicInteger(0);

        var errorHandler = new ConsumerErrorHandler.Builder().setMaxConsecutiveFailures(10)
                                                              .setOnError(context -> {
                                                                  callCount.incrementAndGet();
                                                                  // Custom logic: HALT on 2nd error
                                                                  if (context.consecutiveFailures() >= 2) {
                                                                      return ConsumerErrorHandler.ErrorAction.HALT;
                                                                  }
                                                                  return ConsumerErrorHandler.ErrorAction.CONTINUE;
                                                              })
                                                              .build();

        var exception = new RuntimeException("Test failure");
        var preBlock = Arrays.asList(ByteString.copyFromUtf8("test"));

        var action1 = errorHandler.handleError(exception, preBlock, false);
        assertEquals(ConsumerErrorHandler.ErrorAction.CONTINUE, action1);
        assertEquals(1, callCount.get());

        var action2 = errorHandler.handleError(exception, preBlock, false);
        assertEquals(ConsumerErrorHandler.ErrorAction.HALT, action2);
        assertEquals(2, callCount.get());
    }

    @Test
    public void testErrorContextContainsCorrectData() {
        var capturedContext = new AtomicInteger[1];

        var errorHandler = new ConsumerErrorHandler.Builder().setMaxConsecutiveFailures(5)
                                                              .setOnError(context -> {
                                                                  capturedContext[0] = new AtomicInteger(
                                                                  context.consecutiveFailures());
                                                                  assertNotNull(context.exception());
                                                                  assertNotNull(context.preBlock());
                                                                  assertFalse(context.last());
                                                                  return ConsumerErrorHandler.ErrorAction.CONTINUE;
                                                              })
                                                              .build();

        var exception = new RuntimeException("Test failure");
        var preBlock = Arrays.asList(ByteString.copyFromUtf8("test"));

        errorHandler.handleError(exception, preBlock, false);
        errorHandler.handleError(exception, preBlock, false);
        errorHandler.handleError(exception, preBlock, false);

        assertNotNull(capturedContext[0]);
        assertEquals(3, capturedContext[0].get());
    }

    @Test
    public void testBuilderValidation() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ConsumerErrorHandler.Builder().setMaxConsecutiveFailures(0).build();
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new ConsumerErrorHandler.Builder().setMaxConsecutiveFailures(-1).build();
        });
    }
}
