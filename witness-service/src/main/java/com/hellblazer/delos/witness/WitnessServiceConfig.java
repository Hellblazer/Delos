/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.DigestAlgorithm;

import java.nio.file.Path;
import java.time.Duration;

/**
 * WitnessServiceConfig: Configuration for witness service.
 *
 * Immutable record containing all configurable parameters.
 * Can be built from environment variables, config files, or programmatically.
 *
 * **Parameters**:
 * - port: gRPC service port (0 for automatic)
 * - committeeSize: Number of witness committee members (k)
 * - threshold: Signature threshold for receipts (M)
 * - collectionTimeout: Timeout for receipt collection
 * - drainPeriod: Grace period for in-flight during view changes
 * - maxConcurrentCollections: Max concurrent receipt collections
 * - maxSubscriptions: Max concurrent gRPC subscriptions
 * - mtlsEnabled: Enable mutual TLS for gRPC
 * - certPath: Path to server certificate (if MTLS enabled)
 * - keyPath: Path to server key (if MTLS enabled)
 * - digestAlgorithm: Hash algorithm for digests
 */
public record WitnessServiceConfig(
    int port,
    int committeeSize,
    int threshold,
    Duration collectionTimeout,
    Duration drainPeriod,
    int maxConcurrentCollections,
    int maxSubscriptions,
    boolean mtlsEnabled,
    Path certPath,
    Path keyPath,
    DigestAlgorithm digestAlgorithm
) {
    /**
     * Get default configuration for development/testing.
     * Uses dynamic port allocation and reasonable defaults.
     *
     * @return Default configuration
     */
    public static WitnessServiceConfig defaults() {
        return new WitnessServiceConfig(
            0,                              // Dynamic port
            7,                              // Committee size (k=7)
            5,                              // Threshold (5 of 7)
            Duration.ofSeconds(5),          // 5s collection timeout
            Duration.ofMillis(500),         // 500ms drain period
            1000,                           // 1000 concurrent collections
            100,                            // 100 concurrent subscriptions
            false,                          // MTLS disabled for development
            null,                           // No cert path
            null,                           // No key path
            DigestAlgorithm.DEFAULT         // Default SHA3-256
        );
    }

    /**
     * Get production configuration with MTLS enabled.
     *
     * @param port gRPC service port
     * @param certPath Server certificate path
     * @param keyPath Server key path
     * @return Production configuration
     */
    public static WitnessServiceConfig production(int port, Path certPath, Path keyPath) {
        return new WitnessServiceConfig(
            port,
            7,
            5,
            Duration.ofSeconds(5),
            Duration.ofMillis(500),
            1000,
            100,
            true,                           // MTLS enabled
            certPath,
            keyPath,
            DigestAlgorithm.DEFAULT
        );
    }

    /**
     * Build custom configuration.
     *
     * @return Builder for fluent configuration
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for fluent configuration.
     */
    public static class Builder {
        private int port = 0;
        private int committeeSize = 7;
        private int threshold = 5;
        private Duration collectionTimeout = Duration.ofSeconds(5);
        private Duration drainPeriod = Duration.ofMillis(500);
        private int maxConcurrentCollections = 1000;
        private int maxSubscriptions = 100;
        private boolean mtlsEnabled = false;
        private Path certPath;
        private Path keyPath;
        private DigestAlgorithm digestAlgorithm = DigestAlgorithm.DEFAULT;

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder committeeSize(int committeeSize) {
            this.committeeSize = committeeSize;
            return this;
        }

        public Builder threshold(int threshold) {
            this.threshold = threshold;
            return this;
        }

        public Builder collectionTimeout(Duration timeout) {
            this.collectionTimeout = timeout;
            return this;
        }

        public Builder drainPeriod(Duration period) {
            this.drainPeriod = period;
            return this;
        }

        public Builder maxConcurrentCollections(int max) {
            this.maxConcurrentCollections = max;
            return this;
        }

        public Builder maxSubscriptions(int max) {
            this.maxSubscriptions = max;
            return this;
        }

        public Builder mtlsEnabled(boolean enabled) {
            this.mtlsEnabled = enabled;
            return this;
        }

        public Builder certPath(Path path) {
            this.certPath = path;
            return this;
        }

        public Builder keyPath(Path path) {
            this.keyPath = path;
            return this;
        }

        public Builder digestAlgorithm(DigestAlgorithm algorithm) {
            this.digestAlgorithm = algorithm;
            return this;
        }

        public WitnessServiceConfig build() {
            return new WitnessServiceConfig(
                port,
                committeeSize,
                threshold,
                collectionTimeout,
                drainPeriod,
                maxConcurrentCollections,
                maxSubscriptions,
                mtlsEnabled,
                certPath,
                keyPath,
                digestAlgorithm
            );
        }
    }
}
