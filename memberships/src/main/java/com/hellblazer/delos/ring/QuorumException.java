/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ring;

import java.io.Serial;

/**
 * Exception thrown when a quorum operation fails to achieve the required threshold.
 * Provides diagnostic information about the required vs achieved counts.
 *
 * @author hal.hildebrand
 */
public class QuorumException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final int required;
    private final int achieved;

    public QuorumException(String message) {
        super(message);
        this.required = -1;
        this.achieved = -1;
    }

    public QuorumException(int required, int achieved) {
        super("Quorum not reached: required " + required + ", achieved " + achieved);
        this.required = required;
        this.achieved = achieved;
    }

    public QuorumException(String message, int required, int achieved) {
        super(message + ": required " + required + ", achieved " + achieved);
        this.required = required;
        this.achieved = achieved;
    }

    public QuorumException(String message, Throwable cause) {
        super(message, cause);
        this.required = -1;
        this.achieved = -1;
    }

    public QuorumException(int required, int achieved, Throwable cause) {
        super("Quorum not reached: required " + required + ", achieved " + achieved, cause);
        this.required = required;
        this.achieved = achieved;
    }

    /**
     * @return the required count for quorum, or -1 if not specified
     */
    public int getRequired() {
        return required;
    }

    /**
     * @return the achieved count, or -1 if not specified
     */
    public int getAchieved() {
        return achieved;
    }

    /**
     * @return true if quorum counts were provided
     */
    public boolean hasQuorumInfo() {
        return required >= 0 && achieved >= 0;
    }
}
