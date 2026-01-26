/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.verification;

import com.hellblazer.delos.cryptography.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verification check for CHOAM block consensus across replicas.
 * <p>
 * Verifies that all nodes agree on:
 * <ul>
 *   <li>Current block height (checkpoint height)</li>
 *   <li>Block hashes at the same height</li>
 * </ul>
 * <p>
 * Divergence indicates a consensus failure or partition. This check queries
 * each node's CHOAM checkpoint state and compares block heights and hashes.
 *
 * @author hal.hildebrand
 */
public class BlockConsensusVerifier implements VerificationCheck {
    private static final Logger log = LoggerFactory.getLogger(BlockConsensusVerifier.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private final List<NodeConsensusProvider> nodeProviders;
    private final Duration timeout;

    /**
     * Create a block consensus verifier.
     *
     * @param nodeProviders providers for accessing consensus state from each node
     */
    public BlockConsensusVerifier(List<NodeConsensusProvider> nodeProviders) {
        this(nodeProviders, DEFAULT_TIMEOUT);
    }

    /**
     * Create a block consensus verifier with custom timeout.
     *
     * @param nodeProviders providers for accessing consensus state from each node
     * @param timeout       timeout for verification
     */
    public BlockConsensusVerifier(List<NodeConsensusProvider> nodeProviders, Duration timeout) {
        this.nodeProviders = nodeProviders;
        this.timeout = timeout;
    }

    @Override
    public String getName() {
        return "BlockConsensus";
    }

    @Override
    public VerificationResult execute() {
        var start = Instant.now();
        log.debug("Executing block consensus verification across {} nodes", nodeProviders.size());

        try {
            // Query block heights from all nodes
            var blockHeights = new HashMap<String, Long>();
            var blockHashes = new HashMap<String, Map<Long, Digest>>();

            for (var provider : nodeProviders) {
                try {
                    var height = provider.getCurrentBlockHeight();
                    var hash = provider.getBlockHashAtHeight(height);

                    blockHeights.put(provider.getNodeId(), height);
                    blockHashes.computeIfAbsent(provider.getNodeId(), k -> new HashMap<>()).put(height, hash);

                    log.debug("Node {}: height={}, hash={}", provider.getNodeId(), height, hash);
                } catch (Exception e) {
                    log.error("Failed to query block state from node {}", provider.getNodeId(), e);
                    var elapsed = Duration.between(start, Instant.now());
                    return VerificationResult.error(getName(), elapsed, e);
                }
            }

            // Verify consensus
            var result = verifyBlockConsensus(blockHeights, blockHashes);
            var elapsed = Duration.between(start, Instant.now());

            if (result.isConsistent()) {
                log.debug("Block consensus verification passed: all {} nodes at height {}",
                         nodeProviders.size(), result.consensusHeight());
                return VerificationResult.success(getName(), elapsed, result.details());
            } else {
                var message = String.format("Block consensus divergence detected: %s", result.divergenceReason());
                log.error(message);
                return VerificationResult.failure(getName(), elapsed, message, result.details());
            }

        } catch (Exception e) {
            log.error("Block consensus verification failed", e);
            var elapsed = Duration.between(start, Instant.now());
            return VerificationResult.error(getName(), elapsed, e);
        }
    }

    @Override
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * Verify block consensus across nodes.
     */
    private ConsensusResult verifyBlockConsensus(Map<String, Long> blockHeights,
                                                  Map<String, Map<Long, Digest>> blockHashes) {
        var details = new ArrayList<String>();

        // Check if all nodes have the same block height
        var uniqueHeights = blockHeights.values().stream().distinct().count();
        if (uniqueHeights != 1) {
            blockHeights.forEach((nodeId, height) ->
                                     details.add(String.format("Node %s: height %d", nodeId, height)));

            return new ConsensusResult(
                false,
                -1,
                "Block height divergence: " + uniqueHeights + " different heights across " + blockHeights.size() + " nodes",
                details
            );
        }

        // All nodes at same height - now check hashes
        var consensusHeight = blockHeights.values().iterator().next();
        var hashesAtHeight = new HashMap<String, Digest>();

        blockHashes.forEach((nodeId, hashes) -> {
            var hash = hashes.get(consensusHeight);
            if (hash != null) {
                hashesAtHeight.put(nodeId, hash);
            }
        });

        var uniqueHashes = hashesAtHeight.values().stream().distinct().count();
        if (uniqueHashes != 1) {
            hashesAtHeight.forEach((nodeId, hash) ->
                                       details.add(String.format("Node %s at height %d: hash %s",
                                                                nodeId, consensusHeight, hash)));

            return new ConsensusResult(
                false,
                consensusHeight,
                "Block hash divergence at height " + consensusHeight + ": " + uniqueHashes + " different hashes",
                details
            );
        }

        // All nodes agree on height and hash
        var consensusHash = hashesAtHeight.values().iterator().next();
        details.add(String.format("All %d nodes at height %d with hash %s",
                                 blockHeights.size(), consensusHeight, consensusHash));

        return new ConsensusResult(true, consensusHeight, null, details);
    }

    /**
     * Result of consensus verification.
     */
    private record ConsensusResult(
        boolean isConsistent,
        long consensusHeight,
        String divergenceReason,
        List<String> details
    ) {
    }

    /**
     * Provider interface for accessing consensus state from a node.
     * <p>
     * Implementations query CHOAM checkpoint state via metrics or gRPC.
     */
    public interface NodeConsensusProvider {
        /**
         * Get the node identifier.
         */
        String getNodeId();

        /**
         * Get the current block height (checkpoint height) for this node.
         */
        long getCurrentBlockHeight();

        /**
         * Get the block hash at the specified height.
         */
        Digest getBlockHashAtHeight(long height);
    }
}
