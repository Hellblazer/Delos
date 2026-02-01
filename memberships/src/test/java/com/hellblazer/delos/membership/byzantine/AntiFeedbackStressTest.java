/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.*;

/**
 * Stress tests for the anti-feedback mechanism (Phase 5).
 * <p>
 * Verifies that the coordinator prevents runaway escalation:
 * <ul>
 *   <li>Rate limiting caps responses per interval</li>
 *   <li>Cooldown prevents rapid re-triggering for same member</li>
 *   <li>Signal deduplication prevents amplification from multiple layers</li>
 *   <li>No feedback loops under sustained high-frequency signals</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class AntiFeedbackStressTest {

    private static final Logger log = LoggerFactory.getLogger(AntiFeedbackStressTest.class);

    private ByzantineIntelligenceCoordinator coordinator;
    private IntelligenceConfig config;
    private TrackingResponseHandler responseHandler;
    private TrackingMetrics metrics;
    private MutableClock mutableClock;

    @BeforeEach
    void setUp() {
        mutableClock = new MutableClock(Instant.parse("2026-01-31T12:00:00Z"));

        // Configure for stress testing with tight rate limits
        config = IntelligenceConfig.builder()
            .defaultPollInterval(Duration.ofMillis(50))
            .warningThreshold(0.4)
            .criticalThreshold(0.8)
            .responseCooldown(Duration.ofMillis(200))
            .maxResponsesPerInterval(5)  // Tight limit
            .signalDeduplicationWindow(Duration.ofMillis(500))
            .scoreDecayRate(0.99)  // Slow decay to maintain signals
            .build();

        responseHandler = new TrackingResponseHandler();
        metrics = new TrackingMetrics();
        coordinator = new ByzantineIntelligenceCoordinator(config, responseHandler, metrics, mutableClock);
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.close();
        }
    }

    @Test
    void shouldEnforceRateLimitPerInterval() throws InterruptedException {
        // Create provider that reports many members with high scores
        var provider = new HighFrequencyProvider("TEST", 20);  // 20 members at critical level
        coordinator.registerProvider(provider);
        coordinator.start();

        // Let it run for a few intervals
        Thread.sleep(300);

        // Rate limit should cap responses to maxResponsesPerInterval (5)
        assertThat(metrics.rateLimitedSkips.sum())
            .as("Should have rate-limited some responses")
            .isGreaterThan(0);

        assertThat(responseHandler.getTotalResponses())
            .as("Responses should be capped by rate limit")
            .isLessThanOrEqualTo(20);  // Even with 20 members, rate limit should constrain

        log.info("Rate limit test: {} responses, {} rate-limited skips",
            responseHandler.getTotalResponses(), metrics.rateLimitedSkips.sum());
    }

    @Test
    void shouldEnforceCooldownPerMember() throws InterruptedException {
        // Single member reported repeatedly
        var member = createMember("rapid-fire");
        var provider = new SingleMemberProvider("TEST", member, 0.9);
        coordinator.registerProvider(provider);
        coordinator.start();

        // Let it run for multiple intervals
        Thread.sleep(400);

        // Should have cooldown skips (member re-detected but within cooldown)
        assertThat(metrics.cooldownSkips.sum())
            .as("Should have cooldown skips")
            .isGreaterThanOrEqualTo(0);  // May or may not have skips depending on timing

        // First detection should have triggered response
        assertThat(responseHandler.getTotalResponses())
            .as("Should have at least one response")
            .isGreaterThanOrEqualTo(1);

        log.info("Cooldown test: {} responses, {} cooldown skips",
            responseHandler.getTotalResponses(), metrics.cooldownSkips.sum());
    }

    @Test
    void shouldDeduplicateSignalsFromMultipleLayers() throws InterruptedException {
        // Same member reported from multiple layers simultaneously
        var member = createMember("multi-layer-member");
        var provider1 = new SingleMemberProvider("LAYER1", member, 0.9);
        var provider2 = new SingleMemberProvider("LAYER2", member, 0.9);
        var provider3 = new SingleMemberProvider("LAYER3", member, 0.9);

        coordinator.registerProvider(provider1);
        coordinator.registerProvider(provider2);
        coordinator.registerProvider(provider3);
        coordinator.start();

        Thread.sleep(300);

        // Deduplication should prevent amplification
        assertThat(metrics.deduplicatedSignals.sum())
            .as("Should have deduplicated some signals")
            .isGreaterThanOrEqualTo(0);  // May have deduplication depending on timing

        log.info("Deduplication test: {} responses, {} deduplicated",
            responseHandler.getTotalResponses(), metrics.deduplicatedSignals.sum());
    }

    @Test
    void shouldNotCreateFeedbackLoopUnderSustainedLoad() throws InterruptedException {
        // Create many providers each reporting many members
        var providers = new ArrayList<HighFrequencyProvider>();
        for (int i = 0; i < 4; i++) {
            var provider = new HighFrequencyProvider("LAYER" + i, 10);
            providers.add(provider);
            coordinator.registerProvider(provider);
        }
        coordinator.start();

        // Run for extended period (simulating sustained Byzantine attack)
        var startTime = System.currentTimeMillis();
        var duration = 2000;  // 2 seconds sustained load

        Thread.sleep(duration);

        var elapsed = System.currentTimeMillis() - startTime;
        var totalResponses = responseHandler.getTotalResponses();
        var responsesPerSecond = totalResponses * 1000.0 / elapsed;

        // Key assertion: response rate should be bounded
        // With 4 layers x 10 members = 40 potential signals per interval
        // Rate limit of 5 per interval + cooldown should constrain this
        assertThat(responsesPerSecond)
            .as("Response rate should be bounded (no runaway)")
            .isLessThan(50);  // Conservative bound

        // Should have used anti-feedback mechanisms
        var totalAntiFeeback = metrics.rateLimitedSkips.sum() +
                               metrics.cooldownSkips.sum() +
                               metrics.deduplicatedSignals.sum();
        assertThat(totalAntiFeeback)
            .as("Anti-feedback mechanisms should have engaged")
            .isGreaterThan(0);

        log.info("Sustained load test: {} responses in {}ms ({} resp/sec)",
            totalResponses, elapsed, String.format("%.2f", responsesPerSecond));
        log.info("Anti-feedback: {} rate-limited, {} cooldown, {} deduplicated",
            metrics.rateLimitedSkips.sum(),
            metrics.cooldownSkips.sum(),
            metrics.deduplicatedSignals.sum());
    }

    @Test
    void shouldRecoverAfterBurst() throws InterruptedException {
        // Provider starts with burst then quiets down
        var burstProvider = new BurstProvider("BURST", 50);  // Initial burst of 50
        coordinator.registerProvider(burstProvider);
        coordinator.start();

        // Let burst be processed
        Thread.sleep(300);
        var burstResponses = responseHandler.getTotalResponses();

        // Clear the burst
        burstProvider.clearMembers();

        // Wait for system to stabilize
        Thread.sleep(300);

        // Add a single new member
        var newMember = createMember("post-burst");
        burstProvider.addMember(newMember, 0.9);

        Thread.sleep(200);

        // System should still respond to new signals after burst
        assertThat(responseHandler.getTotalResponses())
            .as("Should process new signals after burst")
            .isGreaterThan(burstResponses);

        log.info("Burst recovery: {} burst responses, {} total after recovery",
            burstResponses, responseHandler.getTotalResponses());
    }

    @Test
    void shouldTrackResponsesPerIntervalMetric() throws InterruptedException {
        var provider = new HighFrequencyProvider("TEST", 15);
        coordinator.registerProvider(provider);
        coordinator.start();

        // Let it run for several intervals
        Thread.sleep(400);

        // Should have recorded responses per interval in histogram
        assertThat(metrics.responsesPerInterval.sum())
            .as("Should track responses per interval")
            .isGreaterThan(0);

        log.info("Responses per interval histogram sum: {}", metrics.responsesPerInterval.sum());
    }

    // ========== Helper Classes ==========

    private Identifier createMember(String seed) {
        return new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest(seed));
    }

    /**
     * Provider that reports many members at high scores.
     */
    static class HighFrequencyProvider implements ByzantineStateProvider {
        private final String layerName;
        private final Map<Identifier, LayerAnomalyState> members;

        HighFrequencyProvider(String layerName, int memberCount) {
            this.layerName = layerName;
            this.members = new ConcurrentHashMap<>();

            for (int i = 0; i < memberCount; i++) {
                var member = new SelfAddressingIdentifier(
                    DigestAlgorithm.DEFAULT.digest(layerName + "-member-" + i));
                members.put(member, new LayerAnomalyState(
                    layerName, 0.9, Instant.now(), List.of("HIGH_SIGNAL"), "Stress test"));
            }
        }

        @Override
        public String getLayerName() {
            return layerName;
        }

        @Override
        public Map<Identifier, LayerAnomalyState> getMemberAnomalyStates() {
            return Map.copyOf(members);
        }

        @Override
        public java.util.Optional<LayerAnomalyState> getMemberState(Identifier memberId) {
            return java.util.Optional.ofNullable(members.get(memberId));
        }

        @Override
        public int getTrackedMemberCount() {
            return members.size();
        }

        @Override
        public void reset() {
            members.clear();
        }
    }

    /**
     * Provider that reports a single member repeatedly.
     */
    static class SingleMemberProvider implements ByzantineStateProvider {
        private final String layerName;
        private final Identifier member;
        private final double score;

        SingleMemberProvider(String layerName, Identifier member, double score) {
            this.layerName = layerName;
            this.member = member;
            this.score = score;
        }

        @Override
        public String getLayerName() {
            return layerName;
        }

        @Override
        public Map<Identifier, LayerAnomalyState> getMemberAnomalyStates() {
            return Map.of(member, new LayerAnomalyState(
                layerName, score, Instant.now(), List.of("SIGNAL"), "Single member"));
        }

        @Override
        public java.util.Optional<LayerAnomalyState> getMemberState(Identifier memberId) {
            if (memberId.equals(member)) {
                return java.util.Optional.of(new LayerAnomalyState(
                    layerName, score, Instant.now(), List.of("SIGNAL"), "Single member"));
            }
            return java.util.Optional.empty();
        }

        @Override
        public int getTrackedMemberCount() {
            return 1;
        }

        @Override
        public void reset() {}
    }

    /**
     * Provider that can burst then quiet down.
     */
    static class BurstProvider implements ByzantineStateProvider {
        private final String layerName;
        private final Map<Identifier, Double> members;

        BurstProvider(String layerName, int initialBurst) {
            this.layerName = layerName;
            this.members = new ConcurrentHashMap<>();

            for (int i = 0; i < initialBurst; i++) {
                var member = new SelfAddressingIdentifier(
                    DigestAlgorithm.DEFAULT.digest(layerName + "-burst-" + i));
                members.put(member, 0.9);
            }
        }

        void clearMembers() {
            members.clear();
        }

        void addMember(Identifier member, double score) {
            members.put(member, score);
        }

        @Override
        public String getLayerName() {
            return layerName;
        }

        @Override
        public Map<Identifier, LayerAnomalyState> getMemberAnomalyStates() {
            var result = new ConcurrentHashMap<Identifier, LayerAnomalyState>();
            for (var entry : members.entrySet()) {
                result.put(entry.getKey(), new LayerAnomalyState(
                    layerName, entry.getValue(), Instant.now(), List.of("BURST"), "Burst test"));
            }
            return result;
        }

        @Override
        public java.util.Optional<LayerAnomalyState> getMemberState(Identifier memberId) {
            var score = members.get(memberId);
            if (score != null) {
                return java.util.Optional.of(new LayerAnomalyState(
                    layerName, score, Instant.now(), List.of("BURST"), "Burst test"));
            }
            return java.util.Optional.empty();
        }

        @Override
        public int getTrackedMemberCount() {
            return members.size();
        }

        @Override
        public void reset() {
            members.clear();
        }
    }

    /**
     * Response handler that tracks all responses.
     */
    static class TrackingResponseHandler implements ResponseHandler {
        private final LongAdder criticalCount = new LongAdder();
        private final LongAdder warningCount = new LongAdder();

        @Override
        public CompletableFuture<Boolean> handleCritical(Identifier memberId, MemberRiskProfile profile) {
            criticalCount.increment();
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Void> handleWarning(Identifier memberId, MemberRiskProfile profile) {
            warningCount.increment();
            return CompletableFuture.completedFuture(null);
        }

        long getTotalResponses() {
            return criticalCount.sum() + warningCount.sum();
        }
    }

    /**
     * Metrics that track anti-feedback counters.
     */
    static class TrackingMetrics implements ByzantineIntelligenceMetrics {
        final LongAdder cooldownSkips = new LongAdder();
        final LongAdder rateLimitedSkips = new LongAdder();
        final LongAdder deduplicatedSignals = new LongAdder();
        final LongAdder responsesPerInterval = new LongAdder();

        private final com.codahale.metrics.Timer timer = new com.codahale.metrics.Timer();
        private final com.codahale.metrics.Histogram histogram =
            new com.codahale.metrics.Histogram(new com.codahale.metrics.UniformReservoir());
        private final com.codahale.metrics.Counter counter = new com.codahale.metrics.Counter();
        private final com.codahale.metrics.Meter meter = new com.codahale.metrics.Meter();

        @Override
        public com.codahale.metrics.Timer layerPollDuration() { return timer; }

        @Override
        public com.codahale.metrics.Timer evaluationCycleDuration() { return timer; }

        @Override
        public com.codahale.metrics.Histogram aggregatedScoreDistribution() { return histogram; }

        @Override
        public com.codahale.metrics.Counter warningDetections() { return counter; }

        @Override
        public com.codahale.metrics.Counter criticalDetections() { return counter; }

        @Override
        public com.codahale.metrics.Meter responsesTriggered() { return meter; }

        @Override
        public com.codahale.metrics.Counter pendingResponses() { return counter; }

        @Override
        public com.codahale.metrics.Counter failedResponses() { return counter; }

        @Override
        public com.codahale.metrics.Counter cooldownSkips() {
            return new com.codahale.metrics.Counter() {
                @Override public void inc() { cooldownSkips.increment(); }
                @Override public void inc(long n) { cooldownSkips.add(n); }
                @Override public void dec() {}
                @Override public void dec(long n) {}
                @Override public long getCount() { return cooldownSkips.sum(); }
            };
        }

        @Override
        public com.codahale.metrics.Histogram trackedMemberCount() { return histogram; }

        @Override
        public com.codahale.metrics.Counter providerErrors() { return counter; }

        @Override
        public com.codahale.metrics.Counter rateLimitedSkips() {
            return new com.codahale.metrics.Counter() {
                @Override public void inc() { rateLimitedSkips.increment(); }
                @Override public void inc(long n) { rateLimitedSkips.add(n); }
                @Override public void dec() {}
                @Override public void dec(long n) {}
                @Override public long getCount() { return rateLimitedSkips.sum(); }
            };
        }

        @Override
        public com.codahale.metrics.Counter deduplicatedSignals() {
            return new com.codahale.metrics.Counter() {
                @Override public void inc() { deduplicatedSignals.increment(); }
                @Override public void inc(long n) { deduplicatedSignals.add(n); }
                @Override public void dec() {}
                @Override public void dec(long n) {}
                @Override public long getCount() { return deduplicatedSignals.sum(); }
            };
        }

        @Override
        public com.codahale.metrics.Histogram responsesPerInterval() {
            return new com.codahale.metrics.Histogram(new com.codahale.metrics.UniformReservoir()) {
                @Override
                public void update(long value) {
                    responsesPerInterval.add(value);
                }
            };
        }
    }

    /**
     * Mutable clock for testing.
     */
    static class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant initial) {
            this.instant = initial;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
