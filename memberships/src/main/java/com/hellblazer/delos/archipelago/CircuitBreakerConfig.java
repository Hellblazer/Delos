/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.archipelago;

import java.time.Duration;

/**
 * Configuration for per-member circuit breaker in ServerConnectionCache.
 * All parameters have sensible defaults. Immutable.
 *
 * @param failureThreshold   Number of consecutive failures before circuit opens (default: 3)
 * @param baseBackoff        Initial backoff duration when circuit opens (default: 1s)
 * @param maxBackoff         Maximum backoff duration cap (default: 30s)
 * @param backoffMultiplier  Exponential multiplier for backoff (default: 2.0)
 * @param jitterFactor       Random jitter as fraction of backoff to prevent thundering herd (default: 0.25)
 * @author hal.hildebrand
 */
public record CircuitBreakerConfig(
    int failureThreshold,
    Duration baseBackoff,
    Duration maxBackoff,
    double backoffMultiplier,
    double jitterFactor
) {
    /**
     * Default configuration suitable for most scenarios.
     * - Threshold: 3 failures
     * - Base backoff: 1 second
     * - Max backoff: 30 seconds
     * - Multiplier: 2.0 (exponential)
     * - Jitter: 25%
     */
    public static CircuitBreakerConfig defaults() {
        return new CircuitBreakerConfig(
            3,
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            2.0,
            0.25
        );
    }

    /**
     * Disabled circuit breaker configuration.
     * Circuit will never open (threshold = Integer.MAX_VALUE).
     * Use this to disable circuit breaker functionality without changing code.
     */
    public static CircuitBreakerConfig disabled() {
        return new CircuitBreakerConfig(
            Integer.MAX_VALUE,
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            2.0,
            0.0
        );
    }

    /**
     * Compact constructor with validation.
     */
    public CircuitBreakerConfig {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        if (baseBackoff.isNegative() || baseBackoff.isZero()) {
            throw new IllegalArgumentException("baseBackoff must be positive");
        }
        if (maxBackoff.isNegative() || maxBackoff.isZero()) {
            throw new IllegalArgumentException("maxBackoff must be positive");
        }
        if (baseBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException("baseBackoff must be <= maxBackoff");
        }
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
        }
        if (jitterFactor < 0.0 || jitterFactor >= 1.0) {
            throw new IllegalArgumentException("jitterFactor must be in [0.0, 1.0)");
        }
    }
}
