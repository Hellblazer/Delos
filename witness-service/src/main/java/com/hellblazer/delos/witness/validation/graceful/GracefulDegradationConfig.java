/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation.graceful;

/**
 * Configuration for graceful degradation during view changes.
 *
 * Defines thresholds and parameters for how the system behaves when Byzantine
 * members are detected or network partitions occur.
 *
 * Phase 1C-3-C: Graceful Degradation (Delos-3938)
 *
 * @param byzantineQuorumReductionFactor 0.0-1.0: How much to reduce Byzantine tolerance when degraded
 * @param maxSignaturesToBuffer Buffer capacity during view changes (default: 1000)
 * @param signatureBufferTTLMs How long to hold buffered signatures in milliseconds (default: 5000ms)
 * @param viewChangeTimeoutMs Timeout before forced view change in milliseconds (default: 10000ms)
 * @param enableAutoRecovery Auto-transition to new view when threshold restored (default: true)
 * @param recoveryCheckIntervalMs How often to check recovery status in milliseconds (default: 100ms)
 * @param dynamicThresholdRecalculation Enable threshold adjustment for Byzantine members (default: true)
 * @param minThresholdPercentage Minimum threshold as percentage 0.5-1.0 (default: 2/3 + 1 / totalMembers)
 * @param enableMetrics Track degradation events (default: true)
 * @param metricsHistorySize How many events to retain (default: 10000)
 */
public record GracefulDegradationConfig(
    double byzantineQuorumReductionFactor,
    int maxSignaturesToBuffer,
    long signatureBufferTTLMs,
    int viewChangeTimeoutMs,
    boolean enableAutoRecovery,
    int recoveryCheckIntervalMs,
    boolean dynamicThresholdRecalculation,
    double minThresholdPercentage,
    boolean enableMetrics,
    int metricsHistorySize
) {
    /**
     * Compact constructor with validation.
     *
     * Validates all parameters before assignment to ensure configuration integrity.
     *
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public GracefulDegradationConfig {
        if (byzantineQuorumReductionFactor < 0.0 || byzantineQuorumReductionFactor > 1.0) {
            throw new IllegalArgumentException("byzantineQuorumReductionFactor must be 0.0-1.0");
        }
        if (maxSignaturesToBuffer <= 0) {
            throw new IllegalArgumentException("maxSignaturesToBuffer must be > 0");
        }
        if (signatureBufferTTLMs <= 0) {
            throw new IllegalArgumentException("signatureBufferTTLMs must be > 0");
        }
        if (viewChangeTimeoutMs <= 0) {
            throw new IllegalArgumentException("viewChangeTimeoutMs must be > 0");
        }
        if (recoveryCheckIntervalMs <= 0) {
            throw new IllegalArgumentException("recoveryCheckIntervalMs must be > 0");
        }
        if (minThresholdPercentage < 0.5 || minThresholdPercentage > 1.0) {
            throw new IllegalArgumentException("minThresholdPercentage must be 0.5-1.0");
        }
        if (metricsHistorySize <= 0) {
            throw new IllegalArgumentException("metricsHistorySize must be > 0");
        }
    }

    /**
     * Default configuration for standard Byzantine tolerance (1/3 Byzantine assumption).
     *
     * This configuration provides balanced settings suitable for most deployments:
     * - 33% Byzantine tolerance (standard BFT assumption)
     * - 1000 signature buffer capacity
     * - 5 second signature buffer TTL
     * - 10 second view change timeout
     * - Auto-recovery enabled with 100ms checks
     * - Dynamic threshold recalculation enabled
     * - 2/3 minimum threshold (67%)
     * - Metrics enabled with 10,000 event history
     *
     * @return a default configuration instance
     */
    public static GracefulDegradationConfig defaultConfig() {
        return new GracefulDegradationConfig(
            0.33,      // byzantineQuorumReductionFactor: 1/3 Byzantine tolerance
            1000,      // maxSignaturesToBuffer
            5000,      // signatureBufferTTLMs
            10000,     // viewChangeTimeoutMs
            true,      // enableAutoRecovery
            100,       // recoveryCheckIntervalMs
            true,      // dynamicThresholdRecalculation
            0.667,     // minThresholdPercentage (2/3 + 1)
            true,      // enableMetrics
            10000      // metricsHistorySize
        );
    }

    /**
     * Conservative configuration (low degradation, high consistency).
     *
     * This configuration prioritizes consistency over availability:
     * - 10% Byzantine tolerance (more conservative)
     * - 500 signature buffer capacity (smaller buffer)
     * - 3 second signature buffer TTL (shorter TTL)
     * - 20 second view change timeout (longer timeout for stability)
     * - Manual recovery (disabled auto-recovery)
     * - Slower recovery checks (500ms interval)
     * - Dynamic threshold recalculation enabled
     * - 75% minimum threshold (higher requirement)
     * - Metrics enabled with 5,000 event history
     *
     * Use this configuration when:
     * - Consistency is critical
     * - Network is stable
     * - Manual intervention for recovery is acceptable
     *
     * @return a conservative configuration instance
     */
    public static GracefulDegradationConfig conservative() {
        return new GracefulDegradationConfig(
            0.1,       // More conservative Byzantine assumption
            500,       // Small buffer
            3000,      // Short TTL
            20000,     // Long timeout
            false,     // Manual recovery
            500,       // Slower checks
            true,      // Dynamic thresholds
            0.75,      // Higher minimum threshold
            true,      // Metrics enabled
            5000
        );
    }

    /**
     * Aggressive configuration (high degradation tolerance, faster recovery).
     *
     * This configuration prioritizes availability over strict consistency:
     * - 50% Byzantine tolerance (higher tolerance)
     * - 2000 signature buffer capacity (larger buffer)
     * - 10 second signature buffer TTL (longer TTL)
     * - 5 second view change timeout (faster timeout)
     * - Auto-recovery enabled
     * - Fast recovery checks (50ms interval)
     * - Dynamic threshold recalculation enabled
     * - 65% minimum threshold (lower requirement)
     * - Metrics enabled with 20,000 event history
     *
     * Use this configuration when:
     * - Availability is prioritized
     * - Network is unreliable
     * - Faster recovery from degradation is needed
     * - Higher Byzantine tolerance is required
     *
     * @return an aggressive configuration instance
     */
    public static GracefulDegradationConfig aggressive() {
        return new GracefulDegradationConfig(
            0.5,       // Higher Byzantine tolerance
            2000,      // Larger buffer
            10000,     // Long TTL
            5000,      // Short timeout
            true,      // Auto recovery
            50,        // Faster checks
            true,      // Dynamic thresholds
            0.65,      // Lower minimum threshold
            true,      // Metrics
            20000
        );
    }
}
