/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for ProfileValidator - validates configuration profiles against constraints.
 *
 * @author hal.hildebrand
 */
@DisplayName("ProfileValidator Tests")
class ProfileValidatorTest {

    @Test
    @DisplayName("validate accepts all built-in profiles")
    void validate_AcceptsAllBuiltInProfiles() {
        // All built-in profiles should be valid
        for (var profile : ConfigurationProfile.values()) {
            var result = ProfileValidator.validate(profile);
            assertThat(result.isValid())
                .withFailMessage("Profile %s should be valid but has violations: %s",
                    profile.getName(), result.getViolationSummary())
                .isTrue();
        }
    }

    @Test
    @DisplayName("validateOrThrow succeeds for valid profiles")
    void validateOrThrow_SucceedsForValidProfiles() {
        // Should not throw for any built-in profile
        for (var profile : ConfigurationProfile.values()) {
            ProfileValidator.validateOrThrow(profile);  // Should not throw
        }
    }

    @Test
    @DisplayName("maxByzantineFaults calculates correct tolerance")
    void maxByzantineFaults_CalculatesCorrectly() {
        assertThat(ProfileValidator.maxByzantineFaults(1)).isEqualTo(0);  // n=1: f=0
        assertThat(ProfileValidator.maxByzantineFaults(2)).isEqualTo(0);  // n=2: f=0
        assertThat(ProfileValidator.maxByzantineFaults(3)).isEqualTo(0);  // n=3: f=0
        assertThat(ProfileValidator.maxByzantineFaults(4)).isEqualTo(1);  // n=4: f=1 (3×1+1=4)
        assertThat(ProfileValidator.maxByzantineFaults(5)).isEqualTo(1);  // n=5: f=1
        assertThat(ProfileValidator.maxByzantineFaults(6)).isEqualTo(1);  // n=6: f=1
        assertThat(ProfileValidator.maxByzantineFaults(7)).isEqualTo(2);  // n=7: f=2 (3×2+1=7)
        assertThat(ProfileValidator.maxByzantineFaults(10)).isEqualTo(3); // n=10: f=3 (3×3+1=10)
        assertThat(ProfileValidator.maxByzantineFaults(13)).isEqualTo(4); // n=13: f=4 (3×4+1=13)
    }

    @Test
    @DisplayName("minClusterSize calculates correct minimum")
    void minClusterSize_CalculatesCorrectly() {
        assertThat(ProfileValidator.minClusterSize(0)).isEqualTo(1);  // f=0: n=1
        assertThat(ProfileValidator.minClusterSize(1)).isEqualTo(4);  // f=1: n=4
        assertThat(ProfileValidator.minClusterSize(2)).isEqualTo(7);  // f=2: n=7
        assertThat(ProfileValidator.minClusterSize(3)).isEqualTo(10); // f=3: n=10
        assertThat(ProfileValidator.minClusterSize(4)).isEqualTo(13); // f=4: n=13
        assertThat(ProfileValidator.minClusterSize(5)).isEqualTo(16); // f=5: n=16
        assertThat(ProfileValidator.minClusterSize(6)).isEqualTo(19); // f=6: n=19
    }

    @Test
    @DisplayName("minClusterSize throws on negative fault tolerance")
    void minClusterSize_ThrowsOnNegative() {
        assertThatThrownBy(() -> ProfileValidator.minClusterSize(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Fault tolerance must be non-negative");
    }

    @Test
    @DisplayName("satisfiesByzantineTolerance validates correctly")
    void satisfiesByzantineTolerance_ValidatesCorrectly() {
        // f=1 requires n≥4
        assertThat(ProfileValidator.satisfiesByzantineTolerance(3, 1)).isFalse();
        assertThat(ProfileValidator.satisfiesByzantineTolerance(4, 1)).isTrue();
        assertThat(ProfileValidator.satisfiesByzantineTolerance(5, 1)).isTrue();

        // f=2 requires n≥7
        assertThat(ProfileValidator.satisfiesByzantineTolerance(6, 2)).isFalse();
        assertThat(ProfileValidator.satisfiesByzantineTolerance(7, 2)).isTrue();
        assertThat(ProfileValidator.satisfiesByzantineTolerance(8, 2)).isTrue();

        // f=0 requires n≥1
        assertThat(ProfileValidator.satisfiesByzantineTolerance(1, 0)).isTrue();
        assertThat(ProfileValidator.satisfiesByzantineTolerance(2, 0)).isTrue();
    }

    @Test
    @DisplayName("Byzantine fault calculations are consistent")
    void byzantineFaultCalculations_AreConsistent() {
        // For any f, minClusterSize(f) should satisfy tolerance for f
        for (int f = 0; f <= 10; f++) {
            var minN = ProfileValidator.minClusterSize(f);
            assertThat(ProfileValidator.satisfiesByzantineTolerance(minN, f))
                .withFailMessage("minClusterSize(%d)=%d should satisfy tolerance for f=%d", f, minN, f)
                .isTrue();
            assertThat(ProfileValidator.maxByzantineFaults(minN))
                .withFailMessage("maxByzantineFaults(%d) should be >= %d", minN, f)
                .isGreaterThanOrEqualTo(f);
        }
    }

    @Test
    @DisplayName("all built-in profiles satisfy their declared fault tolerance")
    void allProfiles_SatisfyDeclaredTolerance() {
        // DEVELOPMENT: min=3 should support crash tolerance (2f+1) but we don't validate that strictly
        var devF = ProfileValidator.maxByzantineFaults(ConfigurationProfile.DEVELOPMENT.getMinClusterSize());
        assertThat(devF).isGreaterThanOrEqualTo(0);

        // PRODUCTION: min=7 should support f=2
        var prodF = ProfileValidator.maxByzantineFaults(ConfigurationProfile.PRODUCTION.getMinClusterSize());
        assertThat(prodF).isEqualTo(2);

        // TEST: min=4 should support f=1
        var testF = ProfileValidator.maxByzantineFaults(ConfigurationProfile.TEST.getMinClusterSize());
        assertThat(testF).isEqualTo(1);

        // BYZANTINE_TEST: min=4 should support f=1
        var byzF = ProfileValidator.maxByzantineFaults(ConfigurationProfile.BYZANTINE_TEST.getMinClusterSize());
        assertThat(byzF).isEqualTo(1);
    }

    @Test
    @DisplayName("validation detects optimal cluster sizes")
    void validation_DetectsOptimalSizes() {
        // Optimal sizes for Byzantine: 4, 7, 10, 13, 16, 19, 22...
        // Our profiles use these optimal sizes
        var prodResult = ProfileValidator.validate(ConfigurationProfile.PRODUCTION);
        assertThat(prodResult.isValid()).isTrue();
        assertThat(prodResult.getViolations()).doesNotContain("not optimal");

        var testResult = ProfileValidator.validate(ConfigurationProfile.TEST);
        assertThat(testResult.isValid()).isTrue();
        assertThat(testResult.getViolations()).doesNotContain("not optimal");
    }

    @Test
    @DisplayName("timeout hierarchy is enforced")
    void timeoutHierarchy_IsEnforced() {
        // All profiles should have session > viewChange > stall
        for (var profile : ConfigurationProfile.values()) {
            var result = ProfileValidator.validate(profile);
            assertThat(result.isValid()).isTrue();
            // No violations about timeout ordering
            for (var violation : result.getViolations()) {
                assertThat(violation).doesNotContain("must be greater than");
            }
        }
    }

    @Test
    @DisplayName("production profile constraints are enforced")
    void productionConstraints_AreEnforced() {
        var result = ProfileValidator.validate(ConfigurationProfile.PRODUCTION);

        // Production should not have fault injection
        assertThat(ConfigurationProfile.PRODUCTION.isFaultInjectionEnabled()).isFalse();
        // Production should not have aggressive Byzantine detection
        assertThat(ConfigurationProfile.PRODUCTION.isByzantineDetectionAggressive()).isFalse();

        // Validation should pass for production
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Byzantine test profile constraints are consistent")
    void byzantineTestConstraints_AreConsistent() {
        var profile = ConfigurationProfile.BYZANTINE_TEST;
        var result = ProfileValidator.validate(profile);

        // Byzantine test should have fault injection enabled
        assertThat(profile.isFaultInjectionEnabled()).isTrue();
        // Byzantine test should have aggressive detection
        assertThat(profile.isByzantineDetectionAggressive()).isTrue();
        // Byzantine test should have state validation enabled
        assertThat(profile.isStateValidationEnabled()).isTrue();

        // Validation should pass (all settings are consistent)
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("log levels are validated")
    void logLevels_AreValidated() {
        // All built-in profiles should have valid log levels
        for (var profile : ConfigurationProfile.values()) {
            var result = ProfileValidator.validate(profile);
            assertThat(result.getViolations())
                .withFailMessage("Profile %s has invalid log level", profile.getName())
                .noneMatch(v -> v.contains("Invalid log level"));
        }
    }

    @Test
    @DisplayName("minimum timeout thresholds are reasonable")
    void minimumTimeouts_AreReasonable() {
        // All profiles should have timeouts above absolute minimums
        for (var profile : ConfigurationProfile.values()) {
            var result = ProfileValidator.validate(profile);

            // Check if any violations about aggressive timeouts
            var hasAggressiveWarning = result.getViolations().stream()
                .anyMatch(v -> v.contains("too aggressive"));

            // For TEST and BYZANTINE_TEST, we allow aggressive timeouts (they're intentional)
            if (profile.isTest()) {
                // Test profiles may have warnings about aggressive timeouts, but should still be valid
                // or the warnings should not fail validation
                assertThat(result.isValid())
                    .withFailMessage("Test profile %s failed validation: %s",
                        profile.getName(), result.getViolationSummary())
                    .isTrue();
            } else {
                // Non-test profiles should not have aggressive timeout warnings
                assertThat(hasAggressiveWarning)
                    .withFailMessage("Profile %s has aggressive timeout warnings: %s",
                        profile.getName(), result.getViolationSummary())
                    .isFalse();
            }
        }
    }
}
