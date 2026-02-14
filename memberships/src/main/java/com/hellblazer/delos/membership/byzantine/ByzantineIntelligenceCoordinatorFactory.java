/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import java.time.Clock;
import java.util.Objects;

/**
 * Factory for creating ByzantineIntelligenceCoordinator instances with sensible defaults.
 * <p>
 * Provides simplified coordinator creation for common use cases while supporting
 * full customization when needed. The factory encapsulates coordinator initialization
 * patterns used across the Delos framework.
 * </p>
 * <p>
 * <b>Usage Patterns</b>:
 * </p>
 * <pre>{@code
 * // Simple creation with defaults
 * var coordinator = ByzantineIntelligenceCoordinatorFactory.create(
 *     responseHandler,
 *     metrics
 * );
 *
 * // Custom configuration
 * var customConfig = IntelligenceConfig.builder()
 *     .warningThreshold(0.6)
 *     .criticalThreshold(0.9)
 *     .build();
 * var coordinator = ByzantineIntelligenceCoordinatorFactory.create(
 *     customConfig,
 *     responseHandler,
 *     metrics
 * );
 *
 * // Full control (testing)
 * var coordinator = ByzantineIntelligenceCoordinatorFactory.create(
 *     customConfig,
 *     responseHandler,
 *     metrics,
 *     Clock.fixed(...)
 * );
 * }</pre>
 * <p>
 * <b>Default Configuration</b>:
 * </p>
 * <ul>
 *   <li>Layer Weights: FIREFLIES=0.4, ETHEREAL=0.3, THOTH=0.2, GORGONEION=0.1</li>
 *   <li>Poll Intervals: ETHEREAL=2s, FIREFLIES=5s, THOTH=10s, GORGONEION=10s</li>
 *   <li>Warning Threshold: 0.5</li>
 *   <li>Critical Threshold: 0.8</li>
 *   <li>Response Cooldown: 15s</li>
 * </ul>
 *
 * @author hal.hildebrand
 * @see ByzantineIntelligenceCoordinator
 * @see IntelligenceConfig
 */
public final class ByzantineIntelligenceCoordinatorFactory {

    /**
     * Private constructor - factory class.
     */
    private ByzantineIntelligenceCoordinatorFactory() {
        throw new UnsupportedOperationException("Factory class");
    }

    /**
     * Create coordinator with default configuration and system clock.
     * <p>
     * Uses {@link IntelligenceConfig#defaults()} for layer weights, poll intervals,
     * and thresholds. Suitable for production use with standard Byzantine detection
     * requirements.
     * </p>
     *
     * @param responseHandler Handler for warning and critical responses (non-null)
     * @param metrics         Metrics for observability (non-null)
     * @return Initialized coordinator (not started)
     * @throws NullPointerException if responseHandler or metrics is null
     */
    public static ByzantineIntelligenceCoordinator create(
        ResponseHandler responseHandler,
        ByzantineIntelligenceMetrics metrics
    ) {
        return create(IntelligenceConfig.defaults(), responseHandler, metrics);
    }

    /**
     * Create coordinator with custom configuration and system clock.
     * <p>
     * Allows tuning of thresholds, weights, and intervals for specific deployment
     * requirements.
     * </p>
     *
     * @param config          Configuration for thresholds, intervals, weights (non-null)
     * @param responseHandler Handler for warning and critical responses (non-null)
     * @param metrics         Metrics for observability (non-null)
     * @return Initialized coordinator (not started)
     * @throws NullPointerException if any parameter is null
     */
    public static ByzantineIntelligenceCoordinator create(
        IntelligenceConfig config,
        ResponseHandler responseHandler,
        ByzantineIntelligenceMetrics metrics
    ) {
        return create(config, responseHandler, metrics, Clock.systemUTC());
    }

    /**
     * Create coordinator with custom configuration and clock.
     * <p>
     * Provides full control over coordinator initialization, primarily for testing
     * with fixed or mocked clocks.
     * </p>
     *
     * @param config          Configuration for thresholds, intervals, weights (non-null)
     * @param responseHandler Handler for warning and critical responses (non-null)
     * @param metrics         Metrics for observability (non-null)
     * @param clock           Clock for time operations (non-null)
     * @return Initialized coordinator (not started)
     * @throws NullPointerException if any parameter is null
     */
    public static ByzantineIntelligenceCoordinator create(
        IntelligenceConfig config,
        ResponseHandler responseHandler,
        ByzantineIntelligenceMetrics metrics,
        Clock clock
    ) {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(responseHandler, "responseHandler cannot be null");
        Objects.requireNonNull(metrics, "metrics cannot be null");
        Objects.requireNonNull(clock, "clock cannot be null");

        return new ByzantineIntelligenceCoordinator(config, responseHandler, metrics, clock);
    }
}
