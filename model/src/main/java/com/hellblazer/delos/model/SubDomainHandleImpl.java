/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.model.demesnes.Demesne;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioDomainSocketChannel;

import java.net.UnixDomainSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.hellblazer.delos.archipelago.RouterImpl.clientInterceptor;

/**
 * Implementation of SubDomainHandle for managing subdomain lifecycle.
 * <p>
 * Thread-safe implementation tracking status, metrics, and providing communication via Portal.
 * Package-private: only ProcessContainerDomain creates instances.
 *
 * @author hal.hildebrand
 */
class SubDomainHandleImpl implements SubDomainHandle {
    private final SelfAddressingIdentifier         id;
    private final Digest                           contextId;
    private final Demesne                          demesne;
    private final UnixDomainSocketAddress          portalEndpoint;
    private final EventLoopGroup                   clientEventLoopGroup;
    private final Instant                          spawnTime;
    private final AtomicReference<SubDomainStatus> status;
    private final AtomicLong                       requestCount;
    private final AtomicLong                       errorCount;
    private final List<Long>                       latencies; // Thread-safe synchronized list
    private final AtomicReference<ResourceLimits>  resourceLimits;
    private volatile ManagedChannel                channel;

    /**
     * Create a handle for a spawned subdomain.
     *
     * @param id                   KERI identifier
     * @param contextId            context digest for Portal routing metadata
     * @param demesne              subdomain instance for lifecycle control
     * @param portalEndpoint       Portal inbound endpoint address
     * @param clientEventLoopGroup event loop group for channel I/O
     */
    SubDomainHandleImpl(SelfAddressingIdentifier id, Digest contextId, Demesne demesne,
                        UnixDomainSocketAddress portalEndpoint, EventLoopGroup clientEventLoopGroup) {
        this.id = id;
        this.contextId = contextId;
        this.demesne = demesne;
        this.portalEndpoint = portalEndpoint;
        this.clientEventLoopGroup = clientEventLoopGroup;
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

        // send() is deprecated — use getChannel() to create typed stubs (RDR-002)
        CompletableFuture<R> future = CompletableFuture.failedFuture(
        new UnsupportedOperationException("send() is deprecated — use getChannel() to create typed stubs. See RDR-002"));

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
    public ManagedChannel getChannel() {
        if (status.get() == SubDomainStatus.STARTING) {
            throw new IllegalStateException("Subdomain not yet running: " + id);
        }
        if (channel == null) {
            synchronized (this) {
                if (channel == null) {
                    channel = NettyChannelBuilder.forAddress(portalEndpoint)
                                                 .withOption(ChannelOption.TCP_NODELAY, true)
                                                 .executor(Executors.newVirtualThreadPerTaskExecutor())
                                                 .eventLoopGroup(clientEventLoopGroup)
                                                 .channelType(NioDomainSocketChannel.class)
                                                 .intercept(clientInterceptor(contextId))
                                                 .usePlaintext()
                                                 .build();
                }
            }
        }
        return channel;
    }

    @Override
    public void stop() {
        if (status.compareAndSet(SubDomainStatus.RUNNING, SubDomainStatus.STOPPING) || status.compareAndSet(
        SubDomainStatus.STARTING, SubDomainStatus.STOPPING)) {
            try {
                // Shut down cached channel before stopping demesne
                var ch = channel;
                if (ch != null) {
                    ch.shutdown();
                    try {
                        ch.awaitTermination(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        ch.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                }
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
