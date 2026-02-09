/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

/**
 * JMX MBean interface for FeatureFlagManager.
 * Provides runtime control over CHOAM security feature flags.
 *
 * @author hal.hildebrand
 */
public interface FeatureFlagManagerMBean {
    /**
     * Check if a feature flag is enabled (by string name for JMX)
     */
    boolean isEnabled(String flagName);

    /**
     * Enable or disable a feature flag (by string name for JMX)
     */
    void setEnabled(String flagName, boolean enabled);

    /**
     * Reset a flag to its default value (remove runtime override)
     */
    void resetToDefault(String flagName);

    /**
     * Get current status of all feature flags
     */
    String getStatus();

    /**
     * Emergency: Disable all security features (rollback)
     */
    void rollbackAll();

    /**
     * Gradual rollout: Enable all features for given percentage of traffic
     * @param percentage 0-100
     */
    void enableForPercentage(int percentage);
}
