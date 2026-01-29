/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.demo.simulation.verification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Verification check for Fireflies membership consistency across nodes.
 * <p>
 * Verifies that all nodes have a consistent view of the membership:
 * <ul>
 *   <li>Same set of active members</li>
 *   <li>No membership view divergence (partition detection)</li>
 *   <li>Ring consistency</li>
 * </ul>
 * <p>
 * Divergence indicates a network partition or gossip failure. This check queries
 * each node's Fireflies View to compare membership sets.
 *
 * @author hal.hildebrand
 */
public class MembershipVerifier implements VerificationCheck {
    private static final Logger log = LoggerFactory.getLogger(MembershipVerifier.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private final List<NodeMembershipProvider> nodeProviders;
    private final Duration timeout;

    /**
     * Create a membership verifier.
     *
     * @param nodeProviders providers for accessing membership state from each node
     */
    public MembershipVerifier(List<NodeMembershipProvider> nodeProviders) {
        this(nodeProviders, DEFAULT_TIMEOUT);
    }

    /**
     * Create a membership verifier with custom timeout.
     *
     * @param nodeProviders providers for accessing membership state from each node
     * @param timeout       timeout for verification
     */
    public MembershipVerifier(List<NodeMembershipProvider> nodeProviders, Duration timeout) {
        this.nodeProviders = nodeProviders;
        this.timeout = timeout;
    }

    @Override
    public String getName() {
        return "Membership";
    }

    @Override
    public VerificationResult execute() {
        var start = Instant.now();
        log.debug("Executing membership verification across {} nodes", nodeProviders.size());

        try {
            // Query membership from all nodes
            var membershipViews = new HashMap<String, Set<String>>();

            for (var provider : nodeProviders) {
                try {
                    var members = provider.getActiveMemberIds();
                    membershipViews.put(provider.getNodeId(), members);
                    log.debug("Node {}: {} active members", provider.getNodeId(), members.size());
                } catch (Exception e) {
                    log.error("Failed to query membership from node {}", provider.getNodeId(), e);
                    var elapsed = Duration.between(start, Instant.now());
                    return VerificationResult.error(getName(), elapsed, e);
                }
            }

            // Verify membership consistency
            var result = verifyMembershipConsistency(membershipViews);
            var elapsed = Duration.between(start, Instant.now());

            if (result.isConsistent()) {
                log.debug("Membership verification passed: all {} nodes see {} members",
                         nodeProviders.size(), result.consensusMemberCount());
                return VerificationResult.success(getName(), elapsed, result.details());
            } else {
                var message = String.format("Membership divergence detected: %s", result.divergenceReason());
                log.error(message);
                return VerificationResult.failure(getName(), elapsed, message, result.details());
            }

        } catch (Exception e) {
            log.error("Membership verification failed", e);
            var elapsed = Duration.between(start, Instant.now());
            return VerificationResult.error(getName(), elapsed, e);
        }
    }

    @Override
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * Verify membership consistency across nodes.
     */
    private MembershipResult verifyMembershipConsistency(Map<String, Set<String>> membershipViews) {
        var details = new ArrayList<String>();

        // Check if all nodes see the same membership set
        var uniqueViews = membershipViews.values().stream()
                                        .map(HashSet::new) // Copy to hashable set
                                        .distinct()
                                        .count();

        if (uniqueViews != 1) {
            // Detect partitions - group nodes by their membership view
            var partitions = detectPartitions(membershipViews);

            partitions.forEach((viewSignature, nodes) -> {
                var viewMembers = membershipViews.get(nodes.iterator().next());
                details.add(String.format("Partition (view %s): %d nodes see %d members",
                                         viewSignature, nodes.size(), viewMembers.size()));
                details.add(String.format("  Nodes in this partition: %s", nodes));
                details.add(String.format("  Members seen: %s", viewMembers));
            });

            return new MembershipResult(
                false,
                -1,
                String.format("%d different membership views detected (potential partition)", uniqueViews),
                details
            );
        }

        // All nodes see the same membership
        var consensusView = membershipViews.values().iterator().next();
        details.add(String.format("All %d nodes see consistent membership of %d members",
                                 membershipViews.size(), consensusView.size()));
        details.add(String.format("Member set: %s", consensusView));

        return new MembershipResult(true, consensusView.size(), null, details);
    }

    /**
     * Detect partitions by grouping nodes with identical membership views.
     * <p>
     * Returns a map of view signature to set of nodes sharing that view.
     */
    private Map<String, Set<String>> detectPartitions(Map<String, Set<String>> membershipViews) {
        var partitions = new HashMap<String, Set<String>>();

        membershipViews.forEach((nodeId, members) -> {
            // Create signature for this view (sorted member list)
            var sortedMembers = new TreeSet<>(members);
            var viewSignature = String.join(",", sortedMembers);

            partitions.computeIfAbsent(viewSignature, k -> new HashSet<>()).add(nodeId);
        });

        return partitions;
    }

    /**
     * Result of membership verification.
     */
    private record MembershipResult(
        boolean isConsistent,
        int consensusMemberCount,
        String divergenceReason,
        List<String> details
    ) {
    }

    /**
     * Provider interface for accessing membership state from a node.
     * <p>
     * Implementations query Fireflies View for active member sets.
     */
    public interface NodeMembershipProvider {
        /**
         * Get the node identifier.
         */
        String getNodeId();

        /**
         * Get the set of active member IDs as seen by this node.
         */
        Set<String> getActiveMemberIds();
    }
}
