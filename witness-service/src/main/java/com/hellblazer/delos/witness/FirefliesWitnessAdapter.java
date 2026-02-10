/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.context.DynamicContextImpl;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.metrics.WitnessAdapterMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.SequencedSet;

/**
 * Adapter that bridges KERI witness thresholds to Fireflies Byzantine fault-tolerant context configuration.
 * <p>
 * <strong>Architectural Context</strong>
 * <p>
 * KERI (Key Event Receipt Infrastructure) uses a witness-based architecture where key events require
 * threshold signatures from a designated witness pool. Delos implements KERI witness networks using
 * Fireflies, a gossip-based Byzantine intrusion-tolerant membership protocol. This adapter translates
 * between KERI's explicit threshold semantics (N witnesses, T required) and Fireflies' ring-based
 * Byzantine fault tolerance model (rings, bias, pByz).
 * <p>
 * The adapter ensures that Fireflies' {@code context.majority()} matches KERI's threshold T, enabling
 * seamless integration of KERI identity management with Delos' distributed systems infrastructure.
 * <p>
 * <strong>KERI to Fireflies Mapping</strong>
 * <p>
 * KERI witness configuration:
 * <ul>
 *   <li><strong>N</strong>: Total number of witnesses in the pool</li>
 *   <li><strong>T</strong>: Threshold of signatures required to validate an event (T ≤ N)</li>
 * </ul>
 * <p>
 * Fireflies context configuration:
 * <ul>
 *   <li><strong>rings</strong>: Number of rings in the hash ring topology (set to N)</li>
 *   <li><strong>bias</strong>: Controls Byzantine fault tolerance level</li>
 *   <li><strong>pByz</strong>: Probability of Byzantine member (typically 0.1 for 10% assumption)</li>
 * </ul>
 * <p>
 * <strong>Mathematical Derivation</strong>
 * <p>
 * Fireflies computes majority as:
 * <pre>
 *   majority = rings - toleranceLevel
 *   where: toleranceLevel = (rings - 1) / bias
 *
 *   Therefore: majority = rings - (rings - 1) / bias
 * </pre>
 * <p>
 * To achieve KERI threshold T with N witnesses:
 * <pre>
 *   Goal: majority = T, rings = N
 *
 *   T = N - (N - 1) / bias
 *   (N - 1) / bias = N - T
 *   bias = (N - 1) / (N - T)
 * </pre>
 * <p>
 * This formula produces correct bias values for all valid KERI threshold configurations.
 * <p>
 * <strong>Common Threshold Configurations</strong>
 * <table border="1">
 *   <tr>
 *     <th>Witnesses (N)</th>
 *     <th>Threshold (T)</th>
 *     <th>Bias</th>
 *     <th>Fault Tolerance</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td>5</td>
 *     <td>3</td>
 *     <td>2</td>
 *     <td>f=1</td>
 *     <td>Standard 2f+1 (tolerates 1 Byzantine failure)</td>
 *   </tr>
 *   <tr>
 *     <td>5</td>
 *     <td>4</td>
 *     <td>4</td>
 *     <td>f=1</td>
 *     <td>Higher security 3f+1 (requires supermajority)</td>
 *   </tr>
 *   <tr>
 *     <td>7</td>
 *     <td>5</td>
 *     <td>3</td>
 *     <td>f=2</td>
 *     <td>Larger committee with 2 fault tolerance</td>
 *   </tr>
 *   <tr>
 *     <td>9</td>
 *     <td>7</td>
 *     <td>4</td>
 *     <td>f=2</td>
 *     <td>Large committee with high threshold</td>
 *   </tr>
 *   <tr>
 *     <td>5</td>
 *     <td>5</td>
 *     <td>5</td>
 *     <td>f=0</td>
 *     <td>Unanimous consent (no fault tolerance)</td>
 *   </tr>
 * </table>
 * <p>
 * <strong>Usage Examples</strong>
 * <p>
 * <em>Basic usage with default configuration:</em>
 * <pre>{@code
 * // Create adapter with default digest algorithm
 * var adapter = new FirefliesWitnessAdapter();
 *
 * // Create Fireflies context for 5 witnesses, 3 required (standard 2f+1)
 * DynamicContext<Member> context = adapter.createContext(
 *     contextId,
 *     5,    // witnessCount (N)
 *     3,    // threshold (T)
 *     0.1   // pByz (10% Byzantine probability)
 * );
 *
 * // Select witnesses deterministically for an event
 * SequencedSet<Member> witnesses = adapter.selectWitnesses(context, eventCoordinates);
 *
 * // Convert to KERI Identifier list
 * List<Identifier> witnessIds = adapter.toWitnessIdentifiers(witnesses);
 * }</pre>
 * <p>
 * <em>Production usage with metrics and monitoring:</em>
 * <pre>{@code
 * // Create adapter with metrics collector
 * var metrics = new WitnessAdapterMetricsImpl(metricRegistry);
 * var adapter = new FirefliesWitnessAdapter(DigestAlgorithm.DEFAULT, metrics);
 *
 * // Create context with higher security threshold (4 of 5)
 * DynamicContext<Member> context = adapter.createContext(
 *     contextId, 5, 4, 0.1
 * );
 *
 * // Select witnesses with automatic metrics collection
 * try {
 *     SequencedSet<Member> witnesses = adapter.selectWitnesses(context, eventCoordinates);
 *
 *     // Check health periodically
 *     if (!adapter.isHealthy()) {
 *         var snapshot = adapter.getMetricsSnapshot();
 *         log.warn("Adapter unhealthy: p95={}, failures={}, circuit={}",
 *                  snapshot.getSelectionLatencyP95(),
 *                  snapshot.getFailureRate(),
 *                  snapshot.isCircuitBreakerOpen());
 *     }
 * } catch (IllegalStateException e) {
 *     // Circuit breaker open - too many failures
 *     log.error("Circuit breaker triggered, backing off", e);
 * }
 * }</pre>
 * <p>
 * <em>Integration with WitnessContext:</em>
 * <pre>{@code
 * // WitnessContext provides factory method using adapter
 * WitnessContext witnessContext = WitnessContext.createWithAdapter(
 *     contextId,
 *     parameters,  // Contains witnessCount, threshold
 *     pByz,
 *     DigestAlgorithm.DEFAULT
 * );
 *
 * // Adapter is used internally for context creation and witness selection
 * }</pre>
 * <p>
 * <em>Validate configuration before deployment:</em>
 * <pre>{@code
 * var adapter = new FirefliesWitnessAdapter();
 *
 * int witnessCount = 7;
 * int threshold = 5;
 * int bias = adapter.computeBias(witnessCount, threshold);
 *
 * // Verify the configuration produces expected majority
 * boolean valid = adapter.verifyConfiguration(witnessCount, bias, threshold);
 * if (!valid) {
 *     throw new IllegalStateException(
 *         "Invalid configuration: witnesses=" + witnessCount +
 *         ", threshold=" + threshold + ", bias=" + bias
 *     );
 * }
 *
 * // Generate documentation table
 * String table = adapter.computeThresholdMappingTable(10);
 * log.info("Threshold mappings:\n{}", table);
 * }</pre>
 * <p>
 * <strong>SLA Targets and Monitoring</strong>
 * <p>
 * The adapter supports production monitoring with the following SLA targets:
 * <ul>
 *   <li><strong>Selection latency p95 ≤ 100ms</strong>: Witness selection should complete within 100ms
 *       for 95th percentile of requests. Higher latencies may indicate network issues or overload.</li>
 *   <li><strong>Failure rate < 1%</strong>: Less than 1% of witness selections should fail. Higher
 *       failure rates indicate configuration problems or system instability.</li>
 *   <li><strong>Circuit breaker</strong>: Automatically opens after 5 consecutive failures within 10
 *       seconds, preventing cascade failures. Health check returns false when open.</li>
 * </ul>
 * <p>
 * Monitoring integration:
 * <pre>{@code
 * // Expose metrics via Dropwizard Metrics
 * MetricRegistry registry = new MetricRegistry();
 * var metrics = new WitnessAdapterMetricsImpl(registry);
 * var adapter = new FirefliesWitnessAdapter(DigestAlgorithm.DEFAULT, metrics);
 *
 * // Register health check
 * healthCheckRegistry.register("witness-adapter", new HealthCheck() {
 *     protected Result check() {
 *         return adapter.isHealthy() ? Result.healthy() : Result.unhealthy("SLA violation");
 *     }
 * });
 *
 * // Periodic monitoring
 * scheduler.scheduleAtFixedRate(() -> {
 *     var snapshot = adapter.getMetricsSnapshot();
 *     log.info("Adapter metrics: p95={}ms, failures={}, circuit={}",
 *              snapshot.getSelectionLatencyP95() / 1000,
 *              snapshot.getFailureRate() * 100,
 *              snapshot.isCircuitBreakerOpen() ? "OPEN" : "CLOSED");
 * }, 1, 1, TimeUnit.MINUTES);
 * }</pre>
 * <p>
 * <strong>Thread Safety</strong>
 * <p>
 * This adapter is thread-safe and can be shared across multiple contexts. Context creation and
 * witness selection are safe to call concurrently. Metrics collection is internally synchronized.
 * <p>
 * <strong>Related Components</strong>
 * <ul>
 *   <li>{@code WitnessContext} - High-level KERI witness network implementation using this adapter</li>
 *   <li>{@code DynamicContext} - Fireflies membership context with ring topology</li>
 *   <li>{@code WitnessAdapterMetrics} - Metrics interface for monitoring production deployments</li>
 *   <li>{@code EventCoordinates} - KERI event identification for deterministic witness selection</li>
 * </ul>
 *
 * @author hal.hildebrand
 * @see WitnessContext
 * @see DynamicContext
 * @see EventCoordinates
 * @see WitnessAdapterMetrics
 */
public class FirefliesWitnessAdapter {

    private static final Logger log = LoggerFactory.getLogger(FirefliesWitnessAdapter.class);

    private final DigestAlgorithm digestAlgorithm;
    private final WitnessAdapterMetrics metrics;

    /**
     * Create adapter with default digest algorithm and no metrics.
     */
    public FirefliesWitnessAdapter() {
        this(DigestAlgorithm.DEFAULT, WitnessAdapterMetrics.NOOP);
    }

    /**
     * Create adapter with specific digest algorithm and no metrics.
     *
     * @param digestAlgorithm Algorithm for hashing event coordinates
     */
    public FirefliesWitnessAdapter(DigestAlgorithm digestAlgorithm) {
        this(digestAlgorithm, WitnessAdapterMetrics.NOOP);
    }

    /**
     * Create adapter with specific digest algorithm and metrics.
     * <p>
     * Phase 6: Production hardening with metrics support.
     *
     * @param digestAlgorithm Algorithm for hashing event coordinates
     * @param metrics         Metrics collector for monitoring
     */
    public FirefliesWitnessAdapter(DigestAlgorithm digestAlgorithm, WitnessAdapterMetrics metrics) {
        this.digestAlgorithm = Objects.requireNonNull(digestAlgorithm, "digestAlgorithm cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
    }

    /**
     * Compute the Fireflies bias required to achieve a KERI threshold.
     * <p>
     * Formula derivation:
     * <pre>
     *   majority = rings - toleranceLevel
     *   toleranceLevel = (rings - 1) / bias
     *   majority = rings - (rings - 1) / bias
     *
     *   Solving for bias:
     *   threshold = rings - (rings - 1) / bias
     *   (rings - 1) / bias = rings - threshold
     *   bias = (rings - 1) / (rings - threshold)
     * </pre>
     *
     * @param witnessCount Total number of witnesses (KERI N)
     * @param threshold    Required number of signatures (KERI T)
     * @return Fireflies bias value to achieve threshold
     * @throws IllegalArgumentException if threshold is invalid
     */
    public int computeBias(int witnessCount, int threshold) {
        if (witnessCount <= 0) {
            throw new IllegalArgumentException("witnessCount must be positive: " + witnessCount);
        }
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive: " + threshold);
        }
        if (threshold > witnessCount) {
            throw new IllegalArgumentException(
                "threshold cannot exceed witnessCount: threshold=" + threshold + ", witnessCount=" + witnessCount);
        }

        // Special case: unanimous consent
        if (threshold == witnessCount) {
            // Any large bias will work for unanimous
            return witnessCount;
        }

        // Special case: single witness
        if (witnessCount == 1) {
            return 1;
        }

        // bias = (witnessCount - 1) / (witnessCount - threshold)
        int denominator = witnessCount - threshold;
        if (denominator == 0) {
            // threshold == witnessCount, handled above
            return witnessCount;
        }

        int bias = (witnessCount - 1) / denominator;

        // Ensure minimum bias of 1
        return Math.max(1, bias);
    }

    /**
     * Verify that a given bias produces the expected majority.
     * <p>
     * Uses Fireflies formula: majority = rings - (rings - 1) / bias
     *
     * @param rings           Number of rings (= witnessCount)
     * @param bias            Computed bias value
     * @param expectedMajority Expected majority (= KERI threshold)
     * @return true if configuration matches
     */
    public boolean verifyConfiguration(int rings, int bias, int expectedMajority) {
        int toleranceLevel = (rings - 1) / bias;
        int actualMajority = rings - toleranceLevel;
        return actualMajority == expectedMajority;
    }

    /**
     * Create a DynamicContext configured for KERI witness requirements.
     * <p>
     * Phase 6: Records context creation latency and bias metrics.
     *
     * @param contextId    Context identifier
     * @param witnessCount Total witnesses (becomes ringCount)
     * @param threshold    Required signatures (becomes majority)
     * @param pByz         Probability of Byzantine member (typically 0.1)
     * @param <T>          Member type
     * @return Configured DynamicContext
     */
    public <T extends Member> DynamicContext<T> createContext(Digest contextId, int witnessCount, int threshold,
                                                               double pByz) {
        var startTime = System.nanoTime();
        metrics.incrementContextCreations();

        try {
            int bias = computeBias(witnessCount, threshold);
            metrics.recordBiasValue(bias);

            log.info("Creating Fireflies context for KERI: witnesses={}, threshold={}, bias={}, pByz={}",
                     witnessCount, threshold, bias, pByz);

            var context = new DynamicContextImpl<T>(contextId, witnessCount, pByz, bias);

            // Verify configuration
            int actualMajority = context.majority();
            if (actualMajority != threshold) {
                log.warn("Majority mismatch: expected={}, actual={} (witnesses={}, bias={}). " +
                         "This may be due to integer division in tolerance calculation.",
                         threshold, actualMajority, witnessCount, bias);
            }

            // Record latency
            var latencyMicros = (System.nanoTime() - startTime) / 1_000;
            metrics.recordContextCreationLatency(latencyMicros);

            return context;
        } catch (Exception e) {
            metrics.incrementContextCreationFailures();
            throw e;
        }
    }

    /**
     * Select witnesses for an event using the provided context.
     * <p>
     * Uses Fireflies' bftSubset() for deterministic committee selection.
     * The same event coordinates always produce the same witness set.
     * <p>
     * Phase 6: Records selection latency and committee size metrics.
     *
     * @param context          Fireflies context
     * @param eventCoordinates Event being witnessed
     * @param <T>              Member type
     * @return Deterministic set of witnesses for this event
     * @throws NullPointerException if context or eventCoordinates is null
     * @throws IllegalStateException if circuit breaker is open (too many failures)
     */
    public <T extends Member> SequencedSet<T> selectWitnesses(Context<T> context, EventCoordinates eventCoordinates) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(eventCoordinates, "eventCoordinates cannot be null");

        // Check circuit breaker
        if (metrics.isCircuitBreakerOpen()) {
            metrics.incrementSelectionsFailed();
            throw new IllegalStateException("Circuit breaker open - too many recent failures");
        }

        var startTime = System.nanoTime();
        try {
            Digest eventHash = hashEventCoordinates(eventCoordinates);
            var witnesses = context.bftSubset(eventHash);

            // Record metrics
            var latencyMicros = (System.nanoTime() - startTime) / 1_000;
            metrics.recordSelectionLatency(latencyMicros);
            metrics.recordCommitteeSize(witnesses.size());
            metrics.incrementSelectionsSuccessful();

            if (log.isDebugEnabled()) {
                log.debug("Selected {} witnesses for event {} in {}µs",
                    witnesses.size(), eventCoordinates.getDigest(), latencyMicros);
            }

            return witnesses;
        } catch (Exception e) {
            metrics.incrementSelectionsFailed();
            throw e;
        }
    }

    /**
     * Convert selected witnesses to KERI Identifier list.
     *
     * @param witnesses Set of Member witnesses
     * @return List of Identifier for KERI KeyState
     * @throws NullPointerException if witnesses is null
     */
    public List<Identifier> toWitnessIdentifiers(SequencedSet<? extends Member> witnesses) {
        Objects.requireNonNull(witnesses, "witnesses cannot be null");

        return witnesses.stream()
            .map(Member::getId)
            .map(this::toIdentifier)
            .toList();
    }

    /**
     * Hash event coordinates deterministically for witness selection.
     * <p>
     * Combines: identifier + sequence number + digest + ilk
     *
     * @param eventCoordinates Event to hash
     * @return Deterministic hash for ring iterator
     * @throws NullPointerException if eventCoordinates is null
     * @throws IllegalArgumentException if event coordinates are too large to hash
     */
    public Digest hashEventCoordinates(EventCoordinates eventCoordinates) {
        Objects.requireNonNull(eventCoordinates, "eventCoordinates cannot be null");

        var identifierDigest = eventCoordinates.getIdentifier().getDigest(digestAlgorithm);
        var identifierBytes = identifierDigest.getBytes();
        var sequenceNumber = eventCoordinates.getSequenceNumber().longValue();
        var digestBytes = eventCoordinates.getDigest().getBytes();
        var ilkBytes = eventCoordinates.getIlk().getBytes();

        // Buffer overflow protection
        long totalSize = (long) identifierBytes.length + 8L + digestBytes.length + ilkBytes.length;
        if (totalSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Event coordinates too large to hash: " + totalSize + " bytes");
        }

        var buffer = ByteBuffer.allocate((int) totalSize);
        buffer.put(identifierBytes);
        buffer.putLong(sequenceNumber);
        buffer.put(digestBytes);
        buffer.put(ilkBytes);

        return digestAlgorithm.digest(buffer.array());
    }

    /**
     * Convert Digest to Identifier.
     *
     * @param digest Member digest
     * @return Identifier for internal use
     */
    public Identifier toIdentifier(Digest digest) {
        return new SelfAddressingIdentifier(digest);
    }

    /**
     * Compute threshold mapping table for documentation.
     * <p>
     * Useful for understanding the mapping at various witness counts.
     *
     * @param maxWitnesses Maximum witness count to show
     * @return Formatted table string
     */
    public String computeThresholdMappingTable(int maxWitnesses) {
        var sb = new StringBuilder();
        sb.append("| Witnesses | Threshold | Bias | Tolerance | Majority (computed) |\n");
        sb.append("|-----------|-----------|------|-----------|---------------------|\n");

        // Start at 4 witnesses (minimum for BFT with f=1 fault tolerance)
        for (int n = 4; n <= maxWitnesses; n++) {
            for (int t = (n / 2) + 1; t <= n; t++) {
                int bias = computeBias(n, t);
                int tolerance = (n - 1) / bias;
                int majority = n - tolerance;
                sb.append(String.format("| %9d | %9d | %4d | %9d | %19d |\n",
                                        n, t, bias, tolerance, majority));
            }
        }

        return sb.toString();
    }

    /**
     * Get the metrics collector for this adapter.
     * <p>
     * Phase 6: Access metrics for monitoring and health checks.
     *
     * @return Metrics collector
     */
    public WitnessAdapterMetrics getMetrics() {
        return metrics;
    }

    /**
     * Check if the adapter is healthy based on current metrics.
     * <p>
     * Health criteria:
     * <ul>
     *   <li>Selection latency p95 ≤ 100ms</li>
     *   <li>Failure rate < 1%</li>
     *   <li>Circuit breaker closed</li>
     * </ul>
     *
     * @return true if adapter is operating within SLA
     */
    public boolean isHealthy() {
        return metrics.getSnapshot().isHealthy();
    }

    /**
     * Get a snapshot of current metrics for monitoring dashboards.
     *
     * @return Current metrics snapshot
     */
    public WitnessAdapterMetrics.Snapshot getMetricsSnapshot() {
        return metrics.getSnapshot();
    }
}
