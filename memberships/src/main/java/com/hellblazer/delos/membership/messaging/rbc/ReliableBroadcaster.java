/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.messaging.rbc;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.archipelago.server.FernetServerInterceptor;
import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.bloomFilters.BloomFilter.DigestBloomFilter;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.proto.Biff;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.messaging.rbc.comms.RbcServer;
import com.hellblazer.delos.membership.messaging.rbc.comms.ReliableBroadcast;
import com.hellblazer.delos.messaging.proto.*;
import com.hellblazer.delos.utils.Entropy;
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

import static com.hellblazer.delos.membership.messaging.rbc.comms.RbcClient.getCreate;

/**
 * Content agnostic reliable broadcast of messages.
 *
 * @author hal.hildebrand
 */
public class ReliableBroadcaster {
    private static final Logger log = LoggerFactory.getLogger(ReliableBroadcaster.class);

    private final MessageAdapter                                   adapter;
    private final Buffer                                           buffer;
    private final Map<UUID, MessageHandler>                        channelHandlers = new ConcurrentHashMap<>();
    private final CommonCommunications<ReliableBroadcast, Service> comm;
    private final Context<Member>                                  context;
    private final SigningMember                                    member;
    private final RbcMetrics                                       metrics;
    private final Parameters                                       params;
    private final Map<UUID, Consumer<Integer>>                     roundListeners  = new ConcurrentHashMap<>();
    private final AtomicBoolean                                    started         = new AtomicBoolean();
    private volatile ScheduledExecutorService                      scheduler;

    public ReliableBroadcaster(Context<Member> context, SigningMember member, Parameters parameters,
                               Router communications, RbcMetrics metrics, MessageAdapter adapter) {
        this.params = parameters;
        this.context = context;
        this.member = member;
        this.metrics = metrics;
        // Use configurable maxAge with fallback to context TTL (Delos-i9nd)
        buffer = new Buffer(params.getMaxAge(context));
        this.comm = communications.create(member, context.getId(), new Service(),
                                          r -> new RbcServer(communications.getClientIdentityProvider(), metrics, r),
                                          getCreate(metrics), ReliableBroadcast.getLocalLoopback(member));
        this.adapter = adapter;
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

    public void start(Duration duration) {
        start(duration, null);
    }

    public void start(Duration duration, Predicate<FernetServerInterceptor.HashedToken> validator) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        log.info("Starting Reliable Broadcaster[{}] for {}", context.getId(), member.getId());
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
        log.info("Stopping Reliable Broadcaster[{}] on: {}", context.getId(), member.getId());
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
        channelHandlers.values().forEach(handler -> {
            try {
                handler.message(context.getId(), newMsgs);
            } catch (Throwable e) {
                log.warn("Error in message handler on: {}", member.getId(), e);
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

                // Handle all results
                futures.stream()
                    .map(f -> {
                        try {
                            return f.join();
                        } catch (Exception e) {
                            log.debug("Gossip future failed: {}", e.getMessage());
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .forEach(r -> handle(r.reconcile, r.link, r.ring, startNanos));
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

    /**
     * Immutable message state with thread-safe age tracking.
     * The AgedMessage is immutable; age is tracked separately via AtomicInteger
     * to allow lock-free concurrent updates.
     */
    private record state(Digest hash, AgedMessage msg, AtomicInteger age) {
        state(Digest hash, AgedMessage msg) {
            this(hash, msg, new AtomicInteger(msg.getAge()));
        }

        /**
         * Build an AgedMessage with the current age value for transmission.
         */
        AgedMessage buildWithCurrentAge() {
            return AgedMessage.newBuilder(msg).setAge(age.get()).build();
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

    private class Buffer {
        private final Semaphore          garbageCollecting = new Semaphore(1);
        private final int                highWaterMark;
        private final int                maxAge;
        private final AtomicInteger      round             = new AtomicInteger();
        private final Map<Digest, state> state             = new ConcurrentHashMap<>();
        private final Semaphore          tickGate          = new Semaphore(1);
        // Per-source message count for rate limiting (Delos-d1an Byzantine defense)
        // Reset each round in tick()
        private final Map<Digest, AtomicInteger> sourceMessageCount = new ConcurrentHashMap<>();
        // Max messages per source per round (prevents flooding attacks)
        private static final int MAX_MESSAGES_PER_SOURCE = 100;

        private Buffer(int maxAge) {
            this.maxAge = maxAge;
            // Ensure highWaterMark is at least 1 for tiny buffer sizes
            highWaterMark = Math.max(1, (int) (params.bufferSize * 0.9));
        }

        public void clear() {
            state.clear();
            sourceMessageCount.clear();
        }

        public BloomFilter<Digest> forReconcilliation() {
            var biff = new DigestBloomFilter(Entropy.nextBitsStreamLong(), params.bufferSize, params.falsePositiveRate);
            // Bloom filter doesn't require ordering - skip shuffle (Delos-2ft7)
            state.keySet().forEach(biff::add);
            return biff;
        }

        public void receive(List<AgedMessage> messages) {
            if (messages.isEmpty()) {
                return;
            }
            log.trace("receiving: {} msgs on: {}", messages.size(), member.getId());
            // Log DoS protection filtering if active
            int inputSize = messages.size();
            if (inputSize > params.maxMessages) {
                log.debug("DoS protection: truncating {} messages to {} limit on: {}",
                          inputSize, params.maxMessages, member.getId());
            }

            // Phase 1: Fast filtering (no crypto) - collect candidates for verification
            List<state> candidates = messages.stream()
                            .limit(params.maxMessages)
                            // Filter oversized messages (DoS protection)
                            .filter(am -> {
                                boolean ok = am.getContent().size() <= params.maxMessageSize();
                                if (!ok) {
                                    log.debug("DoS protection: rejecting oversized message ({} > {} bytes) on: {}",
                                              am.getContent().size(), params.maxMessageSize(), member.getId());
                                }
                                return ok;
                            })
                            // Age bounds validation (Delos-d1an Byzantine defense)
                            .filter(am -> {
                                int age = am.getAge();
                                if (age < 0 || age > maxAge) {
                                    log.debug("Byzantine defense: rejecting invalid age {} (valid: 0-{}) on: {}",
                                              age, maxAge, member.getId());
                                    return false;
                                }
                                if (metrics != null) {
                                    metrics.recordMessageAge(age);
                                }
                                return true;
                            })
                            // Per-source rate limiting (Delos-d1an Byzantine defense)
                            .filter(am -> {
                                List<Digest> sources = adapter.source.apply(am.getContent());
                                for (Digest source : sources) {
                                    var counter = sourceMessageCount.computeIfAbsent(source, _ -> new AtomicInteger(0));
                                    if (counter.incrementAndGet() > MAX_MESSAGES_PER_SOURCE) {
                                        log.debug("Byzantine defense: rate limiting source {} (>{} msgs/round) on: {}",
                                                  source, MAX_MESSAGES_PER_SOURCE, member.getId());
                                        if (metrics != null) {
                                            metrics.incrementRateLimitRejection();
                                        }
                                        return false;
                                    }
                                }
                                return true;
                            })
                            // Create state with hash
                            .map(am -> new state(adapter.hasher.apply(am.getContent()), am))
                            // Insert-first dedup using putIfAbsent (Delos-l1bl)
                            .filter(s -> {
                                var existing = state.putIfAbsent(s.hash, s);
                                if (existing != null) {
                                    // Duplicate - update age if incoming is fresher
                                    int previousAge = existing.age.get();
                                    int incomingAge = s.age.get();
                                    if (incomingAge < previousAge) {
                                        existing.age.updateAndGet(prev -> Math.min(prev, incomingAge));
                                    }
                                    if (metrics != null) {
                                        metrics.incrementDedupCount();
                                    }
                                    return false;
                                }
                                return true; // New message, need verification
                            })
                            .toList();

            // Phase 2: Parallel signature verification using virtual threads (Delos-7set)
            // This prevents crypto from blocking the gossip receiver
            List<Msg> verified;
            if (candidates.size() <= 1) {
                // Single message - verify inline (no parallelism overhead)
                verified = candidates.stream()
                    .filter(this::verifyAndTrack)
                    .map(s -> new Msg(adapter.source.apply(s.msg.getContent()), adapter.extractor.apply(s.msg), s.hash))
                    .toList();
            } else {
                // Multiple messages - verify in parallel with virtual threads
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var futures = candidates.stream()
                        .map(s -> CompletableFuture.supplyAsync(() -> verifyAndTrack(s) ? s : null, executor))
                        .toList();
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    verified = futures.stream()
                        .map(f -> {
                            try { return f.join(); } catch (Exception e) { return null; }
                        })
                        .filter(Objects::nonNull)
                        .map(s -> new Msg(adapter.source.apply(s.msg.getContent()), adapter.extractor.apply(s.msg), s.hash))
                        .toList();
                }
            }

            deliver(verified);
            gc();
            // Record buffer size for observability (Delos-xwen)
            if (metrics != null) {
                metrics.recordBufferSize(state.size());
            }
        }

        /**
         * Verify signature and track metrics. Returns true if verified, false otherwise.
         * On failure, removes the entry from state that was added during dedup.
         */
        private boolean verifyAndTrack(state s) {
            long verifyStart = metrics != null ? System.nanoTime() : 0;
            boolean verified = adapter.verifier.test(s.msg.getContent());
            if (metrics != null) {
                metrics.recordVerificationDuration(System.nanoTime() - verifyStart);
            }
            if (!verified) {
                state.remove(s.hash, s);
                if (metrics != null) {
                    metrics.incrementVerificationFailure();
                }
                log.debug("Signature verification failed for: {} on: {}", s.hash, member.getId());
                return false;
            }
            return true;
        }

        public Iterable<? extends AgedMessage> reconcile(BloomFilter<Digest> biff, Digest from) {
            // PriorityQueue sorts by age - no need to shuffle first (Delos-2ft7)
            PriorityQueue<state> mailBox = new PriorityQueue<>(
            Comparator.comparingInt(s -> s.age.get()));
            state.values()
                 .stream()
                 .filter(s -> !biff.contains(s.hash))
                 .filter(s -> s.age.get() < maxAge)
                 .forEach(mailBox::add);
            List<AgedMessage> reconciled = mailBox.stream().map(s -> s.buildWithCurrentAge()).toList();
            if (!reconciled.isEmpty()) {
                log.trace("reconciled: {} for: {} on: {}", reconciled.size(), from, member.getId());
            }
            return reconciled;
        }

        public int round() {
            return round.get();
        }

        public AgedMessage send(ByteString msg, SigningMember member) {
            AgedMessage message = AgedMessage.newBuilder().setContent(adapter.wrapper.apply(member, msg)).build();
            var hash = adapter.hasher.apply(message.getContent());
            state s = new state(hash, message);
            state.put(hash, s);
            log.trace("Send message:{} on: {}", hash, member.getId());
            return message;
        }

        public int size() {
            return state.size();
        }

        public void tick() {
            round.incrementAndGet();
            // Reset per-source rate limits each round (Delos-d1an)
            sourceMessageCount.clear();
            if (!tickGate.tryAcquire()) {
                log.trace("Unable to acquire tick gate for: {} tick already in progress on: {}", context.getId(),
                          member.getId());
                return;
            }
            try {
                var trav = state.entrySet().iterator();
                int gcd = 0;
                while (trav.hasNext()) {
                    var next = trav.next().getValue();
                    int currentAge = next.age.get();
                    if (currentAge >= maxAge) {
                        trav.remove();
                        gcd++;
                    } else {
                        next.age.incrementAndGet();
                    }
                }
                if (gcd != 0)
                    log.trace("GC'ing: {} on: {}", gcd, member.getId());
            } finally {
                tickGate.release();
            }
        }

        private void gc() {
            if ((size() < highWaterMark) || !garbageCollecting.tryAcquire()) {
                return;
            }
            try {
                int startSize = state.size();
                if (startSize < highWaterMark) {
                    return;
                }
                log.trace("Compacting buffer: {} size: {} on: {}", context.getId(), startSize, member.getId());
                purgeTheAged();
                if (buffer.size() > params.bufferSize) {
                    log.warn("Buffer overflow: {} > {} after compact for: {} on: {} ", buffer.size(), params.bufferSize,
                             context.getId(), member.getId());
                }
                int freed = startSize - state.size();
                if (freed > 0) {
                    log.debug("Buffer freed: {} after compact for: {} on: {} ", freed, context.getId(), member.getId());
                    // Record GC cycle metrics (Delos-xwen)
                    if (metrics != null) {
                        metrics.recordGcCycle(freed);
                    }
                }
            } finally {
                garbageCollecting.release();
            }
        }

        private void purgeTheAged() {
            // Direct iteration is O(n), vs O(n log n) for PriorityQueue (Delos-2ft7)
            var iterator = state.values().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.age.get() > maxAge) {
                    iterator.remove();
                }
            }
        }

    }
}
