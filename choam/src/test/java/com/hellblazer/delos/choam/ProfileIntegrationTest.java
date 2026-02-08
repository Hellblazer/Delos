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

/**
 * Integration tests for ConfigurationProfile with Parameters.Builder.
 * Verifies that profiles correctly initialize Parameters with appropriate defaults.
 *
 * @author hal.hildebrand
 */
@DisplayName("Profile Integration Tests")
class ProfileIntegrationTest {

    @Test
    @DisplayName("from(PRODUCTION) initializes production-optimized parameters")
    void fromProduction_InitializesCorrectly() {
        var builder = Parameters.Builder.from(ConfigurationProfile.PRODUCTION);

        // Submit timeout maps from session timeout
        assertThat(builder.getSubmitTimeout()).isEqualTo(Duration.ofSeconds(60));

        // Production should have longer cycles
        assertThat(builder.getSynchronizationCycles()).isEqualTo(15);
        assertThat(builder.getRegenerationCycles()).isEqualTo(30);

        // Production should allow more memory use
        assertThat(builder.getMinFreeMemoryRatio()).isEqualTo(0.10);
        assertThat(builder.getMaxCachedCheckpoints()).isEqualTo(10);

        // Production should handle more pending work
        assertThat(builder.getMaxPendingBlocks()).isEqualTo(5000);
        assertThat(builder.getMaxSyncAttempts()).isEqualTo(15);

        // Gossip duration should be standard (not fast)
        assertThat(builder.getGossipDuration()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("from(TEST) initializes test-optimized parameters")
    void fromTest_InitializesCorrectly() {
        var builder = Parameters.Builder.from(ConfigurationProfile.TEST);

        // Submit timeout maps from session timeout
        assertThat(builder.getSubmitTimeout()).isEqualTo(Duration.ofSeconds(30));

        // Test should have shorter cycles for speed
        assertThat(builder.getSynchronizationCycles()).isEqualTo(5);
        assertThat(builder.getRegenerationCycles()).isEqualTo(10);

        // Test should be conservative with memory
        assertThat(builder.getMinFreeMemoryRatio()).isEqualTo(0.20);
        assertThat(builder.getMaxCachedCheckpoints()).isEqualTo(3);

        // Test should have smaller queues
        assertThat(builder.getMaxPendingBlocks()).isEqualTo(500);
        assertThat(builder.getMaxSyncAttempts()).isEqualTo(5);

        // Gossip duration should be faster for tests
        assertThat(builder.getGossipDuration()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("from(DEVELOPMENT) initializes development parameters")
    void fromDevelopment_InitializesCorrectly() {
        var builder = Parameters.Builder.from(ConfigurationProfile.DEVELOPMENT);

        // Submit timeout maps from session timeout
        assertThat(builder.getSubmitTimeout()).isEqualTo(Duration.ofSeconds(120));

        // Development should use default cycles
        assertThat(builder.getSynchronizationCycles()).isEqualTo(10);
        assertThat(builder.getRegenerationCycles()).isEqualTo(20);

        // Development should use default memory settings
        assertThat(builder.getMinFreeMemoryRatio()).isEqualTo(0.15);
        assertThat(builder.getMaxCachedCheckpoints()).isEqualTo(5);

        // Development should use default queues
        assertThat(builder.getMaxPendingBlocks()).isEqualTo(1000);
        assertThat(builder.getMaxSyncAttempts()).isEqualTo(10);

        // Gossip duration should be standard (not fast)
        assertThat(builder.getGossipDuration()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("from(BYZANTINE_TEST) initializes Byzantine test parameters")
    void fromByzantineTest_InitializesCorrectly() {
        var builder = Parameters.Builder.from(ConfigurationProfile.BYZANTINE_TEST);

        // Byzantine test is a test profile, so should have test settings
        assertThat(builder.getSubmitTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(builder.getSynchronizationCycles()).isEqualTo(5);
        assertThat(builder.getRegenerationCycles()).isEqualTo(10);
        assertThat(builder.getGossipDuration()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("from(profile) configures bootstrap parameters")
    void fromProfile_ConfiguresBootstrap() {
        var prodBuilder = Parameters.Builder.from(ConfigurationProfile.PRODUCTION);
        var testBuilder = Parameters.Builder.from(ConfigurationProfile.TEST);

        // Production bootstrap should allow more blocks
        assertThat(prodBuilder.getBootstrap().maxViewBlocks()).isEqualTo(100);
        assertThat(prodBuilder.getBootstrap().maxSyncBlocks()).isEqualTo(100);

        // Test bootstrap should allow fewer blocks for speed
        assertThat(testBuilder.getBootstrap().maxViewBlocks()).isEqualTo(50);
        assertThat(testBuilder.getBootstrap().maxSyncBlocks()).isEqualTo(50);

        // Gossip duration should match builder
        assertThat(prodBuilder.getBootstrap().gossipDuration())
            .isEqualTo(prodBuilder.getGossipDuration());
        assertThat(testBuilder.getBootstrap().gossipDuration())
            .isEqualTo(testBuilder.getGossipDuration());
    }

    @Test
    @DisplayName("from(profile) configures producer parameters")
    void fromProfile_ConfiguresProducer() {
        var prodBuilder = Parameters.Builder.from(ConfigurationProfile.PRODUCTION);
        var testBuilder = Parameters.Builder.from(ConfigurationProfile.TEST);

        // Test should have faster batch interval
        assertThat(testBuilder.getProducer().batchInterval())
            .isEqualTo(Duration.ofMillis(50));
        assertThat(prodBuilder.getProducer().batchInterval())
            .isEqualTo(Duration.ofMillis(100));

        // Test should have shorter gossip delay
        assertThat(testBuilder.getProducer().maxGossipDelay())
            .isEqualTo(Duration.ofSeconds(5));
        assertThat(prodBuilder.getProducer().maxGossipDelay())
            .isEqualTo(Duration.ofSeconds(10));

        // Gossip duration should match builder
        assertThat(prodBuilder.getProducer().gossipDuration())
            .isEqualTo(prodBuilder.getGossipDuration());
        assertThat(testBuilder.getProducer().gossipDuration())
            .isEqualTo(testBuilder.getGossipDuration());
    }

    @Test
    @DisplayName("from(profile) configures submit policy")
    void fromProfile_ConfiguresSubmitPolicy() {
        var prodBuilder = Parameters.Builder.from(ConfigurationProfile.PRODUCTION);
        var testBuilder = Parameters.Builder.from(ConfigurationProfile.TEST);
        var devBuilder = Parameters.Builder.from(ConfigurationProfile.DEVELOPMENT);

        // Test should have fast backoff
        var testPolicy = testBuilder.getSubmitPolicy();
        assertThat(testPolicy.getInitialBackoff()).isEqualTo(Duration.ofMillis(100));
        assertThat(testPolicy.getMaxBackoff()).isEqualTo(Duration.ofSeconds(2));
        assertThat(testPolicy.getMultiplier()).isEqualTo(1.5);

        // Production should have slower backoff
        var prodPolicy = prodBuilder.getSubmitPolicy();
        assertThat(prodPolicy.getInitialBackoff()).isEqualTo(Duration.ofSeconds(1));
        assertThat(prodPolicy.getMaxBackoff()).isEqualTo(Duration.ofSeconds(10));
        assertThat(prodPolicy.getMultiplier()).isEqualTo(2.0);

        // Development should have default backoff
        var devPolicy = devBuilder.getSubmitPolicy();
        assertThat(devPolicy.getInitialBackoff()).isEqualTo(Duration.ofMillis(500));
        assertThat(devPolicy.getMaxBackoff()).isEqualTo(Duration.ofSeconds(5));
        assertThat(devPolicy.getMultiplier()).isEqualTo(1.6);

        // All should have jitter
        assertThat(testPolicy.getJitter()).isEqualTo(0.2);
        assertThat(prodPolicy.getJitter()).isEqualTo(0.2);
        assertThat(devPolicy.getJitter()).isEqualTo(0.2);
    }

    @Test
    @DisplayName("from(profile) allows parameter overrides")
    void fromProfile_AllowsOverrides() {
        var builder = Parameters.Builder.from(ConfigurationProfile.TEST)
            .setSubmitTimeout(Duration.ofMinutes(5))
            .setMaxPendingBlocks(10000)
            .setMaxSyncAttempts(20);

        // Overrides should take effect
        assertThat(builder.getSubmitTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(builder.getMaxPendingBlocks()).isEqualTo(10000);
        assertThat(builder.getMaxSyncAttempts()).isEqualTo(20);

        // Non-overridden values should still use profile defaults
        assertThat(builder.getSynchronizationCycles()).isEqualTo(5);
        assertThat(builder.getGossipDuration()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    @DisplayName("from(profile) validates profile before use")
    void fromProfile_ValidatesProfile() {
        // All built-in profiles should be valid
        for (var profile : ConfigurationProfile.values()) {
            var builder = Parameters.Builder.from(profile);
            assertThat(builder).isNotNull();
        }
    }

    @Test
    @DisplayName("profile-based parameters maintain consistency")
    void profileParameters_MaintainConsistency() {
        for (var profile : ConfigurationProfile.values()) {
            var builder = Parameters.Builder.from(profile);

            // Bootstrap gossip duration should match builder
            assertThat(builder.getBootstrap().gossipDuration())
                .isEqualTo(builder.getGossipDuration());

            // Producer gossip duration should match builder
            assertThat(builder.getProducer().gossipDuration())
                .isEqualTo(builder.getGossipDuration());

            // Memory ratio should be valid
            assertThat(builder.getMinFreeMemoryRatio())
                .isBetween(0.0, 1.0);

            // Cached checkpoints should be valid
            assertThat(builder.getMaxCachedCheckpoints())
                .isBetween(1, 100);

            // Sync attempts should be at least 3 (circuit breaker minimum)
            assertThat(builder.getMaxSyncAttempts())
                .isGreaterThanOrEqualTo(3);

            // Pending blocks should be positive
            assertThat(builder.getMaxPendingBlocks())
                .isPositive();
        }
    }

    @Test
    @DisplayName("production profile optimizes for throughput")
    void productionProfile_OptimizesForThroughput() {
        var prodBuilder = Parameters.Builder.from(ConfigurationProfile.PRODUCTION);
        var testBuilder = Parameters.Builder.from(ConfigurationProfile.TEST);

        // Production should have larger queues
        assertThat(prodBuilder.getMaxPendingBlocks())
            .isGreaterThan(testBuilder.getMaxPendingBlocks());

        // Production should have more sync attempts
        assertThat(prodBuilder.getMaxSyncAttempts())
            .isGreaterThan(testBuilder.getMaxSyncAttempts());

        // Production should allow more memory use
        assertThat(prodBuilder.getMinFreeMemoryRatio())
            .isLessThan(testBuilder.getMinFreeMemoryRatio());

        // Production should cache more checkpoints
        assertThat(prodBuilder.getMaxCachedCheckpoints())
            .isGreaterThan(testBuilder.getMaxCachedCheckpoints());
    }

    @Test
    @DisplayName("test profiles optimize for speed")
    void testProfiles_OptimizeForSpeed() {
        var testBuilder = Parameters.Builder.from(ConfigurationProfile.TEST);
        var prodBuilder = Parameters.Builder.from(ConfigurationProfile.PRODUCTION);

        // Test should have faster gossip
        assertThat(testBuilder.getGossipDuration())
            .isLessThan(prodBuilder.getGossipDuration());

        // Test should have shorter cycles
        assertThat(testBuilder.getSynchronizationCycles())
            .isLessThan(prodBuilder.getSynchronizationCycles());
        assertThat(testBuilder.getRegenerationCycles())
            .isLessThan(prodBuilder.getRegenerationCycles());

        // Test should have faster batch interval
        assertThat(testBuilder.getProducer().batchInterval())
            .isLessThan(prodBuilder.getProducer().batchInterval());

        // Test should have smaller block limits for faster bootstrap
        assertThat(testBuilder.getBootstrap().maxViewBlocks())
            .isLessThan(prodBuilder.getBootstrap().maxViewBlocks());
    }
}
