/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.chaos;

import java.time.Duration;
import java.time.Instant;

/**
 * Immutable record of a chaos engineering event.
 * <p>
 * Captures details of fault injection including timing, success status, and diagnostic details.
 *
 * @param scenarioName Name of the chaos scenario executed
 * @param injectTime   When the fault was injected
 * @param recoverTime  When recovery was initiated (may be null if still ongoing)
 * @param successful   Whether the fault injection and recovery succeeded
 * @param details      Diagnostic details about the event
 * @param duration     Total duration from injection to recovery
 * @author hal.hildebrand
 */
public record ChaosEvent(
    String scenarioName,
    Instant injectTime,
    Instant recoverTime,
    boolean successful,
    String details,
    Duration duration
) {
    /**
     * Create a new ChaosEvent for a successful scenario.
     */
    public static ChaosEvent success(String scenarioName, Instant injectTime, Instant recoverTime, String details) {
        return new ChaosEvent(
            scenarioName,
            injectTime,
            recoverTime,
            true,
            details,
            Duration.between(injectTime, recoverTime)
        );
    }

    /**
     * Create a new ChaosEvent for a failed scenario.
     */
    public static ChaosEvent failure(String scenarioName, Instant injectTime, Instant recoverTime, String details) {
        return new ChaosEvent(
            scenarioName,
            injectTime,
            recoverTime,
            false,
            details,
            Duration.between(injectTime, recoverTime)
        );
    }

    /**
     * Format event as a log line.
     */
    public String toLogLine() {
        return String.format("[%s] %s: %s | Duration: %s | Status: %s | Details: %s",
                             injectTime,
                             scenarioName,
                             successful ? "SUCCESS" : "FAILURE",
                             duration,
                             successful ? "OK" : "FAILED",
                             details);
    }
}
