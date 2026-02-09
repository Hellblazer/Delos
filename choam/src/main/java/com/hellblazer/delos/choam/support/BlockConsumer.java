/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.Committee;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.fsm.Combine;
import org.joou.ULong;
import org.slf4j.Logger;

import java.util.function.Consumer;

/**
 * Handles block consumption logic for CHOAM consensus. Validates blocks against the current chain state
 * and either accepts them, defers them to pending queue, or rejects them based on height and view
 * synchronization.
 * <p>
 * Thread Safety: This class is NOT thread-safe. Callers must ensure external synchronization (e.g.,
 * CHOAM's headLock) when invoking consume methods. All state is accessed via holder classes that
 * provide their own atomicity guarantees, but consistent read snapshots require external locking.
 *
 * @author hal.hildebrand
 */
public class BlockConsumer {
    private final BlockChainStateHolder      blockChainState;
    private final CommitteeStateHolder       committeeState;
    private final Parameters.RuntimeParameters params;
    private final Combine.Transitions        transitions;
    private final Logger                     log;

    /**
     * Constructs a BlockConsumer with required dependencies.
     *
     * @param blockChainState holder for blockchain head/view state
     * @param committeeState  holder for current committee
     * @param params          runtime parameters (member ID, algorithms)
     * @param transitions     FSM transitions for error handling
     * @param log             logger instance
     */
    public BlockConsumer(BlockChainStateHolder blockChainState, CommitteeStateHolder committeeState,
                         Parameters.RuntimeParameters params, Combine.Transitions transitions, Logger log) {
        this.blockChainState = blockChainState;
        this.committeeState = committeeState;
        this.params = params;
        this.transitions = transitions;
        this.log = log;
    }

    /**
     * Attempts to consume a certified block, validating view synchronization before acceptance.
     * This method checks if the block's view (last reconfig height) matches the current view,
     * and either consumes it immediately or defers to pending queue.
     * <p>
     * Precondition: Caller must hold CHOAM's headLock.writeLock() for the duration of this call.
     *
     * @param next         the block to consume
     * @param acceptBlock  callback to accept the block if validation succeeds
     * @param isNextBlock  predicate to check if block is the next expected height
     */
    public void consume(HashedCertifiedBlock next, Consumer<HashedCertifiedBlock> acceptBlock,
                        java.util.function.Predicate<HashedBlock> isNextBlock) {
        log.trace("Attempting to consume: {} hash: {} height: {}, head: {} height: {} on: {}",
                  next.block.getBodyCase(), next.hash, next.height(), blockChainState.getHead().hash,
                  blockChainState.getHead().height(), params.member().getId());
        final HashedCertifiedBlock h = blockChainState.getHead();

        if (h.height() != null && next.height().compareTo(h.height()) <= 0) {
            // block already past tense
            log.debug("Stale: {} hash: {} height: {} on: {}", next.block.getBodyCase(), next.hash, next.height(),
                      params.member().getId());
            return;
        }

        final var nlc = ULong.valueOf(next.block.getHeader().getLastReconfig());

        var view = this.blockChainState.getView().height();
        if (h.block == null || nlc.equals(view)) {
            // same view
            consumeWithValidation(next, h, acceptBlock, isNextBlock);
            return;
        }

        if (view != null && nlc.compareTo(view) > 0) {
            // later view
            log.trace("Wait for reconfiguration @ {} block: {} hash: {} height: {} current: {} on: {}",
                      next.block.getHeader().getLastReconfig(), next.block.getBodyCase(), next.hash, next.height(),
                      h.height(), params.member().getId());
            if (!blockChainState.addPending(next)) {
                log.warn("Rejected pending block: {} hash: {} height: {} on: {}", next.block.getBodyCase(), next.hash,
                         next.height(), params.member().getId());
            }
        } else {
            // invalid view
            log.trace("Invalid view @ {} current: {} block: {} hash: {} height: {} current: {} on: {}", nlc, view,
                      next.block.getBodyCase(), next.hash, next.height(), h.height(), params.member().getId());
        }
    }

    /**
     * Consumes a block with full validation against the current head. Checks block height sequencing,
     * previous hash linkage, and committee signatures before acceptance.
     * <p>
     * Precondition: Caller must hold CHOAM's headLock.writeLock() for the duration of this call.
     *
     * @param next         the block to consume
     * @param cur          the current head block for comparison
     * @param acceptBlock  callback to accept the block if validation succeeds
     * @param isNextBlock  predicate to check if block is the next expected height
     */
    public void consumeWithValidation(HashedCertifiedBlock next, HashedCertifiedBlock cur,
                                      Consumer<HashedCertifiedBlock> acceptBlock,
                                      java.util.function.Predicate<HashedBlock> isNextBlock) {
        if (next == null) {
            return;
        }
        final var h = blockChainState.getHead();
        if (isNextBlock.test(next)) {
            if (!h.hash.equals(next.getPrevious())) {
                log.debug("Invalid previous: {} expecting: {} block: {} hash: {} height: {} on: {}", next.getPrevious(),
                          h.hash, next.block.getBodyCase(), next.hash, next.height(), params.member().getId());
            } else {
                final Committee c = committeeState.getCommittee();
                if (c == null) {
                    log.error("No committee to validate block: {} hash: {} height: {} on: {}",
                              next.block.getBodyCase(), next.hash, next.height(), params.member().getId());
                    transitions.fail();
                    return;
                }
                if (c.validate(next)) {
                    log.trace("Accept: {} hash: {} height: {} on: {}", next.block.getBodyCase(), next.hash,
                              next.height(), params.member().getId());
                    acceptBlock.accept(next);
                } else {
                    log.debug("Invalid block: {} hash: {} height: {} on: {}", next.block.getBodyCase(), next.hash,
                              next.height(), params.member().getId());
                }
            }
        } else if (h.height() != null && h.height().compareTo(next.height()) < 0) {
            log.trace("Premature block: {} : {} height: {} current: {} on: {}", next.block.getBodyCase(), next.hash,
                      next.height(), cur.height(), params.member().getId());
            if (!blockChainState.addPending(next)) {
                log.warn("Rejected pending block: {} hash: {} height: {} on: {}", next.block.getBodyCase(), next.hash,
                         next.height(), params.member().getId());
            }
        } else {
            log.trace("Stale block: {} : {} height: {} current: {} on: {}", next.block.getBodyCase(), next.hash,
                      next.height(), cur.height(), params.member().getId());
        }
    }
}
