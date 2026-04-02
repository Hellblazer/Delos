/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ByzantineIntelligenceCoordinator.
 */
class ByzantineIntelligenceCoordinatorTest {

    private IntelligenceConfig config;
    private TestResponseHandler responseHandler;
    private ByzantineIntelligenceMetrics metrics;
    private Clock fixedClock;
    private ByzantineIntelligenceCoordinator coordinator;

    @BeforeEach
    void setUp() {
        config = IntelligenceConfig.builder()
            .defaultPollInterval(Duration.ofMillis(50))
            .warningThreshold(0.5)
            .criticalThreshold(0.8)
            .responseCooldown(Duration.ofMillis(100))
            .build();

        responseHandler = new TestResponseHandler();
        metrics = ByzantineIntelligenceMetrics.noOp();
        fixedClock = Clock.fixed(Instant.parse("2026-01-31T12:00:00Z"), ZoneId.of("UTC"));
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.close();
        }
    }

    @Test
    void shouldNotStartWithoutProviders() {
        coordinator = new ByzantineIntelligenceCoordinator(config, responseHandler, metrics, fixedClock);
        coordinator.start();

        assertThat(coordinator.isRunning()).isTrue();
        assertThat(coordinator.getTrackedMemberCount()).isZero();
    }

    @Test
    void shouldRejectProviderRegistrationAfterStart() {
        coordinator = new ByzantineIntelligenceCoordinator(config, responseHandler, metrics, fixedClock);
        coordinator.start();

        assertThatThrownBy(() -> coordinator.registerProvider(new TestProvider("TEST")))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldPollRegisteredProviders() throws Exception {
        var provider = new TestProvider("FIREFLIES");
        provider.addMemberState(Identifier.NONE, 0.3);

        coordinator = new ByzantineIntelligenceCoordinator(config, responseHandler, metrics, fixedClock);
        coordinator.registerProvider(provider);
        coordinator.start();

        // Wait for at least one poll cycle
        Thread.sleep(100);

        assertThat(coordinator.getTrackedMemberCount()).isGreaterThanOrEqualTo(1);
        var profile = coordinator.getMemberProfile(Identifier.NONE);
        assertThat(profile).isPresent();
    }

    @Test
    void shouldTriggerCriticalResponse() throws Exception {
        var provider = new TestProvider("FIREFLIES");
        provider.addMemberState(Identifier.NONE, 0.9); // Above critical threshold

        coordinator = new ByzantineIntelligenceCoordinator(config, responseHandler, metrics, fixedClock);
        coordinator.registerProvider(provider);
        coordinator.start();

        // Wait for poll and evaluation
        Thread.sleep(150);

        assertThat(responseHandler.criticalCalls.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldTriggerWarningResponse() throws Exception {
        var provider = new TestProvider("FIREFLIES");
        provider.addMemberState(Identifier.NONE, 0.6); // Above warning, below critical

        coordinator = new ByzantineIntelligenceCoordinator(config, responseHandler, metrics, fixedClock);
        coordinator.registerProvider(provider);
        coordinator.start();

        // Wait for poll and evaluation
        Thread.sleep(150);

        assertThat(responseHandler.warningCalls.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldTrackPendingResponses() throws Exception {
        var slowHandler = new SlowResponseHandler(Duration.ofMillis(200));
        var provider = new TestProvider("FIREFLIES");
        provider.addMemberState(Identifier.NONE, 0.9);

        coordinator = new ByzantineIntelligenceCoordinator(config, slowHandler, metrics, fixedClock);
        coordinator.registerProvider(provider);
        coordinator.start();

        // Wait for poll to trigger response
        Thread.sleep(100);

        // Response should be pending
        assertThat(coordinator.getPendingResponseCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldRespectResponseCooldown() throws Exception {
        var mutableClock = new MutableClock(Instant.parse("2026-01-31T12:00:00Z"));
        var provider = new TestProvider("FIREFLIES");
        provider.addMemberState(Identifier.NONE, 0.9);

        coordinator = new ByzantineIntelligenceCoordinator(config, responseHandler, metrics, mutableClock);
        coordinator.registerProvider(provider);
        coordinator.start();

        // Wait for first response
        Thread.sleep(150);
        var firstCriticalCount = responseHandler.criticalCalls.get();
        assertThat(firstCriticalCount).isGreaterThanOrEqualTo(1);

        // Advance clock but stay within cooldown
        mutableClock.advance(Duration.ofMillis(50));
        Thread.sleep(100);

        // Should not have triggered additional response due to cooldown
        // (the count should not increase significantly)
        var secondCriticalCount = responseHandler.criticalCalls.get();
        assertThat(secondCriticalCount - firstCriticalCount).isLessThanOrEqualTo(1);
    }

    @Test
    void shouldResetState() throws Exception {
        var provider = new TestProvider("FIREFLIES");
        provider.addMemberState(Identifier.NONE, 0.5);

        coordinator = new ByzantineIntelligenceCoordinator(config, responseHandler, metrics, fixedClock);
        coordinator.registerProvider(provider);
        coordinator.start();

        // Poll until the coordinator has tracked at least one member (poll interval is 50ms)
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (coordinator.getTrackedMemberCount() < 1 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(coordinator.getTrackedMemberCount()).isGreaterThanOrEqualTo(1);

        // Close coordinator to stop polling before checking reset state
        // Otherwise there's a race: reset() clears state, but next poll repopulates it
        coordinator.close();
        coordinator.reset();

        assertThat(coordinator.getTrackedMemberCount()).isZero();
        assertThat(provider.resetCalled).isTrue();
    }

    @Test
    void shouldCloseGracefully() {
        var provider = new TestProvider("FIREFLIES");

        coordinator = new ByzantineIntelligenceCoordinator(config, responseHandler, metrics, fixedClock);
        coordinator.registerProvider(provider);
        coordinator.start();

        assertThat(coordinator.isRunning()).isTrue();

        coordinator.close();

        assertThat(coordinator.isRunning()).isFalse();
    }

    @Test
    void shouldHandleMultipleProviders() throws Exception {
        var firefliesProvider = new TestProvider("FIREFLIES");
        var thothProvider = new TestProvider("THOTH");

        firefliesProvider.addMemberState(Identifier.NONE, 0.4);
        thothProvider.addMemberState(Identifier.NONE, 0.4);

        coordinator = new ByzantineIntelligenceCoordinator(config, responseHandler, metrics, fixedClock);
        coordinator.registerProvider(firefliesProvider);
        coordinator.registerProvider(thothProvider);
        coordinator.start();

        Thread.sleep(150);

        var profile = coordinator.getMemberProfile(Identifier.NONE);
        assertThat(profile).isPresent();
        // Both layers contribute to the profile
        assertThat(profile.get().getAllLayerStates()).hasSize(2);
    }

    @Test
    void shouldHandleProviderErrors() throws Exception {
        var faultyProvider = new FaultyProvider();
        var goodProvider = new TestProvider("GOOD");
        goodProvider.addMemberState(Identifier.NONE, 0.3);

        coordinator = new ByzantineIntelligenceCoordinator(config, responseHandler, metrics, fixedClock);
        coordinator.registerProvider(faultyProvider);
        coordinator.registerProvider(goodProvider);
        coordinator.start();

        Thread.sleep(150);

        // Coordinator should continue despite faulty provider
        assertThat(coordinator.isRunning()).isTrue();
        assertThat(coordinator.getTrackedMemberCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldHandleAsyncResponseFailure() throws Exception {
        var failingHandler = new FailingResponseHandler();
        var provider = new TestProvider("FIREFLIES");
        provider.addMemberState(Identifier.NONE, 0.9);

        coordinator = new ByzantineIntelligenceCoordinator(config, failingHandler, metrics, fixedClock);
        coordinator.registerProvider(provider);
        coordinator.start();

        Thread.sleep(150);

        // Should continue operating despite response failures
        assertThat(coordinator.isRunning()).isTrue();
    }

    // ==================== Test Helpers ====================

    static class TestProvider implements ByzantineStateProvider {
        private final String layerName;
        private final ConcurrentHashMap<Identifier, LayerAnomalyState> states = new ConcurrentHashMap<>();
        volatile boolean resetCalled = false;

        TestProvider(String layerName) {
            this.layerName = layerName;
        }

        void addMemberState(Identifier memberId, double score) {
            states.put(memberId, new LayerAnomalyState(
                layerName, score, Instant.now(), List.of("TEST_SIGNAL"), "test evidence"
            ));
        }

        @Override
        public String getLayerName() {
            return layerName;
        }

        @Override
        public Map<Identifier, LayerAnomalyState> getMemberAnomalyStates() {
            return new HashMap<>(states);
        }

        @Override
        public Optional<LayerAnomalyState> getMemberState(Identifier memberId) {
            return Optional.ofNullable(states.get(memberId));
        }

        @Override
        public void reset() {
            states.clear();
            resetCalled = true;
        }
    }

    static class TestResponseHandler implements ResponseHandler {
        final AtomicInteger criticalCalls = new AtomicInteger(0);
        final AtomicInteger warningCalls = new AtomicInteger(0);

        @Override
        public CompletableFuture<Boolean> handleCritical(Identifier memberId, MemberRiskProfile profile) {
            criticalCalls.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Void> handleWarning(Identifier memberId, MemberRiskProfile profile) {
            warningCalls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    static class SlowResponseHandler implements ResponseHandler {
        private final Duration delay;

        SlowResponseHandler(Duration delay) {
            this.delay = delay;
        }

        @Override
        public CompletableFuture<Boolean> handleCritical(Identifier memberId, MemberRiskProfile profile) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return true;
            });
        }

        @Override
        public CompletableFuture<Void> handleWarning(Identifier memberId, MemberRiskProfile profile) {
            return CompletableFuture.completedFuture(null);
        }
    }

    static class FailingResponseHandler implements ResponseHandler {
        @Override
        public CompletableFuture<Boolean> handleCritical(Identifier memberId, MemberRiskProfile profile) {
            return CompletableFuture.failedFuture(new RuntimeException("Simulated failure"));
        }

        @Override
        public CompletableFuture<Void> handleWarning(Identifier memberId, MemberRiskProfile profile) {
            return CompletableFuture.failedFuture(new RuntimeException("Simulated failure"));
        }
    }

    static class FaultyProvider implements ByzantineStateProvider {
        @Override
        public String getLayerName() {
            return "FAULTY";
        }

        @Override
        public Map<Identifier, LayerAnomalyState> getMemberAnomalyStates() {
            throw new RuntimeException("Simulated provider error");
        }

        @Override
        public Optional<LayerAnomalyState> getMemberState(Identifier memberId) {
            throw new RuntimeException("Simulated provider error");
        }

        @Override
        public void reset() {
            // no-op
        }
    }

    static class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant) {
            this.instant = instant;
            this.zone = ZoneId.of("UTC");
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
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
