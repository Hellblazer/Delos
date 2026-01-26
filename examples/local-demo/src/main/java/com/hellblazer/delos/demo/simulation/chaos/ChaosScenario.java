/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.chaos;

import java.time.Duration;

/**
 * Interface for chaos engineering scenarios.
 * <p>
 * A chaos scenario follows a three-phase lifecycle:
 * <ol>
 *   <li>inject() - Apply fault to the system</li>
 *   <li>recover() - Reverse the fault</li>
 *   <li>isRecovered() - Verify system returned to healthy state</li>
 * </ol>
 * <p>
 * Implementations must be idempotent and handle partial failures gracefully.
 *
 * @author hal.hildebrand
 */
public interface ChaosScenario {

    /**
     * Get the name of this chaos scenario.
     */
    String getName();

    /**
     * Inject the fault into the system.
     *
     * @throws Exception if fault injection fails
     */
    void inject() throws Exception;

    /**
     * Recover from the fault and restore system to normal operation.
     *
     * @throws Exception if recovery fails
     */
    void recover() throws Exception;

    /**
     * Check if the system has fully recovered from the fault.
     *
     * @return true if system is healthy, false otherwise
     */
    boolean isRecovered();

    /**
     * Get the target duration for this scenario.
     * <p>
     * This is the expected time from injection to full recovery.
     */
    Duration getTargetDuration();
}
