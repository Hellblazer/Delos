/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.verification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Result of a verification check execution.
 * <p>
 * Captures the outcome, timing, and diagnostic details of verification checks
 * performed during simulation. Used for tracking consistency violations and
 * generating audit trails.
 *
 * @author hal.hildebrand
 */
public record VerificationResult(
    String checkName,
    Instant timestamp,
    boolean passed,
    String message,
    List<String> details,
    Duration executionTime
) {

    /**
     * Create a successful verification result.
     */
    public static VerificationResult success(String checkName, Duration executionTime) {
        return new VerificationResult(
            checkName,
            Instant.now(),
            true,
            "Verification passed",
            List.of(),
            executionTime
        );
    }

    /**
     * Create a successful verification result with details.
     */
    public static VerificationResult success(String checkName, Duration executionTime, List<String> details) {
        return new VerificationResult(
            checkName,
            Instant.now(),
            true,
            "Verification passed",
            details,
            executionTime
        );
    }

    /**
     * Create a failed verification result.
     */
    public static VerificationResult failure(String checkName, Duration executionTime, String message) {
        return new VerificationResult(
            checkName,
            Instant.now(),
            false,
            message,
            List.of(),
            executionTime
        );
    }

    /**
     * Create a failed verification result with details.
     */
    public static VerificationResult failure(String checkName, Duration executionTime, String message,
                                            List<String> details) {
        return new VerificationResult(
            checkName,
            Instant.now(),
            false,
            message,
            details,
            executionTime
        );
    }

    /**
     * Create a verification result for a timeout.
     */
    public static VerificationResult timeout(String checkName, Duration timeout) {
        return new VerificationResult(
            checkName,
            Instant.now(),
            false,
            "Verification timed out after " + timeout,
            List.of(),
            timeout
        );
    }

    /**
     * Create a verification result for an error.
     */
    public static VerificationResult error(String checkName, Duration executionTime, Exception e) {
        return new VerificationResult(
            checkName,
            Instant.now(),
            false,
            "Verification error: " + e.getMessage(),
            List.of(e.getClass().getSimpleName() + ": " + e.getMessage()),
            executionTime
        );
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append(String.format("[%s] %s: %s (%dms) - %s",
                                timestamp,
                                checkName,
                                passed ? "PASS" : "FAIL",
                                executionTime.toMillis(),
                                message));
        if (!details.isEmpty()) {
            sb.append("\n  Details:");
            details.forEach(d -> sb.append("\n    - ").append(d));
        }
        return sb.toString();
    }
}
