/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.reporting;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * High-level summary of simulation results for executive reporting.
 * <p>
 * Provides the key metrics and outcomes in a concise format suitable for
 * dashboards and executive summaries.
 *
 * @author hal.hildebrand
 */
public record ExecutiveSummary(
    boolean passed,
    Duration duration,
    double uptimePercent,
    int majorIncidentsCount,
    int verificationsPassed,
    int verificationsFailed,
    int chaosScenarioCount,
    double chaosRecoveryRate,
    Duration averageRecoveryTime,
    List<String> slaViolations,
    Optional<String> failureReason
) {
    /**
     * Create an executive summary from simulation context.
     */
    public static ExecutiveSummary from(SimulationContext context) {
        var passed = context.isSuccess();
        var failureReason = passed ? Optional.<String>empty()
                                   : Optional.of(determineFailureReason(context));

        // Calculate average recovery time (simplified - would need more data in real implementation)
        var avgRecoveryTime = Duration.ofMinutes(5);

        // Count major incidents (critical events)
        var majorIncidents = (int) context.events().stream()
            .filter(e -> e.getSeverity() == SimulationEvent.EventSeverity.CRITICAL
                         || e.getSeverity() == SimulationEvent.EventSeverity.ERROR)
            .count();

        return new ExecutiveSummary(
            passed,
            context.getDuration(),
            context.getUptimePercent(),
            majorIncidents,
            context.verificationsPassed(),
            context.verificationsFailed(),
            context.chaosScenarios(),
            context.getChaosRecoveryRate(),
            avgRecoveryTime,
            context.slaViolations(),
            failureReason
        );
    }

    /**
     * Get a human-readable status string.
     */
    public String getStatusString() {
        return passed ? "PASSED" : "FAILED";
    }

    /**
     * Get a grade (A-F) based on overall performance.
     */
    public String getGrade() {
        if (!passed) {
            return "F";
        }

        var score = calculateScore();
        if (score >= 95) return "A+";
        if (score >= 90) return "A";
        if (score >= 85) return "B+";
        if (score >= 80) return "B";
        if (score >= 75) return "C+";
        if (score >= 70) return "C";
        if (score >= 65) return "D";
        return "F";
    }

    /**
     * Calculate overall score (0-100).
     */
    public double calculateScore() {
        if (!passed) {
            return 0.0;
        }

        // Weighted scoring
        var uptimeScore = uptimePercent;
        var verificationScore = verificationsFailed == 0 ? 100.0 : 0.0;
        var chaosScore = chaosRecoveryRate;

        return (uptimeScore * 0.4) + (verificationScore * 0.4) + (chaosScore * 0.2);
    }

    /**
     * Determine the primary failure reason from context.
     */
    private static String determineFailureReason(SimulationContext context) {
        if (!context.slaViolations().isEmpty()) {
            return "SLA violations: " + String.join(", ", context.slaViolations());
        }
        if (context.verificationsFailed() > 0) {
            return context.verificationsFailed() + " verification(s) failed";
        }
        if (context.healthChecksFailed() > context.healthChecksPassed()) {
            return "Excessive health check failures";
        }
        return "Unknown failure";
    }
}
