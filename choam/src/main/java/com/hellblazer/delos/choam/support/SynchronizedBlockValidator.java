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
import com.hellblazer.delos.choam.proto.Block;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
import com.hellblazer.delos.choam.proto.Header;
import com.hellblazer.delos.cryptography.Digest;
import org.joou.ULong;
import org.slf4j.Logger;

import static com.hellblazer.delos.cryptography.QualifiedBase64.digest;

/**
 * Validates synchronized blocks during state synchronization and recovery. Ensures blocks maintain
 * proper height sequencing, hash linkage, and committee signatures before deferring them to the
 * pending queue for processing.
 * <p>
 * Thread Safety: This class is thread-safe. All state access goes through holder classes that
 * provide their own synchronization guarantees.
 *
 * @author hal.hildebrand
 */
public class SynchronizedBlockValidator {
    private final ControlStateHolder         controlState;
    private final BlockChainStateHolder      blockChainState;
    private final CommitteeStateHolder       committeeState;
    private final Parameters.RuntimeParameters params;
    private final Combine.Transitions        transitions;
    private final Logger                     log;
    private final com.hellblazer.delos.cryptography.DigestAlgorithm digestAlgorithm;

    /**
     * Constructs a SynchronizedBlockValidator with required dependencies.
     *
     * @param controlState    holder for control state (started/stopped)
     * @param blockChainState holder for blockchain head/view state
     * @param committeeState  holder for current committee
     * @param params          runtime parameters (member ID, algorithms)
     * @param transitions     FSM transitions for error handling
     * @param log             logger instance
     * @param digestAlgorithm digest algorithm for hashing blocks
     */
    public SynchronizedBlockValidator(ControlStateHolder controlState, BlockChainStateHolder blockChainState,
                                      CommitteeStateHolder committeeState, Parameters.RuntimeParameters params,
                                      Combine.Transitions transitions, Logger log,
                                      com.hellblazer.delos.cryptography.DigestAlgorithm digestAlgorithm) {
        this.controlState = controlState;
        this.blockChainState = blockChainState;
        this.committeeState = committeeState;
        this.params = params;
        this.transitions = transitions;
        this.log = log;
        this.digestAlgorithm = digestAlgorithm;
    }

    /**
     * Processes a synchronized block received during state synchronization. Validates height continuity,
     * hash linkage, and committee signatures before adding to the pending queue.
     * <p>
     * Validation checks:
     * <ul>
     *   <li>Node must be started</li>
     *   <li>Block height must be sequential (current height + 1)</li>
     *   <li>Previous hash must match current head</li>
     *   <li>Committee signatures must validate (or genesis regeneration for height 0)</li>
     * </ul>
     * <p>
     * Blocks that pass validation are deferred to the pending queue for processing by the block
     * processor. Invalid blocks are rejected with error logging.
     *
     * @param certifiedBlock the certified block to validate and process
     */
    public void processSynchronizedBlock(CertifiedBlock certifiedBlock) {
        if (!controlState.isStarted()) {
            log.info("Not started on: {}", params.member().getId());
            return;
        }
        HashedCertifiedBlock hcb = new HashedCertifiedBlock(digestAlgorithm, certifiedBlock);
        Block block = hcb.block;
        log.info("Synchronizing block: {}:{} height: {} on: {}", hcb.hash, block.getBodyCase(), hcb.height(),
                 params.member().getId());
        final HashedCertifiedBlock previousBlock = blockChainState.getHead();
        Header header = block.getHeader();
        if (previousBlock != null) {
            Digest prev = digest(header.getPrevious());
            ULong prevHeight = previousBlock.height();
            if (prevHeight == null) {
                if (!hcb.height().equals(ULong.valueOf(0))) {
                    if (!blockChainState.addPending(hcb)) {
                        log.warn("Rejected pending block: {} hash: {} height: {} on: {}", hcb.block.getBodyCase(),
                                 hcb.hash, hcb.height(), params.member().getId());
                    }
                    log.debug("Deferring block: {} hash: {} height should be {} and block height is {} on: {}",
                              hcb.block.getBodyCase(), hcb.hash, 0, header.getHeight(), params.member().getId());
                    return;
                }
            } else {
                if (hcb.height().compareTo(prevHeight) <= 0) {
                    log.trace("Discarding previously committed block: {} height: {} current height: {} on: {}",
                              hcb.hash, hcb.height(), prevHeight, params.member().getId());
                    if (!blockChainState.addPending(hcb)) {
                        log.warn("Rejected pending block: {} hash: {} height: {} on: {}", hcb.block.getBodyCase(),
                                 hcb.hash, hcb.height(), params.member().getId());
                    }
                    return;
                }
                if (!hcb.height().equals(prevHeight.add(1))) {
                    if (!blockChainState.addPending(hcb)) {
                        log.warn("Rejected pending block: {} hash: {} height: {} on: {}", hcb.block.getBodyCase(),
                                 hcb.hash, hcb.height(), params.member().getId());
                    }
                    log.debug("Deferring block: {} hash: {} height should be {} and block height is {} on: {}",
                              hcb.block.getBodyCase(), hcb.hash, previousBlock.height().add(1), header.getHeight(),
                              params.member().getId());
                    return;
                }
            }
            if (!previousBlock.hash.equals(prev)) {
                log.error(
                "Protocol violation on: {}. New block does not refer to current block hash. Should be: {} and next block's prev is: {}, current height: {} next height: {} on: {}",
                params.member().getId(), previousBlock.hash, prev, prevHeight, hcb.height(), params.member().getId());
                return;
            }
            final var c = committeeState.getCommittee();
            if (c == null) {
                log.error("No committee for synchronized process on: {}", params.member().getId());
                transitions.fail();
                return;
            }
            if (!c.validate(hcb)) {
                log.error("Protocol violation. New block is not validated: {} hash: {} on: {}", hcb.block.getBodyCase(),
                          hcb.hash, params.member().getId());
                return;
            }
        } else {
            if (!block.hasGenesis()) {
                if (!blockChainState.addPending(hcb)) {
                    log.warn("Rejected pending block: {} hash: {} height: {} on: {}", hcb.block.getBodyCase(), hcb.hash,
                             hcb.height(), params.member().getId());
                }
                log.info("Deferring block on: {}.  Block: {} hash: {} height should be {} and block height is {}",
                         params.member().getId(), hcb.block.getBodyCase(), hcb.hash, 0, header.getHeight());
                return;
            }
            final var c = committeeState.getCommittee();
            if (c == null) {
                log.error("No committee for genesis block validation on: {}", params.member().getId());
                transitions.fail();
                return;
            }
            if (!c.validateRegeneration(hcb)) {
                log.error("Protocol violation. Genesis block is not validated: {} hash {} on: {}",
                          hcb.block.getBodyCase(), hcb.hash, params.member().getId());
                return;
            }
        }
        log.info("Deferring block on: {}. Block: {} hash: {} height is {}", params.member().getId(),
                 hcb.block.getBodyCase(), hcb.hash, header.getHeight());
        if (!blockChainState.addPending(hcb)) {
            log.warn("Rejected pending block: {} hash: {} height: {} on: {}", hcb.block.getBodyCase(), hcb.hash,
                     hcb.height(), params.member().getId());
        }
    }
}
