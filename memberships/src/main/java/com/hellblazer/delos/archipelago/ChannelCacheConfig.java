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
 * Configuration for Demultiplexer channel caching.
 * Immutable configuration with sensible defaults for production use.
 *
 * @param idleTimeout       Close channels idle longer than this duration
 * @param maxCachedChannels Maximum number of cached channels
 * @param evictionInterval  How often to run eviction sweep
 *
 * @author hal.hildebrand
 */
public record ChannelCacheConfig(
    Duration idleTimeout,
    int maxCachedChannels,
    Duration evictionInterval
) {
    /**
     * Default configuration suitable for most use cases.
     * - Idle timeout: 5 minutes
     * - Max cached channels: 100
     * - Eviction interval: 30 seconds
     */
    public static ChannelCacheConfig defaults() {
        return new ChannelCacheConfig(
            Duration.ofMinutes(5),
            100,
            Duration.ofSeconds(30)
        );
    }

    /**
     * Disabled configuration for backward compatibility.
     * Effectively disables caching (max size = 0).
     */
    public static ChannelCacheConfig disabled() {
        return new ChannelCacheConfig(
            Duration.ZERO,
            0,
            Duration.ofDays(365) // Large interval, never runs in practice
        );
    }
}
