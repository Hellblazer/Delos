/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.archipelago;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Privides a safe mechanism for caching expensive connections to a server. We use MTLS, so we want to make good use of
 * the ManagedChannels. Fireflies, by its nature, will keep some subset of connections open for gossip use, based on a
 * ring. Avalanche samples a random subset of known servers. Ghost has access patterns based on hahes. And so on.
 * <p>
 * This cache allows grpc clients to reuse the underlying ManagedChannel as "Bob" inteneded, enforcing some upper limit
 * on the connections used.
 * <p>
 * ManagedChannels are never closed while they are open and used by a client stub. Connections can be opened up to some
 * total limit, which does not have to be the target number of open + idle connections. ManagedChannels in the cache
 * keep track of their overall usage count by client stubs - each borrow increments this usage count.
 * <p>
 * When ManagedChannels are closed, they are closed in the order of least usage count. ManagedChannels may also have a
 * minimum idle duration, to prevent cache thrashing. When this duration is > 0, the connection will not be closed,
 * potentially overshooting target cache counts
 *
 * @author hal.hildebrand
 */
public class ServerConnectionCache {

    private final static Logger log = LoggerFactory.getLogger(ServerConnectionCache.class);

    private final Map<Member, ReleasableManagedChannel>            cache      = new HashMap<>();
    private final Clock                                            clock;
    private final ServerConnectionFactory                          factory;
    private final ReentrantLock                                    lock       = new ReentrantLock(true);
    private final ServerConnectionCacheMetrics                     metrics;
    private final Duration                                         minIdle;
    private final PriorityQueue<ReleasableManagedChannel>          queue      = new PriorityQueue<>();
    private final int                                              target;
    private final Digest                                           member;
    private final CallCredentials                                  credentials;
    private final AtomicBoolean                                    open       = new AtomicBoolean(true);
    private final ConnectionCircuitBreaker                         circuitBreaker;
    private final ConcurrentHashMap<Member, CompletableFuture<ManagedChannel>> connecting = new ConcurrentHashMap<>();
    private final ExecutorService                                  connectionExecutor;
    private final Duration                                         connectionTimeout;

    public ServerConnectionCache(Digest member, CallCredentials credentials, ServerConnectionFactory factory,
                                 int target, Duration minIdle, Clock clock, ServerConnectionCacheMetrics metrics,
                                 ConnectionCircuitBreaker circuitBreaker, Duration connectionTimeout) {
        assert member != null;
        this.factory = factory;
        this.target = Math.max(target, 1);
        this.minIdle = minIdle;
        this.clock = clock;
        this.metrics = metrics;
        this.member = member;
        this.credentials = credentials;
        this.circuitBreaker = circuitBreaker;
        this.connectionTimeout = connectionTimeout;
        this.connectionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public ManagedServerChannel borrow(Digest context, Member to) {
        if (!open.get()) {
            throw new IllegalStateException("not open on: " + member);
        }

        // ================================================================
        // PHASE 1: Circuit breaker fast-fail (NO LOCK)
        // ================================================================
        // Circuit breaker is lock-free (ConcurrentHashMap + AtomicReference CAS).
        // This rejects known-bad members before touching any shared state.
        if (circuitBreaker.shouldReject(to)) {
            log.debug("Circuit breaker OPEN for: {} on: {}", to.getId(), member);
            if (metrics != null) {
                metrics.recordFailedConnection();
            }
            return null;
        }

        // ================================================================
        // PHASE 2: Cache check + future creation (BRIEF LOCK)
        // ================================================================
        // Lock scope: HashMap.get() + ConcurrentHashMap.computeIfAbsent()
        // Duration: ~1 microsecond (no I/O, no blocking)
        CompletableFuture<ManagedChannel> future;
        lock.lock();
        try {
            // Fast path: cache hit
            var cached = cache.get(to);
            if (cached != null) {
                if (cached.incrementBorrow()) {
                    log.debug("Increment borrow to: {} channel to: {} on: {}", cached.borrowed,
                              cached.member.getId(), member);
                    if (metrics != null) {
                        metrics.recordBorrow();
                    }
                    queue.remove(cached);
                }
                log.trace("Borrowed cached channel to: {}, borrowed: {} on: {}", cached.member.getId(),
                          cached.borrowed, member);
                return new ManagedServerChannel(context, cached, credentials);
            }

            // Cache miss: target size warning
            if (cache.size() >= target) {
                log.debug("Cache target open connections exceeded: {}, opening to: {} on: {}", target, to.getId(),
                          member);
            }

            // Get or create a future for this member's connection attempt.
            // The lambda is non-blocking: it only schedules work on the virtual
            // thread executor. The actual factory.connectTo() runs asynchronously
            // AFTER the lock is released.
            future = connecting.computeIfAbsent(to,
                                                target_ -> CompletableFuture.supplyAsync(() -> {
                                                    log.debug("Establishing connection to: {} on: {}", to.getId(),
                                                              member);
                                                    try {
                                                        return factory.connectTo(to);
                                                    } catch (Throwable t) {
                                                        log.error("Cannot connect to: {} on: {}", to.getId(), member,
                                                                  t);
                                                        return null;
                                                    }
                                                }, connectionExecutor));
        } finally {
            lock.unlock();
        }

        // ================================================================
        // PHASE 3: Await connection (NO LOCK)
        // ================================================================
        // The calling thread (typically a virtual thread) parks here while the
        // connection is being established. Virtual threads park efficiently
        // without pinning OS threads. Meanwhile, other threads can freely
        // borrow/release/close the cache.
        //
        // Multiple threads waiting on the same member share the SAME future,
        // so only ONE factory.connectTo() call is made per member.
        ManagedChannel channel;
        try {
            channel = future.orTimeout(connectionTimeout.toMillis(), TimeUnit.MILLISECONDS)
                            .exceptionally(t -> {
                                if (t instanceof java.util.concurrent.TimeoutException) {
                                    log.error("Connection timeout ({}) for: {} on: {}", connectionTimeout, to.getId(),
                                              member);
                                } else {
                                    log.error("Connection failed for: {} on: {}", to.getId(), member, t);
                                }
                                return null;
                            })
                            .join();
        } catch (java.util.concurrent.CompletionException e) {
            log.error("Unexpected error awaiting connection to: {} on: {}", to.getId(), member, e);
            channel = null;
        }

        // ================================================================
        // PHASE 3a: Failure handling (NO LOCK for connecting cleanup)
        // ================================================================
        if (channel == null) {
            // CAS remove: only remove if our future is still the current entry.
            // A newer future may have been created by a concurrent thread.
            connecting.remove(to, future);
            circuitBreaker.recordFailure(to);
            if (metrics != null) {
                metrics.recordFailedConnection();
                metrics.incrementFailedOpenConnection();
            }
            return null;
        }

        // ================================================================
        // PHASE 4: Register in cache (BRIEF LOCK)
        // ================================================================
        // Lock scope: HashMap.get() + HashMap.put() + ConcurrentHashMap.remove()
        // Duration: ~300 nanoseconds (no I/O)
        //
        // Double-check prevents duplicate registrations when multiple threads
        // share the same future and wake up in sequence.
        lock.lock();
        try {
            // Double-check: while we waited, another waiter may have already
            // registered this connection.
            var existing = cache.get(to);
            if (existing != null) {
                // Another thread won the race to register.
                // Since both threads shared the same future, they got the same
                // ManagedChannel object. No duplicate to shut down.
                // (If channels differ due to a rare race with eviction+reconnect,
                // shut down the newer one to avoid leaks.)
                if (existing.channel != channel) {
                    channel.shutdown();
                }
                if (existing.incrementBorrow()) {
                    if (metrics != null) {
                        metrics.recordBorrow();
                    }
                    queue.remove(existing);
                }
                connecting.remove(to);
                return new ManagedServerChannel(context, existing, credentials);
            }

            // We are the first thread to register this connection.
            var conn = new ReleasableManagedChannel(to, channel, member);
            cache.put(to, conn);
            conn.incrementBorrow();
            connecting.remove(to); // Cleanup pending entry

            if (metrics != null) {
                metrics.incrementCreateConnection();
                metrics.incrementOpenConnections();
                metrics.recordBorrow();
            }
            circuitBreaker.recordSuccess(to);

            log.debug("New connection cached for: {} on: {}", to.getId(), member);
            return new ManagedServerChannel(context, conn, credentials);
        } finally {
            lock.unlock();
        }
    }

    public <T> T borrow(Digest context, Member to, CreateClientCommunications<T> createFunction) {
        if (!open.get()) {
            throw new IllegalStateException("not open on: " + member);
        }
        return createFunction.create(borrow(context, to));
    }

    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }

        // Cancel all in-progress connections.
        // Future.cancel(true) may interrupt the virtual thread running connectTo().
        connecting.forEach((member_, future) -> future.cancel(true));
        connecting.clear();

        // Shut down the virtual thread executor with timeout to prevent indefinite hang.
        // shutdownNow() interrupts running tasks; awaitTermination() waits up to 5s.
        connectionExecutor.shutdownNow();
        try {
            if (!connectionExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("Connection executor did not terminate within 5 seconds on: {}", member);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for connection executor shutdown on: {}", member);
        }

        lock(() -> {
            log.info("Closing connection cache on: {}", member);
            for (ReleasableManagedChannel conn : new ArrayList<>(cache.values())) {
                try {
                    conn.channel.shutdown();
                    if (metrics != null) {
                        metrics.recordChannelOpenDuration(
                        Duration.between(conn.created, Instant.now(clock)).toNanos());
                        metrics.decrementOpenConnections();
                    }
                } catch (Throwable e) {
                    log.debug("Error closing connection to: {} on: {}", conn.member.getId(), member);
                }
            }
            cache.clear();
            queue.clear();
            return null;
        });
    }

    public void release(ReleasableManagedChannel connection) {
        if (!open.get()) {
            return;
        }
        lock(() -> {
            if (connection.decrementBorrow()) {
                log.debug("Releasing connection to: {} on: {}", connection.member.getId(), member);
                queue.add(connection);
                if (metrics != null) {
                    metrics.recordRelease();
                }
                manageConnections();
            }
            return null;
        });
    }

    private boolean close(ReleasableManagedChannel connection) {
        if (connection.isCloseable()) {
            try {
                connection.channel.shutdown();
            } catch (Throwable t) {
                log.debug("Error closing connection to: {} on: {}", connection.member.getId(), connection.member);
            }
            log.debug("connection to: {} is closed on: {}", connection.member.getId(), member);
            cache.remove(connection.member);
            if (metrics != null) {
                metrics.decrementOpenConnections();
                metrics.recordCloseConnection();
                metrics.recordChannelOpenDuration(Duration.between(connection.created, Instant.now(clock)).toNanos());
            }
            return true;
        }
        return false;
    }

    private <T> T lock(Supplier<T> supplier) {
        lock.lock();
        try {
            return supplier.get();
        } finally {
            lock.unlock();
        }
    }

    private void manageConnections() {
        log.debug("Managing connections: {} idle: {} on: {}", cache.size(), queue.size(), member);
        Iterator<ReleasableManagedChannel> connections = queue.iterator();
        while (connections.hasNext() && cache.size() > target) {
            if (close(connections.next())) {
                connections.remove();
            }
        }
    }

    @FunctionalInterface
    public interface CreateClientCommunications<Client> {
        Client create(ManagedServerChannel channel);
    }

    public interface ServerConnectionCacheMetrics {

        void recordBorrow();

        void recordChannelOpenDuration(long nanos);

        void recordCloseConnection();

        void incrementCreateConnection();

        void recordFailedConnection();

        void incrementFailedOpenConnection();

        void incrementOpenConnections();

        void decrementOpenConnections();

        void recordRelease();

    }

    public interface ServerConnectionFactory {
        ManagedChannel connectTo(Member to);
    }

    public static class Builder implements Cloneable {
        private Clock                        clock                = Clock.systemUTC();
        private ServerConnectionFactory      factory              = null;
        private ServerConnectionCacheMetrics metrics;
        private Duration                     minIdle              = Duration.ofMillis(100);
        private int                          target               = 10;
        private Digest                       member;
        private CallCredentials              credentials;
        private CircuitBreakerConfig         circuitBreakerConfig = CircuitBreakerConfig.defaults();
        private Duration                     connectionTimeout    = Duration.ofSeconds(30);

        public ServerConnectionCache build() {
            var circuitBreaker = new ConnectionCircuitBreaker(circuitBreakerConfig, clock);
            return new ServerConnectionCache(member, credentials, factory, target, minIdle, clock, metrics,
                                              circuitBreaker, connectionTimeout);
        }

        @Override
        public Builder clone() {
            try {
                return (Builder) super.clone();
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException(e);
            }
        }

        public Clock getClock() {
            return clock;
        }

        public Builder setClock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public CallCredentials getCredentials() {
            return credentials;
        }

        public Builder setCredentials(CallCredentials credentials) {
            this.credentials = credentials;
            return this;
        }

        public ServerConnectionFactory getFactory() {
            return factory;
        }

        public Builder setFactory(ServerConnectionFactory factory) {
            this.factory = factory;
            return this;
        }

        public Digest getMember() {
            return member;
        }

        public Builder setMember(Digest member) {
            this.member = member;
            return this;
        }

        public ServerConnectionCacheMetrics getMetrics() {
            return metrics;
        }

        public Builder setMetrics(ServerConnectionCacheMetrics metrics) {
            this.metrics = metrics;
            return this;
        }

        public Duration getMinIdle() {
            return minIdle;
        }

        public Builder setMinIdle(Duration minIdle) {
            this.minIdle = minIdle;
            return this;
        }

        public int getTarget() {
            return target;
        }

        public Builder setTarget(int target) {
            this.target = target;
            return this;
        }

        public CircuitBreakerConfig getCircuitBreakerConfig() {
            return circuitBreakerConfig;
        }

        public Builder setCircuitBreakerConfig(CircuitBreakerConfig circuitBreakerConfig) {
            this.circuitBreakerConfig = circuitBreakerConfig;
            return this;
        }

        public Duration getConnectionTimeout() {
            return connectionTimeout;
        }

        public Builder setConnectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
            return this;
        }
    }

    class ReleasableManagedChannel implements Comparable<ReleasableManagedChannel>, Releasable {
        private final    AtomicInteger  borrowed = new AtomicInteger();
        private final    ManagedChannel channel;
        private final    Instant        created;
        private final    Member         member;
        private final    Digest         from;
        private volatile Instant        lastUsed;

        public ReleasableManagedChannel(Member member, ManagedChannel channel, Digest from) {
            this.member = member;
            this.channel = channel;
            created = Instant.now(clock);
            lastUsed = Instant.now(clock);
            this.from = from;
        }

        @Override
        public int compareTo(ReleasableManagedChannel o) {
            return Integer.compare(borrowed.get(), o.borrowed.get());
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if ((obj == null) || (getClass() != obj.getClass()))
                return false;
            return member.equals(((ReleasableManagedChannel) obj).member);
        }

        @Override
        public ManagedChannel getChannel() {
            return channel;
        }

        @Override
        public Digest getFrom() {
            return from;
        }

        @Override
        public Member getMember() {
            return member;
        }

        @Override
        public int hashCode() {
            return member.hashCode();
        }

        public boolean isCloseable() {
            return lastUsed.plus(minIdle).isBefore(Instant.now(clock));
        }

        @Override
        public void release() {
            log.trace("Release connection to: {} on: {}", getMember().getId(), getFrom());
            ServerConnectionCache.this.release(this);
        }

        @Override
        public ManagedChannel shutdown() {
            log.warn("shutdown() called on pooled channel - delegating to release()");
            release();
            return channel;
        }

        @Override
        public ManagedChannel shutdownNow() {
            log.warn("shutdownNow() called on pooled channel - delegating to release()");
            release();
            return channel;
        }

        private boolean decrementBorrow() {
            if (borrowed.decrementAndGet() == 0) {
                lastUsed = Instant.now(clock);
                return true;
            }
            return false;
        }

        private boolean incrementBorrow() {
            return borrowed.incrementAndGet() == 1;
        }
    }
}
