/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Tests for KerlDhtMetrics, NoOpKerlDhtMetrics, and MicrometerKerlDhtMetrics.
 */
class KerlDhtMetricsTest {

    @Test
    void noOpFactoryReturnsInstance() {
        var metrics = KerlDhtMetrics.noOp();
        assertThat(metrics).isNotNull();
        assertThat(metrics).isInstanceOf(NoOpKerlDhtMetrics.class);
    }

    @Test
    void noOpDoesNotThrow() {
        var metrics = KerlDhtMetrics.noOp();

        assertThatNoException().isThrownBy(() -> {
            metrics.recordReadLatency("getKeyState", 1_000_000);
            metrics.recordWriteLatency("append", 2_000_000);
            metrics.recordReconciliationLatency(500_000);
            metrics.incrementQuorumSuccess("getKeyState");
            metrics.incrementQuorumFailure("append");
            metrics.recordQuorumRespondentCount("getKeyState", 5);
            metrics.incrementValidationSuccess("getKeyState");
            metrics.incrementValidationFailure("append", "bad signature");
            metrics.incrementValidationSkipped("getKerl", "missing local data");
            metrics.incrementByzantineDetection("VALIDATION");
            metrics.recordByzantineScore(0.75);
            metrics.recordTrackedByzantineMembers(3);
            metrics.incrementMemberTimeout("member-1");
            metrics.incrementMemberCommunicationFailure("member-2");
            metrics.recordReconciliationEventsReceived(10);
            metrics.recordReconciliationEventsSent(5);
            metrics.incrementCacheHit();
            metrics.incrementCacheMiss();
            metrics.recordConnectionPoolActive(3);
            metrics.recordConnectionPoolIdle(7);
        });
    }

    @Test
    void micrometerRecordsReadLatency() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerKerlDhtMetrics(registry);

        metrics.recordReadLatency("getKeyState", 5_000_000);

        var timer = registry.find("thoth.dht.read.latency").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void micrometerRecordsWriteLatency() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerKerlDhtMetrics(registry);

        metrics.recordWriteLatency("append", 10_000_000);

        var timer = registry.find("thoth.dht.write.latency").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void micrometerRecordsReconciliationLatency() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerKerlDhtMetrics(registry);

        metrics.recordReconciliationLatency(500_000);

        var timer = registry.find("thoth.dht.reconciliation.latency").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void micrometerCountsQuorumOutcomes() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerKerlDhtMetrics(registry);

        metrics.incrementQuorumSuccess("getKeyState");
        metrics.incrementQuorumSuccess("getKeyState");
        metrics.incrementQuorumFailure("append");

        var success = registry.find("thoth.dht.quorum.success").counter();
        var failure = registry.find("thoth.dht.quorum.failure").counter();
        assertThat(success).isNotNull();
        assertThat(success.count()).isEqualTo(2.0);
        assertThat(failure).isNotNull();
        assertThat(failure.count()).isEqualTo(1.0);
    }

    @Test
    void micrometerCountsValidation() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerKerlDhtMetrics(registry);

        metrics.incrementValidationSuccess("read");
        metrics.incrementValidationFailure("write", "bad sig");
        metrics.incrementValidationSkipped("read", "no local data");

        assertThat(registry.find("thoth.dht.validation.success").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("thoth.dht.validation.failure").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("thoth.dht.validation.skipped").counter().count()).isEqualTo(1.0);
    }

    @Test
    void micrometerCountsByzantineDetection() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerKerlDhtMetrics(registry);

        metrics.incrementByzantineDetection("VALIDATION");
        metrics.incrementByzantineDetection("TIMEOUT");

        assertThat(registry.find("thoth.dht.byzantine.detection").counter().count()).isEqualTo(2.0);
    }

    @Test
    void micrometerGaugesTrackedMembers() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerKerlDhtMetrics(registry);

        metrics.recordTrackedByzantineMembers(5);

        var gauge = registry.find("thoth.dht.byzantine.tracked").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(5.0);
    }

    @Test
    void micrometerCountsCacheOperations() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerKerlDhtMetrics(registry);

        metrics.incrementCacheHit();
        metrics.incrementCacheHit();
        metrics.incrementCacheMiss();

        assertThat(registry.find("thoth.dht.cache.hit").counter().count()).isEqualTo(2.0);
        assertThat(registry.find("thoth.dht.cache.miss").counter().count()).isEqualTo(1.0);
    }

    @Test
    void micrometerGaugesConnectionPool() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerKerlDhtMetrics(registry);

        metrics.recordConnectionPoolActive(3);
        metrics.recordConnectionPoolIdle(7);

        assertThat(registry.find("thoth.dht.pool.active").gauge().value()).isEqualTo(3.0);
        assertThat(registry.find("thoth.dht.pool.idle").gauge().value()).isEqualTo(7.0);
    }

    @Test
    void micrometerCountsPerMemberMetrics() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerKerlDhtMetrics(registry);

        metrics.incrementMemberTimeout("member-abc");
        metrics.incrementMemberTimeout("member-abc");
        metrics.incrementMemberCommunicationFailure("member-def");

        var timeoutCounter = registry.find("thoth.dht.member.timeout").tag("member", "member-abc").counter();
        assertThat(timeoutCounter).isNotNull();
        assertThat(timeoutCounter.count()).isEqualTo(2.0);

        var failureCounter = registry.find("thoth.dht.member.failure").tag("member", "member-def").counter();
        assertThat(failureCounter).isNotNull();
        assertThat(failureCounter.count()).isEqualTo(1.0);
    }
}
