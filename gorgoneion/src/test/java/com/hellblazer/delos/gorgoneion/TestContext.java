/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.gorgoneion;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages multi-member test context with automatic resource cleanup.
 * Provides access to members, quorum calculations, and BFT subset operations.
 * <p>
 * Usage:
 * <pre>{@code
 * try (var ctx = new TestContext(7, deterministicEntropy())) {
 *     var quorum = ctx.getQuorum();
 *     var bftSubset = ctx.getBftSubset(clientDigest);
 *     // ... test operations
 * } // Auto cleanup on close
 * }</pre>
 *
 * @author hal.hildebrand
 */
public class TestContext implements AutoCloseable {
    private final List<ControlledIdentifierMember> members;
    private final DynamicContext<Member>            context;
    private final SecureRandom                      entropy;
    private final MemKERL                           kerl;
    private final StereotomyImpl                    stereotomy;

    /**
     * Creates a test context with the specified number of members.
     *
     * @param cardinality number of members in context
     * @param entropy     secure random for deterministic member creation
     * @throws Exception if member creation fails
     */
    public TestContext(int cardinality, SecureRandom entropy) throws Exception {
        this.entropy = entropy;
        this.kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        this.stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        this.members = new ArrayList<>();

        // Create dynamic context
        var builder = DynamicContext.<Member>newBuilder();
        builder.setCardinality(cardinality);
        this.context = builder.build();

        // Create and activate members
        for (int i = 0; i < cardinality; i++) {
            var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
            members.add(member);
            context.activate(member);
        }
    }

    /**
     * Gets all members in the context.
     *
     * @return unmodifiable list of members
     */
    public List<ControlledIdentifierMember> getMembers() {
        return List.copyOf(members);
    }

    /**
     * Gets the underlying dynamic context.
     *
     * @return the context instance
     */
    public DynamicContext<Member> getContext() {
        return context;
    }

    /**
     * Gets a specific member by index.
     *
     * @param index member index (0-based)
     * @return the member at that index
     */
    public ControlledIdentifierMember getMember(int index) {
        return members.get(index);
    }

    /**
     * Gets the total number of members.
     *
     * @return member count
     */
    public int getMemberCount() {
        return members.size();
    }

    /**
     * Gets the quorum size (majority).
     *
     * @return quorum size (n/2 + 1)
     */
    public int getQuorum() {
        return context.majority();
    }

    /**
     * Gets the Byzantine fault tolerance level.
     *
     * @return maximum number of Byzantine failures tolerated
     */
    public int getFaultTolerance() {
        return (members.size() - 1) / 3;
    }

    /**
     * Gets the member ID digest at the specified index.
     *
     * @param index member index
     * @return member ID digest
     */
    public Digest getMemberId(int index) {
        return members.get(index).getId();
    }

    /**
     * Computes the BFT subset for a given client digest.
     * Uses deterministic subset computation from context.
     *
     * @param clientDigest the client's identifier digest
     * @return set of members in the BFT subset
     */
    public Set<ControlledIdentifierMember> getBftSubset(Digest clientDigest) {
        return context.bftSubset(clientDigest)
                      .stream()
                      .map(m -> (ControlledIdentifierMember) m)
                      .collect(Collectors.toSet());
    }

    /**
     * Gets the BFT subset coordinator (first member in subset).
     *
     * @param clientDigest the client's identifier digest
     * @return the coordinator member
     */
    public ControlledIdentifierMember getCoordinator(Digest clientDigest) {
        var subset = context.bftSubset(clientDigest);
        return (ControlledIdentifierMember) subset.iterator().next();
    }

    /**
     * Gets the secure random instance.
     *
     * @return the entropy source
     */
    public SecureRandom getEntropy() {
        return entropy;
    }

    /**
     * Gets the KERL instance.
     *
     * @return the key event receipt log
     */
    public MemKERL getKerl() {
        return kerl;
    }

    /**
     * Cleans up all test resources.
     * Called automatically by try-with-resources.
     */
    @Override
    public void close() {
        // Context cleanup is automatic
        members.clear();
    }
}
