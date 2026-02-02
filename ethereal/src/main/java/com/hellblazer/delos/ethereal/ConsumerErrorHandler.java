/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.ethereal;

import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Handles errors from the data consumer with circuit breaker pattern.
 * <p>
 * This class implements error propagation and circuit breaker functionality to prevent
 * silent data corruption when the consumer fails to process preblocks.
 * <p>
 * <strong>Circuit Breaker Pattern:</strong>
 * <ul>
 * <li>Tracks consecutive failures</li>
 * <li>Opens circuit after threshold failures</li>
 * <li>Resets counter on successful consumption</li>
 * <li>Allows configurable error handling strategy</li>
 * </ul>
 * <p>
 * <strong>Error Actions:</strong>
 * <ul>
 * <li>CONTINUE - Log error and continue consensus (default)</li>
 * <li>HALT - Stop consensus processing immediately</li>
 * <li>RETRY - Attempt to retry consumption (future enhancement)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class ConsumerErrorHandler {

    private static final Logger                      log                  = LoggerFactory.getLogger(
    ConsumerErrorHandler.class);
    private final        AtomicInteger               consecutiveFailures  = new AtomicInteger(0);
    private final        int                         maxConsecutiveFailures;
    private final        Function<ErrorContext, ErrorAction> onError;

    private ConsumerErrorHandler(Builder builder) {
        this.maxConsecutiveFailures = builder.maxConsecutiveFailures;
        this.onError = builder.onError != null ? builder.onError : this::defaultErrorHandler;
    }

    /**
     * Record successful consumption, resetting the failure counter.
     */
    public void recordSuccess() {
        var previousFailures = consecutiveFailures.getAndSet(0);
        if (previousFailures > 0) {
            log.debug("Consumer recovered after {} consecutive failures", previousFailures);
        }
    }

    /**
     * Handle a consumer error and determine the appropriate action.
     *
     * @param exception Exception thrown by consumer
     * @param preBlock  PreBlock that failed to be consumed
     * @param last      Whether this was the last block
     * @return ErrorAction to take
     */
    public ErrorAction handleError(Throwable exception, List<ByteString> preBlock, boolean last) {
        var failures = consecutiveFailures.incrementAndGet();

        var context = new ErrorContext(exception, preBlock, last, failures, maxConsecutiveFailures);

        var action = onError.apply(context);

        if (failures >= maxConsecutiveFailures) {
            log.error("Circuit breaker OPEN: {} consecutive failures (threshold: {})", failures,
                      maxConsecutiveFailures);
        }

        return action;
    }

    private ErrorAction defaultErrorHandler(ErrorContext context) {
        log.error("Consumer error (failure {}/{}): {}", context.consecutiveFailures(),
                  context.maxConsecutiveFailures(), context.exception().getMessage(), context.exception());

        if (context.consecutiveFailures() >= context.maxConsecutiveFailures()) {
            log.error("Circuit breaker triggered - halting consensus");
            return ErrorAction.HALT;
        }

        return ErrorAction.CONTINUE;
    }

    /**
     * Action to take after a consumer error.
     */
    public enum ErrorAction {
        /**
         * Continue consensus processing despite error
         */
        CONTINUE,

        /**
         * Halt consensus processing immediately
         */
        HALT,

        /**
         * Retry consumption (future enhancement)
         */
        RETRY
    }

    /**
     * Context information for error handling decisions.
     *
     * @param exception              Exception thrown by consumer
     * @param preBlock               PreBlock that failed
     * @param last                   Whether this was the last block
     * @param consecutiveFailures    Number of consecutive failures
     * @param maxConsecutiveFailures Circuit breaker threshold
     */
    public record ErrorContext(Throwable exception, List<ByteString> preBlock, boolean last, int consecutiveFailures,
                               int maxConsecutiveFailures) {
    }

    public static class Builder {
        private int                                  maxConsecutiveFailures = 10;
        private Function<ErrorContext, ErrorAction> onError;

        public Builder setMaxConsecutiveFailures(int maxConsecutiveFailures) {
            if (maxConsecutiveFailures < 1) {
                throw new IllegalArgumentException("maxConsecutiveFailures must be >= 1");
            }
            this.maxConsecutiveFailures = maxConsecutiveFailures;
            return this;
        }

        public Builder setOnError(Function<ErrorContext, ErrorAction> onError) {
            this.onError = onError;
            return this;
        }

        public ConsumerErrorHandler build() {
            return new ConsumerErrorHandler(this);
        }
    }
}
