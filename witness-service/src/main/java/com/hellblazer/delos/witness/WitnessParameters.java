/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import java.time.Duration;

/**
 * Immutable configuration parameters for KERI witness network service.
 * <p>
 * Configuration enforces KERI witness network constraints:
 * - Minimum witness count: N >= 4 (3f+1 with f=1)
 * - Threshold requirement: M > (2*k)/3
 * - Drain period for view change coordination
 * </p>
 *
 * @param k            Committee cardinality (number of witnesses in pool)
 * @param threshold    Required number of witness signatures (M)
 * @param epoch        Current Fireflies epoch
 * @param drainPeriod  Time to allow in-flight collections to complete during view change
 */
public record WitnessParameters(
    int k,
    int threshold,
    long epoch,
    Duration drainPeriod
) {
    private static final int MINIMUM_WITNESSES = 4;
    private static final Duration DEFAULT_DRAIN_PERIOD = Duration.ofMillis(500);

    /**
     * Validates configuration parameters enforce KERI constraints.
     */
    public WitnessParameters {
        if (k < MINIMUM_WITNESSES) {
            throw new IllegalArgumentException(
                "KERI witness network requires at least " + MINIMUM_WITNESSES +
                " witnesses (N >= 3f+1 with f=1). Current k=" + k
            );
        }

        var minimumThreshold = (2 * k) / 3 + 1;
        if (threshold < minimumThreshold) {
            throw new IllegalArgumentException(
                "Threshold " + threshold + " does not satisfy M > (2*k)/3 " +
                "with k=" + k + ", minimum threshold=" + minimumThreshold
            );
        }

        if (threshold > k) {
            throw new IllegalArgumentException(
                "threshold cannot exceed k (threshold=" + threshold + ", k=" + k + ")"
            );
        }

        if (epoch < 0) {
            throw new IllegalArgumentException(
                "epoch must be non-negative, got: " + epoch
            );
        }

        if (drainPeriod == null || !drainPeriod.isPositive()) {
            throw new IllegalArgumentException(
                "drainPeriod must be positive, got: " + drainPeriod
            );
        }
    }

    /**
     * Calculate Byzantine fault tolerance level.
     * <p>
     * Formula: f = (k-1)/3
     * </p>
     *
     * @return Maximum number of Byzantine failures tolerated
     */
    public int toleranceLevel() {
        return (k - 1) / 3;
    }

    /**
     * Create a new builder instance.
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Builder for WitnessParameters with validation.
     */
    public static class Builder {
        private int k;
        private int threshold;
        private long epoch;
        private Duration drainPeriod = DEFAULT_DRAIN_PERIOD;

        public Builder k(int k) {
            this.k = k;
            return this;
        }

        public Builder threshold(int threshold) {
            this.threshold = threshold;
            return this;
        }

        public Builder epoch(long epoch) {
            this.epoch = epoch;
            return this;
        }

        public Builder drainPeriod(Duration drainPeriod) {
            this.drainPeriod = drainPeriod;
            return this;
        }

        public WitnessParameters build() {
            return new WitnessParameters(k, threshold, epoch, drainPeriod);
        }
    }
}
