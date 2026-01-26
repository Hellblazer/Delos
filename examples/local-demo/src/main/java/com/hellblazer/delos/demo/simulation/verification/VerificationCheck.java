/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.verification;

import java.time.Duration;

/**
 * Interface for verification checks executed during simulation.
 * <p>
 * Implementations verify specific aspects of cluster consistency such as:
 * <ul>
 *   <li>Block consensus across CHOAM replicas</li>
 *   <li>Fireflies membership view consistency</li>
 *   <li>SQL state machine checksums</li>
 * </ul>
 * <p>
 * All checks are subject to timeout constraints to prevent hanging the orchestrator.
 *
 * @author hal.hildebrand
 */
public interface VerificationCheck {

    /**
     * Get the name of this verification check.
     */
    String getName();

    /**
     * Execute the verification check.
     *
     * @return result of the verification
     */
    VerificationResult execute();

    /**
     * Get the timeout for this verification check.
     * <p>
     * Checks that exceed this duration will be terminated and marked as timed out.
     *
     * @return timeout duration
     */
    Duration getTimeout();
}
