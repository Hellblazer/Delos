/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model;

/**
 * Resource limits for a subdomain.
 * <p>
 * Configures maximum memory, thread count, and file descriptor usage.
 * Enforcement depends on isolate implementation:
 * - GraalVM isolates: heap limit enforced, native memory limited by OS
 * - In-process (DemesneImpl): limits are advisory, enforcement via monitoring
 * <p>
 * Immutable configuration object.
 *
 * @author hal.hildebrand
 */
public class ResourceLimits {
    private final long maxHeapMemoryBytes;
    private final long maxNativeMemoryBytes;
    private final int  maxThreads;
    private final int  maxFileDescriptors;

    private ResourceLimits(Builder builder) {
        this.maxHeapMemoryBytes = builder.maxHeapMemoryBytes;
        this.maxNativeMemoryBytes = builder.maxNativeMemoryBytes;
        this.maxThreads = builder.maxThreads;
        this.maxFileDescriptors = builder.maxFileDescriptors;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Maximum heap memory in bytes.
     *
     * @return heap limit
     */
    public long getMaxHeapMemoryBytes() {
        return maxHeapMemoryBytes;
    }

    /**
     * Maximum native memory in bytes.
     *
     * @return native memory limit
     */
    public long getMaxNativeMemoryBytes() {
        return maxNativeMemoryBytes;
    }

    /**
     * Maximum number of threads.
     *
     * @return thread count limit
     */
    public int getMaxThreads() {
        return maxThreads;
    }

    /**
     * Maximum number of file descriptors.
     *
     * @return file descriptor limit
     */
    public int getMaxFileDescriptors() {
        return maxFileDescriptors;
    }

    @Override
    public String toString() {
        return String.format("ResourceLimits{heap=%dMB, native=%dMB, threads=%d, fds=%d}",
                             maxHeapMemoryBytes / (1024 * 1024), maxNativeMemoryBytes / (1024 * 1024), maxThreads,
                             maxFileDescriptors);
    }

    public static class Builder {
        private long maxHeapMemoryBytes    = 50 * 1024 * 1024;  // 50MB default
        private long maxNativeMemoryBytes  = 100 * 1024 * 1024; // 100MB default
        private int  maxThreads            = 20;                // 20 threads default
        private int  maxFileDescriptors    = 10;                // 10 FDs default

        /**
         * Set maximum heap memory in bytes.
         *
         * @param bytes heap limit
         * @return this builder
         */
        public Builder setMaxHeapMemoryBytes(long bytes) {
            this.maxHeapMemoryBytes = bytes;
            return this;
        }

        /**
         * Set maximum heap memory in megabytes.
         *
         * @param megabytes heap limit in MB
         * @return this builder
         */
        public Builder setMaxHeapMemoryMB(long megabytes) {
            this.maxHeapMemoryBytes = megabytes * 1024 * 1024;
            return this;
        }

        /**
         * Set maximum native memory in bytes.
         *
         * @param bytes native memory limit
         * @return this builder
         */
        public Builder setMaxNativeMemoryBytes(long bytes) {
            this.maxNativeMemoryBytes = bytes;
            return this;
        }

        /**
         * Set maximum native memory in megabytes.
         *
         * @param megabytes native memory limit in MB
         * @return this builder
         */
        public Builder setMaxNativeMemoryMB(long megabytes) {
            this.maxNativeMemoryBytes = megabytes * 1024 * 1024;
            return this;
        }

        /**
         * Set maximum number of threads.
         *
         * @param threads thread count limit
         * @return this builder
         */
        public Builder setMaxThreads(int threads) {
            this.maxThreads = threads;
            return this;
        }

        /**
         * Set maximum number of file descriptors.
         *
         * @param fds file descriptor limit
         * @return this builder
         */
        public Builder setMaxFileDescriptors(int fds) {
            this.maxFileDescriptors = fds;
            return this;
        }

        public ResourceLimits build() {
            return new ResourceLimits(this);
        }
    }
}
