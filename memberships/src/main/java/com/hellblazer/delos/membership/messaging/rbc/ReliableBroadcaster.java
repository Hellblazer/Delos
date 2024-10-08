/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.membership.messaging.rbc;

import com.codahale.metrics.Timer;
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
                return JohnHancock.from(SignedDefaultMessage.parseFrom(any).getSignature()).toDigest(algo);
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
        schedule(duration, Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory()));
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping Reliable Broadcaster[{}] on: {}", context.getId(), member.getId());
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

    private void handle(Reconcile gossip, ReliableBroadcast link, int ring, Timer.Context timer) {
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
            if (timer != null) {
                timer.stop();
            }
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
        }
    }

    private void oneRound(Duration duration, ScheduledExecutorService scheduler) {
        if (!started.get()) {
            return;
        }
        try {
            var timer = metrics == null ? null : metrics.gossipRoundDuration().time();
            var successors = context.successors(member.getId(), m -> true, member);
            Collections.shuffle(successors);
            successors.forEach(i -> {
                var link = comm.connect(i.m());
                if (link != null) {
                    var g = gossipRound(link, i.ring());
                    if (g != null) {
                        handle(g, link, i.ring(), timer);
                    }
                }
                try {
                    Thread.sleep(duration.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } finally {
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
                             double falsePositiveRate) {
        public static Parameters.Builder newBuilder() {
            return new Builder();
        }

        public static class Builder implements Cloneable {
            private int             bufferSize        = 1500;
            private DigestAlgorithm digestAlgorithm   = DigestAlgorithm.DEFAULT;
            private double          falsePositiveRate = 0.0000125;
            private int             maxMessages       = 500;

            public Parameters build() {
                return new Parameters(bufferSize, maxMessages, digestAlgorithm, falsePositiveRate);
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
        }

    }

    private record state(Digest hash, AgedMessage.Builder msg) {
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
            highWaterMark = (params.bufferSize - (int) (params.bufferSize + ((params.bufferSize) * 0.1)));
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
            deliver(messages.stream()
                            //                            .limit(params.maxMessages)
                            .map(am -> new state(adapter.hasher.apply(am.getContent()), AgedMessage.newBuilder(am)))
                            .filter(s -> !dup(s))
                            .filter(s -> adapter.verifier.test(s.msg.getContent()))
                            .map(s -> state.merge(s.hash, s, (a, b) -> a.msg.getAge() >= b.msg.getAge() ? a : b))
                            .map(s -> new Msg(adapter.source.apply(s.msg.getContent()), adapter.extractor.apply(s.msg),
                                              s.hash))
                            .toList());
            gc();
        }

        public Iterable<? extends AgedMessage> reconcile(BloomFilter<Digest> biff, Digest from) {
            PriorityQueue<AgedMessage.Builder> mailBox = new PriorityQueue<>(
            Comparator.comparingInt(AgedMessage.Builder::getAge));
            state.values()
                 .stream()
                 .collect(Utils.toShuffledList())
                 .stream()
                 .filter(s -> !biff.contains(s.hash))
                 .filter(s -> s.msg.getAge() < maxAge)
                 .forEach(s -> mailBox.add(s.msg));
            List<AgedMessage> reconciled = mailBox.stream().map(AgedMessage.Builder::build).toList();
            if (!reconciled.isEmpty()) {
                log.trace("reconciled: {} for: {} on: {}", reconciled.size(), from, member.getId());
            }
            return reconciled;
        }

        public int round() {
            return round.get();
        }

        public AgedMessage send(ByteString msg, SigningMember member) {
            AgedMessage.Builder message = AgedMessage.newBuilder().setContent(adapter.wrapper.apply(member, msg));
            var hash = adapter.hasher.apply(message.getContent());
            state s = new state(hash, message);
            state.put(hash, s);
            log.trace("Send message:{} on: {}", hash, member.getId());
            return s.msg.build();
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
                    int age = next.msg.getAge();
                    if (age >= maxAge) {
                        trav.remove();
                        gcd++;
                    } else {
                        next.msg.setAge(age + 1);
                    }
                }
                if (gcd != 0)
                    log.trace("GC'ing: {} on: {}", gcd, member.getId());
            } finally {
                tickGate.release();
            }
        }

        private boolean dup(state s) {
            if (s.msg.getAge() > maxAge) {
                log.trace("Rejecting message too old: {} age: {} > {} on: {}", s.hash, s.msg.getAge(), maxAge,
                          member.getId());
                return true;
            }
            var previous = state.get(s.hash);
            if (previous != null) {
                int nextAge = Math.max(previous.msg().getAge(), s.msg.getAge());
                if (nextAge > maxAge) {
                    state.remove(s.hash);
                } else if (previous.msg.getAge() != nextAge) {
                    previous.msg().setAge(nextAge);
                }
                //                log.trace("duplicate event: {} on: {}", s.hash, member.getId());
                return true;
            }
            return false;
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
            Collections.reverseOrder((a, b) -> Integer.compare(a.msg.getAge(), b.msg.getAge())));
            candidates.addAll(state.values());
            for (ReliableBroadcaster.state m : candidates) {
                if (m.msg.getAge() > maxAge) {
                    state.remove(m.hash);
                } else {
                    break;
                }
            }
        }

    }
}
