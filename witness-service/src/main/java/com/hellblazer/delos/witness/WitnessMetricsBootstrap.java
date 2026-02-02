/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import com.hellblazer.delos.witness.detection.ByzantineDetectionMetrics;
// import com.hellblazer.delos.witness.detection.ByzantineDetectionMetricsImpl;
import com.hellblazer.delos.witness.detection.ResponseOrchestrationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WitnessMetricsBootstrap: Central metrics lifecycle manager for Phase 1C Byzantine detection.
 * <p>
 * <strong>Responsibilities</strong>:
 * - Create and manage MetricRegistry lifecycle
 * - Instantiate and register all Phase 1C metrics implementations
 * - Provide accessor methods for metrics instances
 * - Manage reporters (JMX, Prometheus, etc.)
 * <p>
 * <strong>Design Pattern</strong>:
 * - Lazy initialization: metrics not created until explicitly requested
 * - Thread-safe: MetricRegistry is thread-safe, metrics are registered atomically
 * - Idempotent: register() calls are safe to call multiple times
 * <p>
 * <strong>Usage</strong>:
 * <pre>{@code
 *   var metricsBootstrap = new WitnessMetricsBootstrap();
 *   metricsBootstrap.startReporters(); // Enable JMX/Prometheus
 *
 *   // Get metrics and wire into components
 *   var byzantineMetrics = metricsBootstrap.getByzantineMetrics();
 *
 *   // Use in detectors, orchestrators, etc.
 *   var detector = new SignatureAnomalyDetector(config, byzantineMetrics);
 *
 *   // Shutdown on service termination
 *   metricsBootstrap.shutdown();
 * }</pre>
 * <p>
 * <strong>Metrics Included</strong>:
 * - Byzantine Detection (40+ metrics): Anomaly detection, escalation, quarantine, impact
 * - Response Orchestration: Escalation coordination metrics
 * <p>
 * <strong>Note on BLS Metrics</strong>:
 * BLS metrics have been migrated to Micrometer (MicrometerBLSMetrics).
 * Create BLS metrics separately using SimpleMeterRegistry or your preferred Micrometer registry.
 * <p>
 * <strong>Performance</strong>:
 * - Negligible overhead: <1% throughput impact (validated in Delos-3969)
 * - Lock-free atomic operations for thread safety
 * - No allocation on hot paths
 *
 * @author hal.hildebrand
 * @since 1.0 (Phase 1C-3-D)
 */
public class WitnessMetricsBootstrap implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WitnessMetricsBootstrap.class);

    private final MeterRegistry registry;
    // TODO: ByzantineDetectionMetricsImpl does not exist yet - restore when implemented
    // private final ByzantineDetectionMetricsImpl byzantineMetrics;
    private final ResponseOrchestrationMetrics orchestrationMetrics;

    /**
     * Create metrics bootstrap with new MeterRegistry.
     * <p>
     * Initializes all Phase 1C metrics implementations. Metrics are registered
     * with the registry but reporters are not started until startReporters() is called.
     */
    public WitnessMetricsBootstrap() {
        this.registry = new SimpleMeterRegistry();

        // TODO: Byzantine detection metrics implementation not yet available
        // this.byzantineMetrics = new ByzantineDetectionMetricsImpl();
        // this.byzantineMetrics.register(registry);
        // log.debug("Byzantine detection metrics registered");

        // Create response orchestration metrics (standalone, no register needed)
        this.orchestrationMetrics = new ResponseOrchestrationMetrics();

        log.info("WitnessMetricsBootstrap initialized");
    }

    /**
     * Get the MeterRegistry for querying metrics.
     * <p>
     * Use this to directly access or monitor metrics programmatically.
     *
     * @return MeterRegistry containing all Phase 1C metrics
     */
    public MeterRegistry getRegistry() {
        return registry;
    }

    /**
     * Get Byzantine detection metrics instance.
     * <p>
     * Use this when creating detectors, orchestrators, and escalation coordinators.
     *
     * @return Byzantine detection metrics (currently null - implementation pending)
     */
    public ByzantineDetectionMetrics getByzantineMetrics() {
        // TODO: Return actual implementation when ByzantineDetectionMetricsImpl exists
        return null;  // byzantineMetrics;
    }


    /**
     * Get response orchestration metrics instance.
     * <p>
     * Tracks escalation coordinator performance and state transitions.
     *
     * @return Response orchestration metrics
     */
    public ResponseOrchestrationMetrics getOrchestrationMetrics() {
        return orchestrationMetrics;
    }

    /**
     * Start metrics reporters and bind JVM metrics.
     * <p>
     * Enables metrics to be exported for monitoring:
     * - JVM metrics: Memory, GC, ClassLoader, Processor
     * - Prometheus: Requires HTTP endpoint configuration (see Micrometer docs)
     * <p>
     * Safe to call multiple times.
     */
    public void startReporters() {
        // Bind JVM metrics to registry
        new ClassLoaderMetrics().bindTo(registry);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);

        log.info("JVM metrics bound to registry");
        log.debug("Metrics reporters started");
    }

    /**
     * Stop all reporters and clean up resources.
     * <p>
     * Should be called during service shutdown to cleanly close reporters.
     * Safe to call multiple times.
     */
    @Override
    public void close() {
        shutdown();
    }

    /**
     * Shutdown metrics bootstrap.
     * <p>
     * Cleans up resources.
     * Safe to call multiple times.
     */
    public void shutdown() {
        // Micrometer registries handle cleanup automatically
        log.info("WitnessMetricsBootstrap shutdown complete");
    }
}
