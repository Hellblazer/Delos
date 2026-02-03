/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.messaging.beg;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.archipelago.server.FernetServerInterceptor;
import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.proto.Biff;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.messaging.beg.comms.BegServer;
import com.hellblazer.delos.membership.messaging.beg.comms.ReliableBroadcast;
import com.hellblazer.delos.messaging.proto.*;
import com.hellblazer.delos.utils.Utils;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.OptionalInt;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.hellblazer.delos.membership.messaging.beg.comms.BegClient.getCreate;

/**
 * Bounded epidemic gossip for disseminating messages across a ring topology.
 * <p>
 * This implementation provides best-effort message dissemination with Byzantine fault tolerance
 * through several defensive mechanisms:
 * <ul>
 *   <li><b>Bounded buffer</b> - Fixed capacity with high-water mark GC prevents memory exhaustion</li>
 *   <li><b>Bounded message age</b> - Messages expire after maxAge rounds, preventing stale accumulation</li>
 *   <li><b>Bounded message size</b> - Oversized messages rejected to prevent DoS</li>
 *   <li><b>Per-source rate limiting</b> - Limits messages per source per round to prevent flooding</li>
 *   <li><b>Signature verification</b> - All messages cryptographically verified</li>
 *   <li><b>Predecessor validation</b> - Ring topology enforced on gossip operations</li>
 * </ul>
 * <p>
 * <b>Important:</b> This is NOT reliable broadcast in the formal distributed systems sense
 * (Bracha/Cachin). Messages may be lost due to buffer overflow, age expiry, network partitions,
 * or node failures. Use for disseminating already-certified content where occasional loss is
 * acceptable. For guaranteed delivery, use ChRbcGossip (Bracha-style reliable broadcast).
 *
 * @author hal.hildebrand
 * @see MessageBuffer
 */
public class BoundedEpidemicGossip {
    private static final Logger log = LoggerFactory.getLogger(BoundedEpidemicGossip.class);

    private static final int CIRCUIT_BREAKER_THRESHOLD = 10;

    private final MessageAdapter                                   adapter;
    private final MessageBuffer                                    buffer;
    private final Map<UUID, MessageHandler>                        channelHandlers = new ConcurrentHashMap<>();
    private final CommonCommunications<ReliableBroadcast, Service> comm;
    private final Context<Member>                                  context;
    private final SigningMember                                    member;
    private final BegMetrics                                       metrics;
    private final Parameters                                       params;
    private final Map<UUID, Consumer<Integer>>                     roundListeners  = new ConcurrentHashMap<>();
    private final AtomicBoolean                                    started         = new AtomicBoolean();
    private volatile ScheduledExecutorService                      scheduler;
    // Circuit breaker: track consecutive failures per handler (Delos-4oww)
    private final Map<UUID, AtomicInteger>                         handlerFailures = new ConcurrentHashMap<>();
    private final Set<UUID>                                        disabledHandlers = ConcurrentHashMap.newKeySet();
    // Health tracking
    private volatile int                                           lastSuccessfulRound = -1;
    private final AtomicInteger                                    successfulGossips = new AtomicInteger();
    private final AtomicInteger                                    failedGossips = new AtomicInteger();

    public BoundedEpidemicGossip(Context<Member> context, SigningMember member, Parameters parameters,
                                 Router communications, BegMetrics metrics, MessageAdapter adapter) {
        this.params = parameters;
        this.context = context;
        this.member = member;
        this.metrics = metrics;
        this.adapter = adapter;
        // Use configurable maxAge with fallback to context TTL (Delos-i9nd)
        // Extract buffer to separate class for testability (Delos-ybss)
        buffer = new MessageBuffer(
            params.bufferSize,
            params.falsePositiveRate,
            params.maxMessages,
            params.maxMessageSize,
            params.getMaxAge(context),
            adapter,
            metrics,
            member.getId(),
            context.getId(),
            this::deliver
        );
        this.comm = communications.create(member, context.getId(), new Service(),
                                          r -> new BegServer(communications.getClientIdentityProvider(), metrics, r),
                                          getCreate(metrics), ReliableBroadcast.getLocalLoopback(member));
    }

    public static MessageAdapter defaultMessageAdapter(Context<Member> context, DigestAlgorithm algo) {
        final Predicate<ByteString> verifier = any -> {
            SignedDefaultMessage sdm;
            try {
                sdm = SignedDefaultMessage.parseFrom(any);
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException("Cannot unwrap", e);
            }
            var dm = sdm.getContent();
            var member = context.getMember(Digest.from(dm.getSource()));
            if (member == null) {
                return false;
            }
            return member.verify(JohnHancock.from(sdm.getSignature()), dm.toByteString());
        };
        final Function<ByteString, Digest> hasher = any -> {
            try {
                // Hash the content for deduplication, not the signature (which includes nonce)
                return algo.digest(SignedDefaultMessage.parseFrom(any).getContent().toByteString());
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException("Cannot unwrap", e);
            }
        };
        Function<ByteString, List<Digest>> source = any -> {
            try {
                return Collections.singletonList(
                Digest.from(SignedDefaultMessage.parseFrom(any).getContent().getSource()));
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException("Cannot unwrap", e);
            }
        };
        var sn = new AtomicInteger();
        BiFunction<SigningMember, ByteString, ByteString> wrapper = (m, any) -> {
            final var dm = DefaultMessage.newBuilder()
                                         .setNonce(sn.incrementAndGet())
                                         .setSource(m.getId().toDigeste())
                                         .setContent(any)
                                         .build();
            return SignedDefaultMessage.newBuilder()
                                       .setContent(dm)
                                       .setSignature(m.sign(dm.toByteString()).toSig())
                                       .build()
                                       .toByteString();
        };
        Function<AgedMessageOrBuilder, ByteString> extractor = am -> {
            try {
                return SignedDefaultMessage.parseFrom(am.getContent()).getContent().getContent();
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException("Cannot unwrap", e);
            }
        };
        return new MessageAdapter(verifier, hasher, source, wrapper, extractor);
    }

    public void clearBuffer() {
        log.warn("Clearing message buffer on: {}", member.getId());
        buffer.clear();
    }

    public Context<Member> getContext() {
        return context;
    }

    public Member getMember() {
        return member;
    }

    public int getRound() {
        return buffer.round();
    }

    public void publish(Message message) {
        publish(message, false);
    }

    public void publish(Message message, boolean notifyLocal) {
        if (!started.get()) {
            return;
        }
        AgedMessage m = buffer.send(message.toByteString(), member);
        if (notifyLocal) {
            deliver(Collections.singletonList(
            new Msg(Collections.singletonList(member.getId()), adapter.extractor.apply(m),
                    adapter.hasher.apply(m.getContent()))));
        }
    }

    public UUID register(Consumer<Integer> roundListener) {
        UUID reg = UUID.randomUUID();
        roundListeners.put(reg, roundListener);
        return reg;
    }

    public UUID registerHandler(MessageHandler listener) {
        UUID reg = UUID.randomUUID();
        channelHandlers.put(reg, listener);
        return reg;
    }

    public void removeHandler(UUID registration) {
        channelHandlers.remove(registration);
    }

    public void removeRoundListener(UUID registration) {
        roundListeners.remove(registration);
    }

    /**
     * Reset a disabled handler's circuit breaker, allowing it to receive messages again.
     * @param registration the handler registration UUID
     * @return true if the handler was re-enabled, false if it wasn't disabled
     */
    public boolean resetHandler(UUID registration) {
        boolean wasDisabled = disabledHandlers.remove(registration);
        if (wasDisabled) {
            handlerFailures.remove(registration);
            log.info("Circuit breaker reset for handler on: {}", member.getId());
        }
        return wasDisabled;
    }

    // === Health Check API (Delos-2xfp) ===

    /**
     * Get the current health status of this broadcaster.
     */
    public HealthStatus getHealth() {
        int successful = successfulGossips.get();
        int failed = failedGossips.get();
        int total = successful + failed;
        double successRate = total > 0 ? (double) successful / total : 1.0;

        return new HealthStatus(
            started.get(),
            buffer.size(),
            params.bufferSize,
            successRate,
            buffer.round(),
            lastSuccessfulRound,
            disabledHandlers.size(),
            channelHandlers.size()
        );
    }

    /**
     * Quick health check - returns true if broadcaster is operational.
     */
    public boolean isHealthy() {
        if (!started.get()) {
            return false;
        }
        // Healthy if: running, buffer not overflowing, and recent successful gossip
        int currentRound = buffer.round();
        boolean recentSuccess = lastSuccessfulRound >= currentRound - 3; // Within last 3 rounds
        boolean bufferOk = buffer.size() < params.bufferSize;
        return recentSuccess && bufferOk;
    }

    /**
     * Health status record for monitoring and orchestration.
     */
    public record HealthStatus(
        boolean running,
        int bufferSize,
        int bufferCapacity,
        double gossipSuccessRate,
        int currentRound,
        int lastSuccessfulRound,
        int disabledHandlers,
        int totalHandlers
    ) {
        public double bufferUtilization() {
            return bufferCapacity > 0 ? (double) bufferSize / bufferCapacity : 0.0;
        }

        public boolean isOverloaded() {
            return bufferUtilization() > 0.9;
        }
    }

    public void start(Duration duration) {
        start(duration, null);
    }

    public void start(Duration duration, Predicate<FernetServerInterceptor.HashedToken> validator) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        log.info("Starting BoundedEpidemicGossip[{}] for {}", context.getId(), member.getId());
        comm.register(context.getId(), new Service(), validator);
        // Synchronize scheduler access to prevent race with concurrent stop()
        synchronized (this) {
            // Use platform thread for scheduler - virtual threads are for I/O (Delos-rnsw)
            scheduler = Executors.newScheduledThreadPool(1);
            // Start first round immediately (Delos-4ww4)
            scheduler.execute(Utils.wrapped(() -> oneRound(duration, scheduler), log));
        }
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping BoundedEpidemicGossip[{}] on: {}", context.getId(), member.getId());
        // Synchronize scheduler access to prevent race with concurrent start()
        synchronized (this) {
            ScheduledExecutorService toShutdown = scheduler;
            scheduler = null;
            if (toShutdown != null) {
                toShutdown.shutdown();
                try {
                    if (!toShutdown.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        toShutdown.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    toShutdown.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
        buffer.clear();
        comm.deregister(context.getId());
    }

    private void deliver(List<Msg> newMsgs) {
        if (newMsgs.isEmpty()) {
            return;
        }
        log.trace("delivering: {} on: {}", newMsgs.size(), member.getId());
        channelHandlers.forEach((id, handler) -> {
            // Circuit breaker: skip disabled handlers (Delos-4oww)
            if (disabledHandlers.contains(id)) {
                return;
            }
            try {
                handler.message(context.getId(), newMsgs);
                // Reset failure count on success
                var failures = handlerFailures.get(id);
                if (failures != null) {
                    failures.set(0);
                }
            } catch (Throwable e) {
                log.warn("Error in message handler on: {}", member.getId(), e);
                // Track consecutive failures
                var failures = handlerFailures.computeIfAbsent(id, _ -> new AtomicInteger(0));
                if (failures.incrementAndGet() >= CIRCUIT_BREAKER_THRESHOLD) {
                    disabledHandlers.add(id);
                    log.error("Circuit breaker tripped: disabling handler after {} consecutive failures on: {}",
                              CIRCUIT_BREAKER_THRESHOLD, member.getId());
                }
            }
        });
    }

    private Reconcile gossipRound(ReliableBroadcast link, int ring) {
        if (!started.get()) {
            return null;
        }
        log.trace("rbc gossiping[{}:{}] with: {} ring: {} on: {}", context.getId(), buffer.round(),
                  link.getMember().getId(), ring, member.getId());
        try {
            return link.gossip(
            MessageBff.newBuilder().setRing(ring).setDigests(buffer.forReconcilliation().toBff()).build());
        } catch (StatusRuntimeException sre) {
            log.trace("rbc gossiping[{}:{}] failed: {} with: {} ring: {} on: {}", context.getId(), buffer.round(),
                      sre.getStatus(), link.getMember().getId(), ring, member.getId());
            return null;
        } catch (Throwable e) {
            log.trace("rbc gossiping[{}:{}] failed with: {} ring: {} on: {}", context.getId(), buffer.round(),
                      link.getMember().getId(), ring, member.getId(), e);
            return null;
        }
    }

    private void handle(Reconcile gossip, ReliableBroadcast link, int ring, long startNanos) {
        try {
            buffer.receive(gossip.getUpdatesList());
            var biff = gossip.getDigests();
            if (!Biff.getDefaultInstance().equals(biff)) {
                link.update(ReconcileContext.newBuilder()
                                            .setRing(ring)
                                            .addAllUpdates(
                                            buffer.reconcile(BloomFilter.from(biff), link.getMember().getId()))
                                            .build());
            }
        } finally {
            if (metrics != null && startNanos > 0) {
                metrics.recordGossipRoundDuration(System.nanoTime() - startNanos);
            }
        }
    }

    private void oneRound(Duration duration, ScheduledExecutorService scheduler) {
        if (!started.get()) {
            return;
        }
        try {
            long startNanos = metrics != null ? System.nanoTime() : 0;
            var successors = context.successors(member.getId(), m -> true, member);
            Collections.shuffle(successors);

            // Parallel fan-out to all successors using virtual threads (Delos-d5un)
            // O(duration + max(latency)) instead of O(n × duration)
            record GossipResult(Reconcile reconcile, ReliableBroadcast link, int ring) {}
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = successors.stream()
                    .map(i -> CompletableFuture.supplyAsync(() -> {
                        var link = comm.connect(i.m());
                        if (link == null) return null;
                        var g = gossipRound(link, i.ring());
                        return g != null ? new GossipResult(g, link, i.ring()) : null;
                    }, executor))
                    .toList();

                // Wait for all gossips to complete
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                // Handle all results and track health metrics (Delos-2xfp)
                int successCount = 0;
                int failCount = 0;
                for (var future : futures) {
                    try {
                        var result = future.join();
                        if (result != null) {
                            handle(result.reconcile, result.link, result.ring, startNanos);
                            successCount++;
                        } else {
                            failCount++;
                        }
                    } catch (Exception e) {
                        log.debug("Gossip future failed: {}", e.getMessage());
                        failCount++;
                    }
                }
                successfulGossips.addAndGet(successCount);
                failedGossips.addAndGet(failCount);
                if (successCount > 0) {
                    lastSuccessfulRound = buffer.round();
                }
            }

            // Single delay after parallel gossips
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            // Tick once per round regardless of gossip success (Delos-tofw)
            if (started.get()) {
                buffer.tick();
                int gossipRound = buffer.round();
                roundListeners.values().forEach(l -> {
                    try {
                        l.accept(gossipRound);
                    } catch (StatusRuntimeException e) {
                        log.error("error: {} sending round() to listener on: {}", e.getStatus(), member.getId(), e);
                    } catch (Throwable e) {
                        log.error("error sending round() to listener on: {}", member.getId(), e);
                    }
                });
            }
            schedule(duration, scheduler);
        }
    }

    private void schedule(final Duration duration, ScheduledExecutorService scheduler) {
        if (!started.get()) {
            return;
        }
        try {
            // Use scheduler instead of creating new virtual thread (Delos-4ww4)
            scheduler.schedule(
                Utils.wrapped(() -> oneRound(duration, scheduler), log),
                duration.toNanos(),
                TimeUnit.NANOSECONDS
            );
        } catch (RejectedExecutionException e) {
            // Scheduler shut down, ignore
        }
    }

    @FunctionalInterface
    public interface MessageHandler {
        void message(Digest context, List<Msg> messages);
    }

    public record MessageAdapter(Predicate<ByteString> verifier, Function<ByteString, Digest> hasher,
                                 Function<ByteString, List<Digest>> source,
                                 BiFunction<SigningMember, ByteString, ByteString> wrapper,
                                 Function<AgedMessageOrBuilder, ByteString> extractor) {
    }

    public record Msg(List<Digest> source, ByteString content, Digest hash) {
    }

    public record Parameters(int bufferSize, int maxMessages, DigestAlgorithm digestAlgorithm,
                             double falsePositiveRate, int maxMessageSize, OptionalInt maxAgeOverride) {
        /**
         * Default maximum message size: 10 MB
         */
        public static final int DEFAULT_MAX_MESSAGE_SIZE = 10 * 1024 * 1024;

        /**
         * Get the effective maxAge, using override if set, otherwise default to context TTL + 1
         */
        public int getMaxAge(Context<?> context) {
            return maxAgeOverride.orElse(context.timeToLive() + 1);
        }

        public static Parameters.Builder newBuilder() {
            return new Builder();
        }

        public static class Builder implements Cloneable {
            private int             bufferSize        = 1500;
            private DigestAlgorithm digestAlgorithm   = DigestAlgorithm.DEFAULT;
            private double          falsePositiveRate = 0.0000125;
            private int             maxMessages       = 500;
            private int             maxMessageSize    = DEFAULT_MAX_MESSAGE_SIZE;
            private OptionalInt     maxAgeOverride    = OptionalInt.empty();

            public Parameters build() {
                // Validate all parameters (Delos-zpgo)
                if (bufferSize <= 0) {
                    throw new IllegalArgumentException("bufferSize must be positive: " + bufferSize);
                }
                if (maxMessages <= 0) {
                    throw new IllegalArgumentException("maxMessages must be positive: " + maxMessages);
                }
                if (maxMessages > bufferSize) {
                    throw new IllegalArgumentException(
                        "maxMessages (" + maxMessages + ") cannot exceed bufferSize (" + bufferSize + ")");
                }
                if (falsePositiveRate <= 0 || falsePositiveRate >= 1) {
                    throw new IllegalArgumentException(
                        "falsePositiveRate must be in (0, 1): " + falsePositiveRate);
                }
                if (maxMessageSize <= 0) {
                    throw new IllegalArgumentException("maxMessageSize must be positive: " + maxMessageSize);
                }
                if (digestAlgorithm == null) {
                    throw new IllegalArgumentException("digestAlgorithm cannot be null");
                }
                maxAgeOverride.ifPresent(age -> {
                    if (age <= 0) {
                        throw new IllegalArgumentException("maxAgeOverride must be positive: " + age);
                    }
                });
                return new Parameters(bufferSize, maxMessages, digestAlgorithm, falsePositiveRate,
                                      maxMessageSize, maxAgeOverride);
            }

            @Override
            public Builder clone() {
                try {
                    return (Builder) super.clone();
                } catch (CloneNotSupportedException e) {
                    throw new IllegalStateException();
                }
            }

            public int getBufferSize() {
                return bufferSize;
            }

            public Parameters.Builder setBufferSize(int bufferSize) {
                this.bufferSize = bufferSize;
                return this;
            }

            public DigestAlgorithm getDigestAlgorithm() {
                return digestAlgorithm;
            }

            public Parameters.Builder setDigestAlgorithm(DigestAlgorithm digestAlgorithm) {
                this.digestAlgorithm = digestAlgorithm;
                return this;
            }

            public double getFalsePositiveRate() {
                return falsePositiveRate;
            }

            public Builder setFalsePositiveRate(double falsePositiveRate) {
                this.falsePositiveRate = falsePositiveRate;
                return this;
            }

            public int getMaxMessages() {
                return maxMessages;
            }

            public Builder setMaxMessages(int maxMessages) {
                this.maxMessages = maxMessages;
                return this;
            }

            public int getMaxMessageSize() {
                return maxMessageSize;
            }

            public Builder setMaxMessageSize(int maxMessageSize) {
                this.maxMessageSize = maxMessageSize;
                return this;
            }

            public OptionalInt getMaxAgeOverride() {
                return maxAgeOverride;
            }

            /**
             * Set a custom maxAge override instead of deriving from context TTL.
             * Useful for tuning retention independently of cluster size.
             */
            public Builder setMaxAgeOverride(int maxAge) {
                this.maxAgeOverride = OptionalInt.of(maxAge);
                return this;
            }

            /**
             * Clear any maxAge override, reverting to context.timeToLive() + 1
             */
            public Builder clearMaxAgeOverride() {
                this.maxAgeOverride = OptionalInt.empty();
                return this;
            }
        }

    }

    public class Service implements Router.ServiceRouting {

        public Reconcile gossip(MessageBff request, Digest from) {
            if (!validatePredecessor(request.getRing(), from, "gossip")) {
                return Reconcile.getDefaultInstance();
            }
            return Reconcile.newBuilder()
                            .addAllUpdates(buffer.reconcile(BloomFilter.from(request.getDigests()), from))
                            .setDigests(buffer.forReconcilliation().toBff())
                            .build();
        }

        public void update(ReconcileContext reconcile, Digest from) {
            if (!validatePredecessor(reconcile.getRing(), from, "update")) {
                return;
            }
            buffer.receive(reconcile.getUpdatesList());
        }

        /**
         * Validate that the sender is our predecessor on the specified ring (Delos-c2w5)
         */
        private boolean validatePredecessor(int ring, Digest from, String operation) {
            Member predecessor = context.predecessor(ring, member);
            if (predecessor == null || !from.equals(predecessor.getId())) {
                log.debug("Invalid inbound {} on {}:{} from: {} on ring: {} - not predecessor: {}",
                          operation, context.getId(), member.getId(), from, ring,
                          predecessor == null ? "<null>" : predecessor.getId());
                return false;
            }
            return true;
        }
    }
}
