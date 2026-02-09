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

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime feature flag manager with JMX support.
 * Enables/disables CHOAM security features without restart.
 *
 * Usage:
 * <pre>
 * // Programmatic
 * FeatureFlagManager.getInstance().setEnabled(FeatureFlags.VERIFIER_VALIDATION, true);
 *
 * // System property (startup)
 * java -Dfeature.verifier.validation=true ...
 *
 * // JMX (runtime)
 * jconsole -> com.hellblazer.delos.choam:type=FeatureFlagManager
 * </pre>
 *
 * @author hal.hildebrand
 */
public class FeatureFlagManager implements FeatureFlagManagerMBean {
    private static final Logger                   log      = LoggerFactory.getLogger(FeatureFlagManager.class);
    private static final FeatureFlagManager       INSTANCE = new FeatureFlagManager();
    private final        Map<FeatureFlags, State> overrides;
    private              boolean                  jmxRegistered;

    private FeatureFlagManager() {
        this.overrides = new ConcurrentHashMap<>();
        registerJMX();
    }

    /**
     * @return Singleton instance
     */
    public static FeatureFlagManager getInstance() {
        return INSTANCE;
    }

    /**
     * Check if a feature flag is enabled (internal method).
     * Priority: Runtime override > System property > Default
     */
    public boolean isEnabled(FeatureFlags flag) {
        State state = overrides.get(flag);
        if (state != null) {
            return state.enabled;
        }

        String sysProp = System.getProperty(flag.getSystemProperty());
        if (sysProp != null) {
            return Boolean.parseBoolean(sysProp);
        }

        return flag.isDefaultEnabled();
    }

    /**
     * Enable or disable a feature at runtime (internal method).
     * Change takes effect immediately without restart.
     */
    public void setEnabled(FeatureFlags flag, boolean enabled) {
        State prev = overrides.put(flag, new State(enabled, System.currentTimeMillis()));
        log.info("Feature flag {} {} (was {})",
                 flag.name(),
                 enabled ? "ENABLED" : "DISABLED",
                 prev != null ? (prev.enabled ? "ENABLED" : "DISABLED") : "DEFAULT");
    }

    /**
     * Get enabled status for JMX (by flag name string)
     */
    @Override
    public boolean isEnabled(String flagName) {
        try {
            return isEnabled(FeatureFlags.valueOf(flagName));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown feature flag: " + flagName);
        }
    }

    /**
     * Set enabled status for JMX (by flag name string)
     */
    @Override
    public void setEnabled(String flagName, boolean enabled) {
        try {
            setEnabled(FeatureFlags.valueOf(flagName), enabled);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown feature flag: " + flagName);
        }
    }

    /**
     * Reset a flag to its default value (remove override)
     */
    @Override
    public void resetToDefault(String flagName) {
        try {
            FeatureFlags flag = FeatureFlags.valueOf(flagName);
            State prev = overrides.remove(flag);
            log.info("Feature flag {} reset to DEFAULT (was {})",
                     flag.name(),
                     prev != null ? (prev.enabled ? "ENABLED" : "DISABLED") : "DEFAULT");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown feature flag: " + flagName);
        }
    }

    /**
     * Get current status of all flags
     */
    @Override
    public String getStatus() {
        var sb = new StringBuilder();
        sb.append("Feature Flags Status:\n");
        for (FeatureFlags flag : FeatureFlags.values()) {
            boolean enabled = isEnabled(flag);
            String source = getSource(flag);
            sb.append(String.format("  %-30s: %s (%s) - %s\n",
                                   flag.name(),
                                   enabled ? "ENABLED" : "DISABLED",
                                   source,
                                   flag.getDescription()));
        }
        return sb.toString();
    }

    /**
     * Emergency: Disable all security features (rollback)
     */
    @Override
    public void rollbackAll() {
        log.warn("EMERGENCY ROLLBACK: Disabling all security features");
        for (FeatureFlags flag : FeatureFlags.values()) {
            setEnabled(flag, false);
        }
    }

    /**
     * Gradual rollout: Enable all features for given percentage of traffic
     * @param percentage 0-100
     */
    @Override
    public void enableForPercentage(int percentage) {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("Percentage must be 0-100");
        }

        log.info("Gradual rollout: enabling features for {}% of traffic", percentage);
        // In a real system, this would use consistent hashing on member IDs
        // For now, just enable/disable all flags based on threshold
        boolean enable = percentage >= 50; // Simple threshold for baseline
        for (FeatureFlags flag : FeatureFlags.values()) {
            setEnabled(flag, enable);
        }
    }

    private String getSource(FeatureFlags flag) {
        if (overrides.containsKey(flag)) {
            return "RUNTIME";
        }
        if (System.getProperty(flag.getSystemProperty()) != null) {
            return "SYSPROP";
        }
        return "DEFAULT";
    }

    private void registerJMX() {
        if (jmxRegistered) {
            return;
        }

        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("com.hellblazer.delos.choam:type=FeatureFlagManager");

            if (!mbs.isRegistered(name)) {
                mbs.registerMBean(this, name);
                log.info("FeatureFlagManager registered with JMX: {}", name);
                jmxRegistered = true;
            }
        } catch (MalformedObjectNameException | InstanceAlreadyExistsException |
                 MBeanRegistrationException | NotCompliantMBeanException e) {
            log.error("Failed to register FeatureFlagManager with JMX", e);
        }
    }

    private record State(boolean enabled, long timestamp) {}
}
