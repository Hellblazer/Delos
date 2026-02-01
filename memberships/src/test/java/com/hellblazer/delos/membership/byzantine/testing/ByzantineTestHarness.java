/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine.testing;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.byzantine.*;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-layer test harness for Byzantine detection testing.
 * <p>
 * Provides a complete test environment with:
 * <ul>
 *   <li>Multiple layer fault injectors</li>
 *   <li>Preconfigured coordinator</li>
 *   <li>Response handler tracking</li>
 *   <li>Metrics collection</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>{@code
 * try (var harness = ByzantineTestHarness.builder()
 *         .withDeterministicEntropy()
 *         .withFixedClock()
 *         .withLayers("fireflies", "thoth", "gorgoneion")
 *         .build()) {
 *
 *     var member = harness.createMember("test-member");
 *
 *     // Inject fault on fireflies layer
 *     harness.injectFault("fireflies", member, FaultType.EQUIVOCATION);
 *
 *     // Run detection
 *     var result = harness.evaluate(member);
 *     assertThat(result.isAnomalous()).isTrue();
 *
 *     // Check metrics
 *     assertThat(harness.getMetrics().getTruePositiveRate()).isGreaterThan(0.95);
 * }
 * }</pre>
 *
 * @author hal.hildebrand
 */
public class ByzantineTestHarness implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ByzantineTestHarness.class);

    private final SecureRandom entropy;
    private final Clock clock;
    private final Map<String, ByzantineFaultInjector> layerInjectors;
    private final IntelligenceConfig config;
    private final ByzantineMetricsCollector metrics;
    private final TrackingResponseHandler responseHandler;
    private final List<FaultInjectionHandle> activeHandles;
    private final Map<Identifier, MemberRiskProfile> memberProfiles;

    private ByzantineTestHarness(Builder builder) {
        this.entropy = builder.entropy;
        this.clock = builder.clock;
        this.layerInjectors = new HashMap<>();
        this.metrics = new ByzantineMetricsCollector();
        this.responseHandler = new TrackingResponseHandler();
        this.activeHandles = Collections.synchronizedList(new ArrayList<>());
        this.memberProfiles = new ConcurrentHashMap<>();

        // Create injectors for each layer
        for (var layerName : builder.layers) {
            layerInjectors.put(layerName, new ByzantineFaultInjector(layerName, entropy, clock));
        }

        // Create config
        this.config = builder.config != null ? builder.config :
            IntelligenceConfig.builder()
                              .criticalThreshold(0.8)
                              .warningThreshold(0.4)
                              .defaultPollInterval(Duration.ofMillis(100))
                              .build();

        log.info("Created ByzantineTestHarness with {} layers", builder.layers.size());
    }

    /**
     * Creates a builder for the test harness.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a member identifier for testing.
     *
     * @param seed deterministic seed
     * @return member identifier
     */
    public Identifier createMember(String seed) {
        var digest = DigestAlgorithm.DEFAULT.digest(seed);
        return new SelfAddressingIdentifier(digest);
    }

    /**
     * Injects a fault on a specific layer.
     *
     * @param layerName the layer to inject on
     * @param memberId  the member to inject for
     * @param faultType the type of fault
     * @return injection handle for restoration
     */
    public FaultInjectionHandle injectFault(String layerName, Identifier memberId, FaultType faultType) {
        return injectFault(layerName, memberId, faultType, Duration.ofMinutes(10));
    }

    /**
     * Injects a fault with custom duration.
     *
     * @param layerName the layer to inject on
     * @param memberId  the member
     * @param faultType the fault type
     * @param duration  fault duration
     * @return injection handle
     */
    public FaultInjectionHandle injectFault(String layerName, Identifier memberId,
                                            FaultType faultType, Duration duration) {
        var injector = layerInjectors.get(layerName);
        if (injector == null) {
            throw new IllegalArgumentException("Unknown layer: " + layerName);
        }

        var handle = injector.injectFault(memberId, faultType, duration);
        activeHandles.add(handle);
        metrics.recordInjection(memberId, faultType);

        return handle;
    }

    /**
     * Injects faults on multiple layers for the same member.
     *
     * @param memberId   the member
     * @param faultTypes map of layer name to fault type
     * @return list of injection handles
     */
    public List<FaultInjectionHandle> injectMultiLayerFaults(Identifier memberId,
                                                              Map<String, FaultType> faultTypes) {
        var handles = new ArrayList<FaultInjectionHandle>();
        for (var entry : faultTypes.entrySet()) {
            handles.add(injectFault(entry.getKey(), memberId, entry.getValue()));
        }
        return handles;
    }

    /**
     * Evaluates a member and records metrics.
     *
     * @param memberId the member to evaluate
     * @return detection result
     */
    public DetectionResult evaluate(Identifier memberId) {
        // Poll all layers for this member
        var profile = memberProfiles.computeIfAbsent(memberId, id -> new MemberRiskProfile(id, config));

        for (var injector : layerInjectors.values()) {
            var state = injector.getMemberState(memberId);
            state.ifPresent(profile::updateLayerState);
        }

        var result = DetectionResult.from(profile);
        metrics.recordDetection(memberId, result);
        return result;
    }

    /**
     * Runs a full polling cycle for all tracked members.
     */
    public void runPollingCycle() {
        // Collect all members with active faults
        var allMembers = new HashSet<Identifier>();
        for (var injector : layerInjectors.values()) {
            allMembers.addAll(injector.getMemberAnomalyStates().keySet());
        }

        // Evaluate each and trigger responses
        for (var memberId : allMembers) {
            var result = evaluate(memberId);

            if (result.isCritical()) {
                var profile = memberProfiles.get(memberId);
                responseHandler.handleCritical(memberId, profile);
            } else if (result.isAnomalous()) {
                var profile = memberProfiles.get(memberId);
                responseHandler.handleWarning(memberId, profile);
            }
        }
    }

    /**
     * Gets the metrics collector.
     *
     * @return metrics
     */
    public ByzantineMetricsCollector getMetrics() {
        return metrics;
    }

    /**
     * Gets the response handler for verification.
     *
     * @return response handler
     */
    public TrackingResponseHandler getResponseHandler() {
        return responseHandler;
    }

    /**
     * Gets the configuration.
     *
     * @return config
     */
    public IntelligenceConfig getConfig() {
        return config;
    }

    /**
     * Gets a specific layer injector.
     *
     * @param layerName the layer name
     * @return the injector or null
     */
    public ByzantineFaultInjector getInjector(String layerName) {
        return layerInjectors.get(layerName);
    }

    /**
     * Gets the clock used by this harness.
     *
     * @return the clock
     */
    public Clock getClock() {
        return clock;
    }

    /**
     * Gets the entropy source.
     *
     * @return secure random
     */
    public SecureRandom getEntropy() {
        return entropy;
    }

    /**
     * Restores all active faults.
     */
    public void restoreAllFaults() {
        for (var handle : activeHandles) {
            handle.restore();
        }
        activeHandles.clear();
    }

    /**
     * Resets the harness state.
     */
    public void reset() {
        restoreAllFaults();
        for (var injector : layerInjectors.values()) {
            injector.reset();
        }
        memberProfiles.clear();
        metrics.reset();
        responseHandler.reset();
    }

    @Override
    public void close() {
        restoreAllFaults();
        memberProfiles.clear();
    }

    // ========== Builder ==========

    /**
     * Builder for ByzantineTestHarness.
     */
    public static class Builder {
        private SecureRandom entropy;
        private Clock clock;
        private List<String> layers = new ArrayList<>();
        private IntelligenceConfig config;

        /**
         * Uses deterministic entropy (seeded random).
         *
         * @return this builder
         */
        public Builder withDeterministicEntropy() {
            try {
                this.entropy = SecureRandom.getInstance("SHA1PRNG");
                this.entropy.setSeed(new byte[] { 6, 6, 6 });
            } catch (Exception e) {
                throw new RuntimeException("Failed to create deterministic entropy", e);
            }
            return this;
        }

        /**
         * Uses custom entropy source.
         *
         * @param entropy the secure random
         * @return this builder
         */
        public Builder withEntropy(SecureRandom entropy) {
            this.entropy = entropy;
            return this;
        }

        /**
         * Uses a fixed clock for deterministic timing.
         *
         * @return this builder
         */
        public Builder withFixedClock() {
            this.clock = Clock.fixed(Instant.parse("2026-01-31T12:00:00Z"), ZoneId.of("UTC"));
            return this;
        }

        /**
         * Uses a custom clock.
         *
         * @param clock the clock
         * @return this builder
         */
        public Builder withClock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Adds layers to test.
         *
         * @param layerNames layer names
         * @return this builder
         */
        public Builder withLayers(String... layerNames) {
            this.layers.addAll(Arrays.asList(layerNames));
            return this;
        }

        /**
         * Uses standard Delos layers.
         *
         * @return this builder
         */
        public Builder withStandardLayers() {
            return withLayers(
                IntelligenceConfig.LAYER_FIREFLIES,
                IntelligenceConfig.LAYER_THOTH,
                IntelligenceConfig.LAYER_GORGONEION
            );
        }

        /**
         * Uses custom intelligence config.
         *
         * @param config the config
         * @return this builder
         */
        public Builder withConfig(IntelligenceConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Builds the test harness.
         *
         * @return new harness
         */
        public ByzantineTestHarness build() {
            if (entropy == null) {
                withDeterministicEntropy();
            }
            if (clock == null) {
                withFixedClock();
            }
            if (layers.isEmpty()) {
                withStandardLayers();
            }
            return new ByzantineTestHarness(this);
        }
    }

    // ========== Tracking Response Handler ==========

    /**
     * Response handler that tracks all responses for test verification.
     */
    public static class TrackingResponseHandler implements ResponseHandler {

        private final List<Identifier> criticalResponses = Collections.synchronizedList(new ArrayList<>());
        private final List<Identifier> warningResponses = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableFuture<Boolean> handleCritical(Identifier memberId, MemberRiskProfile profile) {
            criticalResponses.add(memberId);
            log.info("Critical detection handled for: {}", memberId);
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Void> handleWarning(Identifier memberId, MemberRiskProfile profile) {
            warningResponses.add(memberId);
            log.info("Warning detection handled for: {}", memberId);
            return CompletableFuture.completedFuture(null);
        }

        /**
         * Gets all critical responses.
         *
         * @return list of member IDs
         */
        public List<Identifier> getCriticalResponses() {
            return List.copyOf(criticalResponses);
        }

        /**
         * Gets all warning responses.
         *
         * @return list of member IDs
         */
        public List<Identifier> getWarningResponses() {
            return List.copyOf(warningResponses);
        }

        /**
         * Checks if a member had critical response.
         *
         * @param memberId the member
         * @return true if critical
         */
        public boolean hadCriticalResponse(Identifier memberId) {
            return criticalResponses.contains(memberId);
        }

        /**
         * Checks if a member had warning response.
         *
         * @param memberId the member
         * @return true if warned
         */
        public boolean hadWarningResponse(Identifier memberId) {
            return warningResponses.contains(memberId);
        }

        /**
         * Resets tracking state.
         */
        public void reset() {
            criticalResponses.clear();
            warningResponses.clear();
        }
    }
}
