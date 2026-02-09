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
 * @param replayCacheSize       Size of the replay attack prevention cache.
 *                              Controls how many recent nonces are tracked to prevent duplicate submissions.
 *                              Default: 10,000. Valid range: 100 to 1,000,000.
 *                              <p>
 *                              Sizing guidance:
 *                              <ul>
 *                              <li>Low traffic (1-3 admissions/sec): 1,000-5,000 entries sufficient</li>
 *                              <li>Medium traffic (5-10 admissions/sec): 10,000 entries (default)</li>
 *                              <li>High traffic (>10 admissions/sec): 50,000-100,000 entries</li>
 *                              <li>Burst scenarios: Consider maxDuration × peak rate × 1.5 safety factor</li>
 *                              </ul>
 *                              Cache uses LRU eviction. Entries older than maxDuration + clockSkewTolerance
 *                              are automatically removed via TTL.
 * @author hal.hildebrand
 */
public record Parameters(Clock clock, Duration registrationTimeout, Duration frequency, DigestAlgorithm digestAlgorithm,
                         Duration maxDuration, Duration clockSkewTolerance, KERL kerl, int replayCacheSize) {

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
        private int             replayCacheSize     = 10_000;

        public Parameters build() {
            if (replayCacheSize < 100 || replayCacheSize > 1_000_000) {
                throw new IllegalArgumentException(
                    "replayCacheSize must be between 100 and 1,000,000 (inclusive), got: " + replayCacheSize);
            }
            return new Parameters(clock, registrationTimeout, frequency, digestAlgorithm, maxDuration, clockSkewTolerance, kerl, replayCacheSize);
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

        /**
         * Gets the replay cache size.
         *
         * @return the configured cache size
         */
        public int getReplayCacheSize() {
            return replayCacheSize;
        }

        /**
         * Sets the replay cache size for preventing replay attacks.
         * The cache tracks recent nonces to detect and reject duplicate submissions.
         * <p>
         * Valid range: 100 to 1,000,000 (inclusive)
         * <p>
         * Default: 10,000
         * <p>
         * Sizing guidance:
         * <ul>
         * <li>Low traffic (1-3 admissions/sec): 1,000-5,000 entries</li>
         * <li>Medium traffic (5-10 admissions/sec): 10,000 entries (default)</li>
         * <li>High traffic (>10 admissions/sec): 50,000-100,000 entries</li>
         * <li>Burst scenarios: maxDuration × peak rate × 1.5 safety factor</li>
         * </ul>
         *
         * @param replayCacheSize the cache size (must be between 100 and 1,000,000)
         * @return this builder
         * @throws IllegalArgumentException if size is outside valid range (validated in build())
         */
        public Builder setReplayCacheSize(int replayCacheSize) {
            this.replayCacheSize = replayCacheSize;
            return this;
        }
    }

}
