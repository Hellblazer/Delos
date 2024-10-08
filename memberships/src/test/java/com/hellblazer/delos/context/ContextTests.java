/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.context;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.SequencedSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author hal.hildebrand
 */
public class ContextTests {

    @Test
    public void bftSubset() throws Exception {
        var context = new DynamicContextImpl<>(DigestAlgorithm.DEFAULT.getOrigin().prefix(1), 10, 0.2, 2);
        List<SigningMember> members = new ArrayList<>();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        for (int i = 0; i < 7; i++) {
            SigningMember m = new ControlledIdentifierMember(stereotomy.newIdentifier());
            members.add(m);
            context.activate(m);
        }
        var testEntropy = SecureRandom.getInstance("SHA1PRNG");
        testEntropy.setSeed(new byte[] { 6, 6, 6 });
        var algo = DigestAlgorithm.DEFAULT;
        List<SequencedSet<Member>> subsets = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            var subset = context.bftSubset(algo.random(testEntropy));
            System.out.println(subset.stream().map(Member::getId).toList());
            subsets.add(subset);
        }
    }

    @Test
    public void consistency() throws Exception {
        var context = new DynamicContextImpl<>(DigestAlgorithm.DEFAULT.getOrigin().prefix(1), 10, 0.2, 2);
        List<SigningMember> members = new ArrayList<>();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        for (int i = 0; i < 10; i++) {
            SigningMember m = new ControlledIdentifierMember(stereotomy.newIdentifier());
            members.add(m);
            context.activate(m);
        }

        List<Member> predecessors = context.predecessors(members.get(0));
        assertEquals(predecessors.get(2), members.get(3));

        List<Member> successors = context.successors(members.get(1));
        assertEquals(members.get(8), successors.get(0));
        assertEquals(members.get(9), context.successor(1, members.get(0)));
    }

    @Test
    public void successors() throws Exception {
        var context = new DynamicContextImpl<>(DigestAlgorithm.DEFAULT.getOrigin().prefix(1), 10, 0.2, 2);
        List<SigningMember> members = new ArrayList<>();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        for (int i = 0; i < 50; i++) {
            SigningMember m = new ControlledIdentifierMember(stereotomy.newIdentifier());
            members.add(m);
            context.activate(m);
        }
        var successors = context.bftSubset(members.get(10).getId());
        assertEquals(context.getRingCount(), successors.size());

        successors = context.bftSubset(members.get(10).getId(), m -> {
            return context.isActive(m);
        });
        assertEquals(context.getRingCount(), successors.size());
    }
}
