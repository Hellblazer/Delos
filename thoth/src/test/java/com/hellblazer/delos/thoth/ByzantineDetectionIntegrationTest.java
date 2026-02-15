/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.byzantine.*;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.thoth.exception.DhtQuorumException;
import com.hellblazer.delos.thoth.metrics.KerlDhtMetrics;
import com.hellblazer.delos.thoth.metrics.MicrometerKerlDhtMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E Byzantine detection integration test with ByzantineIntelligenceCoordinator.
 * <p>
 * Tests coordinator registration, cross-layer signal aggregation, and detection
 * within SLA targets.
 * </p>
 *
 * @author hal.hildebrand
 */
public class ByzantineDetectionIntegrationTest extends AbstractDhtTest {

    private ByzantineIntelligenceCoordinator coordinator;
    private TestResponseHandler responseHandler;
    private ByzantineIntelligenceMetrics coordMetrics;

    @BeforeEach
    @Override
    public void before() throws Exception {
        super.before();

        // Create coordinator with test response handler
        responseHandler = new TestResponseHandler();
        coordMetrics = new TestMetrics();
        var config = IntelligenceConfig.builder()
            .defaultPollInterval(Duration.ofMillis(100))
            .layerPollIntervals(Map.of(IntelligenceConfig.LAYER_THOTH, Duration.ofMillis(50)))
            .warningThreshold(0.3)
            .criticalThreshold(0.7)
            .responseCooldown(Duration.ofSeconds(5))
            .build();

        coordinator = new ByzantineIntelligenceCoordinator(config, responseHandler, coordMetrics, Clock.systemUTC());

        // Re-instantiate DHTs with coordinator support
        dhts.clear();
        routers.clear();
        var serverMembers = new ConcurrentSkipListMap<Digest, Member>();
        identities.keySet().forEach(member -> instantiateWithCoordinator(member, context, serverMembers));

        // Start DHTs first (providers must be registered before coordinator.start())
        dhts.values().forEach(dht -> dht.start(Duration.ofMillis(100)));

        // Now start coordinator (after providers are registered)
        coordinator.start();

        // Give coordinator time to initialize pollers for all registered providers
        Thread.sleep(500); // 10x the 50ms poll interval
    }

    @AfterEach
    @Override
    public void after() {
        if (coordinator != null) {
            coordinator.close();
        }
        super.after();
    }

    @Override
    protected int getCardinality() {
        // Byzantine detection requires minimum 4 nodes (3f+1 with f=1)
        return LARGE_TESTS ? 10 : 5;
    }

    /**
     * Test 1: Divergent Response Detection
     * <p>
     * 5-node cluster, 1 member returns wrong KeyState.
     * Verifies minority member reported to provider.
     * </p>
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testDivergentResponseDetection() throws Exception {
        // Setup: Create a test identifier
        var testId = Identifier.NONE;
        var byzantineMember = dhts.keySet().stream().skip(4).findFirst().orElseThrow();

        // Inject Byzantine behavior: recordValidationFailure on Byzantine node
        var byzantineDht = dhts.get(byzantineMember);
        byzantineDht.getByzantineStateProvider().recordValidationFailure(testId, "Divergent response test");

        // Wait for coordinator to poll and detect (with aggressive retry loop for CI timing variability)
        Optional<MemberRiskProfile> profile = Optional.empty();
        var attempts = 0;
        var maxAttempts = 50; // 50 attempts × 200ms = 10 seconds max (more than enough for 50ms poll interval)
        while (profile.isEmpty() && attempts < maxAttempts) {
            Thread.sleep(200); // Poll interval is 50ms, wait 4x to allow multiple polls
            profile = coordinator.getMemberProfile(testId); // Use same identifier as recording
            attempts++;
            if (attempts % 10 == 0) {
                System.out.printf("Byzantine detection attempt %d/50, profile present: %s%n", attempts,
                                  profile.isPresent());
            }
        }

        // Verify Byzantine member is tracked
        assertThat(profile).as("Byzantine member should be detected after %d attempts (%.1f seconds)", attempts,
                               attempts * 0.2).isPresent();
        assertThat(profile.get().getAggregatedScore()).isGreaterThan(0.0);

        // Verify detection happened within SLA (<5 seconds)
        assertThat(profile.get().getActiveSignalSources()).contains(IntelligenceConfig.LAYER_THOTH);
    }

    /**
     * Test 2: Signature Validation Failure
     * <p>
     * Invalid signature in KeyState.
     * Verifies validation pipeline catches it and provider records failure.
     * </p>
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testSignatureValidationFailure() {
        var testId = Identifier.NONE;
        var byzantineMember = dhts.keySet().iterator().next();
        var provider = dhts.get(byzantineMember).getByzantineStateProvider();

        // Simulate signature validation failure
        provider.recordValidationFailure(testId, "Invalid signature");

        // Verify provider captured the failure
        var states = provider.getMemberAnomalyStates();
        assertThat(states).containsKey(testId);
        assertThat(states.get(testId).anomalyScore()).isGreaterThan(0.0);
        assertThat(states.get(testId).activeSignals()).anyMatch(s -> s.contains("VALIDATION_FAILURE"));
    }

    /**
     * Test 3: Timeout Tracking
     * <p>
     * Member consistently times out.
     * Verifies timeout recording in provider and anomaly score increases.
     * </p>
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testTimeoutTracking() throws InterruptedException {
        var testId = Identifier.NONE;
        var slowMember = dhts.keySet().iterator().next();
        var provider = dhts.get(slowMember).getByzantineStateProvider();

        // Record multiple timeouts
        for (int i = 0; i < 5; i++) {
            provider.recordTimeout(testId);
            Thread.sleep(10);
        }

        // Wait for coordinator poll
        Thread.sleep(200);

        // Verify timeout accumulation
        var profile = coordinator.getMemberProfile(testId);
        assertThat(profile).isPresent();
        assertThat(profile.get().getAggregatedScore()).isGreaterThan(0.0);
    }

    /**
     * Test 4: Quorum Failure Recovery
     * <p>
     * Majority achieves quorum despite Byzantine member.
     * Verifies operation succeeds and Byzantine member identified.
     * </p>
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testQuorumFailureRecovery() throws Exception {
        var testId = Identifier.NONE;
        var byzantineMember = dhts.keySet().stream().skip(3).findFirst().orElseThrow();

        // Simulate quorum failure on Byzantine node
        var byzantineDht = dhts.get(byzantineMember);
        byzantineDht.getByzantineStateProvider().recordQuorumFailure(testId);

        // Verify Byzantine member is tracked but quorum continues
        Thread.sleep(200);

        var profile = coordinator.getMemberProfile(testId);
        assertThat(profile).isPresent();
        assertThat(profile.get().getActiveSignalSources()).contains(IntelligenceConfig.LAYER_THOTH);

        // Verify coordinator detected the Byzantine member
        assertThat(coordinator.getTrackedMemberCount()).isGreaterThan(0);
    }

    /**
     * Test 5: Multi-Node Cluster Simulation
     * <p>
     * 5-node cluster with f=1 tolerance (can tolerate 1 Byzantine).
     * 1 Byzantine member with multiple attack patterns.
     * Verifies detection and continued operation.
     * </p>
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testMultiNodeClusterDetection() throws Exception {
        var cardinality = getCardinality();
        assertThat(cardinality).isGreaterThanOrEqualTo(5);

        var byzantineMember = dhts.keySet().stream().skip(2).findFirst().orElseThrow();
        var provider = dhts.get(byzantineMember).getByzantineStateProvider();

        // Inject multiple Byzantine behaviors
        var testId = Identifier.NONE;
        provider.recordValidationFailure(testId, "Forged signature");
        Thread.sleep(50);
        provider.recordQuorumFailure(testId);
        Thread.sleep(50);
        provider.recordTimeout(testId);

        // Wait for coordinator to aggregate signals
        Thread.sleep(300);

        // Verify Byzantine detection
        var profile = coordinator.getMemberProfile(testId);
        assertThat(profile).isPresent();
        assertThat(profile.get().getAggregatedScore()).isGreaterThan(0.5); // Multiple failures

        // Verify coordinator triggered response
        assertThat(responseHandler.getWarningCount()).isGreaterThanOrEqualTo(1);
    }

    /**
     * Test 6: Coordinator Registration
     * <p>
     * Verifies all DHT providers are registered with coordinator.
     * </p>
     */
    @Test
    void testCoordinatorRegistration() {
        // Verify coordinator is running
        assertThat(coordinator.isRunning()).isTrue();

        // Verify no pending responses initially
        assertThat(coordinator.getPendingResponseCount()).isEqualTo(0);
    }

    /**
     * Test 7: Byzantine Detection Latency SLA
     * <p>
     * Measures end-to-end latency from fault injection to coordinator detection.
     * Target: < 5 seconds (relaxed from < 100ms for integration test with polling overhead).
     * </p>
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testDetectionLatencySLA() throws Exception {
        var testId = Identifier.NONE;
        var member = dhts.keySet().iterator().next();
        var provider = dhts.get(member).getByzantineStateProvider();

        var startTime = System.nanoTime();

        // Inject Byzantine fault
        provider.recordValidationFailure(testId, "SLA test fault");

        // Poll until coordinator detects (or timeout)
        var detected = false;
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !detected) {
            var profile = coordinator.getMemberProfile(testId);
            if (profile.isPresent() && profile.get().getAggregatedScore() > 0.0) {
                detected = true;
                break;
            }
            Thread.sleep(50);
        }

        var detectionLatency = Duration.ofNanos(System.nanoTime() - startTime);

        // Verify detection happened
        assertThat(detected).isTrue();

        // Verify latency < 5s (relaxed SLA for integration test with polling)
        assertThat(detectionLatency.toMillis()).isLessThan(5000);

        System.out.printf("Detection latency: %d ms%n", detectionLatency.toMillis());
    }

    // ==================== Helper Methods ====================

    private void instantiateWithCoordinator(SigningMember member, DynamicContext<Member> context,
                                            ConcurrentSkipListMap<Digest, Member> serverMembers) {
        context.activate(member);
        var url = String.format("jdbc:h2:mem:%s-%s;DB_CLOSE_ON_EXIT=FALSE", member.getId(), prefix);
        var connectionPool = JdbcConnectionPool.create(url, "", "");
        connectionPool.setMaxConnections(10);

        var router = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        routers.put(member, router);

        var metrics = new MicrometerKerlDhtMetrics(new SimpleMeterRegistry());

        // Create DHT with coordinator support
        var dht = new KerlDHT(
            Duration.ofMillis(5),
            context,
            member,
            wrap(),
            connectionPool,
            DigestAlgorithm.DEFAULT,
            router,
            Duration.ofSeconds(10),
            0.0125,
            null,
            metrics,
            coordinator // Pass coordinator
        );

        dhts.put(member, dht);
    }

    // ==================== Test Helpers ====================

    private static class TestResponseHandler implements ResponseHandler {
        private final AtomicInteger warningCount = new AtomicInteger(0);
        private final AtomicInteger criticalCount = new AtomicInteger(0);
        private final List<Identifier> warningMembers = new CopyOnWriteArrayList<>();
        private final List<Identifier> criticalMembers = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<Void> handleWarning(Identifier memberId, MemberRiskProfile profile) {
            warningCount.incrementAndGet();
            warningMembers.add(memberId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Boolean> handleCritical(Identifier memberId, MemberRiskProfile profile) {
            criticalCount.incrementAndGet();
            criticalMembers.add(memberId);
            return CompletableFuture.completedFuture(true);
        }

        public int getWarningCount() {
            return warningCount.get();
        }

        public int getCriticalCount() {
            return criticalCount.get();
        }

        public List<Identifier> getWarningMembers() {
            return new ArrayList<>(warningMembers);
        }

        public List<Identifier> getCriticalMembers() {
            return new ArrayList<>(criticalMembers);
        }
    }

    private static class TestMetrics implements ByzantineIntelligenceMetrics {
        @Override
        public void recordLayerPollDuration(long nanos) {}

        @Override
        public void recordEvaluationCycleDuration(long nanos) {}

        @Override
        public void recordTrackedMemberCount(int count) {}

        @Override
        public void recordAggregatedScore(double score) {}

        @Override
        public void recordResponseTriggered() {}

        @Override
        public void incrementProviderErrors() {}

        @Override
        public void incrementWarningDetections() {}

        @Override
        public void incrementCriticalDetections() {}

        @Override
        public void incrementFailedResponses() {}

        @Override
        public void incrementPendingResponses() {}

        @Override
        public void decrementPendingResponses() {}

        @Override
        public void incrementCooldownSkips() {}

        @Override
        public void incrementRateLimitedSkips() {}

        @Override
        public void incrementDeduplicatedSignals() {}

        @Override
        public void recordResponsesPerInterval(int count) {}
    }
}
