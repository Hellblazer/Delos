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
 * @author hal.hildebrand
 */
public record Config(String label, short nProc, int epochLength, short pid, Signer signer,
                     DigestAlgorithm digestAlgorithm, int numberOfEpochs, WeakThresholdKey WTKey, double bias,
                     double fpr, long unitTimeoutMillis) {

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

        private int              bias            = 3;
        private DigestAlgorithm  digestAlgorithm = DigestAlgorithm.DEFAULT;
        private int              epochLength     = 11;
        private double           fpr             = 0.00125;
        private String           label           = "";
        private short            nProc;
        private int              numberOfEpochs  = 3;  // < 0 for unbounded
        private double           pByz            = -1;
        private short            pid;
        private Signer           signer          = new MockSigner(SignatureAlgorithm.DEFAULT, ULong.MIN);
        private long             unitTimeoutMillis = 5000L;  // Default 5 seconds
        private WeakThresholdKey wtk;

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
            return new Config(label, nProc, epochLength, pid, signer, digestAlgorithm, numberOfEpochs, wtk, bias, fpr,
                              unitTimeoutMillis);
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
    }
}
