/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.cryptography.Signer.MockSigner;
import com.hellblazer.delos.ethereal.WeakThresholdKey.NoOpWeakThresholdKey;
import org.joou.ULong;

import java.util.Objects;

/**
 * Configuration for an Ethereal instantiation.
 * <p>
 * <strong>Byzantine Fault Tolerance Requirements:</strong>
 * <p>
 * Ethereal uses Aleph-BFT consensus which requires:
 * - n >= 3f+1 nodes to tolerate f Byzantine failures
 * - Byzantine quorum: > 2n/3 nodes (equivalent to 2f+1 for n=3f+1)
 * - Minimum network size: n=4 (tolerates f=1)
 * <p>
 * <strong>Reliable Broadcast (RBC) Delivery Guarantees:</strong>
 * <p>
 * RBC ensures that if any honest node delivers a unit, all honest nodes eventually deliver it:
 * 1. <em>Validity</em>: If an honest node broadcasts a unit, all honest nodes eventually deliver it
 * 2. <em>Agreement</em>: If an honest node delivers a unit, all honest nodes eventually deliver it
 * 3. <em>Integrity</em>: A unit is delivered at most once, and only if it was broadcast
 * <p>
 * These guarantees hold because:
 * - Gossip to 2f+1 nodes ensures at least f+1 honest nodes receive the unit
 * - Honest nodes re-gossip to all other nodes
 * - Byzantine nodes (at most f) cannot prevent propagation to n-f honest nodes
 * <p>
 * <strong>Quorum Intersection Property:</strong>
 * <p>
 * Any two quorums Q1, Q2 of size 2f+1 intersect in at least f+1 nodes:
 * - Overlap = |Q1| + |Q2| - n = (2f+1) + (2f+1) - (3f+1) = f+1
 * - Since overlap > f, at least one honest node is in the intersection
 * - This guarantees Byzantine safety for consensus
 *
 * @param label             Human-readable label for logging
 * @param nProc             Total number of nodes (must satisfy n >= 3f+1)
 * @param epochLength       Number of levels per epoch
 * @param pid               Process ID of this node
 * @param signer            Cryptographic signer for this node
 * @param digestAlgorithm   Hash algorithm for content addressing
 * @param numberOfEpochs    Number of epochs to run (< 0 for unbounded)
 * @param WTKey             Weak threshold key for aggregation
 * @param bias              BFT bias parameter (typically 3 for n=3f+1)
 * @param fpr               False positive rate for Bloom filters
 * @param unitTimeoutMillis Timeout threshold for stale waiting units (1000-60000ms, default 5000ms)
 * @param timeoutCheckIntervalMillis Interval for running timeout check (100-10000ms, default 1000ms)
 * @param shutdownDrainTimeoutMillis Timeout for draining pending units during shutdown (1000-60000ms, default 5000ms)
 * @param parentFailureRetryTimeoutMillis Timeout for retrying transient parent failures before cascading (1000-120000ms, default 30000ms)
 * @param consumerErrorHandler Error handler for consumer failures (null for default behavior)
 * @param gossipRetryLimit Maximum number of retries for failed gossip RPC calls (1-10, default 3)
 * @param gossipBaseBackoffMs Base backoff delay in milliseconds for gossip retry (50-1000ms, default 100ms)
 * @param gossipMaxBackoffMs Maximum backoff delay in milliseconds for gossip retry (1000-30000ms, default 5000ms)
 * @param consumerThreadCount Number of threads for parallel unit consumption (1-32, default min(4, cores-1))
 * @author hal.hildebrand
 */
public record Config(String label, short nProc, int epochLength, short pid, Signer signer,
                     DigestAlgorithm digestAlgorithm, int numberOfEpochs, WeakThresholdKey WTKey, double bias,
                     double fpr, long unitTimeoutMillis, long timeoutCheckIntervalMillis,
                     long shutdownDrainTimeoutMillis, long parentFailureRetryTimeoutMillis,
                     ConsumerErrorHandler consumerErrorHandler, int gossipRetryLimit, long gossipBaseBackoffMs,
                     long gossipMaxBackoffMs, int consumerThreadCount) {

    public static Builder newBuilder() {
        return new Builder();
    }

    public int lastLevel() {
        return epochLength - 1;
    }

    public String logLabel() {
        return label + "(" + pid + ")";
    }

    public static class Builder implements Cloneable {

        private int                    bias                         = 3;
        private ConsumerErrorHandler   consumerErrorHandler;
        private DigestAlgorithm        digestAlgorithm              = DigestAlgorithm.DEFAULT;
        private int                    epochLength                  = 11;
        private double                 fpr                          = 0.00125;
        private String                 label                        = "";
        private short                  nProc;
        private int                    numberOfEpochs               = 3;  // < 0 for unbounded
        private double                 pByz                         = -1;
        private short                  pid;
        private long                   shutdownDrainTimeoutMillis   = 5000L;  // Default 5 seconds
        private long                   parentFailureRetryTimeoutMillis = 30000L;  // Default 30 seconds
        private Signer                 signer                       = new MockSigner(SignatureAlgorithm.DEFAULT,
                                                                                      ULong.MIN);
        private long                   timeoutCheckIntervalMillis   = 1000L;  // Default 1 second
        private long                   unitTimeoutMillis            = 5000L;  // Default 5 seconds
        private int                    gossipRetryLimit             = 3;      // Default 3 retries
        private long                   gossipBaseBackoffMs          = 100L;   // Default 100ms
        private long                   gossipMaxBackoffMs           = 5000L;  // Default 5000ms
        private int                    consumerThreadCount          = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        private WeakThresholdKey       wtk;

        public Builder() {
        }

        public Config build() {
            if (pByz <= -1) {
                pByz = 1.0 / bias;
            }

            // CRITICAL: Validate nProc meets Byzantine fault tolerance requirements (f < n/3)
            // Requires minimum 3f+1 nodes to tolerate f faults. Also validates that n is valid
            // for BFT consensus (e.g., 3f+1, 3f+2, or 3f+3).
            if (!Dag.validate(nProc)) {
                throw new IllegalArgumentException(
                    "Invalid nProc: " + nProc + ". Must be >= 4 and satisfy Byzantine fault tolerance requirements (n >= 3f+1)");
            }

            final var minimalQuorum = Context.minimalQuorum(nProc, bias);
            if (wtk == null) {
                wtk = new NoOpWeakThresholdKey(minimalQuorum + 1);
            }
            Objects.requireNonNull(signer, "Signer cannot be null");
            Objects.requireNonNull(digestAlgorithm, "Digest Algorithm cannot be null");
            if (epochLength <= 10) {
                throw new IllegalArgumentException("Epoch length must be at least 11: " + epochLength);
            }
            if (unitTimeoutMillis < 1000 || unitTimeoutMillis > 60000) {
                throw new IllegalArgumentException(
                    "unitTimeoutMillis must be between 1000 and 60000 (1-60 seconds): " + unitTimeoutMillis);
            }
            if (timeoutCheckIntervalMillis < 100 || timeoutCheckIntervalMillis > 10000) {
                throw new IllegalArgumentException(
                    "timeoutCheckIntervalMillis must be between 100 and 10000 (0.1-10 seconds): " + timeoutCheckIntervalMillis);
            }
            if (shutdownDrainTimeoutMillis < 1000 || shutdownDrainTimeoutMillis > 60000) {
                throw new IllegalArgumentException(
                    "shutdownDrainTimeoutMillis must be between 1000 and 60000 (1-60 seconds): " + shutdownDrainTimeoutMillis);
            }
            if (parentFailureRetryTimeoutMillis < 1000 || parentFailureRetryTimeoutMillis > 120000) {
                throw new IllegalArgumentException(
                    "parentFailureRetryTimeoutMillis must be between 1000 and 120000 (1-120 seconds): " + parentFailureRetryTimeoutMillis);
            }
            if (gossipRetryLimit < 1 || gossipRetryLimit > 10) {
                throw new IllegalArgumentException(
                    "gossipRetryLimit must be between 1 and 10: " + gossipRetryLimit);
            }
            if (gossipBaseBackoffMs < 50 || gossipBaseBackoffMs > 1000) {
                throw new IllegalArgumentException(
                    "gossipBaseBackoffMs must be between 50 and 1000 (50ms-1s): " + gossipBaseBackoffMs);
            }
            if (gossipMaxBackoffMs < 1000 || gossipMaxBackoffMs > 30000) {
                throw new IllegalArgumentException(
                    "gossipMaxBackoffMs must be between 1000 and 30000 (1-30 seconds): " + gossipMaxBackoffMs);
            }
            if (consumerThreadCount < 1 || consumerThreadCount > 32) {
                throw new IllegalArgumentException(
                    "consumerThreadCount must be between 1 and 32: " + consumerThreadCount);
            }
            return new Config(label, nProc, epochLength, pid, signer, digestAlgorithm, numberOfEpochs, wtk, bias, fpr,
                              unitTimeoutMillis, timeoutCheckIntervalMillis, shutdownDrainTimeoutMillis,
                              parentFailureRetryTimeoutMillis, consumerErrorHandler, gossipRetryLimit,
                              gossipBaseBackoffMs, gossipMaxBackoffMs, consumerThreadCount);
        }

        @Override
        public Builder clone() {
            try {
                return (Builder) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException(e);
            }
        }

        public int getBias() {
            return bias;
        }

        public Builder setBias(int bias) {
            this.bias = bias;
            return this;
        }

        public DigestAlgorithm getDigestAlgorithm() {
            return digestAlgorithm;
        }

        public Builder setDigestAlgorithm(DigestAlgorithm digestAlgorithm) {
            this.digestAlgorithm = digestAlgorithm;
            return this;
        }

        public int getEpochLength() {
            return epochLength;
        }

        public Builder setEpochLength(int epochLength) {
            this.epochLength = epochLength;
            return this;
        }

        public double getFpr() {
            return fpr;
        }

        public Builder setFpr(double fpr) {
            this.fpr = fpr;
            return this;
        }

        public String getLabel() {
            return label;
        }

        public Builder setLabel(String label) {
            this.label = label;
            return this;
        }

        public int getNumberOfEpochs() {
            return numberOfEpochs;
        }

        public Builder setNumberOfEpochs(int numberOfEpochs) {
            this.numberOfEpochs = numberOfEpochs;
            return this;
        }

        public short getPid() {
            return pid;
        }

        public Builder setPid(short pid) {
            this.pid = pid;
            return this;
        }

        public Signer getSigner() {
            return signer;
        }

        public Builder setSigner(Signer signer) {
            this.signer = signer;
            return this;
        }

        public WeakThresholdKey getWtk() {
            return wtk;
        }

        public Builder setWtk(WeakThresholdKey wtk) {
            this.wtk = wtk;
            return this;
        }

        public short getnProc() {
            return nProc;
        }

        public Builder setnProc(short nProc) {
            this.nProc = nProc;
            return this;
        }

        public double getpByz() {
            return pByz;
        }

        public Builder setpByz(double pByz) {
            this.pByz = pByz;
            return this;
        }

        public long getUnitTimeoutMillis() {
            return unitTimeoutMillis;
        }

        public Builder setUnitTimeoutMillis(long unitTimeoutMillis) {
            this.unitTimeoutMillis = unitTimeoutMillis;
            return this;
        }

        public long getTimeoutCheckIntervalMillis() {
            return timeoutCheckIntervalMillis;
        }

        public Builder setTimeoutCheckIntervalMillis(long timeoutCheckIntervalMillis) {
            this.timeoutCheckIntervalMillis = timeoutCheckIntervalMillis;
            return this;
        }

        public long getShutdownDrainTimeoutMillis() {
            return shutdownDrainTimeoutMillis;
        }

        public Builder setShutdownDrainTimeoutMillis(long shutdownDrainTimeoutMillis) {
            this.shutdownDrainTimeoutMillis = shutdownDrainTimeoutMillis;
            return this;
        }

        public ConsumerErrorHandler getConsumerErrorHandler() {
            return consumerErrorHandler;
        }

        public Builder setConsumerErrorHandler(ConsumerErrorHandler consumerErrorHandler) {
            this.consumerErrorHandler = consumerErrorHandler;
            return this;
        }

        public long getParentFailureRetryTimeoutMillis() {
            return parentFailureRetryTimeoutMillis;
        }

        public Builder setParentFailureRetryTimeoutMillis(long parentFailureRetryTimeoutMillis) {
            this.parentFailureRetryTimeoutMillis = parentFailureRetryTimeoutMillis;
            return this;
        }

        public int getGossipRetryLimit() {
            return gossipRetryLimit;
        }

        public Builder setGossipRetryLimit(int gossipRetryLimit) {
            this.gossipRetryLimit = gossipRetryLimit;
            return this;
        }

        public long getGossipBaseBackoffMs() {
            return gossipBaseBackoffMs;
        }

        public Builder setGossipBaseBackoffMs(long gossipBaseBackoffMs) {
            this.gossipBaseBackoffMs = gossipBaseBackoffMs;
            return this;
        }

        public long getGossipMaxBackoffMs() {
            return gossipMaxBackoffMs;
        }

        public Builder setGossipMaxBackoffMs(long gossipMaxBackoffMs) {
            this.gossipMaxBackoffMs = gossipMaxBackoffMs;
            return this;
        }

        public int getConsumerThreadCount() {
            return consumerThreadCount;
        }

        public Builder setConsumerThreadCount(int consumerThreadCount) {
            this.consumerThreadCount = consumerThreadCount;
            return this;
        }
    }
}
