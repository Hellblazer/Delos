/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test runtime feature flag management for CHOAM security features.
 *
 * @author hal.hildebrand
 */
public class FeatureFlagManagerTest {

    private FeatureFlagManager manager;

    @BeforeEach
    public void setUp() {
        manager = FeatureFlagManager.getInstance();
        // Reset all flags to default before each test
        for (FeatureFlags flag : FeatureFlags.values()) {
            manager.resetToDefault(flag.name());
        }
    }

    @AfterEach
    public void tearDown() {
        // Clean up system properties
        for (FeatureFlags flag : FeatureFlags.values()) {
            System.clearProperty(flag.getSystemProperty());
        }
    }

    @Test
    public void testDefaultState() {
        // VERIFIER_VALIDATION now defaults to enabled (secure by default, Delos-izm.1.2)
        assertTrue(FeatureFlags.VERIFIER_VALIDATION.isEnabled(),
                   "Verifier validation should default to enabled (secure by default)");
        // Other Phase 1 security features still default to disabled for gradual rollout
        assertFalse(FeatureFlags.NONCE_PERSISTENCE.isEnabled(),
                   "Nonce persistence should default to disabled");
        assertFalse(FeatureFlags.QUEUE_EVICTION.isEnabled(),
                   "Queue eviction should default to disabled");
    }

    @Test
    public void testRuntimeToggle() {
        // VERIFIER_VALIDATION defaults to enabled (secure by default, Delos-izm.1.2)
        assertTrue(FeatureFlags.VERIFIER_VALIDATION.isEnabled());

        // Disable at runtime
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(false);
        assertFalse(FeatureFlags.VERIFIER_VALIDATION.isEnabled(),
                   "Should be disabled after runtime toggle");

        // Re-enable at runtime (no restart)
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);
        assertTrue(FeatureFlags.VERIFIER_VALIDATION.isEnabled(),
                  "Should be enabled after runtime toggle");

        // Verify other flags unaffected
        assertFalse(FeatureFlags.NONCE_PERSISTENCE.isEnabled());
        assertFalse(FeatureFlags.QUEUE_EVICTION.isEnabled());
    }

    @Test
    public void testSystemPropertyOverride() {
        // Set system property
        System.setProperty(FeatureFlags.NONCE_PERSISTENCE.getSystemProperty(), "true");

        // Reset manager override to respect system property
        manager.resetToDefault(FeatureFlags.NONCE_PERSISTENCE.name());

        // Verify system property is respected
        assertTrue(FeatureFlags.NONCE_PERSISTENCE.isEnabled(),
                  "Should be enabled via system property");

        // Runtime override takes precedence over system property
        FeatureFlags.NONCE_PERSISTENCE.setEnabled(false);
        assertFalse(FeatureFlags.NONCE_PERSISTENCE.isEnabled(),
                   "Runtime override should take precedence");
    }

    @Test
    public void testResetToDefault() {
        // Enable flag
        FeatureFlags.QUEUE_EVICTION.setEnabled(true);
        assertTrue(FeatureFlags.QUEUE_EVICTION.isEnabled());

        // Reset to default
        manager.resetToDefault(FeatureFlags.QUEUE_EVICTION.name());

        // Should return to default (disabled)
        assertFalse(FeatureFlags.QUEUE_EVICTION.isEnabled(),
                   "Should return to default after reset");
    }

    @Test
    public void testEmergencyRollback() {
        // Enable all flags
        for (FeatureFlags flag : FeatureFlags.values()) {
            flag.setEnabled(true);
        }

        // Verify all enabled
        for (FeatureFlags flag : FeatureFlags.values()) {
            assertTrue(flag.isEnabled(), flag.name() + " should be enabled");
        }

        // Emergency rollback
        manager.rollbackAll();

        // Verify all disabled
        for (FeatureFlags flag : FeatureFlags.values()) {
            assertFalse(flag.isEnabled(),
                       flag.name() + " should be disabled after rollback");
        }
    }

    @Test
    public void testGradualRollout() {
        // Week 1: 10% rollout (below threshold, all disabled)
        manager.enableForPercentage(10);
        assertFalse(FeatureFlags.VERIFIER_VALIDATION.isEnabled());
        assertFalse(FeatureFlags.NONCE_PERSISTENCE.isEnabled());
        assertFalse(FeatureFlags.QUEUE_EVICTION.isEnabled());

        // Week 2: 50% rollout (at threshold, all enabled)
        manager.enableForPercentage(50);
        assertTrue(FeatureFlags.VERIFIER_VALIDATION.isEnabled());
        assertTrue(FeatureFlags.NONCE_PERSISTENCE.isEnabled());
        assertTrue(FeatureFlags.QUEUE_EVICTION.isEnabled());

        // Week 3: 100% rollout (all enabled)
        manager.enableForPercentage(100);
        assertTrue(FeatureFlags.VERIFIER_VALIDATION.isEnabled());
        assertTrue(FeatureFlags.NONCE_PERSISTENCE.isEnabled());
        assertTrue(FeatureFlags.QUEUE_EVICTION.isEnabled());

        // Rollback: 0% (all disabled)
        manager.enableForPercentage(0);
        assertFalse(FeatureFlags.VERIFIER_VALIDATION.isEnabled());
        assertFalse(FeatureFlags.NONCE_PERSISTENCE.isEnabled());
        assertFalse(FeatureFlags.QUEUE_EVICTION.isEnabled());
    }

    @Test
    public void testGetStatus() {
        // Enable one flag
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);

        // Get status string
        String status = manager.getStatus();

        // Verify status contains all flags
        assertTrue(status.contains("VERIFIER_VALIDATION"));
        assertTrue(status.contains("NONCE_PERSISTENCE"));
        assertTrue(status.contains("QUEUE_EVICTION"));

        // Verify enabled/disabled states (format has padding and source info)
        assertTrue(status.contains("VERIFIER_VALIDATION") && status.contains("ENABLED"));
        assertTrue(status.contains("NONCE_PERSISTENCE") && status.contains("DISABLED"));
        assertTrue(status.contains("QUEUE_EVICTION"));
    }

    @Test
    public void testJMXStringInterface() {
        // Test string-based interface used by JMX
        manager.setEnabled("VERIFIER_VALIDATION", true);
        assertTrue(manager.isEnabled("VERIFIER_VALIDATION"));

        manager.setEnabled("VERIFIER_VALIDATION", false);
        assertFalse(manager.isEnabled("VERIFIER_VALIDATION"));
    }

    @Test
    public void testInvalidFlagName() {
        // Verify exception for invalid flag name
        assertThrows(IllegalArgumentException.class, () -> {
            manager.isEnabled("INVALID_FLAG");
        });

        assertThrows(IllegalArgumentException.class, () -> {
            manager.setEnabled("INVALID_FLAG", true);
        });
    }

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        // Test thread-safety of concurrent flag toggling
        var threads = new Thread[100];
        for (int i = 0; i < threads.length; i++) {
            final int index = i;
            threads[i] = Thread.ofVirtual().start(() -> {
                FeatureFlags flag = FeatureFlags.values()[index % FeatureFlags.values().length];
                flag.setEnabled(index % 2 == 0);
                boolean enabled = flag.isEnabled();
                // Just verify no exceptions thrown
                assertNotNull(enabled);
            });
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // Verify manager still functional after concurrent access
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);
        assertTrue(FeatureFlags.VERIFIER_VALIDATION.isEnabled());
    }
}
