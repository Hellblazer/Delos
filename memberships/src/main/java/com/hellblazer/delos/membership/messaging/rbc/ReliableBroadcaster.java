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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
        buffer = new Buffer(context.timeToLive() + 1);
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
        scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        schedule(duration, scheduler);
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping Reliable Broadcaster[{}] on: {}", context.getId(), member.getId());
        // Capture scheduler reference before nulling to prevent race with start()
        // (Delos-vbyg fix: avoid shutting down a newly-created scheduler from concurrent start())
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
            successors.forEach(i -> {
                var link = comm.connect(i.m());
                if (link != null) {
                    var g = gossipRound(link, i.ring());
                    if (g != null) {
                        handle(g, link, i.ring(), startNanos);
                    }
                }
                try {
                    Thread.sleep(duration.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
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
        Thread.ofVirtual().start(Utils.wrapped(() -> oneRound(duration, scheduler), log));
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
                             double falsePositiveRate, int maxMessageSize) {
        /**
         * Default maximum message size: 10 MB
         */
        public static final int DEFAULT_MAX_MESSAGE_SIZE = 10 * 1024 * 1024;

        public static Parameters.Builder newBuilder() {
            return new Builder();
        }

        public static class Builder implements Cloneable {
            private int             bufferSize        = 1500;
            private DigestAlgorithm digestAlgorithm   = DigestAlgorithm.DEFAULT;
            private double          falsePositiveRate = 0.0000125;
            private int             maxMessages       = 500;
            private int             maxMessageSize    = DEFAULT_MAX_MESSAGE_SIZE;

            public Parameters build() {
                return new Parameters(bufferSize, maxMessages, digestAlgorithm, falsePositiveRate, maxMessageSize);
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
            Member predecessor = context.predecessor(request.getRing(), member);
            if (predecessor == null || !from.equals(predecessor.getId())) {
                log.trace("Invalid inbound messages gossip on {}:{} from: {} on ring: {} - not predecessor: {}",
                          context.getId(), member.getId(), from, request.getRing(),
                          predecessor == null ? "<null>" : predecessor.getId());
                return Reconcile.getDefaultInstance();
            }
            return Reconcile.newBuilder()
                            .addAllUpdates(buffer.reconcile(BloomFilter.from(request.getDigests()), from))
                            .setDigests(buffer.forReconcilliation().toBff())
                            .build();
        }

        public void update(ReconcileContext reconcile, Digest from) {
            Member predecessor = context.predecessor(reconcile.getRing(), member);
            if (predecessor == null || !from.equals(predecessor.getId())) {
                log.info("Invalid inbound messages reconcile on {}:{} from: {} on ring: {} - not predecessor: {}",
                         context.getId(), member.getId(), from, reconcile.getRing(),
                         predecessor == null ? "<null>" : predecessor.getId());
                return;
            }
            buffer.receive(reconcile.getUpdatesList());
        }
    }

    private class Buffer {
        private final Semaphore          garbageCollecting = new Semaphore(1);
        private final int                highWaterMark;
        private final int                maxAge;
        private final AtomicInteger      round             = new AtomicInteger();
        private final Map<Digest, state> state             = new ConcurrentHashMap<>();
        private final Semaphore          tickGate          = new Semaphore(1);

        private Buffer(int maxAge) {
            this.maxAge = maxAge;
            // Ensure highWaterMark is at least 1 for tiny buffer sizes
            highWaterMark = Math.max(1, (int) (params.bufferSize * 0.9));
        }

        public void clear() {
            state.clear();
        }

        public BloomFilter<Digest> forReconcilliation() {
            var biff = new DigestBloomFilter(Entropy.nextBitsStreamLong(), params.bufferSize, params.falsePositiveRate);
            state.keySet().stream().collect(Utils.toShuffledList()).forEach(biff::add);
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
            deliver(messages.stream()
                            .limit(params.maxMessages)
                            .filter(am -> {
                                boolean ok = am.getContent().size() <= params.maxMessageSize();
                                if (!ok) {
                                    log.debug("DoS protection: rejecting oversized message ({} > {} bytes) on: {}",
                                              am.getContent().size(), params.maxMessageSize(), member.getId());
                                }
                                return ok;
                            })
                            .map(am -> new state(adapter.hasher.apply(am.getContent()), am))
                            .filter(s -> !dup(s))
                            .filter(s -> adapter.verifier.test(s.msg.getContent()))
                            .map(s -> state.merge(s.hash, s, (a, b) -> a.age.get() <= b.age.get() ? a : b))
                            .map(s -> new Msg(adapter.source.apply(s.msg.getContent()), adapter.extractor.apply(s.msg),
                                              s.hash))
                            .toList());
            gc();
        }

        public Iterable<? extends AgedMessage> reconcile(BloomFilter<Digest> biff, Digest from) {
            PriorityQueue<state> mailBox = new PriorityQueue<>(
            Comparator.comparingInt(s -> s.age.get()));
            state.values()
                 .stream()
                 .collect(Utils.toShuffledList())
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

        /**
         * Check if message is duplicate. Uses atomic compute() to avoid TOCTOU races.
         * If duplicate exists, keeps the one with lower (fresher) age.
         */
        private boolean dup(state s) {
            int incomingAge = s.age.get();
            if (incomingAge > maxAge) {
                log.trace("Rejecting message too old: {} age: {} > {} on: {}", s.hash, incomingAge, maxAge,
                          member.getId());
                return true;
            }
            // Atomic check-and-update to avoid TOCTOU race (Delos-jke1)
            var result = new AtomicBoolean(false);
            state.compute(s.hash, (key, previous) -> {
                if (previous == null) {
                    // Not a duplicate, will be inserted by caller via merge()
                    return null;
                }
                result.set(true); // It's a duplicate
                int previousAge = previous.age.get();
                int fresherAge = Math.min(previousAge, incomingAge);
                if (fresherAge > maxAge) {
                    return null; // Remove expired entry
                }
                // Keep fresher age (atomically update if incoming is fresher)
                if (incomingAge < previousAge) {
                    previous.age.set(incomingAge);
                }
                return previous;
            });
            return result.get();
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
                }
            } finally {
                garbageCollecting.release();
            }
        }

        private void purgeTheAged() {
            Queue<state> candidates = new PriorityQueue<>(
            Collections.reverseOrder((a, b) -> Integer.compare(a.age.get(), b.age.get())));
            candidates.addAll(state.values());
            for (ReliableBroadcaster.state m : candidates) {
                if (m.age.get() > maxAge) {
                    state.remove(m.hash);
                } else {
                    break;
                }
            }
        }

    }
}
