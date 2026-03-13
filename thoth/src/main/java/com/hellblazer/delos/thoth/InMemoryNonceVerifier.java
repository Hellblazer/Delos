/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.cryptography.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * In-memory implementation of {@link NonceVerifier} for replay attack prevention.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Nonces are mapped to their expiry time (epoch ms) in a {@link ConcurrentHashMap}.</li>
 *   <li>Each nonce expires after {@code ttl}. Expired nonces are not replays — the
 *       attack window has closed — so they are accepted again.</li>
 *   <li>A background eviction task runs every {@code ttl/2} to prune expired entries
 *       and keep memory bounded without blocking request handling.</li>
 *   <li>When size exceeds {@code maxCapacity}, the oldest 10% of entries (by expiry)
 *       are evicted eagerly before inserting the new nonce.</li>
 *   <li>Thread safety is achieved by using {@link ConcurrentHashMap#putIfAbsent} as the
 *       atomic check-and-set primitive.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * All public methods are safe for concurrent use without external synchronisation.
 *
 * @author hal.hildebrand
 */
public class InMemoryNonceVerifier implements NonceVerifier {

    private static final Logger log = LoggerFactory.getLogger(InMemoryNonceVerifier.class);

    /** Maps nonce → expiry timestamp (ms since epoch). */
    private final ConcurrentHashMap<String, Long> nonceMap;

    /** Time-to-live for each nonce entry. */
    private final Duration ttl;

    /** Maximum number of live entries before eager eviction kicks in. */
    private final int maxCapacity;

    /** Supplies current time in ms; injectable for testing. */
    private final Supplier<Long> clock;

    /** The scheduled eviction task future (cancelled on close). */
    private final ScheduledFuture<?> evictionTask;

    /**
     * Production constructor using the real wall clock.
     *
     * @param ttl         time-to-live per nonce (should match KerlDHT operationTimeout)
     * @param maxCapacity maximum live nonce entries before eager eviction; must be &gt; 0
     * @param scheduler   shared scheduler for the background eviction task (not shut down by close())
     */
    public InMemoryNonceVerifier(Duration ttl, int maxCapacity, ScheduledExecutorService scheduler) {
        this(ttl, maxCapacity, scheduler, System::currentTimeMillis);
    }

    /**
     * Testable constructor with injectable clock.
     *
     * @param ttl         time-to-live per nonce
     * @param maxCapacity maximum live nonce entries before eager eviction; must be &gt; 0
     * @param scheduler   shared scheduler for the background eviction task
     * @param clock       supplier of current time in milliseconds (injectable for tests)
     */
    public InMemoryNonceVerifier(Duration ttl, int maxCapacity, ScheduledExecutorService scheduler,
                                 Supplier<Long> clock) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be a positive duration");
        }
        if (maxCapacity <= 0) {
            throw new IllegalArgumentException("maxCapacity must be > 0");
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler must not be null");
        }
        this.ttl = ttl;
        this.maxCapacity = maxCapacity;
        this.clock = clock != null ? clock : System::currentTimeMillis;
        this.nonceMap = new ConcurrentHashMap<>();

        // Schedule eviction at ttl/2 intervals to keep memory bounded
        var evictionInterval = ttl.dividedBy(2).toMillis();
        if (evictionInterval < 1) {
            evictionInterval = 1; // minimum 1ms for very short TTLs (testing)
        }
        this.evictionTask = scheduler.scheduleAtFixedRate(
            this::evictExpired, evictionInterval, evictionInterval, TimeUnit.MILLISECONDS);
    }

    @Override
    public String generateNonce() {
        return UUID.randomUUID().toString();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The map key is a composite of {@code nonce + "|" + memberId} so that the same
     * nonce accepted from multiple members in a quorum does not falsely reject legitimate
     * responses. The replay protection is per-member: a given (nonce, member) pair may
     * only appear once within the TTL window.</p>
     *
     * <p>Implementation uses {@link ConcurrentHashMap#putIfAbsent} for atomic
     * check-and-insert semantics. If the existing entry is expired, it is
     * removed first so that the fresh nonce can be accepted.</p>
     */
    @Override
    public boolean recordAndVerify(String nonce, Digest memberId) {
        if (nonce == null) {
            log.warn("Null nonce received from member={}, rejecting", memberId);
            return false;
        }

        // Composite key: nonce + memberId — prevents the same member from replaying
        // the same nonce, while allowing different quorum members to respond to the same request.
        var key = nonce + "|" + (memberId != null ? memberId.toString() : "unknown");

        var now = clock.get();
        var expiry = now + ttl.toMillis();

        // Check for an existing entry that has expired (attack window closed)
        var existing = nonceMap.get(key);
        if (existing != null && existing <= now) {
            // Expired entry — remove it so the slot is free
            nonceMap.remove(key, existing);
            existing = null;
        }

        if (existing != null) {
            // Nonce is still live: replay detected
            log.warn("Replay detected: nonce={} from member={}", nonce, memberId);
            return false;
        }

        // Enforce capacity before inserting
        if (nonceMap.size() >= maxCapacity) {
            evictOldest();
        }

        // Atomic insert — only succeeds if no other thread inserted concurrently for this (nonce, member) key
        var previous = nonceMap.putIfAbsent(key, expiry);
        if (previous != null) {
            // Another thread beat us; check if it's expired
            if (previous <= now) {
                // Expired — try to replace atomically
                if (nonceMap.replace(key, previous, expiry)) {
                    return true; // we replaced the expired entry — fresh nonce
                }
                // Another thread replaced it too; treat as seen
                log.warn("Concurrent replay on nonce={} from member={}", nonce, memberId);
                return false;
            }
            // Live entry already exists — replay
            log.warn("Replay detected (concurrent): nonce={} from member={}", nonce, memberId);
            return false;
        }
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Cancels the background eviction task without shutting down the shared scheduler.
     * The nonce map is cleared to release memory promptly.</p>
     */
    @Override
    public void close() {
        evictionTask.cancel(false);
        nonceMap.clear();
        log.debug("InMemoryNonceVerifier closed; nonce map cleared");
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Remove all expired entries from the nonce map.
     * Called by the scheduled eviction task.
     */
    private void evictExpired() {
        var now = clock.get();
        var before = nonceMap.size();
        nonceMap.entrySet().removeIf(e -> e.getValue() <= now);
        var removed = before - nonceMap.size();
        if (removed > 0) {
            log.debug("Nonce eviction: removed {} expired entries, {} remaining", removed, nonceMap.size());
        }
    }

    /**
     * Evict the oldest 10% of entries (by expiry time) to make room for new nonces.
     * Called when size reaches maxCapacity.
     */
    private void evictOldest() {
        int toEvict = Math.max(1, maxCapacity / 10);
        log.debug("Capacity reached ({}), evicting {} oldest entries", maxCapacity, toEvict);

        // Collect all entries sorted by expiry ascending (oldest first)
        var entries = new ArrayList<>(nonceMap.entrySet());
        entries.sort(Comparator.comparingLong(Map.Entry::getValue));

        int evicted = 0;
        for (var entry : entries) {
            if (evicted >= toEvict) {
                break;
            }
            if (nonceMap.remove(entry.getKey(), entry.getValue())) {
                evicted++;
            }
        }
        log.debug("Evicted {} oldest entries", evicted);
    }
}
