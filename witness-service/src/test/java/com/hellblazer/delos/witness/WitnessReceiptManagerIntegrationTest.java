/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.witness.aggregation.recursive.compression.CompressionConfig;
import com.hellblazer.delos.witness.aggregation.storage.config.WitnessReceiptConfiguration;
import com.hellblazer.delos.witness.aggregation.storage.factory.ReceiptStoreFactory;
import com.hellblazer.delos.witness.aggregation.storage.memory.InMemoryAggregateReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.memory.InMemoryRecursiveReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.jdbc.JdbcAggregateReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.jdbc.JdbcRecursiveReceiptStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for WitnessReceiptManager storage integration (Phase 3.4.5).
 * <p>
 * Verifies:
 * - Factory creates correct store implementations
 * - WitnessReceiptManager accepts stores correctly
 * - WitnessBootstrap wires stores properly
 * - Configuration presets work end-to-end
 * - Backward compatibility maintained
 * <p>
 * Follows Phase 3.4.5 specification from Delos-4024.
 *
 * @author hal.hildebrand
 */
class WitnessReceiptManagerIntegrationTest {

    private DataSource dataSource;
    private WitnessBootstrap bootstrap;

    @AfterEach
    void cleanup() throws InterruptedException {
        if (bootstrap != null) {
            bootstrap.stop();
            bootstrap = null;
        }
        dataSource = null;
    }

    @Test
    void shouldUseInMemoryStoreByDefault() {
        var config = WitnessReceiptConfiguration.DEFAULT;

        var aggregateStore = ReceiptStoreFactory.createAggregateReceiptStore(config, null, null);
        var recursiveStore = ReceiptStoreFactory.createRecursiveReceiptStore(config, null, null);

        assertThat(aggregateStore).isInstanceOf(InMemoryAggregateReceiptStore.class);
        assertThat(recursiveStore).isInstanceOf(InMemoryRecursiveReceiptStore.class);
    }

    @Test
    void shouldUseJdbcStoreWhenConfigured() {
        dataSource = createTestDataSource();

        var config = new WitnessReceiptConfiguration(
            WitnessReceiptConfiguration.StoreType.JDBC,
            CompressionConfig.FAST,
            true,
            1000,
            60
        );

        var aggregateStore = ReceiptStoreFactory.createAggregateReceiptStore(config, dataSource, null);
        var recursiveStore = ReceiptStoreFactory.createRecursiveReceiptStore(config, dataSource, null);

        assertThat(aggregateStore).isInstanceOf(JdbcAggregateReceiptStore.class);
        assertThat(recursiveStore).isInstanceOf(JdbcRecursiveReceiptStore.class);
    }

    @Test
    void shouldAcceptNullStores() {
        // Backward compatibility: manager accepts null stores and defaults to in-memory
        var parameters = WitnessParameters.newBuilder()
            .k(5)
            .threshold(4)  // M > (2*k)/3, so for k=5, min threshold=4
            .epoch(0)
            .drainPeriod(Duration.ofSeconds(10))
            .build();

        var manager = new WitnessReceiptManager(
            parameters,
            null,  // signatureBuffer
            null,  // isViewChangeActive
            null,  // degradedCalculator
            null,  // metrics
            null,  // aggregateReceiptStore (nullable, defaults to in-memory)
            null   // recursiveReceiptStore (nullable, defaults to in-memory)
        );

        assertThat(manager.getAggregateReceiptStore()).isNotNull();
        assertThat(manager.getRecursiveReceiptStore()).isNotNull();
        assertThat(manager.getAggregateReceiptStore()).isInstanceOf(InMemoryAggregateReceiptStore.class);
        assertThat(manager.getRecursiveReceiptStore()).isInstanceOf(InMemoryRecursiveReceiptStore.class);
    }

    @Test
    void shouldAcceptProvidedStores() {
        dataSource = createTestDataSource();

        var config = WitnessReceiptConfiguration.PRODUCTION;

        var aggregateStore = ReceiptStoreFactory.createAggregateReceiptStore(config, dataSource, null);
        var recursiveStore = ReceiptStoreFactory.createRecursiveReceiptStore(config, dataSource, null);

        var parameters = WitnessParameters.newBuilder()
            .k(5)
            .threshold(4)  // M > (2*k)/3, so for k=5, min threshold=4
            .epoch(0)
            .drainPeriod(Duration.ofSeconds(10))
            .build();

        var manager = new WitnessReceiptManager(
            parameters,
            null,
            null,
            null,
            null,
            aggregateStore,
            recursiveStore
        );

        assertThat(manager.getAggregateReceiptStore()).isSameAs(aggregateStore);
        assertThat(manager.getRecursiveReceiptStore()).isSameAs(recursiveStore);
        assertThat(manager.getAggregateReceiptStore()).isInstanceOf(JdbcAggregateReceiptStore.class);
        assertThat(manager.getRecursiveReceiptStore()).isInstanceOf(JdbcRecursiveReceiptStore.class);
    }

    @Test
    void shouldBootstrapWithDefaultConfiguration() {
        var serviceConfig = createTestServiceConfig();

        bootstrap = new WitnessBootstrap(serviceConfig);

        // Should use DEFAULT receipt configuration (in-memory, no compression)
        assertThat(bootstrap.getReceiptConfiguration()).isEqualTo(WitnessReceiptConfiguration.DEFAULT);
        assertThat(bootstrap.getReceiptConfiguration().storeType())
            .isEqualTo(WitnessReceiptConfiguration.StoreType.MEMORY);
        assertThat(bootstrap.getReceiptConfiguration().compressionConfig())
            .isEqualTo(CompressionConfig.NONE);
    }

    @Test
    void shouldBootstrapWithCustomConfiguration() {
        dataSource = createTestDataSource();

        var serviceConfig = createTestServiceConfig();

        var receiptConfig = new WitnessReceiptConfiguration(
            WitnessReceiptConfiguration.StoreType.JDBC,
            CompressionConfig.FAST,
            true,
            2000,
            120
        );

        bootstrap = new WitnessBootstrap(serviceConfig)
            .withReceiptConfiguration(receiptConfig)
            .withDataSource(dataSource);

        assertThat(bootstrap.getReceiptConfiguration()).isEqualTo(receiptConfig);
        assertThat(bootstrap.getReceiptConfiguration().storeType())
            .isEqualTo(WitnessReceiptConfiguration.StoreType.JDBC);
        assertThat(bootstrap.getReceiptConfiguration().compressionConfig())
            .isEqualTo(CompressionConfig.FAST);
        assertThat(bootstrap.getReceiptConfiguration().maxCacheSize()).isEqualTo(2000);
        assertThat(bootstrap.getReceiptConfiguration().cacheTTLMinutes()).isEqualTo(120);
    }

    @Test
    void shouldBootstrapWithProductionPreset() {
        dataSource = createTestDataSource();

        var serviceConfig = createTestServiceConfig();

        bootstrap = new WitnessBootstrap(serviceConfig)
            .withReceiptConfiguration(WitnessReceiptConfiguration.PRODUCTION)
            .withDataSource(dataSource);

        assertThat(bootstrap.getReceiptConfiguration()).isEqualTo(WitnessReceiptConfiguration.PRODUCTION);
        assertThat(bootstrap.getReceiptConfiguration().storeType())
            .isEqualTo(WitnessReceiptConfiguration.StoreType.JDBC);
        assertThat(bootstrap.getReceiptConfiguration().compressionConfig())
            .isEqualTo(CompressionConfig.BEST);
    }

    @Test
    void shouldSupportFluentConfiguration() {
        dataSource = createTestDataSource();

        var serviceConfig = createTestServiceConfig();

        var receiptConfig = new WitnessReceiptConfiguration(
            WitnessReceiptConfiguration.StoreType.JDBC,
            CompressionConfig.FAST,
            true,
            1000,
            60
        );

        // Test fluent API returns same instance
        bootstrap = new WitnessBootstrap(serviceConfig);
        var result1 = bootstrap.withReceiptConfiguration(receiptConfig);
        var result2 = bootstrap.withDataSource(dataSource);

        assertThat(result1).isSameAs(bootstrap);
        assertThat(result2).isSameAs(bootstrap);
    }

    @Test
    void shouldRejectNullReceiptConfiguration() {
        var serviceConfig = createTestServiceConfig();

        bootstrap = new WitnessBootstrap(serviceConfig);

        assertThatThrownBy(() -> bootstrap.withReceiptConfiguration(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("receiptConfig cannot be null");
    }

    @Test
    void shouldRejectNullDataSource() {
        var serviceConfig = createTestServiceConfig();

        bootstrap = new WitnessBootstrap(serviceConfig);

        assertThatThrownBy(() -> bootstrap.withDataSource(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("dataSource cannot be null");
    }

    @Test
    void shouldMaintainBackwardCompatibilityWithOldConstructor() {
        // Old constructor (no stores) should still work
        var parameters = WitnessParameters.newBuilder()
            .k(5)
            .threshold(4)  // M > (2*k)/3, so for k=5, min threshold=4
            .epoch(0)
            .drainPeriod(Duration.ofSeconds(10))
            .build();

        var manager = new WitnessReceiptManager(parameters);

        assertThat(manager.getAggregateReceiptStore()).isNotNull();
        assertThat(manager.getRecursiveReceiptStore()).isNotNull();
        assertThat(manager.getAggregateReceiptStore()).isInstanceOf(InMemoryAggregateReceiptStore.class);
        assertThat(manager.getRecursiveReceiptStore()).isInstanceOf(InMemoryRecursiveReceiptStore.class);
    }

    @Test
    void shouldMaintainBackwardCompatibilityWithMetricsConstructor() {
        // Constructor with metrics but no stores should still work
        var parameters = WitnessParameters.newBuilder()
            .k(5)
            .threshold(4)  // M > (2*k)/3, so for k=5, min threshold=4
            .epoch(0)
            .drainPeriod(Duration.ofSeconds(10))
            .build();

        var manager = new WitnessReceiptManager(
            parameters,
            null,  // signatureBuffer
            null,  // isViewChangeActive
            null,  // degradedCalculator
            null   // metrics
        );

        assertThat(manager.getAggregateReceiptStore()).isNotNull();
        assertThat(manager.getRecursiveReceiptStore()).isNotNull();
        assertThat(manager.getAggregateReceiptStore()).isInstanceOf(InMemoryAggregateReceiptStore.class);
        assertThat(manager.getRecursiveReceiptStore()).isInstanceOf(InMemoryRecursiveReceiptStore.class);
    }

    /**
     * Create in-memory H2 data source for testing.
     */
    private DataSource createTestDataSource() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test_" + System.currentTimeMillis() + ";DB_CLOSE_DELAY=-1");
        return ds;
    }

    /**
     * Create minimal witness service config for testing.
     */
    private WitnessServiceConfig createTestServiceConfig() {
        return new WitnessServiceConfig(
            0,  // Dynamic port
            5,  // Committee size
            4,  // Threshold (M > (2*k)/3, so for k=5, min threshold=4)
            Duration.ofSeconds(5),  // Collection timeout
            Duration.ofSeconds(10),  // Drain period
            1000,  // Max concurrent collections
            100,  // Max subscriptions
            false,  // MTLS disabled
            null,  // No cert path
            null,  // No key path
            com.hellblazer.delos.cryptography.DigestAlgorithm.DEFAULT
        );
    }
}
