/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.support.BoundedPriorityBlockingQueue;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock.NullBlock;
import com.hellblazer.delos.cryptography.DigestAlgorithm;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages CHOAM blockchain state (head, genesis, view blocks, pending queue).
 * <p>
 * This holder encapsulates blockchain state that was previously embedded in CHOAM.java.
 * It provides lock-protected state management for blockchain operations and exposes
 * the headLock for external caller coordination.
 * </p>
 * <p>
 * <b>Invariants:</b>
 * <ul>
 *   <li>Genesis immutability: genesis != null ⇒ genesis.height() == 0</li>
 *   <li>Head monotonicity: head != null ⇒ head.height() >= 0 (monotonic increase)</li>
 *   <li>View lags head: view != null ⇒ view.height() <= head.height()</li>
 *   <li>Pending bounded: pending.size() <= maxPendingBlocks (DoS prevention)</li>
 * </ul>
 * </p>
 * <p>
 * <b>Thread Safety:</b> Block references use AtomicReference. Multi-step operations
 * require callers to hold headLock appropriately (write lock for mutations, read lock for reads).
 * </p>
 * <p>
 * <b>Lock Ownership:</b> This holder owns the headLock. Callers MUST hold the appropriate
 * lock (read or write) when performing operations that span multiple state accesses.
 * </p>
 * <p>
 * Extracted as part of CHOAMStateManager extraction plan (Phase 4, Bead: Delos-cr7n).
 * See choam/BASELINE.md and choam/LOCK_ORDERING.md for context.
 * </p>
 *
 * @author hal.hildebrand
 */
public class BlockChainStateHolder {

    /**
     * Genesis block: first block in the chain, immutable after first set.
     */
    private final AtomicReference<HashedCertifiedBlock> genesis = new AtomicReference<>();

    /**
     * Head block: current tip of the blockchain, monotonically increasing height.
     */
    private final AtomicReference<HashedCertifiedBlock> head = new AtomicReference<>();

    /**
     * View block: checkpoint block for views, lags behind head.
     */
    private final AtomicReference<HashedCertifiedBlock> view = new AtomicReference<>();

    /**
     * Pending block queue: blocks waiting to be processed, bounded for DoS protection.
     */
    private final BoundedPriorityBlockingQueue<HashedCertifiedBlock> pending;

    /**
     * Head lock: protects multi-step blockchain operations.
     * Exposed publicly so callers can coordinate complex operations.
     * <p>
     * <b>CRITICAL:</b> This lock MUST remain disjoint from viewStateLock.
     * Never hold both locks simultaneously to prevent deadlock.
     * </p>
     */
    public final ReadWriteLock headLock = new ReentrantReadWriteLock();

    /**
     * Create a new BlockChainStateHolder with bounded pending queue.
     *
     * @param digestAlgorithm digest algorithm for NullBlock sentinels
     * @param maxPendingBlocks maximum number of blocks in pending queue
     */
    public BlockChainStateHolder(DigestAlgorithm digestAlgorithm, int maxPendingBlocks) {
        // Initialize head and view to NullBlock sentinels (height() returns null)
        this.head.set(new NullBlock(digestAlgorithm));
        this.view.set(new NullBlock(digestAlgorithm));

        this.pending = new BoundedPriorityBlockingQueue<>(maxPendingBlocks,
                                                           (a, b) -> Long.compare(a.height().longValue(),
                                                                                  b.height().longValue()));
    }

    /**
     * Get the genesis block.
     *
     * @return the genesis block, or null if not set
     */
    public HashedCertifiedBlock getGenesis() {
        return genesis.get();
    }

    /**
     * Set the genesis block (immutable - can only be set once).
     * <p>
     * Once genesis is set, it cannot be changed. Attempting to set it again
     * will throw IllegalStateException.
     * </p>
     *
     * @param block the genesis block
     * @throws IllegalStateException if genesis is already set
     */
    public void setGenesis(HashedCertifiedBlock block) {
        if (!genesis.compareAndSet(null, block)) {
            throw new IllegalStateException("Genesis block already set");
        }
    }

    /**
     * Get the current head block.
     *
     * @return the head block, or null if not set
     */
    public HashedCertifiedBlock getHead() {
        return head.get();
    }

    /**
     * Get direct access to the head AtomicReference for legacy compatibility.
     * <p>
     * <b>Use with caution:</b> Direct manipulation bypasses holder semantics.
     * Prefer getHead()/setHead() methods for normal operations.
     * </p>
     *
     * @return the underlying AtomicReference for head block
     */
    public AtomicReference<HashedCertifiedBlock> getHeadRef() {
        return head;
    }

    /**
     * Set the head block.
     * <p>
     * <b>Caller responsibility:</b> Should hold headLock.writeLock() during
     * multi-step operations involving head updates.
     * </p>
     *
     * @param block the new head block (may be null)
     */
    public void setHead(HashedCertifiedBlock block) {
        head.set(block);
    }

    /**
     * Get the current view block.
     *
     * @return the view block, or null if not set
     */
    public HashedCertifiedBlock getView() {
        return view.get();
    }

    /**
     * Set the view block.
     * <p>
     * <b>Caller responsibility:</b> Should hold headLock.writeLock() during
     * multi-step operations involving view updates.
     * </p>
     *
     * @param block the new view block (may be null)
     */
    public void setView(HashedCertifiedBlock block) {
        view.set(block);
    }

    /**
     * Add a block to the pending queue (non-blocking).
     *
     * @param block the block to add
     * @return true if added, false if queue is full
     */
    public boolean addPending(HashedCertifiedBlock block) {
        return pending.offer(block);
    }

    /**
     * Take a block from the pending queue (blocking).
     * <p>
     * Blocks until a block is available or timeout expires.
     * </p>
     *
     * @return the next block from the queue, or null if timeout expires
     * @throws InterruptedException if interrupted while waiting
     */
    public HashedCertifiedBlock takePending() throws InterruptedException {
        return pending.poll(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    /**
     * Poll a block from the pending queue (non-blocking).
     *
     * @return the next block, or null if queue is empty
     */
    public HashedCertifiedBlock pollPending() {
        return pending.poll();
    }

    /**
     * Get the number of blocks in the pending queue.
     *
     * @return pending queue size
     */
    public int getPendingSize() {
        return pending.size();
    }

    /**
     * Get direct access to the pending queue for legacy compatibility.
     * <p>
     * <b>Use with caution:</b> Direct manipulation bypasses holder semantics.
     * Prefer addPending()/pollPending()/takePending() methods for normal operations.
     * </p>
     *
     * @return the underlying BoundedPriorityBlockingQueue
     */
    public BoundedPriorityBlockingQueue<HashedCertifiedBlock> getPendingQueue() {
        return pending;
    }

    /**
     * Get a human-readable representation of the blockchain state.
     *
     * @return string representation showing genesis, head, view, and pending count
     */
    @Override
    public String toString() {
        var g = genesis.get();
        var h = head.get();
        var v = view.get();
        return String.format("BlockChainStateHolder{genesis=%s, head=%s, view=%s, pending=%d}",
                             g == null ? "null" : "height=" + g.height(),
                             h == null ? "null" : "height=" + h.height(),
                             v == null ? "null" : "height=" + v.height(), pending.size());
    }
}
