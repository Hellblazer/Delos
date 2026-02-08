/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Configuration profiles providing environment-specific parameter presets for CHOAM.
 * <p>
 * Profiles:
 * - DEVELOPMENT: Relaxed timeouts, verbose logging, small clusters
 * - PRODUCTION: Production timeouts, minimal logging, large clusters
 * - TEST: Fast timeouts, deterministic parameters, test-friendly settings
 * - BYZANTINE_TEST: Aggressive Byzantine detection, fault injection enabled
 * <p>
 * Usage:
 * <pre>
 * var params = Parameters.Builder.from(ConfigurationProfile.PRODUCTION)
 *     .withCustomTimeout(Duration.ofSeconds(10))  // Override specific params
 *     .build();
 * </pre>
 *
 * @author hal.hildebrand
 */
public enum ConfigurationProfile {
    /**
     * Development profile: Relaxed timeouts, verbose logging, small clusters.
     * Suitable for local development and debugging.
     */
    DEVELOPMENT(
        "DEVELOPMENT",
        Duration.ofSeconds(10),      // stallTimeout (2x production)
        Duration.ofSeconds(60),      // viewChangeTimeout (2x production)
        Duration.ofSeconds(120),     // sessionTimeout (2x production)
        3,                           // minClusterSize (f=1 tolerance)
        5,                           // maxClusterSize (f=1 tolerance)
        true,                        // stateValidationEnabled
        false,                       // byzantineDetectionAggressive
        false,                       // faultInjectionEnabled
        "DEBUG"                      // logLevel
    ),

    /**
     * Production profile: Production timeouts, error-level logging, large clusters.
     * Optimized for performance and low false-positive rates.
     */
    PRODUCTION(
        "PRODUCTION",
        Duration.ofSeconds(5),       // stallTimeout (baseline)
        Duration.ofSeconds(30),      // viewChangeTimeout (baseline)
        Duration.ofSeconds(60),      // sessionTimeout (baseline)
        7,                           // minClusterSize (f=2 tolerance)
        21,                          // maxClusterSize (f=6 tolerance)
        false,                       // stateValidationEnabled (performance)
        false,                       // byzantineDetectionAggressive (low false positives)
        false,                       // faultInjectionEnabled
        "ERROR"                      // logLevel (minimal)
    ),

    /**
     * Test profile: Fast timeouts, deterministic parameters, test-friendly settings.
     * Optimized for unit and integration test execution speed.
     */
    TEST(
        "TEST",
        Duration.ofSeconds(2),       // stallTimeout (0.4x production - fast failure)
        Duration.ofSeconds(15),      // viewChangeTimeout (0.5x production)
        Duration.ofSeconds(30),      // sessionTimeout (0.5x production)
        4,                           // minClusterSize (f=1 tolerance)
        7,                           // maxClusterSize (f=2 tolerance)
        true,                        // stateValidationEnabled (test verification)
        false,                       // byzantineDetectionAggressive (disabled unless Byzantine test)
        false,                       // faultInjectionEnabled
        "WARN"                       // logLevel (reduce test output noise)
    ),

    /**
     * Byzantine test profile: Aggressive detection, strict validation, fault injection.
     * For testing Byzantine fault tolerance with intentional fault injection.
     */
    BYZANTINE_TEST(
        "BYZANTINE_TEST",
        Duration.ofSeconds(2),       // stallTimeout (0.4x production - fast detection)
        Duration.ofSeconds(15),      // viewChangeTimeout (0.5x production)
        Duration.ofSeconds(30),      // sessionTimeout (0.5x production)
        4,                           // minClusterSize (3f+1, f=1)
        4,                           // maxClusterSize (exactly 4 for deterministic Byzantine testing)
        true,                        // stateValidationEnabled (enforced - throw on violations)
        true,                        // byzantineDetectionAggressive (low tolerance)
        true,                        // faultInjectionEnabled (ByzantineTestFramework enabled)
        "DEBUG"                      // logLevel (verbose for Byzantine indicators)
    );

    private static final Logger log = LoggerFactory.getLogger(ConfigurationProfile.class);

    private final String   name;
    private final Duration stallTimeout;
    private final Duration viewChangeTimeout;
    private final Duration sessionTimeout;
    private final int      minClusterSize;
    private final int      maxClusterSize;
    private final boolean  stateValidationEnabled;
    private final boolean  byzantineDetectionAggressive;
    private final boolean  faultInjectionEnabled;
    private final String   logLevel;

    static {
        // Validate all profiles at class initialization (defense in depth)
        for (var profile : values()) {
            ProfileValidator.validateOrThrow(profile);
        }
    }

    ConfigurationProfile(String name, Duration stallTimeout, Duration viewChangeTimeout,
                        Duration sessionTimeout, int minClusterSize, int maxClusterSize,
                        boolean stateValidationEnabled, boolean byzantineDetectionAggressive,
                        boolean faultInjectionEnabled, String logLevel) {
        this.name = name;
        this.stallTimeout = stallTimeout;
        this.viewChangeTimeout = viewChangeTimeout;
        this.sessionTimeout = sessionTimeout;
        this.minClusterSize = minClusterSize;
        this.maxClusterSize = maxClusterSize;
        this.stateValidationEnabled = stateValidationEnabled;
        this.byzantineDetectionAggressive = byzantineDetectionAggressive;
        this.faultInjectionEnabled = faultInjectionEnabled;
        this.logLevel = logLevel;
    }

    /**
     * Get profile by name (case-insensitive).
     *
     * @param name the profile name
     * @return the matching profile
     * @throws IllegalArgumentException if no profile matches
     */
    public static ConfigurationProfile fromString(String name) {
        for (var profile : values()) {
            if (profile.name.equalsIgnoreCase(name)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown configuration profile: " + name +
            ". Valid profiles: DEVELOPMENT, PRODUCTION, TEST, BYZANTINE_TEST");
    }

    /**
     * Get profile from system property, environment variable, or default.
     * <p>
     * Resolution order:
     * 1. System property: -Dchoam.profile=PRODUCTION
     * 2. Environment variable: CHOAM_PROFILE=PRODUCTION
     * 3. Default: TEST (safe default for most environments)
     * <p>
     * Security considerations:
     * - System properties and environment variables are trusted inputs
     * - Profile names are validated against enum values (injection protection)
     * - Default to TEST profile (fail-safe, not fail-secure)
     * - Production deployments MUST explicitly set profile
     *
     * @return the resolved profile
     */
    public static ConfigurationProfile fromEnvironment() {
        // Try system property first
        var sysProp = System.getProperty("choam.profile");
        if (sysProp != null) {
            var profile = fromString(sysProp);
            log.info("Loaded configuration profile from system property: {}", profile.getName());
            return profile;
        }

        // Try environment variable
        var envVar = System.getenv("CHOAM_PROFILE");
        if (envVar != null) {
            var profile = fromString(envVar);
            log.info("Loaded configuration profile from environment variable: {}", profile.getName());
            return profile;
        }

        // Default to TEST (safe for most use cases)
        log.warn("No configuration profile specified, defaulting to TEST. " +
                 "Set -Dchoam.profile=PRODUCTION or CHOAM_PROFILE=PRODUCTION for production deployment.");
        return TEST;
    }

    public String getName() {
        return name;
    }

    public Duration getStallTimeout() {
        return stallTimeout;
    }

    public Duration getViewChangeTimeout() {
        return viewChangeTimeout;
    }

    public Duration getSessionTimeout() {
        return sessionTimeout;
    }

    public int getMinClusterSize() {
        return minClusterSize;
    }

    public int getMaxClusterSize() {
        return maxClusterSize;
    }

    public boolean isStateValidationEnabled() {
        return stateValidationEnabled;
    }

    public boolean isByzantineDetectionAggressive() {
        return byzantineDetectionAggressive;
    }

    public boolean isFaultInjectionEnabled() {
        return faultInjectionEnabled;
    }

    public String getLogLevel() {
        return logLevel;
    }

    /**
     * Check if this profile is suitable for production deployment.
     *
     * @return true if PRODUCTION profile
     */
    public boolean isProduction() {
        return this == PRODUCTION;
    }

    /**
     * Check if this profile is suitable for testing.
     *
     * @return true if TEST or BYZANTINE_TEST profile
     */
    public boolean isTest() {
        return this == TEST || this == BYZANTINE_TEST;
    }

    /**
     * Check if this profile enables Byzantine testing features.
     *
     * @return true if BYZANTINE_TEST profile
     */
    public boolean isByzantineTesting() {
        return this == BYZANTINE_TEST;
    }

    @Override
    public String toString() {
        return String.format("ConfigurationProfile{%s, stall=%s, viewChange=%s, session=%s, " +
                           "cluster=%d-%d, validation=%s, byzantine=%s, faultInjection=%s, log=%s}",
            name, stallTimeout, viewChangeTimeout, sessionTimeout,
            minClusterSize, maxClusterSize, stateValidationEnabled,
            byzantineDetectionAggressive, faultInjectionEnabled, logLevel);
    }
}
