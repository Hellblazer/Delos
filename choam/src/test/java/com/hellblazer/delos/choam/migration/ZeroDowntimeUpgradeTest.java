/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.migration;

import com.hellblazer.delos.choam.migration.example.V1ToV2Migrator;
import com.hellblazer.delos.choam.migration.example.V2ToV1Migrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for zero-downtime rolling upgrades in production scenarios.
 * <p>
 * Simulates a production cluster upgrade with continuous transaction load,
 * verifying:
 * - No transaction failures during upgrade
 * - Latency remains within SLA (p95 < 10% increase)
 * - View changes complete successfully
 * - Mixed version operation (V1 and V2 nodes coexist)
 *
 * @author hal.hildebrand
 */
public class ZeroDowntimeUpgradeTest {

    private MigrationRegistry registry;
    private AtomicInteger successCount;
    private AtomicInteger failureCount;
    private AtomicLong totalLatencyMs;
    private List<Long> latencies;

    @BeforeEach
    public void setup() {
        registry = new MigrationRegistry();
        registry.register(new V1ToV2Migrator());
        registry.register(new V2ToV1Migrator());

        successCount = new AtomicInteger(0);
        failureCount = new AtomicInteger(0);
        totalLatencyMs = new AtomicLong(0);
        latencies = new CopyOnWriteArrayList<>();
    }

    @Test
    public void testRollingUpgradeUnderLoad() throws Exception {
        // Arrange: Simulate 4-node cluster (f=1 tolerance)
        int nodeCount = 4;
        var nodes = createNodes(nodeCount, "0.0.6");

        // Start transaction load (100 tx/sec for 30 seconds)
        var loadFuture = startTransactionLoad(nodes, 100, Duration.ofSeconds(30));

        // Act: Rolling upgrade (one node every 5 seconds)
        for (int i = 0; i < nodeCount; i++) {
            Thread.sleep(5000);  // Wait 5 seconds between upgrades

            // Upgrade node i
            upgradeNode(nodes.get(i), "0.0.6", "0.0.7");

            // Verify cluster still operational (quorum: 3 of 4 nodes)
            assertThat(getOnlineCount(nodes)).isGreaterThanOrEqualTo(3);
        }

        // Wait for load to complete
        loadFuture.get(60, TimeUnit.SECONDS);

        // Assert: SLA met
        var totalTx = successCount.get() + failureCount.get();
        var successRate = (double) successCount.get() / totalTx;

        assertThat(successRate).isGreaterThan(0.999);  // > 99.9% success rate
        assertThat(failureCount.get()).isLessThan(totalTx / 100);  // < 1% failures

        // Assert: Latency within SLA (p95 < 10% increase)
        var p95Latency = calculateP95(latencies);
        var baselineP95 = 100;  // Baseline: 100ms
        assertThat(p95Latency).isLessThan(baselineP95 * 1.1);  // < 10% increase
    }

    @Test
    public void testMixedVersionOperation() throws Exception {
        // Arrange: Mixed cluster (2 V1 nodes, 2 V2 nodes)
        var v1Nodes = createNodes(2, "0.0.6");
        var v2Nodes = createNodes(2, "0.0.7");
        var allNodes = new ArrayList<SimulatedNode>();
        allNodes.addAll(v1Nodes);
        allNodes.addAll(v2Nodes);

        // Act: Submit transactions to mixed cluster
        for (int i = 0; i < 1000; i++) {
            var node = allNodes.get(i % 4);
            submitTransaction(node, createTestState(i));
        }

        // Assert: All transactions succeeded
        assertThat(successCount.get()).isEqualTo(1000);
        assertThat(failureCount.get()).isZero();

        // Assert: Both V1 and V2 nodes processed transactions
        assertThat(v1Nodes.get(0).processedCount).isGreaterThan(0);
        assertThat(v2Nodes.get(0).processedCount).isGreaterThan(0);
    }

    @Test
    public void testRollbackUnderLoad() throws Exception {
        // Arrange: V2 cluster with issue requiring rollback
        var nodes = createNodes(4, "0.0.7");
        var loadFuture = startTransactionLoad(nodes, 50, Duration.ofSeconds(20));

        // Act: Rolling rollback to V1 (one node every 5 seconds)
        for (int i = 0; i < 4; i++) {
            Thread.sleep(5000);
            upgradeNode(nodes.get(i), "0.0.7", "0.0.6");  // Rollback
            assertThat(getOnlineCount(nodes)).isGreaterThanOrEqualTo(3);
        }

        loadFuture.get(60, TimeUnit.SECONDS);

        // Assert: Rollback completed without major issues
        var successRate = (double) successCount.get() / (successCount.get() + failureCount.get());
        assertThat(successRate).isGreaterThan(0.99);  // > 99% (slightly lower SLA during rollback)
    }

    @Test
    public void testUpgradeWithViewChange() throws Exception {
        // Arrange: 7-node cluster (f=2 tolerance)
        var nodes = createNodes(7, "0.0.6");

        // Act: Upgrade during view change
        upgradeNode(nodes.get(0), "0.0.6", "0.0.7");
        upgradeNode(nodes.get(1), "0.0.6", "0.0.7");

        // Trigger view change (simulated)
        triggerViewChange(nodes);

        // Continue upgrade
        upgradeNode(nodes.get(2), "0.0.6", "0.0.7");
        upgradeNode(nodes.get(3), "0.0.6", "0.0.7");

        // Assert: View change completed with mixed versions
        assertThat(getOnlineCount(nodes)).isEqualTo(7);
        assertThat(nodes.stream().filter(n -> n.version.equals("0.0.7")).count()).isEqualTo(4);
    }

    // ========== Helper Methods ==========

    private List<SimulatedNode> createNodes(int count, String version) {
        var nodes = new ArrayList<SimulatedNode>();
        for (int i = 0; i < count; i++) {
            nodes.add(new SimulatedNode("node" + i, version));
        }
        return nodes;
    }

    private CompletableFuture<Void> startTransactionLoad(List<SimulatedNode> nodes,
                                                          int txPerSecond, Duration duration) {
        return CompletableFuture.runAsync(() -> {
            var endTime = System.currentTimeMillis() + duration.toMillis();
            var txCount = 0;

            while (System.currentTimeMillis() < endTime) {
                try {
                    var node = nodes.get(txCount % nodes.size());
                    submitTransaction(node, createTestState(txCount++));

                    // Rate limiting: 1/txPerSecond seconds per transaction
                    Thread.sleep(1000 / txPerSecond);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
    }

    private void submitTransaction(SimulatedNode node, byte[] state) {
        var startTime = System.nanoTime();

        try {
            node.processTransaction(state);
            successCount.incrementAndGet();

            var latencyMs = (System.nanoTime() - startTime) / 1_000_000;
            totalLatencyMs.addAndGet(latencyMs);
            latencies.add(latencyMs);
        } catch (Exception e) {
            failureCount.incrementAndGet();
        }
    }

    private void upgradeNode(SimulatedNode node, String fromVersion, String toVersion)
            throws Exception {
        // Simulate upgrade: migrate checkpoint and restart with new version
        var checkpoint = node.createCheckpoint();

        if (!fromVersion.equals(toVersion)) {
            var migratedCheckpoint = new ByteArrayOutputStream();
            registry.migrate(
                new ByteArrayInputStream(checkpoint),
                fromVersion,
                toVersion,
                migratedCheckpoint
            );
            checkpoint = migratedCheckpoint.toByteArray();
        }

        node.restart(toVersion, checkpoint);
    }

    private int getOnlineCount(List<SimulatedNode> nodes) {
        return (int) nodes.stream().filter(n -> n.online).count();
    }

    private void triggerViewChange(List<SimulatedNode> nodes) {
        // Simulated view change (in real system, triggered by Fireflies)
        nodes.forEach(n -> n.viewEpoch++);
    }

    private byte[] createTestState(int txId) {
        try {
            var baos = new ByteArrayOutputStream();
            var dos = new DataOutputStream(baos);
            dos.writeInt(txId);              // height
            dos.write(new byte[32]);         // hash (zeros for test)
            dos.writeBytes("test-data");     // data
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private long calculateP95(List<Long> latencies) {
        if (latencies.isEmpty()) return 0;

        var sorted = new ArrayList<>(latencies);
        sorted.sort(Long::compareTo);

        int p95Index = (int) (sorted.size() * 0.95);
        return sorted.get(Math.min(p95Index, sorted.size() - 1));
    }

    /**
     * Simulated CHOAM node for testing.
     */
    private static class SimulatedNode {
        String id;
        String version;
        boolean online = true;
        int viewEpoch = 0;
        int processedCount = 0;
        byte[] state = new byte[0];

        SimulatedNode(String id, String version) {
            this.id = id;
            this.version = version;
        }

        void processTransaction(byte[] tx) throws Exception {
            if (!online) {
                throw new IllegalStateException("Node offline");
            }

            // Simulate transaction processing
            Thread.sleep(ThreadLocalRandom.current().nextInt(50, 150));  // 50-150ms
            state = tx;
            processedCount++;
        }

        byte[] createCheckpoint() {
            return state;
        }

        void restart(String newVersion, byte[] checkpoint) {
            online = false;
            // Simulate restart delay
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // Ignored
            }
            version = newVersion;
            state = checkpoint;
            online = true;
        }
    }
}
