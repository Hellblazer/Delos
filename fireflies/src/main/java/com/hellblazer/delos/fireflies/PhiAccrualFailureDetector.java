/*
 * Copyright 2021 Mitsunori Komatsu (komamitsu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hellblazer.delos.fireflies;

import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * This is a port of <a
 * href="https://github.com/akka/akka/blob/master/akka-remote/src/main/scala/akka/remote/PhiAccrualFailureDetector.scala">Scala
 * implementation</a> of 'The Phi Accrual Failure Detector' as defined in their paper:
 * <a href="http://ddg.jaist.ac.jp/pub/HDY+04.pdf">by Hayashibara et al.</a>
 *
 * The suspicion level of failure is given by a value called φ (phi). The basic idea of the φ failure detector is to
 * express the value of φ on a scale that is dynamically adjusted to reflect current network conditions. A configurable
 * threshold is used to decide if φ is considered to be a failure.
 *
 * The value of φ is calculated as:
 *
 * {{{ φ = -log10(1 - F(timeSinceLastHeartbeat) }}} where F is the cumulative distribution function of a normal
 * distribution with mean and standard deviation estimated from historical heartbeat inter-arrival times.
 */
public class PhiAccrualFailureDetector {
    private final ReadWriteLock         lock                = new ReentrantReadWriteLock();
    private final double                threshold;
    private final double                minStdDeviationMillis;
    private final long                  acceptableHeartbeatPauseMillis;
    private final HeartbeatHistory      heartbeatHistory;
    private final AtomicReference<Long> lastTimestampMillis = new AtomicReference<Long>();

    /**
     * @param threshold                      A low threshold is prone to generate many wrong suspicions but ensures a
     *                                       quick detection in the event of a real crash. Conversely, a high threshold
     *                                       generates fewer mistakes but needs more time to detect actual crashes
     * @param maxSampleSize                  Number of samples to use for calculation of mean and standard deviation of
     *                                       inter-arrival times.
     * @param minStdDeviationMillis          Minimum standard deviation to use for the normal distribution used when
     *                                       calculating phi. Too low standard deviation might result in too much
     *                                       sensitivity for sudden, but normal, deviations in heartbeat inter-arrival
     *                                       times.
     * @param acceptableHeartbeatPauseMillis Duration corresponding to the number of potentially lost/delayed heartbeats
     *                                       that will be accepted before considering it to be an anomaly. This margin
     *                                       is important to be able to survive sudden, occasional, pauses in heartbeat
     *                                       arrivals, due to, for example, garbage collect or network drop.
     * @param firstHeartbeatEstimateMillis   Bootstrap the stats with heartbeats that correspond to this duration, with
     *                                       a with rather high standard deviation (since the environment is unknown in
     *                                       the beginning)
     */
    private PhiAccrualFailureDetector(double threshold, int maxSampleSize, double minStdDeviationMillis,
                                      long acceptableHeartbeatPauseMillis, long firstHeartbeatEstimateMillis) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("Threshold must be > 0: " + threshold);
        }
        if (maxSampleSize <= 0) {
            throw new IllegalArgumentException("Sample size must be > 0: " + maxSampleSize);
        }
        if (minStdDeviationMillis <= 0) {
            throw new IllegalArgumentException("Minimum standard deviation must be > 0: " + minStdDeviationMillis);
        }
        if (acceptableHeartbeatPauseMillis < 0) {
            throw new IllegalArgumentException(
            "Acceptable heartbeat pause millis must be >= 0: " + acceptableHeartbeatPauseMillis);
        }
        if (firstHeartbeatEstimateMillis <= 0) {
            throw new IllegalArgumentException("First heartbeat value must be > 0: " + firstHeartbeatEstimateMillis);
        }

        this.threshold = threshold;
        this.minStdDeviationMillis = minStdDeviationMillis;
        this.acceptableHeartbeatPauseMillis = acceptableHeartbeatPauseMillis;

        long stdDeviationMillis = firstHeartbeatEstimateMillis / 4;
        heartbeatHistory = new HeartbeatHistory(maxSampleSize);
        heartbeatHistory.add(firstHeartbeatEstimateMillis - stdDeviationMillis)
                        .add(firstHeartbeatEstimateMillis + stdDeviationMillis);
    }

    public void heartbeat(long timestampMillis) {
        final var lock = this.lock.writeLock();
        lock.lock();
        try {
            Long lastTimestampMillis = this.lastTimestampMillis.getAndSet(timestampMillis);
            if (lastTimestampMillis != null) {
                long interval = timestampMillis - lastTimestampMillis;
                if (isAvailable(timestampMillis)) {
                    heartbeatHistory.add(interval);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void heartbeat() {
        heartbeat(System.currentTimeMillis());
    }

    public boolean isAvailable(long timestampMillis) {
        return phi(timestampMillis) < threshold;
    }

    public boolean isAvailable() {
        return phi(System.currentTimeMillis()) < threshold;
    }

    public double phi(long timestampMillis) {
        final var lock = this.lock.readLock();
        lock.lock();
        try {
            Long lastTimestampMillis = this.lastTimestampMillis.get();
            if (lastTimestampMillis == null) {
                return 0.0;
            }

            long timeDiffMillis = timestampMillis - lastTimestampMillis;
            double meanMillis = heartbeatHistory.mean() + acceptableHeartbeatPauseMillis;
            double stdDeviationMillis = ensureValidStdDeviation(heartbeatHistory.stdDeviation());

            double y = (timeDiffMillis - meanMillis) / stdDeviationMillis;
            double e = Math.exp(-y * (1.5976 + 0.070566 * y * y));
            if (timeDiffMillis > meanMillis) {
                return -Math.log10(e / (1.0 + e));
            } else {
                return -Math.log10(1.0 - 1.0 / (1.0 + e));
            }
        } finally {
            lock.unlock();
        }
    }

    public double phi() {
        var lock = this.lock.readLock();
        lock.lock();
        try {
            return phi(System.currentTimeMillis());
        } finally {
            lock.unlock();
        }
    }

    private double ensureValidStdDeviation(double stdDeviationMillis) {
        return Math.max(stdDeviationMillis, minStdDeviationMillis);
    }

    public static class Builder {
        private double threshold                      = 16.0;
        private int    maxSampleSize                  = 200;
        private double minStdDeviationMillis          = 500;
        private long   acceptableHeartbeatPauseMillis = 0;
        private long   firstHeartbeatEstimateMillis   = 500;

        public PhiAccrualFailureDetector build() {
            return new PhiAccrualFailureDetector(threshold, maxSampleSize, minStdDeviationMillis,
                                                 acceptableHeartbeatPauseMillis, firstHeartbeatEstimateMillis);
        }

        public Builder setAcceptableHeartbeatPauseMillis(long acceptableHeartbeatPauseMillis) {
            this.acceptableHeartbeatPauseMillis = acceptableHeartbeatPauseMillis;
            return this;
        }

        public Builder setFirstHeartbeatEstimateMillis(long firstHeartbeatEstimateMillis) {
            this.firstHeartbeatEstimateMillis = firstHeartbeatEstimateMillis;
            return this;
        }

        public Builder setMaxSampleSize(int maxSampleSize) {
            this.maxSampleSize = maxSampleSize;
            return this;
        }

        public Builder setMinStdDeviationMillis(double minStdDeviationMillis) {
            this.minStdDeviationMillis = minStdDeviationMillis;
            return this;
        }

        public Builder setThreshold(double threshold) {
            this.threshold = threshold;
            return this;
        }
    }

    private static class HeartbeatHistory {
        private final int              maxSampleSize;
        private final LinkedList<Long> intervals          = new LinkedList<Long>();
        private final AtomicLong       intervalSum        = new AtomicLong();
        private final AtomicLong       squaredIntervalSum = new AtomicLong();

        public HeartbeatHistory(int maxSampleSize) {
            if (maxSampleSize < 1) {
                throw new IllegalArgumentException("maxSampleSize must be >= 1, got " + maxSampleSize);
            }
            this.maxSampleSize = maxSampleSize;
        }

        public HeartbeatHistory add(long interval) {
            if (intervals.size() >= maxSampleSize) {
                Long dropped = intervals.pollFirst();
                intervalSum.addAndGet(-dropped);
                squaredIntervalSum.addAndGet(-pow2(dropped));
            }
            intervals.add(interval);
            intervalSum.addAndGet(interval);
            squaredIntervalSum.addAndGet(pow2(interval));
            return this;
        }

        public double mean() {
            return (double) intervalSum.get() / intervals.size();
        }

        public double stdDeviation() {
            return Math.sqrt(variance());
        }

        public double variance() {
            return ((double) squaredIntervalSum.get() / intervals.size()) - (mean() * mean());
        }

        private long pow2(long x) {
            return x * x;
        }
    }
}
