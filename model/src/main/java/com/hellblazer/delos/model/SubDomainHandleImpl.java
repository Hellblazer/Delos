/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model;

import com.hellblazer.delos.model.demesnes.Demesne;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation of SubDomainHandle for managing subdomain lifecycle.
 * <p>
 * Thread-safe implementation tracking status, metrics, and providing communication via Portal.
 * Package-private: only ProcessContainerDomain creates instances.
 *
 * @author hal.hildebrand
 */
class SubDomainHandleImpl implements SubDomainHandle {
    private final SelfAddressingIdentifier      id;
    private final Demesne                       demesne;
    private final Instant                       spawnTime;
    private final AtomicReference<SubDomainStatus> status;
    private final AtomicLong                    requestCount;
    private final AtomicLong                    errorCount;
    private final List<Long>                    latencies; // Thread-safe synchronized list
    private final AtomicReference<ResourceLimits> resourceLimits;

    /**
     * Create a handle for a spawned subdomain.
     *
     * @param id      KERI identifier
     * @param demesne subdomain instance for lifecycle control
     */
    SubDomainHandleImpl(SelfAddressingIdentifier id, Demesne demesne) {
        this.id = id;
        this.demesne = demesne;
        this.spawnTime = Instant.now();
        this.status = new AtomicReference<>(SubDomainStatus.STARTING);
        this.requestCount = new AtomicLong(0);
        this.errorCount = new AtomicLong(0);
        this.latencies = Collections.synchronizedList(new ArrayList<>());
        this.resourceLimits = new AtomicReference<>(ResourceLimits.newBuilder().build());
    }

    @Override
    public SelfAddressingIdentifier getId() {
        return id;
    }

    @Override
    public <T, R> CompletableFuture<R> send(T request) {
        return send(request, Duration.ofSeconds(30)); // Default 30s timeout
    }

    @Override
    public <T, R> CompletableFuture<R> send(T request, Duration timeout) {
        if (status.get() != SubDomainStatus.RUNNING) {
            return CompletableFuture.failedFuture(
            new IllegalStateException("Subdomain not running: " + status.get()));
        }

        requestCount.incrementAndGet();
        long startTime = System.nanoTime();

        // TODO: Implement actual Portal routing when Portal API is available
        // For now, return a failed future indicating not implemented
        CompletableFuture<R> future = CompletableFuture.failedFuture(
        new UnsupportedOperationException("Portal routing not yet implemented - requires Portal.link() API"));

        // Track latency and errors
        future.whenComplete((response, error) -> {
            long latencyNanos = System.nanoTime() - startTime;
            latencies.add(Duration.ofNanos(latencyNanos).toMillis());
            if (error != null) {
                errorCount.incrementAndGet();
            }
        });

        return future.orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (status.compareAndSet(SubDomainStatus.RUNNING, SubDomainStatus.STOPPING) || status.compareAndSet(
        SubDomainStatus.STARTING, SubDomainStatus.STOPPING)) {
            try {
                demesne.stop();
                status.set(SubDomainStatus.STOPPED);
            } catch (Exception e) {
                status.set(SubDomainStatus.FAILED);
                throw new RuntimeException("Failed to stop subdomain: " + id, e);
            }
        }
    }

    @Override
    public CompletableFuture<Void> stopGracefully(Duration timeout) {
        return CompletableFuture.runAsync(() -> {
            // TODO: Implement graceful shutdown with timeout
            // For now, just call stop() immediately
            stop();
        }).orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS).exceptionally(throwable -> {
            // If timeout expires, force stop
            status.set(SubDomainStatus.STOPPING);
            demesne.stop();
            status.set(SubDomainStatus.STOPPED);
            return null;
        });
    }

    @Override
    public boolean isActive() {
        SubDomainStatus current = status.get();
        return current == SubDomainStatus.RUNNING || current == SubDomainStatus.STARTING;
    }

    @Override
    public SubDomainStatus getStatus() {
        return status.get();
    }

    @Override
    public SubDomainMetrics getMetrics() {
        long requests = requestCount.get();
        long errors = errorCount.get();

        Duration avg = calculateAverageLatency();
        Duration p95 = calculatePercentile(0.95);
        Duration p99 = calculatePercentile(0.99);

        return new SubDomainMetrics(spawnTime, requests, errors, avg, p95, p99);
    }

    @Override
    public void setResourceLimits(ResourceLimits limits) {
        this.resourceLimits.set(limits);
        // TODO: Enforce limits via isolate configuration when available
    }

    /**
     * Mark subdomain as running (called by ProcessContainerDomain after routes registered).
     */
    void markRunning() {
        status.compareAndSet(SubDomainStatus.STARTING, SubDomainStatus.RUNNING);
    }

    /**
     * Get current resource limits.
     *
     * @return configured limits
     */
    ResourceLimits getResourceLimits() {
        return resourceLimits.get();
    }

    private Duration calculateAverageLatency() {
        synchronized (latencies) {
            if (latencies.isEmpty()) {
                return Duration.ZERO;
            }
            long sum = latencies.stream().mapToLong(Long::longValue).sum();
            return Duration.ofMillis(sum / latencies.size());
        }
    }

    private Duration calculatePercentile(double percentile) {
        synchronized (latencies) {
            if (latencies.isEmpty()) {
                return Duration.ZERO;
            }
            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);
            int index = (int) Math.ceil(percentile * sorted.size()) - 1;
            return Duration.ofMillis(sorted.get(Math.max(0, index)));
        }
    }
}
