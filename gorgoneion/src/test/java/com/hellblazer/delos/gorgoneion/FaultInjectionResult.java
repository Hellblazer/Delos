/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.gorgoneion;

import java.time.Duration;
import java.time.Instant;

/**
 * Result of fault injection with restoration capability.
 * Enables undo of injected faults for test cleanup.
 * <p>
 * Usage:
 * <pre>{@code
 * var fault = simulateByzantineNodeFailure(cluster, nodeIndex);
 * try {
 *     // Test with fault active
 *     assertFailureDetected();
 * } finally {
 *     fault.restore(); // Clean up
 * }
 * }</pre>
 *
 * @author hal.hildebrand
 */
public class FaultInjectionResult {
    private final Runnable restore;
    private final Instant  injectedAt;
    private volatile boolean restored;

    /**
     * Creates a fault injection result with restoration callback.
     *
     * @param restore the restoration action to undo the fault
     */
    public FaultInjectionResult(Runnable restore) {
        this.restore = restore;
        this.injectedAt = Instant.now();
        this.restored = false;
    }

    /**
     * Restores the system state by undoing the injected fault.
     * Idempotent - safe to call multiple times.
     */
    public void restore() {
        if (!restored) {
            restored = true;
            restore.run();
        }
    }

    /**
     * Checks whether this fault has been restored.
     *
     * @return true if restore() has been called
     */
    public boolean isRestored() {
        return restored;
    }

    /**
     * Calculates the time elapsed since fault injection.
     *
     * @return duration from injection to current time
     */
    public Duration elapsed() {
        return Duration.between(injectedAt, Instant.now());
    }
}
