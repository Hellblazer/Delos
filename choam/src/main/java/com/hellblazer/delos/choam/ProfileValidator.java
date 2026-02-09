/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates ConfigurationProfile instances against Byzantine fault tolerance
 * constraints and operational requirements.
 * <p>
 * Validation rules:
 * - Cluster size must satisfy n ≥ 3f+1 for Byzantine tolerance
 * - Timeout hierarchy: session > viewChange > stall
 * - All timeouts must be positive
 * - Log level must be valid
 *
 * @author hal.hildebrand
 */
public class ProfileValidator {
    private static final List<String> VALID_LOG_LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");

    /**
     * Validation result containing any violations found.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final List<String> violations;

        private ValidationResult(boolean valid, List<String> violations) {
            this.valid = valid;
            this.violations = List.copyOf(violations);
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getViolations() {
            return violations;
        }

        public String getViolationSummary() {
            return String.join("\n", violations);
        }

        static ValidationResult success() {
            return new ValidationResult(true, List.of());
        }

        static ValidationResult failure(List<String> violations) {
            return new ValidationResult(false, violations);
        }
    }

    /**
     * Validate a configuration profile against all constraints.
     *
     * @param profile the profile to validate
     * @return validation result
     */
    public static ValidationResult validate(ConfigurationProfile profile) {
        var violations = new ArrayList<String>();

        validateTimeouts(profile, violations);
        validateClusterSize(profile, violations);
        validateLogLevel(profile, violations);
        validateByzantineSettings(profile, violations);

        return violations.isEmpty() ? ValidationResult.success() : ValidationResult.failure(violations);
    }

    /**
     * Validate timeout hierarchy and values.
     */
    private static void validateTimeouts(ConfigurationProfile profile, List<String> violations) {
        // All timeouts must be positive
        if (!profile.getStallTimeout().isPositive()) {
            violations.add("Stall timeout must be positive: " + profile.getStallTimeout());
        }
        if (!profile.getViewChangeTimeout().isPositive()) {
            violations.add("View change timeout must be positive: " + profile.getViewChangeTimeout());
        }
        if (!profile.getSessionTimeout().isPositive()) {
            violations.add("Session timeout must be positive: " + profile.getSessionTimeout());
        }

        // Timeout hierarchy: session > viewChange > stall
        if (profile.getViewChangeTimeout().compareTo(profile.getStallTimeout()) <= 0) {
            violations.add(String.format(
                "View change timeout (%s) must be greater than stall timeout (%s)",
                profile.getViewChangeTimeout(), profile.getStallTimeout()));
        }
        if (profile.getSessionTimeout().compareTo(profile.getViewChangeTimeout()) <= 0) {
            violations.add(String.format(
                "Session timeout (%s) must be greater than view change timeout (%s)",
                profile.getSessionTimeout(), profile.getViewChangeTimeout()));
        }

        // Minimum timeout values (prevent overly aggressive settings)
        if (profile.getStallTimeout().compareTo(Duration.ofMillis(500)) < 0) {
            violations.add(String.format(
                "Stall timeout (%s) is too aggressive (minimum 500ms recommended)",
                profile.getStallTimeout()));
        }
        if (profile.getViewChangeTimeout().compareTo(Duration.ofSeconds(5)) < 0) {
            violations.add(String.format(
                "View change timeout (%s) is too aggressive (minimum 5s recommended)",
                profile.getViewChangeTimeout()));
        }
    }

    /**
     * Validate cluster size satisfies Byzantine fault tolerance.
     */
    private static void validateClusterSize(ConfigurationProfile profile, List<String> violations) {
        var minSize = profile.getMinClusterSize();
        var maxSize = profile.getMaxClusterSize();

        // Basic sanity checks
        if (minSize <= 0) {
            violations.add("Minimum cluster size must be positive: " + minSize);
        }
        if (maxSize < minSize) {
            violations.add(String.format(
                "Maximum cluster size (%d) must be >= minimum cluster size (%d)",
                maxSize, minSize));
        }

        // Byzantine fault tolerance: n ≥ 3f+1 for f failures
        // Minimum viable: 3 nodes for crash tolerance, 4 nodes for Byzantine tolerance
        if (minSize < 3) {
            violations.add(String.format(
                "Minimum cluster size (%d) is too small (minimum 3 for crash tolerance, 4 for Byzantine)",
                minSize));
        }

        // Note: n=3 provides crash tolerance (2f+1, f=1) but not Byzantine tolerance (3f+1, f=1)
        // This is acceptable for DEVELOPMENT profile but logged for awareness

        // Warn if cluster size is not optimal for Byzantine tolerance
        // Optimal sizes are 3f+1: 4, 7, 10, 13, 16, 19, 22, etc.
        if (minSize > 3) {
            var f = (minSize - 1) / 3;  // Maximum f for this n
            var optimal = 3 * f + 1;
            if (minSize != optimal) {
                // Only allow n=3f+1 as valid (n=3f violates Byzantine tolerance)
                violations.add(String.format(
                    "Cluster size %d is not optimal for Byzantine tolerance (required: %d for f=%d)",
                    minSize, optimal, f));
            }
        }
    }

    /**
     * Validate log level is recognized.
     */
    private static void validateLogLevel(ConfigurationProfile profile, List<String> violations) {
        var logLevel = profile.getLogLevel();
        if (logLevel == null || logLevel.isBlank()) {
            violations.add("Log level must not be null or blank");
        } else if (!VALID_LOG_LEVELS.contains(logLevel.toUpperCase())) {
            violations.add(String.format(
                "Invalid log level '%s' (valid: %s)",
                logLevel, String.join(", ", VALID_LOG_LEVELS)));
        }
    }

    /**
     * Validate Byzantine-specific settings are consistent.
     */
    private static void validateByzantineSettings(ConfigurationProfile profile, List<String> violations) {
        // If fault injection is enabled, Byzantine detection should be enabled
        if (profile.isFaultInjectionEnabled() && !profile.isByzantineDetectionAggressive()) {
            violations.add(
                "Fault injection enabled but Byzantine detection not aggressive (detection may miss injected faults)");
        }

        // If Byzantine detection is aggressive, state validation should be enabled
        if (profile.isByzantineDetectionAggressive() && !profile.isStateValidationEnabled()) {
            violations.add(
                "Byzantine detection aggressive but state validation disabled (detection may be incomplete)");
        }

        // Production profile should not have fault injection enabled
        if (profile.isProduction() && profile.isFaultInjectionEnabled()) {
            violations.add("Production profile should not have fault injection enabled");
        }

        // Production profile should not have aggressive Byzantine detection
        if (profile.isProduction() && profile.isByzantineDetectionAggressive()) {
            violations.add("Production profile should not have aggressive Byzantine detection (high false positive rate)");
        }
    }

    /**
     * Validate profile and throw if invalid.
     *
     * @param profile the profile to validate
     * @throws IllegalStateException if validation fails
     */
    public static void validateOrThrow(ConfigurationProfile profile) {
        var result = validate(profile);
        if (!result.isValid()) {
            throw new IllegalStateException(
                String.format("Configuration profile %s is invalid:\n%s",
                    profile.getName(), result.getViolationSummary()));
        }
    }

    /**
     * Calculate the maximum Byzantine fault tolerance level for a cluster size.
     *
     * @param clusterSize the cluster size
     * @return maximum f such that n ≥ 3f+1
     */
    public static int maxByzantineFaults(int clusterSize) {
        if (clusterSize < 3) {
            return 0;
        }
        return (clusterSize - 1) / 3;
    }

    /**
     * Calculate the minimum cluster size for a desired fault tolerance level.
     *
     * @param faultTolerance the desired f
     * @return minimum n such that n ≥ 3f+1
     */
    public static int minClusterSize(int faultTolerance) {
        if (faultTolerance < 0) {
            throw new IllegalArgumentException("Fault tolerance must be non-negative");
        }
        return 3 * faultTolerance + 1;
    }

    /**
     * Check if a cluster size satisfies Byzantine fault tolerance for f failures.
     *
     * @param clusterSize the cluster size
     * @param faultTolerance the desired f
     * @return true if n ≥ 3f+1
     */
    public static boolean satisfiesByzantineTolerance(int clusterSize, int faultTolerance) {
        return clusterSize >= minClusterSize(faultTolerance);
    }
}
