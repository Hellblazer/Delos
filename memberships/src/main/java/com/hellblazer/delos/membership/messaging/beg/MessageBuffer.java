/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.messaging.beg;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.bloomFilters.BloomFilter.DigestBloomFilter;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.messaging.beg.BoundedEpidemicGossip.MessageAdapter;
import com.hellblazer.delos.membership.messaging.beg.BoundedEpidemicGossip.Msg;
import com.hellblazer.delos.messaging.proto.AgedMessage;
import com.hellblazer.delos.utils.Entropy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Thread-safe message buffer for BoundedEpidemicGossip.
 * Handles message storage, deduplication, age-based expiry, and Byzantine defenses.
 * <p>
 * Extracted from BoundedEpidemicGossip for improved testability (Delos-ybss).
 *
 * @author hal.hildebrand
 * @see BoundedEpidemicGossip
 */
public class MessageBuffer {
    private static final Logger log = LoggerFactory.getLogger(MessageBuffer.class);

    // Max messages per source per round (prevents flooding attacks)
    private static final int MAX_MESSAGES_PER_SOURCE = 100;

    private final int              bufferSize;
    private final double           falsePositiveRate;
    private final int              maxMessages;
    private final int              maxMessageSize;
    private final int              maxAge;
    private final int              highWaterMark;
    private final MessageAdapter   adapter;
    private final BegMetrics       metrics;
    private final Digest           memberId;
    private final Digest           contextId;
    private final Consumer<List<Msg>> deliveryCallback;

    private final Semaphore          garbageCollecting = new Semaphore(1);
    private final AtomicInteger      round             = new AtomicInteger();
    private final Map<Digest, State> state             = new ConcurrentHashMap<>();
    private final Semaphore          tickGate          = new Semaphore(1);
    // Per-source message count for rate limiting (Delos-d1an Byzantine defense)
    private final Map<Digest, AtomicInteger> sourceMessageCount = new ConcurrentHashMap<>();

    /**
     * Immutable message state with thread-safe age tracking.
     * The AgedMessage is immutable; age is tracked separately via AtomicInteger
     * to allow lock-free concurrent updates.
     */
    record State(Digest hash, AgedMessage msg, AtomicInteger age) {
        State(Digest hash, AgedMessage msg) {
            this(hash, msg, new AtomicInteger(msg.getAge()));
        }

        /**
         * Build an AgedMessage with the current age value for transmission.
         */
        AgedMessage buildWithCurrentAge() {
            return AgedMessage.newBuilder(msg).setAge(age.get()).build();
        }
    }

    /**
     * Create a new MessageBuffer.
     *
     * @param bufferSize       maximum buffer capacity
     * @param falsePositiveRate bloom filter false positive rate
     * @param maxMessages      max messages per receive batch
     * @param maxMessageSize   max message size in bytes
     * @param maxAge           max age before expiry (rounds)
     * @param adapter          message adapter for verification/hashing
     * @param metrics          metrics recorder (may be null)
     * @param memberId         local member ID for logging
     * @param contextId        context ID for logging
     * @param deliveryCallback callback to deliver verified messages
     */
    public MessageBuffer(int bufferSize, double falsePositiveRate, int maxMessages, int maxMessageSize,
                         int maxAge, MessageAdapter adapter, BegMetrics metrics,
                         Digest memberId, Digest contextId, Consumer<List<Msg>> deliveryCallback) {
        this.bufferSize = bufferSize;
        this.falsePositiveRate = falsePositiveRate;
        this.maxMessages = maxMessages;
        this.maxMessageSize = maxMessageSize;
        this.maxAge = maxAge;
        this.adapter = adapter;
        this.metrics = metrics;
        this.memberId = memberId;
        this.contextId = contextId;
        this.deliveryCallback = deliveryCallback;
        // Ensure highWaterMark is at least 1 for tiny buffer sizes
        this.highWaterMark = Math.max(1, (int) (bufferSize * 0.9));
    }

    public void clear() {
        state.clear();
        sourceMessageCount.clear();
    }

    public BloomFilter<Digest> forReconcilliation() {
        var biff = new DigestBloomFilter(Entropy.nextBitsStreamLong(), bufferSize, falsePositiveRate);
        // Bloom filter doesn't require ordering - skip shuffle (Delos-2ft7)
        state.keySet().forEach(biff::add);
        return biff;
    }

    public void receive(List<AgedMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        log.trace("receiving: {} msgs on: {}", messages.size(), memberId);
        // Log DoS protection filtering if active
        int inputSize = messages.size();
        if (inputSize > maxMessages) {
            log.debug("DoS protection: truncating {} messages to {} limit on: {}",
                      inputSize, maxMessages, memberId);
        }

        // Phase 1: Fast filtering (no crypto) - collect candidates for verification
        List<State> candidates = messages.stream()
                        .limit(maxMessages)
                        // Filter oversized messages (DoS protection)
                        .filter(am -> {
                            boolean ok = am.getContent().size() <= maxMessageSize;
                            if (!ok) {
                                log.debug("DoS protection: rejecting oversized message ({} > {} bytes) on: {}",
                                          am.getContent().size(), maxMessageSize, memberId);
                            }
                            return ok;
                        })
                        // Age bounds validation (Delos-d1an Byzantine defense)
                        .filter(am -> {
                            int age = am.getAge();
                            if (age < 0 || age > maxAge) {
                                log.debug("Byzantine defense: rejecting invalid age {} (valid: 0-{}) on: {}",
                                          age, maxAge, memberId);
                                return false;
                            }
                            if (metrics != null) {
                                metrics.recordMessageAge(age);
                            }
                            return true;
                        })
                        // Per-source rate limiting (Delos-d1an Byzantine defense)
                        .filter(am -> {
                            List<Digest> sources = adapter.source().apply(am.getContent());
                            for (Digest source : sources) {
                                var counter = sourceMessageCount.computeIfAbsent(source, _ -> new AtomicInteger(0));
                                if (counter.incrementAndGet() > MAX_MESSAGES_PER_SOURCE) {
                                    log.debug("Byzantine defense: rate limiting source {} (>{} msgs/round) on: {}",
                                              source, MAX_MESSAGES_PER_SOURCE, memberId);
                                    if (metrics != null) {
                                        metrics.incrementRateLimitRejection();
                                    }
                                    return false;
                                }
                            }
                            return true;
                        })
                        // Create state with hash
                        .map(am -> new State(adapter.hasher().apply(am.getContent()), am))
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
                .map(s -> new Msg(adapter.source().apply(s.msg.getContent()), adapter.extractor().apply(s.msg), s.hash))
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
                    .map(s -> new Msg(adapter.source().apply(s.msg.getContent()), adapter.extractor().apply(s.msg), s.hash))
                    .toList();
            }
        }

        deliveryCallback.accept(verified);
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
    private boolean verifyAndTrack(State s) {
        long verifyStart = metrics != null ? System.nanoTime() : 0;
        boolean verified = adapter.verifier().test(s.msg.getContent());
        if (metrics != null) {
            metrics.recordVerificationDuration(System.nanoTime() - verifyStart);
        }
        if (!verified) {
            state.remove(s.hash, s);
            if (metrics != null) {
                metrics.incrementVerificationFailure();
            }
            log.debug("Signature verification failed for: {} on: {}", s.hash, memberId);
            return false;
        }
        return true;
    }

    public Iterable<? extends AgedMessage> reconcile(BloomFilter<Digest> biff, Digest from) {
        // PriorityQueue sorts by age - no need to shuffle first (Delos-2ft7)
        PriorityQueue<State> mailBox = new PriorityQueue<>(
        Comparator.comparingInt(s -> s.age.get()));
        state.values()
             .stream()
             .filter(s -> !biff.contains(s.hash))
             .filter(s -> s.age.get() < maxAge)
             .forEach(mailBox::add);
        List<AgedMessage> reconciled = mailBox.stream().map(State::buildWithCurrentAge).toList();
        if (!reconciled.isEmpty()) {
            log.trace("reconciled: {} for: {} on: {}", reconciled.size(), from, memberId);
        }
        return reconciled;
    }

    public int round() {
        return round.get();
    }

    public AgedMessage send(ByteString msg, SigningMember member) {
        AgedMessage message = AgedMessage.newBuilder().setContent(adapter.wrapper().apply(member, msg)).build();
        var hash = adapter.hasher().apply(message.getContent());
        State s = new State(hash, message);
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
            log.trace("Unable to acquire tick gate for: {} tick already in progress on: {}", contextId,
                      memberId);
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
                log.trace("GC'ing: {} on: {}", gcd, memberId);
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
            log.trace("Compacting buffer: {} size: {} on: {}", contextId, startSize, memberId);
            purgeTheAged();
            if (size() > bufferSize) {
                log.warn("Buffer overflow: {} > {} after compact for: {} on: {} ", size(), bufferSize,
                         contextId, memberId);
            }
            int freed = startSize - state.size();
            if (freed > 0) {
                log.debug("Buffer freed: {} after compact for: {} on: {} ", freed, contextId, memberId);
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
