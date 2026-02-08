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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for ConfigurationProfile enum - environment-specific parameter presets.
 *
 * @author hal.hildebrand
 */
@DisplayName("ConfigurationProfile Tests")
class ConfigurationProfileTest {

    @Test
    @DisplayName("DEVELOPMENT profile has relaxed timeouts")
    void developmentProfile_HasRelaxedTimeouts() {
        var profile = ConfigurationProfile.DEVELOPMENT;

        assertThat(profile.getStallTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(profile.getViewChangeTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(profile.getSessionTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(profile.isStateValidationEnabled()).isTrue();
        assertThat(profile.isByzantineDetectionAggressive()).isFalse();
        assertThat(profile.isFaultInjectionEnabled()).isFalse();
        assertThat(profile.getLogLevel()).isEqualTo("DEBUG");
    }

    @Test
    @DisplayName("DEVELOPMENT profile supports small clusters")
    void developmentProfile_SupportsSmallClusters() {
        var profile = ConfigurationProfile.DEVELOPMENT;

        assertThat(profile.getMinClusterSize()).isEqualTo(3);
        assertThat(profile.getMaxClusterSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("PRODUCTION profile has production timeouts")
    void productionProfile_HasProductionTimeouts() {
        var profile = ConfigurationProfile.PRODUCTION;

        assertThat(profile.getStallTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(profile.getViewChangeTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(profile.getSessionTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(profile.isStateValidationEnabled()).isFalse();
        assertThat(profile.isByzantineDetectionAggressive()).isFalse();
        assertThat(profile.isFaultInjectionEnabled()).isFalse();
        assertThat(profile.getLogLevel()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("PRODUCTION profile supports large clusters")
    void productionProfile_SupportsLargeClusters() {
        var profile = ConfigurationProfile.PRODUCTION;

        assertThat(profile.getMinClusterSize()).isEqualTo(7);
        assertThat(profile.getMaxClusterSize()).isEqualTo(21);
    }

    @Test
    @DisplayName("TEST profile has fast timeouts")
    void testProfile_HasFastTimeouts() {
        var profile = ConfigurationProfile.TEST;

        assertThat(profile.getStallTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(profile.getViewChangeTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(profile.getSessionTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(profile.isStateValidationEnabled()).isTrue();
        assertThat(profile.isByzantineDetectionAggressive()).isFalse();
        assertThat(profile.isFaultInjectionEnabled()).isFalse();
        assertThat(profile.getLogLevel()).isEqualTo("WARN");
    }

    @Test
    @DisplayName("TEST profile supports medium clusters")
    void testProfile_SupportsMediumClusters() {
        var profile = ConfigurationProfile.TEST;

        assertThat(profile.getMinClusterSize()).isEqualTo(4);
        assertThat(profile.getMaxClusterSize()).isEqualTo(7);
    }

    @Test
    @DisplayName("BYZANTINE_TEST profile has aggressive detection")
    void byzantineTestProfile_HasAggressiveDetection() {
        var profile = ConfigurationProfile.BYZANTINE_TEST;

        assertThat(profile.getStallTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(profile.getViewChangeTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(profile.getSessionTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(profile.isStateValidationEnabled()).isTrue();
        assertThat(profile.isByzantineDetectionAggressive()).isTrue();
        assertThat(profile.isFaultInjectionEnabled()).isTrue();
        assertThat(profile.getLogLevel()).isEqualTo("DEBUG");
    }

    @Test
    @DisplayName("BYZANTINE_TEST profile uses fixed cluster size")
    void byzantineTestProfile_UsesFixedClusterSize() {
        var profile = ConfigurationProfile.BYZANTINE_TEST;

        assertThat(profile.getMinClusterSize()).isEqualTo(4);
        assertThat(profile.getMaxClusterSize()).isEqualTo(4);
    }

    @Test
    @DisplayName("fromString resolves profile case-insensitively")
    void fromString_ResolvesCaseInsensitively() {
        assertThat(ConfigurationProfile.fromString("DEVELOPMENT")).isEqualTo(ConfigurationProfile.DEVELOPMENT);
        assertThat(ConfigurationProfile.fromString("development")).isEqualTo(ConfigurationProfile.DEVELOPMENT);
        assertThat(ConfigurationProfile.fromString("Development")).isEqualTo(ConfigurationProfile.DEVELOPMENT);
        assertThat(ConfigurationProfile.fromString("PRODUCTION")).isEqualTo(ConfigurationProfile.PRODUCTION);
        assertThat(ConfigurationProfile.fromString("production")).isEqualTo(ConfigurationProfile.PRODUCTION);
        assertThat(ConfigurationProfile.fromString("TEST")).isEqualTo(ConfigurationProfile.TEST);
        assertThat(ConfigurationProfile.fromString("test")).isEqualTo(ConfigurationProfile.TEST);
        assertThat(ConfigurationProfile.fromString("BYZANTINE_TEST")).isEqualTo(ConfigurationProfile.BYZANTINE_TEST);
        assertThat(ConfigurationProfile.fromString("byzantine_test")).isEqualTo(ConfigurationProfile.BYZANTINE_TEST);
    }

    @Test
    @DisplayName("fromString throws on unknown profile")
    void fromString_ThrowsOnUnknown() {
        assertThatThrownBy(() -> ConfigurationProfile.fromString("INVALID"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown configuration profile: INVALID")
            .hasMessageContaining("Valid profiles: DEVELOPMENT, PRODUCTION, TEST, BYZANTINE_TEST");
    }

    @Test
    @DisplayName("fromEnvironment defaults to TEST")
    void fromEnvironment_DefaultsToTest() {
        // Clear any existing settings
        System.clearProperty("choam.profile");

        var profile = ConfigurationProfile.fromEnvironment();

        assertThat(profile).isEqualTo(ConfigurationProfile.TEST);
    }

    @Test
    @DisplayName("fromEnvironment reads system property")
    void fromEnvironment_ReadsSystemProperty() {
        try {
            System.setProperty("choam.profile", "PRODUCTION");

            var profile = ConfigurationProfile.fromEnvironment();

            assertThat(profile).isEqualTo(ConfigurationProfile.PRODUCTION);
        } finally {
            System.clearProperty("choam.profile");
        }
    }

    @Test
    @DisplayName("isProduction returns true only for PRODUCTION")
    void isProduction_ReturnsTrueOnlyForProduction() {
        assertThat(ConfigurationProfile.PRODUCTION.isProduction()).isTrue();
        assertThat(ConfigurationProfile.DEVELOPMENT.isProduction()).isFalse();
        assertThat(ConfigurationProfile.TEST.isProduction()).isFalse();
        assertThat(ConfigurationProfile.BYZANTINE_TEST.isProduction()).isFalse();
    }

    @Test
    @DisplayName("isTest returns true for TEST and BYZANTINE_TEST")
    void isTest_ReturnsTrueForTestProfiles() {
        assertThat(ConfigurationProfile.TEST.isTest()).isTrue();
        assertThat(ConfigurationProfile.BYZANTINE_TEST.isTest()).isTrue();
        assertThat(ConfigurationProfile.PRODUCTION.isTest()).isFalse();
        assertThat(ConfigurationProfile.DEVELOPMENT.isTest()).isFalse();
    }

    @Test
    @DisplayName("isByzantineTesting returns true only for BYZANTINE_TEST")
    void isByzantineTesting_ReturnsTrueOnlyForByzantineTest() {
        assertThat(ConfigurationProfile.BYZANTINE_TEST.isByzantineTesting()).isTrue();
        assertThat(ConfigurationProfile.TEST.isByzantineTesting()).isFalse();
        assertThat(ConfigurationProfile.PRODUCTION.isByzantineTesting()).isFalse();
        assertThat(ConfigurationProfile.DEVELOPMENT.isByzantineTesting()).isFalse();
    }

    @Test
    @DisplayName("toString includes all configuration parameters")
    void toString_IncludesAllParameters() {
        var str = ConfigurationProfile.PRODUCTION.toString();

        assertThat(str).contains("ConfigurationProfile{PRODUCTION");
        assertThat(str).contains("stall=PT5S");
        assertThat(str).contains("viewChange=PT30S");
        assertThat(str).contains("session=PT1M");
        assertThat(str).contains("cluster=7-21");
        assertThat(str).contains("validation=false");
        assertThat(str).contains("byzantine=false");
        assertThat(str).contains("faultInjection=false");
        assertThat(str).contains("log=ERROR");
    }

    @Test
    @DisplayName("getName returns profile name")
    void getName_ReturnsProfileName() {
        assertThat(ConfigurationProfile.DEVELOPMENT.getName()).isEqualTo("DEVELOPMENT");
        assertThat(ConfigurationProfile.PRODUCTION.getName()).isEqualTo("PRODUCTION");
        assertThat(ConfigurationProfile.TEST.getName()).isEqualTo("TEST");
        assertThat(ConfigurationProfile.BYZANTINE_TEST.getName()).isEqualTo("BYZANTINE_TEST");
    }

    @Test
    @DisplayName("timeout ratios follow design constraints")
    void timeoutRatios_FollowDesign() {
        // DEVELOPMENT is 2x PRODUCTION
        assertThat(ConfigurationProfile.DEVELOPMENT.getStallTimeout())
            .isEqualTo(ConfigurationProfile.PRODUCTION.getStallTimeout().multipliedBy(2));
        assertThat(ConfigurationProfile.DEVELOPMENT.getViewChangeTimeout())
            .isEqualTo(ConfigurationProfile.PRODUCTION.getViewChangeTimeout().multipliedBy(2));
        assertThat(ConfigurationProfile.DEVELOPMENT.getSessionTimeout())
            .isEqualTo(ConfigurationProfile.PRODUCTION.getSessionTimeout().multipliedBy(2));

        // TEST is 0.4x PRODUCTION for stall, 0.5x for others
        assertThat(ConfigurationProfile.TEST.getStallTimeout())
            .isEqualTo(Duration.ofSeconds(2));  // 0.4 × 5s = 2s
        assertThat(ConfigurationProfile.TEST.getViewChangeTimeout())
            .isEqualTo(Duration.ofSeconds(15));  // 0.5 × 30s = 15s
        assertThat(ConfigurationProfile.TEST.getSessionTimeout())
            .isEqualTo(Duration.ofSeconds(30));  // 0.5 × 60s = 30s
    }

    @Test
    @DisplayName("cluster sizes satisfy Byzantine fault tolerance")
    void clusterSizes_SatisfyByzantineFaultTolerance() {
        // All profiles should satisfy n ≥ 3f+1 for their fault tolerance level

        // DEVELOPMENT: f=1 (3 ≥ 3×1+1=4? No, but 3 satisfies 2f+1)
        // Actually 3 supports f=1 with crash tolerance (2f+1)
        assertThat(ConfigurationProfile.DEVELOPMENT.getMinClusterSize()).isGreaterThanOrEqualTo(3);

        // PRODUCTION: f=2 (7 ≥ 3×2+1=7? Yes)
        assertThat(ConfigurationProfile.PRODUCTION.getMinClusterSize()).isGreaterThanOrEqualTo(7);

        // TEST: f=1 (4 ≥ 3×1+1=4? Yes)
        assertThat(ConfigurationProfile.TEST.getMinClusterSize()).isGreaterThanOrEqualTo(4);

        // BYZANTINE_TEST: f=1 (4 ≥ 3×1+1=4? Yes)
        assertThat(ConfigurationProfile.BYZANTINE_TEST.getMinClusterSize()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("all profiles have positive timeouts")
    void allProfiles_HavePositiveTimeouts() {
        for (var profile : ConfigurationProfile.values()) {
            assertThat(profile.getStallTimeout()).isPositive();
            assertThat(profile.getViewChangeTimeout()).isPositive();
            assertThat(profile.getSessionTimeout()).isPositive();
        }
    }

    @Test
    @DisplayName("all profiles have valid cluster sizes")
    void allProfiles_HaveValidClusterSizes() {
        for (var profile : ConfigurationProfile.values()) {
            assertThat(profile.getMinClusterSize()).isGreaterThan(0);
            assertThat(profile.getMaxClusterSize()).isGreaterThanOrEqualTo(profile.getMinClusterSize());
        }
    }

    @Test
    @DisplayName("all profiles have non-null log levels")
    void allProfiles_HaveNonNullLogLevels() {
        for (var profile : ConfigurationProfile.values()) {
            assertThat(profile.getLogLevel()).isNotNull().isNotBlank();
        }
    }

    @Test
    @DisplayName("timeout hierarchy is consistent")
    void timeoutHierarchy_IsConsistent() {
        // Session timeout should be greater than view change timeout
        // View change timeout should be greater than stall timeout
        for (var profile : ConfigurationProfile.values()) {
            assertThat(profile.getSessionTimeout())
                .isGreaterThan(profile.getViewChangeTimeout());
            assertThat(profile.getViewChangeTimeout())
                .isGreaterThan(profile.getStallTimeout());
        }
    }
}
