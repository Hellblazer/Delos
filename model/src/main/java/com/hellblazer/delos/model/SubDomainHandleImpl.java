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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger                        log = LoggerFactory.getLogger(SubDomainHandleImpl.class);

    private final SelfAddressingIdentifier         id;
    private final Digest                           contextId;
    private final Demesne                          demesne;
    private final UnixDomainSocketAddress          portalEndpoint;
    private final EventLoopGroup                   clientEventLoopGroup;
    private final Runnable                         onTerminal;
    private final AtomicBoolean                    terminalFired = new AtomicBoolean(false);
    private final Instant                          spawnTime;
    private final AtomicReference<SubDomainStatus> status;
    private final AtomicLong                       requestCount;
    private final AtomicLong                       errorCount;
    private final long[]                           latencies;
    private int                                    latencyIndex;
    private int                                    latencyCount;
    private final AtomicReference<ResourceLimits>  resourceLimits;
    private final AtomicBoolean                    limitsSet = new AtomicBoolean(false);
    private volatile ManagedChannel                channel;

    /**
     * Create a handle for a spawned subdomain.
     *
     * @param id                   KERI identifier
     * @param contextId            context digest for Portal routing metadata
     * @param demesne              subdomain instance for lifecycle control
     * @param portalEndpoint       Portal inbound endpoint address
     * @param clientEventLoopGroup event loop group for channel I/O
     * @param onTerminal           callback invoked exactly once on terminal state (STOPPED/FAILED)
     */
    SubDomainHandleImpl(SelfAddressingIdentifier id, Digest contextId, Demesne demesne,
                        UnixDomainSocketAddress portalEndpoint, EventLoopGroup clientEventLoopGroup,
                        Runnable onTerminal) {
        this.id = id;
        this.contextId = contextId;
        this.demesne = demesne;
        this.portalEndpoint = portalEndpoint;
        this.clientEventLoopGroup = clientEventLoopGroup;
        this.onTerminal = onTerminal;
        this.spawnTime = Instant.now();
        this.status = new AtomicReference<>(SubDomainStatus.STARTING);
        this.requestCount = new AtomicLong(0);
        this.errorCount = new AtomicLong(0);
        this.latencies = new long[1024];
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
            recordLatency(Duration.ofNanos(latencyNanos).toMillis());
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
                fireTerminal();
            } catch (Exception e) {
                status.set(SubDomainStatus.FAILED);
                fireTerminal();
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
            // If timeout expires, force stop via this.stop() which handles
            // compareAndSet guard, FAILED transitions, and onTerminal cleanup
            stop();
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
        if (!limitsSet.compareAndSet(false, true)) {
            log.warn("setResourceLimits() already called for {}; ignoring (set-once semantics)", id);
            return;
        }
        this.resourceLimits.set(limits);
        // True isolate enforcement requires future JNI API extension (deferred).
        // JniBridge has no native limit API; limits stored for future use.
        log.info("Resource limits stored for {} (enforcement deferred — no native isolate limit API)", id);
    }

    /**
     * Fire terminal callback exactly once. Idempotent — safe to call multiple times.
     */
    private void fireTerminal() {
        if (terminalFired.compareAndSet(false, true)) {
            try {
                onTerminal.run();
            } catch (Exception e) {
                log.warn("onTerminal callback failed for {}", id, e);
            }
        }
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

    private synchronized void recordLatency(long millis) {
        latencies[latencyIndex] = millis;
        latencyIndex = (latencyIndex + 1) % latencies.length;
        if (latencyCount < latencies.length) {
            latencyCount++;
        }
    }

    private synchronized Duration calculateAverageLatency() {
        if (latencyCount == 0) {
            return Duration.ZERO;
        }
        long sum = 0;
        for (int i = 0; i < latencyCount; i++) {
            sum += latencies[i];
        }
        return Duration.ofMillis(sum / latencyCount);
    }

    private synchronized Duration calculatePercentile(double percentile) {
        if (latencyCount == 0) {
            return Duration.ZERO;
        }
        long[] sorted = new long[latencyCount];
        System.arraycopy(latencies, 0, sorted, 0, latencyCount);
        java.util.Arrays.sort(sorted);
        int index = (int) Math.ceil(percentile * latencyCount) - 1;
        return Duration.ofMillis(sorted[Math.max(0, index)]);
    }
}
