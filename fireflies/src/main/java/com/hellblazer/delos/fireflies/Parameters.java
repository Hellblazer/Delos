/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import java.time.Duration;

/**
 * @author hal.hildebrand
 */
public record Parameters(int joinRetries, int minimumBiffCardinality, int rebuttalTimeout, int viewChangeRounds,
                         int finalizeViewRounds, double fpr, int maximumTxfr, Duration retryDelay, int maxPending,
                         Duration seedingTimeout, int validationRetries, int crowns, Duration populateDuration,
                         Duration joinMessageTtl, Duration pendingJoinTtl,
                         int maxJoinAttemptsPerMinute, Duration joinRateLimitWindow,
                         Duration enjoinPropagationDelay, Duration shunRecoveryDuration) {

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        /**
         * Number of crowns for the view's hexbloom
         */
        private int      crowns                 = 2;
        /**
         * Number of TTL rounds to wait before finalizing a view change
         */
        private int      finalizeViewRounds     = 3;
        /**
         * False positive rate for bloom filter state replication (high fpr is good)
         */
        private double   fpr                    = 0.00125;
        /**
         * Number of retries when joining until giving up
         */
        private int      joinRetries            = 500;
        /**
         * Maximum number of elements to transfer per type per update
         */
        private int      maximumTxfr            = 1024;
        /**
         * Maximum pending joins
         */
        private int      maxPending             = 200;
        /**
         * Minimum cardinality for bloom filters
         */
        private int      minimumBiffCardinality = 1025;
        /**
         * Number of TTL rounds an accused has to rebut the accusation
         */
        private int      rebuttalTimeout        = 2;
        /**
         * Max duration to delay retrying join operations
         */
        private Duration retryDelay             = Duration.ofMillis(200);
        /**
         * Timeout for contacting seed gateways during seeding and join operations
         */
        private Duration seedingTimout          = Duration.ofSeconds(15);
        /**
         * Max number of times to attempt validation when joining a view
         */
        private int      validationRetries      = 3;
        /**
         * Minimum number of rounds to check for view change
         */
        private int      viewChangeRounds       = 7;
        private Duration populateDuration       = Duration.ofMillis(20);
        /**
         * TTL for join messages (replay attack prevention)
         */
        private Duration joinMessageTtl         = Duration.ofSeconds(30);
        /**
         * TTL for pending join entries before cleanup (prevents slot exhaustion).
         * Should be longer than typical cluster formation time.
         */
        private Duration pendingJoinTtl         = Duration.ofMinutes(5);
        /**
         * Maximum join attempts per identity within the rate limit window (Sybil protection)
         */
        private int      maxJoinAttemptsPerMinute = 10;
        /**
         * Time window for join rate limiting (Sybil protection)
         */
        private Duration joinRateLimitWindow    = Duration.ofMinutes(1);
        /**
         * Delay between enjoin propagation RPCs to observers.
         * Allows time for each RPC to complete before moving to next observer.
         */
        private Duration enjoinPropagationDelay = Duration.ofMillis(10);
        /**
         * Duration after shunning before a member can attempt recovery.
         * Prevents rapid shun/recover cycles and allows system to stabilize.
         */
        private Duration shunRecoveryDuration   = Duration.ofMinutes(5);

        public Parameters build() {
            return new Parameters(joinRetries, minimumBiffCardinality, rebuttalTimeout, viewChangeRounds,
                                  finalizeViewRounds, fpr, maximumTxfr, retryDelay, maxPending, seedingTimout,
                                  validationRetries, crowns, populateDuration, joinMessageTtl, pendingJoinTtl,
                                  maxJoinAttemptsPerMinute, joinRateLimitWindow, enjoinPropagationDelay,
                                  shunRecoveryDuration);
        }

        public int getCrowns() {
            return crowns;
        }

        public Builder setCrowns(int crowns) {
            this.crowns = crowns;
            return this;
        }

        public int getFinalizeViewRounds() {
            return finalizeViewRounds;
        }

        public Builder setFinalizeViewRounds(int finalizeViewRounds) {
            this.finalizeViewRounds = finalizeViewRounds;
            return this;
        }

        public double getFpr() {
            return fpr;
        }

        public Builder setFpr(double fpr) {
            this.fpr = fpr;
            return this;
        }

        public int getJoinRetries() {
            return joinRetries;
        }

        public Builder setJoinRetries(int joinRetries) {
            this.joinRetries = joinRetries;
            return this;
        }

        public int getMaxPending() {
            return maxPending;
        }

        public Builder setMaxPending(int maxPending) {
            this.maxPending = maxPending;
            return this;
        }

        public int getMaximumTxfr() {
            return maximumTxfr;
        }

        public Builder setMaximumTxfr(int maximumTxfr) {
            this.maximumTxfr = maximumTxfr;
            return this;
        }

        public int getMinimumBiffCardinality() {
            return minimumBiffCardinality;
        }

        public Builder setMinimumBiffCardinality(int minimumBiffCardinality) {
            this.minimumBiffCardinality = minimumBiffCardinality;
            return this;
        }

        public Duration getPopulateDuration() {
            return populateDuration;
        }

        public Builder setPopulateDuration(Duration populateDuration) {
            this.populateDuration = populateDuration;
            return this;
        }

        public int getRebuttalTimeout() {
            return rebuttalTimeout;
        }

        public Builder setRebuttalTimeout(int rebuttalTimeout) {
            this.rebuttalTimeout = rebuttalTimeout;
            return this;
        }

        public Duration getRetryDelay() {
            return retryDelay;
        }

        public Builder setRetryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay;
            return this;
        }

        public Duration getSeedingTimout() {
            return seedingTimout;
        }

        public Builder setSeedingTimout(Duration seedingTimout) {
            this.seedingTimout = seedingTimout;
            return this;
        }

        public int getValidationRetries() {
            return validationRetries;
        }

        public Builder setValidationRetries(int validationRetries) {
            this.validationRetries = validationRetries;
            return this;
        }

        public int getViewChangeRounds() {
            return viewChangeRounds;
        }

        public Builder setViewChangeRounds(int viewChangeRounds) {
            this.viewChangeRounds = viewChangeRounds;
            return this;
        }

        public Duration getJoinMessageTtl() {
            return joinMessageTtl;
        }

        public Builder setJoinMessageTtl(Duration joinMessageTtl) {
            this.joinMessageTtl = joinMessageTtl;
            return this;
        }

        public Duration getPendingJoinTtl() {
            return pendingJoinTtl;
        }

        public Builder setPendingJoinTtl(Duration pendingJoinTtl) {
            this.pendingJoinTtl = pendingJoinTtl;
            return this;
        }

        public int getMaxJoinAttemptsPerMinute() {
            return maxJoinAttemptsPerMinute;
        }

        public Builder setMaxJoinAttemptsPerMinute(int maxJoinAttemptsPerMinute) {
            this.maxJoinAttemptsPerMinute = maxJoinAttemptsPerMinute;
            return this;
        }

        public Duration getJoinRateLimitWindow() {
            return joinRateLimitWindow;
        }

        public Builder setJoinRateLimitWindow(Duration joinRateLimitWindow) {
            this.joinRateLimitWindow = joinRateLimitWindow;
            return this;
        }

        public Duration getEnjoinPropagationDelay() {
            return enjoinPropagationDelay;
        }

        public Builder setEnjoinPropagationDelay(Duration enjoinPropagationDelay) {
            this.enjoinPropagationDelay = enjoinPropagationDelay;
            return this;
        }

        public Duration getShunRecoveryDuration() {
            return shunRecoveryDuration;
        }

        public Builder setShunRecoveryDuration(Duration shunRecoveryDuration) {
            this.shunRecoveryDuration = shunRecoveryDuration;
            return this;
        }
    }

}
