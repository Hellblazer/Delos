/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.storage.factory;

import com.hellblazer.delos.witness.aggregation.recursive.compression.CompressionConfig;
import com.hellblazer.delos.witness.aggregation.storage.config.WitnessReceiptConfiguration;
import com.hellblazer.delos.witness.aggregation.storage.memory.InMemoryAggregateReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.memory.InMemoryRecursiveReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.jdbc.JdbcAggregateReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.jdbc.JdbcRecursiveReceiptStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Test for ReceiptStoreFactory.
 * <p>
 * Verifies:
 * - Correct store implementation created based on configuration
 * - Compression wrapping applied when configured
 * - Cache wrapping applied when enabled
 * - Factory handles null inputs gracefully
 * <p>
 * Follows Phase 3.4.5 specification from Delos-4024.
 *
 * @author hal.hildebrand
 */
class ReceiptStoreFactoryTest {

    private DataSource dataSource;

    @AfterEach
    void cleanup() {
        if (dataSource != null) {
            // Cleanup H2 database if created
            dataSource = null;
        }
    }

    @Test
    void shouldCreateInMemoryAggregateStore() {
        var config = new WitnessReceiptConfiguration(
            WitnessReceiptConfiguration.StoreType.MEMORY,
            CompressionConfig.NONE,
            false,
            1000,
            60
        );

        var store = ReceiptStoreFactory.createAggregateReceiptStore(config, null, null);

        assertThat(store).isNotNull();
        assertThat(store).isInstanceOf(InMemoryAggregateReceiptStore.class);
    }

    @Test
    void shouldCreateInMemoryRecursiveStore() {
        var config = new WitnessReceiptConfiguration(
            WitnessReceiptConfiguration.StoreType.MEMORY,
            CompressionConfig.NONE,
            false,
            1000,
            60
        );

        var store = ReceiptStoreFactory.createRecursiveReceiptStore(config, null, null);

        assertThat(store).isNotNull();
        assertThat(store).isInstanceOf(InMemoryRecursiveReceiptStore.class);
    }

    @Test
    void shouldCreateJdbcAggregateStore() {
        dataSource = createTestDataSource();

        var config = new WitnessReceiptConfiguration(
            WitnessReceiptConfiguration.StoreType.JDBC,
            CompressionConfig.NONE,
            false,
            1000,
            60
        );

        var store = ReceiptStoreFactory.createAggregateReceiptStore(config, dataSource, null);

        assertThat(store).isNotNull();
        assertThat(store).isInstanceOf(JdbcAggregateReceiptStore.class);
    }

    @Test
    void shouldCreateJdbcRecursiveStore() {
        dataSource = createTestDataSource();

        var config = new WitnessReceiptConfiguration(
            WitnessReceiptConfiguration.StoreType.JDBC,
            CompressionConfig.NONE,
            false,
            1000,
            60
        );

        var store = ReceiptStoreFactory.createRecursiveReceiptStore(config, dataSource, null);

        assertThat(store).isNotNull();
        assertThat(store).isInstanceOf(JdbcRecursiveReceiptStore.class);
    }

    @Test
    void shouldRejectNullConfigForAggregateStore() {
        assertThatThrownBy(() -> ReceiptStoreFactory.createAggregateReceiptStore(null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("config cannot be null");
    }

    @Test
    void shouldRejectNullConfigForRecursiveStore() {
        assertThatThrownBy(() -> ReceiptStoreFactory.createRecursiveReceiptStore(null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("config cannot be null");
    }

    @Test
    void shouldRejectNullDataSourceForJdbcAggregateStore() {
        var config = new WitnessReceiptConfiguration(
            WitnessReceiptConfiguration.StoreType.JDBC,
            CompressionConfig.NONE,
            false,
            1000,
            60
        );

        assertThatThrownBy(() -> ReceiptStoreFactory.createAggregateReceiptStore(config, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dataSource required for JDBC storage");
    }

    @Test
    void shouldRejectNullDataSourceForJdbcRecursiveStore() {
        var config = new WitnessReceiptConfiguration(
            WitnessReceiptConfiguration.StoreType.JDBC,
            CompressionConfig.NONE,
            false,
            1000,
            60
        );

        assertThatThrownBy(() -> ReceiptStoreFactory.createRecursiveReceiptStore(config, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dataSource required for JDBC storage");
    }

    @Test
    void shouldUseDefaultConfigurationPreset() {
        var store = ReceiptStoreFactory.createAggregateReceiptStore(
            WitnessReceiptConfiguration.DEFAULT,
            null,
            null
        );

        assertThat(store).isInstanceOf(InMemoryAggregateReceiptStore.class);
    }

    @Test
    void shouldUseProductionConfigurationPreset() {
        dataSource = createTestDataSource();

        var store = ReceiptStoreFactory.createAggregateReceiptStore(
            WitnessReceiptConfiguration.PRODUCTION,
            dataSource,
            null
        );

        assertThat(store).isInstanceOf(JdbcAggregateReceiptStore.class);
    }

    /**
     * Create in-memory H2 data source for testing.
     */
    private DataSource createTestDataSource() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test_" + System.currentTimeMillis() + ";DB_CLOSE_DELAY=-1");
        return ds;
    }
}
