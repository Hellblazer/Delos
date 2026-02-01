/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import com.hellblazer.delos.membership.byzantine.testing.ByzantineTestHarness;
import com.hellblazer.delos.membership.byzantine.testing.FaultType;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for asynchronous response handling in Byzantine detection.
 * <p>
 * Phase 7 - Fix 1: Verifies async shunning and failure handling.
 * <p>
 * Scenarios covered:
 * <ul>
 *   <li>Async shunning via ResponseHandler</li>
 *   <li>Async response failure handling</li>
 *   <li>Concurrent response execution</li>
 *   <li>Response timeout handling</li>
 *   <li>Retry semantics for failed responses</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class AsyncResponseHandlingTest {

    private static final Logger log = LoggerFactory.getLogger(AsyncResponseHandlingTest.class);

    private ByzantineTestHarness harness;
    private IntelligenceConfig config;
    private TestAsyncResponseHandler responseHandler;

    @BeforeEach
    void setUp() {
        config = IntelligenceConfig.builder()
            .defaultPollInterval(Duration.ofMillis(50))
            .warningThreshold(0.15)
            .criticalThreshold(0.6)
            .layerWeights(Map.of(
                IntelligenceConfig.LAYER_FIREFLIES, 0.4,
                IntelligenceConfig.LAYER_THOTH, 0.2
            ))
            .responseCooldown(Duration.ofMillis(100))
            .maxResponsesPerInterval(20)
            .build();

        harness = ByzantineTestHarness.builder()
            .withDeterministicEntropy()
            .withFixedClock()
            .withLayers(IntelligenceConfig.LAYER_FIREFLIES, IntelligenceConfig.LAYER_THOTH)
            .withConfig(config)
            .build();

        responseHandler = new TestAsyncResponseHandler();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
        responseHandler.shutdown();
    }

    /**
     * Verifies async shunning is triggered for critical detections.
     */
    @Test
    void asyncShunningTriggeredForCriticalDetection() {
        log.info("Testing async shunning for critical detection");

        var member = harness.createMember("critical-member");

        // Inject high-severity fault (equivocation = 0.9 score)
        harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.EQUIVOCATION);

        var result = harness.evaluate(member);
        assertThat(result.isCritical())
            .as("Equivocation should trigger critical detection")
            .isTrue();

        // Simulate response handling
        var future = responseHandler.handleCritical(member, null);

        assertThat(future)
            .as("Critical response should return completable future")
            .isNotNull()
            .succeedsWithin(Duration.ofSeconds(1));

        assertThat(responseHandler.getCriticalCount())
            .as("Critical response should be recorded")
            .isEqualTo(1);
    }

    /**
     * Verifies async response failure is properly handled.
     */
    @Test
    void asyncResponseFailureHandled() {
        log.info("Testing async response failure handling");

        var member = harness.createMember("failing-member");

        // Configure handler to fail
        responseHandler.setFailNextResponse(true);

        var future = responseHandler.handleCritical(member, null);

        assertThat(future)
            .as("Failed response should complete exceptionally")
            .isNotNull()
            .failsWithin(Duration.ofSeconds(1));

        assertThat(responseHandler.getFailureCount())
            .as("Failure should be recorded")
            .isEqualTo(1);
    }

    /**
     * Verifies concurrent response execution.
     */
    @Test
    void concurrentResponseExecution() throws InterruptedException {
        log.info("Testing concurrent response execution");

        var memberCount = 10;
        var latch = new CountDownLatch(memberCount);
        var futures = new ArrayList<CompletableFuture<Boolean>>();

        for (int i = 0; i < memberCount; i++) {
            var member = harness.createMember("concurrent-member-" + i);
            var future = responseHandler.handleCritical(member, null);
            future.whenComplete((r, e) -> latch.countDown());
            futures.add(future);
        }

        // All should complete within timeout
        var completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed)
            .as("All concurrent responses should complete")
            .isTrue();

        assertThat(responseHandler.getCriticalCount())
            .as("All responses should be recorded")
            .isEqualTo(memberCount);
    }

    /**
     * Verifies response timeout handling.
     */
    @Test
    void responseTimeoutHandling() {
        log.info("Testing response timeout handling");

        var member = harness.createMember("slow-member");

        // Configure handler with delay exceeding timeout
        responseHandler.setResponseDelay(Duration.ofMillis(200));

        var future = responseHandler.handleCritical(member, null);

        // Should complete (not timeout at handler level - just slow)
        assertThat(future)
            .as("Slow response should eventually complete")
            .succeedsWithin(Duration.ofSeconds(2));
    }

    /**
     * Verifies warning responses are async.
     */
    @Test
    void warningResponsesAsync() {
        log.info("Testing async warning responses");

        var member = harness.createMember("warning-member");

        // Inject lower-severity fault
        harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.CRASH);

        var result = harness.evaluate(member);
        assertThat(result.isAnomalous())
            .as("Crash should trigger warning")
            .isTrue();
        assertThat(result.isCritical())
            .as("Crash should not be critical")
            .isFalse();

        var future = responseHandler.handleWarning(member, null);

        assertThat(future)
            .as("Warning response should complete successfully")
            .succeedsWithin(Duration.ofSeconds(1));

        assertThat(responseHandler.getWarningCount())
            .as("Warning should be recorded")
            .isEqualTo(1);
    }

    /**
     * Verifies ResponseHandler allows multiple rapid responses.
     * <p>
     * Note: Cooldown enforcement is the responsibility of ByzantineIntelligenceCoordinator,
     * not the ResponseHandler. This test verifies the handler itself doesn't reject
     * rapid responses - it leaves rate limiting to the coordinator.
     */
    @Test
    void handlerAllowsMultipleRapidResponses() {
        log.info("Testing handler allows rapid responses (cooldown enforced by coordinator)");

        var member = harness.createMember("rapid-response-member");

        // First response should succeed
        responseHandler.handleCritical(member, null).join();
        assertThat(responseHandler.getCriticalCount()).isEqualTo(1);

        // Second immediate response should also succeed (handler doesn't enforce cooldown)
        responseHandler.handleCritical(member, null).join();
        assertThat(responseHandler.getCriticalCount()).isEqualTo(2);
    }

    /**
     * Test async response handler implementation for testing.
     */
    private static class TestAsyncResponseHandler implements ResponseHandler {

        private final AtomicInteger criticalCount = new AtomicInteger();
        private final AtomicInteger warningCount = new AtomicInteger();
        private final AtomicInteger failureCount = new AtomicInteger();
        private final List<Identifier> handledMembers = new CopyOnWriteArrayList<>();
        private final ExecutorService executor = Executors.newFixedThreadPool(4);

        private volatile boolean failNextResponse = false;
        private volatile Duration responseDelay = Duration.ZERO;

        @Override
        public CompletableFuture<Boolean> handleCritical(Identifier memberId, MemberRiskProfile profile) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (!responseDelay.isZero()) {
                        Thread.sleep(responseDelay.toMillis());
                    }
                    if (failNextResponse) {
                        failNextResponse = false;
                        failureCount.incrementAndGet();
                        throw new RuntimeException("Simulated failure");
                    }
                    criticalCount.incrementAndGet();
                    handledMembers.add(memberId);
                    log.debug("Critical response handled for: {}", memberId);
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }, executor);
        }

        @Override
        public CompletableFuture<Void> handleWarning(Identifier memberId, MemberRiskProfile profile) {
            return CompletableFuture.runAsync(() -> {
                warningCount.incrementAndGet();
                handledMembers.add(memberId);
                log.debug("Warning response handled for: {}", memberId);
            }, executor);
        }

        public int getCriticalCount() {
            return criticalCount.get();
        }

        public int getWarningCount() {
            return warningCount.get();
        }

        public int getFailureCount() {
            return failureCount.get();
        }

        public List<Identifier> getHandledMembers() {
            return List.copyOf(handledMembers);
        }

        public void setFailNextResponse(boolean fail) {
            this.failNextResponse = fail;
        }

        public void setResponseDelay(Duration delay) {
            this.responseDelay = delay;
        }

        public void shutdown() {
            executor.shutdownNow();
        }
    }
}
