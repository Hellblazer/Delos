/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.gorgoneion.proto.SignedAttestation;
import com.hellblazer.delos.stereotomy.KERL;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Predicate;

/**
 * Configuration parameters for Gorgoneion identity admission service.
 *
 * @param clock                 Clock for time-based operations
 * @param registrationTimeout   Timeout duration for registration operations
 * @param frequency             Frequency of periodic operations
 * @param digestAlgorithm       Algorithm used for cryptographic digests
 * @param maxDuration           Maximum age of valid timestamps (past window)
 * @param clockSkewTolerance    Tolerance for clock skew between nodes (future window).
 *                              Allows acceptance of timestamps up to this duration in the future.
 *                              Default: 5 seconds. Set to Duration.ZERO for strict (no future tolerance) behavior.
 *                              Used to accommodate network delays and minor clock differences in distributed systems.
 * @param kerl                  Key Event Receipt Log for identity management
 * @author hal.hildebrand
 */
public record Parameters(Clock clock, Duration registrationTimeout, Duration frequency, DigestAlgorithm digestAlgorithm,
                         Duration maxDuration, Duration clockSkewTolerance, KERL kerl) {

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private final static Predicate<SignedAttestation> defaultVerifier;

        static {
            defaultVerifier = x -> true;
        }

        private Clock           clock               = Clock.systemUTC();
        private DigestAlgorithm digestAlgorithm     = DigestAlgorithm.DEFAULT;
        private Duration        frequency           = Duration.ofMillis(5);
        private KERL            kerl;
        private Duration        maxDuration         = Duration.ofSeconds(30);
        private Duration        clockSkewTolerance  = Duration.ofSeconds(5);
        private Duration        registrationTimeout = Duration.ofSeconds(30);

        public Parameters build() {
            return new Parameters(clock, registrationTimeout, frequency, digestAlgorithm, maxDuration, clockSkewTolerance, kerl);
        }

        public Clock getClock() {
            return clock;
        }

        public Builder setClock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public DigestAlgorithm getDigestAlgorithm() {
            return digestAlgorithm;
        }

        public Builder setDigestAlgorithm(DigestAlgorithm digestAlgorithm) {
            this.digestAlgorithm = digestAlgorithm;
            return this;
        }

        public Duration getFrequency() {
            return frequency;
        }

        public Builder setFrequency(Duration frequency) {
            this.frequency = frequency;
            return this;
        }

        public KERL getKerl() {
            return kerl;
        }

        public Builder setKerl(KERL kerl) {
            this.kerl = kerl;
            return this;
        }

        public Duration getMaxDuration() {
            return maxDuration;
        }

        public Builder setMaxDuration(Duration maxDuration) {
            this.maxDuration = maxDuration;
            return this;
        }

        public Duration getRegistrationTimeout() {
            return registrationTimeout;
        }

        public Builder setRegistrationTimeout(Duration registrationTimeout) {
            this.registrationTimeout = registrationTimeout;
            return this;
        }

        /**
         * Gets the clock skew tolerance.
         *
         * @return the tolerance duration for future timestamps
         */
        public Duration getClockSkewTolerance() {
            return clockSkewTolerance;
        }

        /**
         * Sets the clock skew tolerance for accepting future timestamps.
         * This allows the system to accept timestamps that are slightly in the future
         * to accommodate clock differences between nodes in a distributed system.
         * <p>
         * Default: 5 seconds
         * Set to Duration.ZERO for strict validation (no future timestamps accepted)
         *
         * @param clockSkewTolerance the tolerance duration
         * @return this builder
         */
        public Builder setClockSkewTolerance(Duration clockSkewTolerance) {
            this.clockSkewTolerance = clockSkewTolerance;
            return this;
        }
    }

}
