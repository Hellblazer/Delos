/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.storage.config;

import com.hellblazer.delos.witness.aggregation.recursive.compression.CompressionConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Test for WitnessReceiptConfiguration record.
 * <p>
 * Verifies:
 * - Default and production presets work correctly
 * - Validation rules enforced for field values
 * - Immutability via record semantics
 * <p>
 * Follows Phase 3.4.5 specification from Delos-4024.
 *
 * @author hal.hildebrand
 */
class WitnessReceiptConfigurationTest {

    @Test
    void shouldCreateDefaultConfiguration() {
        var config = WitnessReceiptConfiguration.DEFAULT;

        assertThat(config.storeType()).isEqualTo(WitnessReceiptConfiguration.StoreType.MEMORY);
        assertThat(config.compressionConfig()).isEqualTo(CompressionConfig.NONE);
        assertThat(config.cacheEnabled()).isTrue();
        assertThat(config.maxCacheSize()).isEqualTo(1000);
        assertThat(config.cacheTTLMinutes()).isEqualTo(60);
    }

    @Test
    void shouldCreateProductionConfiguration() {
        var config = WitnessReceiptConfiguration.PRODUCTION;

        assertThat(config.storeType()).isEqualTo(WitnessReceiptConfiguration.StoreType.JDBC);
        assertThat(config.compressionConfig()).isEqualTo(CompressionConfig.BEST);
        assertThat(config.cacheEnabled()).isTrue();
        assertThat(config.maxCacheSize()).isEqualTo(1000);
        assertThat(config.cacheTTLMinutes()).isEqualTo(60);
    }

    @Test
    void shouldCreateCustomConfiguration() {
        var config = new WitnessReceiptConfiguration(
            WitnessReceiptConfiguration.StoreType.JDBC,
            CompressionConfig.FAST,
            false,
            5000,
            120
        );

        assertThat(config.storeType()).isEqualTo(WitnessReceiptConfiguration.StoreType.JDBC);
        assertThat(config.compressionConfig()).isEqualTo(CompressionConfig.FAST);
        assertThat(config.cacheEnabled()).isFalse();
        assertThat(config.maxCacheSize()).isEqualTo(5000);
        assertThat(config.cacheTTLMinutes()).isEqualTo(120);
    }

    @Test
    void shouldRejectNullStoreType() {
        assertThatThrownBy(() -> new WitnessReceiptConfiguration(
            null,
            CompressionConfig.DEFAULT,
            true,
            1000,
            60
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("storeType cannot be null");
    }

    @Test
    void shouldRejectNullCompressionConfig() {
        assertThatThrownBy(() -> new WitnessReceiptConfiguration(
            WitnessReceiptConfiguration.StoreType.MEMORY,
            null,
            true,
            1000,
            60
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("compressionConfig cannot be null");
    }

    @Test
    void shouldRejectMaxCacheSizeTooSmall() {
        assertThatThrownBy(() -> new WitnessReceiptConfiguration(
            WitnessReceiptConfiguration.StoreType.MEMORY,
            CompressionConfig.DEFAULT,
            true,
            99,  // Below minimum of 100
            60
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxCacheSize must be >= 100");
    }

    @Test
    void shouldRejectCacheTTLTooSmall() {
        assertThatThrownBy(() -> new WitnessReceiptConfiguration(
            WitnessReceiptConfiguration.StoreType.MEMORY,
            CompressionConfig.DEFAULT,
            true,
            1000,
            0  // Below minimum of 1
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cacheTTLMinutes must be >= 1");
    }

    @Test
    void shouldAllowMinimumValidValues() {
        var config = new WitnessReceiptConfiguration(
            WitnessReceiptConfiguration.StoreType.MEMORY,
            CompressionConfig.NONE,
            false,
            100,  // Minimum
            1     // Minimum
        );

        assertThat(config.maxCacheSize()).isEqualTo(100);
        assertThat(config.cacheTTLMinutes()).isEqualTo(1);
    }

    @Test
    void shouldSupportRecordEquality() {
        var config1 = new WitnessReceiptConfiguration(
            WitnessReceiptConfiguration.StoreType.MEMORY,
            CompressionConfig.FAST,
            true,
            1000,
            60
        );

        var config2 = new WitnessReceiptConfiguration(
            WitnessReceiptConfiguration.StoreType.MEMORY,
            CompressionConfig.FAST,
            true,
            1000,
            60
        );

        assertThat(config1).isEqualTo(config2);
        assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
    }

    @Test
    void shouldSupportRecordToString() {
        var config = WitnessReceiptConfiguration.DEFAULT;

        var str = config.toString();

        assertThat(str).contains("MEMORY");
        assertThat(str).contains("NONE");
        assertThat(str).contains("1000");
        assertThat(str).contains("60");
    }
}
