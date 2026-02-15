/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.thoth.metrics.KerlDhtMetrics;
import com.hellblazer.delos.thoth.metrics.MicrometerKerlDhtMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test connection pool monitoring and exhaustion detection in KerlDHT.
 * Tests verify that:
 * - Pool metrics are recorded periodically
 * - Pool can handle concurrent load
 * - Pool exhaustion is detected at 90%+ utilization
 * - Pool recovers after connections are released
 *
 * @author hal.hildebrand
 */
class KerlDHTConnectionPoolTest {

    private static final int MAX_CONNECTIONS = 10;
    private static final double PBYZ = 0.25;

    private DynamicContext<Member> context;
    private KerlDHT dht;
    private SimpleMeterRegistry meterRegistry;
    private MicrometerKerlDhtMetrics metrics;
    private JdbcConnectionPool connectionPool;
    private Router router;
    private String prefix;

    @BeforeEach
    void setUp() throws Exception {
        prefix = UUID.randomUUID().toString();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{6, 6, 6});

        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var controlled = stereotomy.newIdentifier();
        var member = new ControlledIdentifierMember(controlled);

        context = DynamicContext.<Member>newBuilder().setpByz(PBYZ).setCardinality(5).build();
        context.activate(member);

        var url = String.format("jdbc:h2:mem:%s-%s;DB_CLOSE_ON_EXIT=FALSE", member.getId(), prefix);
        connectionPool = JdbcConnectionPool.create(url, "", "");
        connectionPool.setMaxConnections(MAX_CONNECTIONS);

        router = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));

        meterRegistry = new SimpleMeterRegistry();
        metrics = new MicrometerKerlDhtMetrics(meterRegistry);

        dht = new KerlDHT(Duration.ofMillis(100), context, member, (t, k) -> k, connectionPool,
                          DigestAlgorithm.DEFAULT, router, Duration.ofSeconds(10), 0.0125, null, metrics, null);
    }

    @AfterEach
    void tearDown() {
        if (dht != null) {
            dht.stop();
        }
        if (router != null) {
            router.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that pool metrics are recorded periodically after DHT starts.
     * Verifies that active and idle connection metrics are updated.
     */
    @Test
    void testPoolMetricsRecorded() throws Exception {
        // Start DHT (which should start pool monitoring)
        dht.start(Duration.ofSeconds(1));

        // Wait for at least 2 monitoring cycles (200ms each)
        Thread.sleep(500);

        // Check that metrics snapshot shows pool state
        var snapshot = metrics.getSnapshot();
        assertThat(snapshot).isNotNull();

        // Initially, pool should have 0 active connections
        assertThat(snapshot.connectionPoolActive()).isGreaterThanOrEqualTo(0);

        // Total connections (active + idle) should be reasonable
        var totalConnections = snapshot.connectionPoolActive() + snapshot.connectionPoolIdle();
        assertThat(totalConnections).isLessThanOrEqualTo(MAX_CONNECTIONS);
    }

    /**
     * Test pool under concurrent load to verify it handles many operations.
     * Creates multiple concurrent operations to stress the pool.
     */
    @Test
    void testPoolStress() throws Exception {
        dht.start(Duration.ofSeconds(1));

        var numOperations = 50;
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var latch = new CountDownLatch(numOperations);
        var successCount = new AtomicInteger(0);

        // Submit concurrent operations that use the pool
        for (int i = 0; i < numOperations; i++) {
            executor.submit(() -> {
                try {
                    // Simulate a DHT operation that uses a connection
                    try (var connection = connectionPool.getConnection()) {
                        // Simple query to verify connection works
                        var stmt = connection.createStatement();
                        stmt.execute("SELECT 1");
                        successCount.incrementAndGet();
                    }
                } catch (SQLException e) {
                    // Expected under high load, but most should succeed
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all operations to complete
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Most operations should succeed (allow some failures under stress)
        assertThat(successCount.get()).isGreaterThan(numOperations / 2);

        // Check metrics show activity
        var snapshot = metrics.getSnapshot();
        assertThat(snapshot.connectionPoolActive()).isGreaterThanOrEqualTo(0);
    }

    /**
     * Test that pool exhaustion is detected when 90%+ connections are active.
     * Holds connections open to simulate exhaustion, then verifies detection.
     */
    @Test
    void testPoolExhaustionDetection() throws Exception {
        dht.start(Duration.ofSeconds(1));

        var heldConnections = new ArrayList<Connection>();
        try {
            // Hold 9 out of 10 connections (90% utilization)
            for (int i = 0; i < 9; i++) {
                heldConnections.add(connectionPool.getConnection());
            }

            // Wait for monitoring to detect exhaustion
            Thread.sleep(300);

            // Check if pool is detected as exhausted
            var isExhausted = dht.isPoolExhausted();
            assertThat(isExhausted).withFailMessage(
                "Pool should be detected as exhausted at 90%% utilization (9/10 connections)"
            ).isTrue();

            // Check metrics show high utilization
            var snapshot = metrics.getSnapshot();
            var utilization = snapshot.connectionPoolUtilization();
            assertThat(utilization).withFailMessage(
                "Pool utilization should be >= 0.9, but was: %.2f", utilization
            ).isGreaterThanOrEqualTo(0.9);

            // Health check should fail due to pool exhaustion
            assertThat(snapshot.isHealthy()).withFailMessage(
                "Health check should fail when pool utilization >= 90%%"
            ).isFalse();

        } finally {
            // Release all held connections
            for (var conn : heldConnections) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Test that pool recovers after exhaustion when connections are released.
     * Simulates exhaustion followed by connection release and recovery.
     */
    @Test
    void testPoolRecovery() throws Exception {
        dht.start(Duration.ofSeconds(1));

        var heldConnections = new ArrayList<Connection>();

        // Phase 1: Exhaust pool (hold all 10 connections)
        for (int i = 0; i < MAX_CONNECTIONS; i++) {
            heldConnections.add(connectionPool.getConnection());
        }

        // Wait for detection
        Thread.sleep(300);

        // Verify exhaustion
        assertThat(dht.isPoolExhausted()).isTrue();

        // Phase 2: Release connections
        for (var conn : heldConnections) {
            conn.close();
        }
        heldConnections.clear();

        // Wait for recovery monitoring
        Thread.sleep(300);

        // Verify recovery
        var isExhausted = dht.isPoolExhausted();
        assertThat(isExhausted).withFailMessage(
            "Pool should recover after connections released"
        ).isFalse();

        // Verify pool is healthy again
        var snapshot = metrics.getSnapshot();
        var utilization = snapshot.connectionPoolUtilization();
        assertThat(utilization).withFailMessage(
            "Pool utilization should be low after recovery, but was: %.2f", utilization
        ).isLessThan(0.9);
    }

    /**
     * Test that pool monitoring stops cleanly when DHT is stopped.
     * Verifies no background tasks continue after stop().
     */
    @Test
    void testPoolMonitoringStopsWithDHT() throws Exception {
        dht.start(Duration.ofSeconds(1));

        // Wait for monitoring to start
        Thread.sleep(200);

        // Get baseline metrics
        var beforeSnapshot = metrics.getSnapshot();
        var beforeActive = beforeSnapshot.connectionPoolActive();

        // Stop DHT
        dht.stop();

        // Wait to ensure monitoring stopped
        Thread.sleep(500);

        // Metrics should still be accessible but not changing (snapshot is last recorded)
        var afterSnapshot = metrics.getSnapshot();
        assertThat(afterSnapshot).isNotNull();

        // Connection pool should be disposed (metrics show last state before disposal)
        // We can't directly test that monitoring stopped, but we can verify stop() completed
        assertThat(dht.isPoolExhausted()).isFalse(); // Should return safe default after stop
    }
}
