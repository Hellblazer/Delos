/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StallDiagnostics}.
 * <p>
 * Tests threshold-based stall diagnosis covering partition, consensus slowdown,
 * and Byzantine fault detection.
 * </p>
 *
 * @author hal.hildebrand
 */
class StallDiagnosticsTest {

    @Mock
    private Context<?>                 context;
    @Mock
    private CommonCommunications<?, ?> communications;

    private ByzantineDetectionMapper byzantineMapper;
    private SimpleMeterRegistry      metrics;
    private StallDiagnostics         diagnostics;
    private List<Member>             members;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        byzantineMapper = new ByzantineDetectionMapper();
        metrics = new SimpleMeterRegistry();

        // Create test members (mocked)
        members = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            var member = mock(Member.class);
            when(member.getId()).thenReturn(DigestAlgorithm.DEFAULT.digest("member-" + i));
            members.add(member);
        }

        when(context.getId()).thenReturn(DigestAlgorithm.DEFAULT.digest("test-context"));

        // Wrap concrete implementation with adapter
        var bftValidator = new com.hellblazer.delos.choam.validation.DefaultBFTValidator(byzantineMapper);
        diagnostics = new StallDiagnostics(context, communications, bftValidator, metrics);
    }

    /**
     * Tests that partition is diagnosed when consecutive heartbeat failures exceed threshold.
     */
    @Test
    void diagnosePartition_WhenHeartbeatFailuresExceedThreshold() {
        // Given: Member with consecutive heartbeat failures > 3 (default threshold)
        var member = members.get(0);
        for (int i = 0; i < 4; i++) {
            diagnostics.recordHeartbeatFailure(member);
        }

        // When: Diagnose stall
        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnostics.diagnose(event);

        // Then: Should diagnose PARTITION
        assertThat(cause).isEqualTo(StallCause.PARTITION);

        // Verify metric incremented
        assertThat(metrics.counter("choam.stall.diagnosis.partition").count()).isEqualTo(1);
    }

    /**
     * Tests that partition is NOT diagnosed when heartbeat failures are below threshold.
     */
    @Test
    void diagnoseNotPartitioned_WhenHeartbeatFailuresBelowThreshold() {
        // Given: Member with only 2 consecutive heartbeat failures (threshold is 3)
        var member = members.get(0);
        diagnostics.recordHeartbeatFailure(member);
        diagnostics.recordHeartbeatFailure(member);

        // When: Diagnose stall
        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnostics.diagnose(event);

        // Then: Should NOT diagnose PARTITION (will default to CONSENSUS_SLOW)
        assertThat(cause).isNotEqualTo(StallCause.PARTITION);
        assertThat(cause).isEqualTo(StallCause.CONSENSUS_SLOW);
    }

    /**
     * Tests that heartbeat success resets consecutive failure count.
     */
    @Test
    void heartbeatSuccess_ResetsConsecutiveFailures() {
        // Given: Member with 2 failures, then success, then 2 more failures
        var member = members.get(0);
        diagnostics.recordHeartbeatFailure(member);
        diagnostics.recordHeartbeatFailure(member);

        // When: Heartbeat succeeds (resets counter)
        diagnostics.recordHeartbeatSuccess(member);

        // Then: 2 more failures should NOT trigger partition (total would be 4, but counter reset)
        diagnostics.recordHeartbeatFailure(member);
        diagnostics.recordHeartbeatFailure(member);

        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnostics.diagnose(event);

        assertThat(cause).isNotEqualTo(StallCause.PARTITION);
    }

    /**
     * Tests consensus slowdown diagnosis when participation rate is below threshold.
     */
    @Test
    void diagnoseConsensusSlow_WhenParticipationBelowThreshold() {
        // Given: Low consensus participation (40% < 50% threshold)
        // 10 proposals, only 4 accepted
        for (int i = 0; i < 10; i++) {
            diagnostics.recordConsensusParticipation(true, i < 4);
        }

        // When: Diagnose stall
        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnostics.diagnose(event);

        // Then: Should diagnose CONSENSUS_SLOW
        assertThat(cause).isEqualTo(StallCause.CONSENSUS_SLOW);

        // Verify metric incremented
        assertThat(metrics.counter("choam.stall.diagnosis.consensus_slow").count()).isEqualTo(1);
    }

    /**
     * Tests that consensus participation is correctly tracked over time window.
     */
    @Test
    void consensusParticipation_TrackedOverTimeWindow() {
        // Given: Good participation (8 out of 10 = 80%)
        for (int i = 0; i < 10; i++) {
            diagnostics.recordConsensusParticipation(true, i < 8);
        }

        // When: Diagnose stall
        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnostics.diagnose(event);

        // Then: With no Byzantine or Partition indicators, defaults to CONSENSUS_SLOW
        // (even with good participation, a stall must have some cause)
        assertThat(cause).isEqualTo(StallCause.CONSENSUS_SLOW);
    }

    /**
     * Tests Byzantine diagnosis when signature failure rate exceeds threshold.
     */
    @Test
    void diagnoseByzantine_WhenSignatureFailureRateExceedsThreshold() {
        // Given: High signature failure rate (10% > 5% threshold)
        // 100 checks, 10 failures
        for (int i = 0; i < 100; i++) {
            diagnostics.recordSignatureCheck(i >= 10);  // First 10 fail
        }

        // When: Diagnose stall
        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnostics.diagnose(event);

        // Then: Should diagnose BYZANTINE
        assertThat(cause).isEqualTo(StallCause.BYZANTINE);

        // Verify metric incremented
        assertThat(metrics.counter("choam.stall.diagnosis.byzantine").count()).isEqualTo(1);
    }

    /**
     * Tests Byzantine diagnosis when timing anomaly rate exceeds threshold.
     */
    @Test
    void diagnoseByzantine_WhenTimingAnomalyRateExceedsThreshold() {
        // Given: High timing anomaly rate (15% > 10% threshold)
        // 100 transitions, 15 anomalies
        for (int i = 0; i < 100; i++) {
            diagnostics.recordStateTransition(i < 15);  // First 15 are anomalies
        }

        // When: Diagnose stall
        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnostics.diagnose(event);

        // Then: Should diagnose BYZANTINE
        assertThat(cause).isEqualTo(StallCause.BYZANTINE);
    }

    /**
     * Tests that Byzantine is NOT diagnosed when indicators are below thresholds.
     */
    @Test
    void diagnoseNotByzantine_WhenIndicatorsBelowThresholds() {
        // Given: Low signature failure rate (2% < 5% threshold)
        for (int i = 0; i < 100; i++) {
            diagnostics.recordSignatureCheck(i >= 2);  // Only 2 failures
        }

        // And: Low timing anomaly rate (5% < 10% threshold)
        for (int i = 0; i < 100; i++) {
            diagnostics.recordStateTransition(i < 5);  // Only 5 anomalies
        }

        // When: Diagnose stall
        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnostics.diagnose(event);

        // Then: Should NOT diagnose BYZANTINE
        assertThat(cause).isNotEqualTo(StallCause.BYZANTINE);
    }

    /**
     * Tests diagnostic priority: BYZANTINE > PARTITION > CONSENSUS_SLOW.
     */
    @Test
    void diagnosePriority_ByzantineHighestPriority() {
        // Given: All three conditions met
        // 1. Partition: 4 consecutive heartbeat failures
        var member = members.get(0);
        for (int i = 0; i < 4; i++) {
            diagnostics.recordHeartbeatFailure(member);
        }

        // 2. Consensus slow: 30% participation
        for (int i = 0; i < 10; i++) {
            diagnostics.recordConsensusParticipation(true, i < 3);
        }

        // 3. Byzantine: 20% signature failures
        for (int i = 0; i < 100; i++) {
            diagnostics.recordSignatureCheck(i >= 20);
        }

        // When: Diagnose stall
        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnostics.diagnose(event);

        // Then: Should diagnose BYZANTINE (highest priority)
        assertThat(cause).isEqualTo(StallCause.BYZANTINE);
    }

    /**
     * Tests diagnostic priority: PARTITION > CONSENSUS_SLOW when Byzantine is not present.
     */
    @Test
    void diagnosePriority_PartitionOverConsensusSlow() {
        // Given: Partition and consensus slow, but no Byzantine
        // 1. Partition: 4 consecutive heartbeat failures
        var member = members.get(0);
        for (int i = 0; i < 4; i++) {
            diagnostics.recordHeartbeatFailure(member);
        }

        // 2. Consensus slow: 30% participation
        for (int i = 0; i < 10; i++) {
            diagnostics.recordConsensusParticipation(true, i < 3);
        }

        // 3. No Byzantine indicators
        for (int i = 0; i < 100; i++) {
            diagnostics.recordSignatureCheck(true);  // All succeed
        }

        // When: Diagnose stall
        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnostics.diagnose(event);

        // Then: Should diagnose PARTITION (second priority)
        assertThat(cause).isEqualTo(StallCause.PARTITION);
    }

    /**
     * Tests custom thresholds can be configured.
     */
    @Test
    void customThresholds_CanBeConfigured() {
        // Given: Custom thresholds (stricter than defaults)
        var customThresholds = new StallDiagnostics.Thresholds(
            5,     // Partition: 5 heartbeat failures (default: 3)
            0.7,   // Consensus slow: 70% participation threshold (default: 50%)
            0.02,  // Byzantine signature: 2% threshold (default: 5%)
            0.05,  // Byzantine timing: 5% threshold (default: 10%)
            Duration.ofSeconds(45),  // Partition window (default: 30s)
            Duration.ofSeconds(90),  // Consensus window (default: 1min)
            Duration.ofMinutes(10)   // Byzantine window (default: 5min)
        );

        var bftValidator = new com.hellblazer.delos.choam.validation.DefaultBFTValidator(byzantineMapper);
        var customDiagnostics = new StallDiagnostics(
            context, communications, bftValidator, customThresholds, metrics);

        // When: 4 heartbeat failures (would trigger default, but not custom threshold of 5)
        var member = members.get(0);
        for (int i = 0; i < 4; i++) {
            customDiagnostics.recordHeartbeatFailure(member);
        }

        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = customDiagnostics.diagnose(event);

        // Then: Should NOT diagnose PARTITION (4 < 5)
        assertThat(cause).isNotEqualTo(StallCause.PARTITION);
    }

    /**
     * Tests concurrent access from multiple threads is thread-safe.
     */
    @Test
    void concurrentAccess_IsThreadSafe() throws InterruptedException {
        // Given: 10 threads concurrently recording signals
        int threadCount = 10;
        int operationsPerThread = 100;
        var latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // When: Each thread records heartbeat failures, consensus participation, and signature checks
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    var member = members.get(threadId % members.size());
                    for (int i = 0; i < operationsPerThread; i++) {
                        diagnostics.recordHeartbeatFailure(member);
                        diagnostics.recordConsensusParticipation(true, i % 2 == 0);
                        diagnostics.recordSignatureCheck(i % 10 != 0);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Then: All operations complete without exceptions
        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // And: Diagnosis doesn't throw exceptions
        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnostics.diagnose(event);

        assertThat(cause).isIn(StallCause.PARTITION, StallCause.CONSENSUS_SLOW, StallCause.BYZANTINE);

        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.SECONDS);
    }

    /**
     * Tests zero proposals results in 0% participation rate.
     */
    @Test
    void zeroProposals_ResultsInZeroParticipation() {
        // Given: No consensus participation recorded

        // When: Diagnose stall
        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnostics.diagnose(event);

        // Then: Should diagnose CONSENSUS_SLOW (0% < 50% threshold)
        assertThat(cause).isEqualTo(StallCause.CONSENSUS_SLOW);
    }

    /**
     * Tests zero signature checks results in 0% failure rate.
     */
    @Test
    void zeroSignatureChecks_ResultsInZeroFailureRate() {
        // Given: No signature checks recorded

        // When: Diagnose stall
        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnostics.diagnose(event);

        // Then: Should NOT diagnose BYZANTINE (0% < 5% threshold)
        assertThat(cause).isNotEqualTo(StallCause.BYZANTINE);
    }

    /**
     * Tests diagnostics with null metrics registry (metrics disabled).
     */
    @Test
    void nullMetrics_DoesNotThrowExceptions() {
        // Given: Diagnostics with null metrics
        var bftValidator = new com.hellblazer.delos.choam.validation.DefaultBFTValidator(byzantineMapper);
        var diagnosticsWithoutMetrics = new StallDiagnostics(
            context, communications, bftValidator, null);

        // When: Diagnose stall with Byzantine condition
        for (int i = 0; i < 100; i++) {
            diagnosticsWithoutMetrics.recordSignatureCheck(i >= 10);
        }

        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnosticsWithoutMetrics.diagnose(event);

        // Then: Diagnosis works without metrics
        assertThat(cause).isEqualTo(StallCause.BYZANTINE);
    }

    /**
     * Tests multiple members with heartbeat failures, max is used for partition detection.
     */
    @Test
    void multipleMembers_MaxConsecutiveFailuresUsedForPartition() {
        // Given: Three members with different failure counts
        diagnostics.recordHeartbeatFailure(members.get(0));  // 1 failure
        diagnostics.recordHeartbeatFailure(members.get(1));  // 1 failure
        diagnostics.recordHeartbeatFailure(members.get(1));  // 2 failures
        diagnostics.recordHeartbeatFailure(members.get(2));  // 1 failure
        diagnostics.recordHeartbeatFailure(members.get(2));  // 2 failures
        diagnostics.recordHeartbeatFailure(members.get(2));  // 3 failures
        diagnostics.recordHeartbeatFailure(members.get(2));  // 4 failures (exceeds threshold)

        // When: Diagnose stall
        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnostics.diagnose(event);

        // Then: Should diagnose PARTITION (max is 4 > 3 threshold)
        assertThat(cause).isEqualTo(StallCause.PARTITION);
    }

    /**
     * Tests edge case: exactly at threshold should NOT trigger.
     */
    @Test
    void exactlyAtThreshold_DoesNotTrigger() {
        // Given: Exactly 3 consecutive heartbeat failures (threshold is > 3, not ≥ 3)
        var member = members.get(0);
        diagnostics.recordHeartbeatFailure(member);
        diagnostics.recordHeartbeatFailure(member);
        diagnostics.recordHeartbeatFailure(member);

        // When: Diagnose stall
        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnostics.diagnose(event);

        // Then: Should NOT diagnose PARTITION (3 is not > 3)
        assertThat(cause).isNotEqualTo(StallCause.PARTITION);
    }

    /**
     * Tests edge case: exactly one above threshold should trigger.
     */
    @Test
    void oneAboveThreshold_Triggers() {
        // Given: 4 consecutive heartbeat failures (threshold is 3, so > 3 triggers)
        var member = members.get(0);
        diagnostics.recordHeartbeatFailure(member);
        diagnostics.recordHeartbeatFailure(member);
        diagnostics.recordHeartbeatFailure(member);
        diagnostics.recordHeartbeatFailure(member);

        // When: Diagnose stall
        var event = new StallDetectedEvent(ULong.valueOf(100), Duration.ofMinutes(2), 10, context);
        var cause = diagnostics.diagnose(event);

        // Then: Should diagnose PARTITION (4 > 3)
        assertThat(cause).isEqualTo(StallCause.PARTITION);
    }
}
