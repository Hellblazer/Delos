/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.gossip;

import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.proto.Biff;
import com.hellblazer.delos.fireflies.proto.ReceiptGossip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Anti-entropy service for efficient receipt synchronization across the witness network.
 * <p>
 * Tracks known receipts, builds bloom filters for anti-entropy, and identifies
 * missing receipts by comparing bloom filters with peers.
 * <p>
 * Responsibilities:
 * - Maintain index of known receipts by digest
 * - Build bloom filters for gossip rounds
 * - Identify missing receipts from peer bloom filters
 * - Provide receipts to peers based on their bloom filters
 * <p>
 * Thread-safe: All operations are thread-safe and can be called concurrently.
 *
 * @author hal.hildebrand
 * @since Phase F3 (Distributed Receipt Queries)
 */
public class ReceiptAntiEntropyService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptAntiEntropyService.class);

    private final DigestAlgorithm digestAlgorithm;
    private final double falsePositiveRate;
    private final int minimumCardinality;

    // Index: receipt digest -> receipt
    private final Map<Digest, GossipableReceipt> receiptIndex;
    private final ReadWriteLock lock;

    // Metrics
    private volatile long receiptsAdded = 0;
    private volatile long receiptsRemoved = 0;
    private volatile long bloomFiltersBuilt = 0;
    private volatile long missingReceiptsIdentified = 0;

    /**
     * Create anti-entropy service with default parameters.
     *
     * @param digestAlgorithm Algorithm for computing receipt digests
     */
    public ReceiptAntiEntropyService(DigestAlgorithm digestAlgorithm) {
        this(digestAlgorithm, 0.01, 10); // 1% FPR, minimum 10 elements
    }

    /**
     * Create anti-entropy service with custom parameters.
     *
     * @param digestAlgorithm    Algorithm for computing receipt digests
     * @param falsePositiveRate  Desired false positive rate for bloom filters (e.g., 0.01 for 1%)
     * @param minimumCardinality Minimum bloom filter cardinality for empty/small sets
     */
    public ReceiptAntiEntropyService(DigestAlgorithm digestAlgorithm, double falsePositiveRate, int minimumCardinality) {
        this.digestAlgorithm = Objects.requireNonNull(digestAlgorithm, "digestAlgorithm required");
        this.falsePositiveRate = falsePositiveRate;
        this.minimumCardinality = minimumCardinality;
        this.receiptIndex = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
    }

    /**
     * Add receipt to the anti-entropy index.
     * <p>
     * Receipt becomes available for anti-entropy and will be included in
     * bloom filters and gossip responses.
     *
     * @param receipt Receipt to add
     * @return true if receipt was added (new), false if already present
     * @throws NullPointerException if receipt is null
     */
    public boolean addReceipt(GossipableReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt required");

        var digest = ReceiptGossipCodec.digestOf(receipt, digestAlgorithm);

        lock.writeLock().lock();
        try {
            var previous = receiptIndex.put(digest, receipt);
            if (previous == null) {
                receiptsAdded++;
                if (log.isDebugEnabled()) {
                    log.debug("Added receipt to anti-entropy index: event={}, witness={}, total={}",
                        receipt.eventCoordinates(),
                        receipt.witnessId(),
                        receiptIndex.size()
                    );
                }
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Remove receipt from the anti-entropy index.
     *
     * @param receipt Receipt to remove
     * @return true if receipt was removed, false if not present
     * @throws NullPointerException if receipt is null
     */
    public boolean removeReceipt(GossipableReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt required");

        var digest = ReceiptGossipCodec.digestOf(receipt, digestAlgorithm);

        lock.writeLock().lock();
        try {
            var removed = receiptIndex.remove(digest);
            if (removed != null) {
                receiptsRemoved++;
                if (log.isDebugEnabled()) {
                    log.debug("Removed receipt from anti-entropy index: event={}, total={}",
                        receipt.eventCoordinates(),
                        receiptIndex.size()
                    );
                }
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Build bloom filter of all known receipts for anti-entropy.
     * <p>
     * Bloom filter can be sent to peers to identify missing receipts.
     *
     * @param seed Random seed for bloom filter hash functions
     * @return Bloom filter proto (Biff) containing all known receipt digests
     */
    public Biff buildBloomFilter(long seed) {
        lock.readLock().lock();
        try {
            var n = Math.max(minimumCardinality, receiptIndex.size());
            var bff = new BloomFilter.DigestBloomFilter(seed, n, falsePositiveRate);

            receiptIndex.keySet().forEach(bff::add);

            bloomFiltersBuilt++;

            if (log.isTraceEnabled()) {
                log.trace("Built bloom filter: receipts={}, seed={}",
                    receiptIndex.size(),
                    seed
                );
            }

            return bff.toBff();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Identify receipts the peer is missing based on their bloom filter.
     * <p>
     * Compares peer's bloom filter against local receipts to find which
     * receipts we have that the peer likely doesn't have.
     * <p>
     * Note: Due to bloom filter false positives, may occasionally skip
     * receipts the peer actually needs. Subsequent gossip rounds will catch these.
     *
     * @param peerBloomFilter Peer's bloom filter (from Digests.receiptBff)
     * @param maxReceipts     Maximum number of receipts to return
     * @return List of receipts peer is likely missing (up to maxReceipts)
     * @throws NullPointerException if peerBloomFilter is null
     */
    public List<GossipableReceipt> identifyMissingReceipts(Biff peerBloomFilter, int maxReceipts) {
        Objects.requireNonNull(peerBloomFilter, "peerBloomFilter required");

        if (maxReceipts <= 0) {
            return List.of();
        }

        lock.readLock().lock();
        try {
            var peerBff = BloomFilter.<Digest>from(peerBloomFilter);
            var missing = new ArrayList<GossipableReceipt>(Math.min(maxReceipts, receiptIndex.size()));

            for (var entry : receiptIndex.entrySet()) {
                if (missing.size() >= maxReceipts) {
                    break;
                }

                var digest = entry.getKey();
                var receipt = entry.getValue();

                // If peer's bloom filter doesn't contain this digest, they're missing it
                if (!peerBff.contains(digest)) {
                    missing.add(receipt);
                    missingReceiptsIdentified++;
                }
            }

            if (log.isDebugEnabled() && !missing.isEmpty()) {
                log.debug("Identified {} missing receipts for peer (max: {}, total: {})",
                    missing.size(),
                    maxReceipts,
                    receiptIndex.size()
                );
            }

            return missing;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Build gossip response with receipts the peer needs.
     * <p>
     * Uses peer's bloom filter to identify missing receipts and builds
     * a ReceiptGossip message containing them.
     *
     * @param peerBloomFilter Peer's bloom filter
     * @param maxReceipts     Maximum receipts to include
     * @param seed            Random seed for our bloom filter
     * @return ReceiptGossip with missing receipts and our bloom filter
     * @throws NullPointerException if peerBloomFilter is null
     */
    public ReceiptGossip buildGossipResponse(Biff peerBloomFilter, int maxReceipts, long seed) {
        var missingReceipts = identifyMissingReceipts(peerBloomFilter, maxReceipts);
        var knownDigests = getKnownDigests();

        return ReceiptGossipCodec.toReceiptGossip(
            missingReceipts,
            knownDigests,
            seed,
            falsePositiveRate,
            digestAlgorithm
        );
    }

    /**
     * Get set of all known receipt digests.
     *
     * @return Immutable set of receipt digests
     */
    public Set<Digest> getKnownDigests() {
        lock.readLock().lock();
        try {
            return Set.copyOf(receiptIndex.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get current count of indexed receipts.
     *
     * @return Number of receipts in the index
     */
    public int getReceiptCount() {
        return receiptIndex.size();
    }

    /**
     * Get total receipts added since creation.
     *
     * @return Count of receipts added
     */
    public long getReceiptsAdded() {
        return receiptsAdded;
    }

    /**
     * Get total receipts removed since creation.
     *
     * @return Count of receipts removed
     */
    public long getReceiptsRemoved() {
        return receiptsRemoved;
    }

    /**
     * Get total bloom filters built since creation.
     *
     * @return Count of bloom filters built
     */
    public long getBloomFiltersBuilt() {
        return bloomFiltersBuilt;
    }

    /**
     * Get total missing receipts identified since creation.
     *
     * @return Count of missing receipts identified
     */
    public long getMissingReceiptsIdentified() {
        return missingReceiptsIdentified;
    }

    /**
     * Reset metrics counters (for testing).
     */
    public void resetMetrics() {
        receiptsAdded = 0;
        receiptsRemoved = 0;
        bloomFiltersBuilt = 0;
        missingReceiptsIdentified = 0;
    }

    /**
     * Clear all receipts from the index.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            receiptIndex.clear();
            if (log.isDebugEnabled()) {
                log.debug("Cleared anti-entropy receipt index");
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
