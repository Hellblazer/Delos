/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.integration;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.MockMember;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Base infrastructure for Phase 1C end-to-end integration tests.
 *
 * Provides reusable test fixtures for 7-node Byzantine-resilient witness committee testing:
 * - Witness pool and Fireflies context setup
 * - BLS threshold collection infrastructure
 * - Byzantine detection test helpers
 * - Performance measurement utilities
 *
 * Cluster Configuration:
 * - 7 committee members (k=7)
 * - 21-node witness pool
 * - Byzantine tolerance: f=2 (supports 2 Byzantine nodes)
 * - Threshold: 5 signatures (2f+1 quorum)
 *
 * @author hal.hildebrand
 */
abstract public class Phase1CTestBase {

    protected static final int COMMITTEE_SIZE = 7;
    protected static final int WITNESS_POOL_SIZE = 21;
    protected static final int THRESHOLD = 5;
    protected static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    // Core infrastructure
    protected SecureRandom entropy;
    protected List<MockMember> witnessPool;
    protected Context<MockMember> firefliesContext;
    protected Set<Identifier> committee;
    protected Map<Identifier, ByzantineNodeState> byzantineState;

    @BeforeEach
    public void setUp() {
        entropy = new SecureRandom();
        byzantineState = new HashMap<>();

        // Initialize witness pool: 21 members
        witnessPool = createWitnessPool(WITNESS_POOL_SIZE);

        // Initialize Fireflies context: 7 members selected from pool
        Digest contextId = ALGORITHM.digest("phase1c-test-context".getBytes());
        firefliesContext = new StaticContext<>(contextId, 0.1, witnessPool, COMMITTEE_SIZE);

        // Select initial committee (first 7 members)
        committee = selectCommittee(witnessPool.stream().limit(COMMITTEE_SIZE).toList());

        // Initialize Byzantine state tracking
        committee.forEach(id -> byzantineState.put(id, new ByzantineNodeState(id)));
    }

    @AfterEach
    public void tearDown() {
        witnessPool.clear();
        committee.clear();
        byzantineState.clear();
    }

    /**
     * Create a witness pool of n MockMembers
     */
    protected List<MockMember> createWitnessPool(int poolSize) {
        return IntStream.range(0, poolSize)
                .mapToObj(i -> new MockMember(ALGORITHM.digest(("witness-" + i).getBytes())))
                .collect(Collectors.toList());
    }

    /**
     * Select committee identifiers from members
     */
    protected Set<Identifier> selectCommittee(List<MockMember> members) {
        return members.stream().limit(COMMITTEE_SIZE)
                .map(m -> new SelfAddressingIdentifier(m.getId()))
                .collect(Collectors.toSet());
    }

    /**
     * Create event coordinates for testing
     */
    protected EventCoordinates createEventCoordinates(String label, long sequenceNumber) {
        Digest eventDigest = ALGORITHM.digest(label.getBytes());
        // Create self-addressing identifier for event
        return new EventCoordinates(
                new SelfAddressingIdentifier(eventDigest),
                ULong.valueOf(sequenceNumber),
                eventDigest,
                "icp");
    }

    /**
     * Get random committee member
     */
    protected MockMember getRandomCommitteeMember() {
        var committeeList = new ArrayList<>(committee);
        var randomId = committeeList.get(entropy.nextInt(committeeList.size()));
        return witnessPool.stream()
                .filter(m -> new SelfAddressingIdentifier(m.getId()).equals(randomId))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Mark node as Byzantine and track its state
     */
    protected void markAsByzantine(Identifier nodeId) {
        var state = byzantineState.get(nodeId);
        if (state != null) {
            state.isByzantine = true;
            state.anomalyScore = 0.95;
        }
    }

    /**
     * Check if node is marked as Byzantine
     */
    protected boolean isByzantine(Identifier nodeId) {
        var state = byzantineState.get(nodeId);
        return state != null && state.isByzantine;
    }

    /**
     * Get count of Byzantine nodes in committee
     */
    protected int getByzantineCount() {
        return (int) byzantineState.values().stream()
                .filter(s -> s.isByzantine)
                .count();
    }

    /**
     * Get active (non-Byzantine) node count
     */
    protected int getActiveCount() {
        return committee.size() - getByzantineCount();
    }

    /**
     * Simulate Byzantine behavior: random signature
     */
    protected byte[] generateByzantineSignature(Identifier nodeId) {
        byte[] sig = new byte[256];
        entropy.nextBytes(sig);
        return sig;
    }

    /**
     * Simulate Byzantine behavior: equivocation (sign different messages)
     */
    protected Digest createEquivocatingMessage(Identifier nodeId, int variant) {
        return ALGORITHM.digest(("equivocation-" + nodeId + "-" + variant).getBytes());
    }

    /**
     * Simulate Byzantine behavior: replay old signature
     */
    protected byte[] replayOldSignature(Identifier nodeId) {
        // Return a known invalid signature pattern
        byte[] sig = new byte[256];
        entropy.nextBytes(sig);
        return sig;
    }

    /**
     * Wait for condition with timeout
     */
    protected void waitForCondition(String description, long timeoutMs, java.util.function.BooleanSupplier condition) {
        long startTime = System.currentTimeMillis();
        while (true) {
            if (condition.getAsBoolean()) {
                return;
            }
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw new AssertionError("Timeout waiting for: " + description + " (" + timeoutMs + "ms)");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Measure operation latency with nanosecond precision
     */
    protected long measureLatencyNanos(String description, Runnable operation) {
        long startNanos = System.nanoTime();
        operation.run();
        long endNanos = System.nanoTime();
        return endNanos - startNanos;
    }

    /**
     * Calculate percentiles from latency list
     */
    protected Map<String, Long> calculatePercentiles(List<Long> latencies) {
        latencies.sort(Long::compareTo);

        Map<String, Long> percentiles = new LinkedHashMap<>();
        percentiles.put("p50", latencies.get((int) (latencies.size() * 0.50)));
        percentiles.put("p95", latencies.get((int) (latencies.size() * 0.95)));
        percentiles.put("p99", latencies.get((int) (latencies.size() * 0.99)));

        return percentiles;
    }

    /**
     * Calculate throughput (operations per second)
     */
    protected double calculateThroughput(int operationCount, long durationNanos) {
        if (durationNanos == 0) return 0;
        double durationSeconds = durationNanos / 1_000_000_000.0;
        return operationCount / durationSeconds;
    }

    /**
     * Verify Byzantine safety: at least 2f+1 honest nodes needed for consensus
     * With f=2, need at least 5 honest nodes from 7 total
     */
    protected boolean isByzantineSafe() {
        int activeCount = getActiveCount();
        return activeCount >= THRESHOLD;  // 2f+1 = 5
    }

    /**
     * Track Byzantine node state
     */
    protected static class ByzantineNodeState {
        public final Identifier nodeId;
        public boolean isByzantine;
        public double anomalyScore;
        public int failureCount;
        public long lastAnomalyTime;

        public ByzantineNodeState(Identifier nodeId) {
            this.nodeId = nodeId;
            this.isByzantine = false;
            this.anomalyScore = 0.0;
            this.failureCount = 0;
            this.lastAnomalyTime = 0;
        }

        public void recordAnomaly(double score) {
            this.anomalyScore = Math.max(this.anomalyScore, score);
            this.failureCount++;
            this.lastAnomalyTime = System.currentTimeMillis();

            // Mark as Byzantine after 5 failures
            if (this.failureCount >= 5) {
                this.isByzantine = true;
            }
        }
    }
}
