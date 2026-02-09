/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hellblazer.delos.choam.support.*;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FSM transition and snapshot baseline measurements for Phase 0.
 * Establishes performance baseline for FSM transitions and StateHolder snapshots
 * BEFORE implementing validation layer.
 *
 * Measurements:
 * - FSM transition latency (p50/p95/p99) - raw Tron FSM overhead
 * - StateHolder read latency - single AtomicReference.get() cost
 * - Full snapshot capture cost - all StateHolders under fsm.synchonizeOnState()
 * - Snapshot vs no-snapshot comparison - overhead of snapshot layer
 *
 * These baselines will be used in Phase 4 to validate that validation overhead
 * meets SLA targets (e.g., p95 < baseline + 10%).
 *
 * @author hal.hildebrand
 */
public class FSMTransitionBaselineTest {
    private static final Logger log = LoggerFactory.getLogger(FSMTransitionBaselineTest.class);

    // Test parameters
    private static final int WARMUP_ITERATIONS = 1000;
    private static final int MEASUREMENT_ITERATIONS = 10_000;

    // Note: FSM transition latency is measured end-to-end via PerformanceBaselineTest.
    // Direct FSM access is not exposed in CHOAM's public API.

    /**
     * Measure StateHolder read cost (single AtomicReference.get()).
     * Establishes baseline for reading state without synchronization.
     */
    @Test
    public void measureStateHolderReadLatency() {
        log.info("Measuring StateHolder read latency (AtomicReference.get)");

        // Create StateHolders
        var controlState = new ControlStateHolder();
        var viewState = new ViewStateHolder(null);
        var blockChainState = new BlockChainStateHolder(DigestAlgorithm.DEFAULT, 1000);
        var committeeState = new CommitteeStateHolder();
        var asyncState = new AsyncOperationStateHolder();

        // Warmup
        log.info("Warming up StateHolder reads ({} iterations)", WARMUP_ITERATIONS);
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            controlState.isStarted();
            viewState.getNextViewId();
            blockChainState.getHead();
            committeeState.hasCommittee();
            asyncState.getSyncAttempts();
        }

        // Measure single StateHolder read
        log.info("Measuring single StateHolder reads ({} iterations)", MEASUREMENT_ITERATIONS);
        List<Long> latencies = new ArrayList<>(MEASUREMENT_ITERATIONS);

        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            controlState.isStarted();
            long end = System.nanoTime();
            latencies.add(end - start);
        }

        Collections.sort(latencies);
        var singleReadResults = new LatencyResults(
            "single_stateholder_read",
            latencies.get((int) (MEASUREMENT_ITERATIONS * 0.50)),
            latencies.get((int) (MEASUREMENT_ITERATIONS * 0.95)),
            latencies.get((int) (MEASUREMENT_ITERATIONS * 0.99)),
            latencies.get((int) (MEASUREMENT_ITERATIONS * 0.999)),
            latencies.stream().mapToLong(Long::longValue).average().orElse(0)
        );

        logResults("Single StateHolder Read", singleReadResults);

        // Measure full snapshot (all StateHolders)
        log.info("Measuring full StateHolder snapshot ({} iterations)", MEASUREMENT_ITERATIONS);
        latencies.clear();

        for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
            long start = System.nanoTime();
            // Simulate snapshot capture (all StateHolder reads)
            boolean started = controlState.isStarted();
            boolean joinOngoing = controlState.isJoinOngoing();
            boolean hasCommittee = committeeState.hasCommittee();
            var head = blockChainState.getHead();
            var view = blockChainState.getView();
            var nextView = viewState.getNextViewId();
            int syncAttempts = asyncState.getSyncAttempts();
            long end = System.nanoTime();
            latencies.add(end - start);
        }

        Collections.sort(latencies);
        var fullSnapshotResults = new LatencyResults(
            "full_snapshot_unsynchronized",
            latencies.get((int) (MEASUREMENT_ITERATIONS * 0.50)),
            latencies.get((int) (MEASUREMENT_ITERATIONS * 0.95)),
            latencies.get((int) (MEASUREMENT_ITERATIONS * 0.99)),
            latencies.get((int) (MEASUREMENT_ITERATIONS * 0.999)),
            latencies.stream().mapToLong(Long::longValue).average().orElse(0)
        );

        logResults("Full Snapshot (unsynchronized)", fullSnapshotResults);

        // Sanity checks - AtomicReference.get() should be very fast (< 1μs)
        assertTrue(singleReadResults.p99Nanos() < 1_000, "Single StateHolder read should be under 1μs p99");
        assertTrue(fullSnapshotResults.p99Nanos() < 10_000, "Full snapshot should be under 10μs p99");
    }

    // Note: Synchronized snapshot cost will be measured in Phase 1 when implementing
    // CHOAMStateSnapshot.capture() using fsm.synchonizeOnState().

    /**
     * Comprehensive baseline test - measures StateHolder read costs.
     * Combined with PerformanceBaselineTest for end-to-end transaction metrics.
     */
    @Test
    public void establishComprehensiveBaseline() throws Exception {
        log.info("=== Establishing StateHolder Read Baseline ===");

        // Measure StateHolder reads (atomic reference costs)
        measureStateHolderReadLatency();

        log.info("=== Baseline Establishment Complete ===");
        log.info("StateHolder read costs established.");
        log.info("For end-to-end metrics, see PerformanceBaselineTest results.");
        log.info("Recommended SLA for validation overhead: p95 < 10μs, p99 < 25μs");
    }

    private void logResults(String component, LatencyResults results) {
        log.info("=== {} Baseline ===", component);
        log.info("p50:  {} ns ({} μs)", results.p50Nanos(), results.p50Nanos() / 1000.0);
        log.info("p95:  {} ns ({} μs)", results.p95Nanos(), results.p95Nanos() / 1000.0);
        log.info("p99:  {} ns ({} μs)", results.p99Nanos(), results.p99Nanos() / 1000.0);
        log.info("p999: {} ns ({} μs)", results.p999Nanos(), results.p999Nanos() / 1000.0);
        log.info("mean: {} ns ({} μs)", String.format("%.2f", results.meanNanos()),
                 String.format("%.2f", results.meanNanos() / 1000.0));
    }

    /**
     * Latency measurement results (all times in nanoseconds).
     */
    public record LatencyResults(
        String component,
        long p50Nanos,
        long p95Nanos,
        long p99Nanos,
        long p999Nanos,
        double meanNanos
    ) {}
}
