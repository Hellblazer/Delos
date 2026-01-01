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

import static org.junit.jupiter.api.Assertions.*;
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

    /**
     * Test that dynamicDiameter adapts based on active membership count.
     * Per Fireflies paper section 4.2: diameter = log(n) / log(k)
     * where n is active member count and k is ring count.
     */
    @Test
    public void dynamicDiameter() throws Exception {
        var context = new DynamicContextImpl<>(DigestAlgorithm.DEFAULT.getOrigin().prefix(1), 100, 0.2, 2);
        List<SigningMember> members = new ArrayList<>();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        // With no active members, diameter should be 1
        assertEquals(1, context.dynamicDiameter());
        assertEquals(context.getRingCount() + 1, context.dynamicTimeToLive());

        // Add and activate 10 members
        for (int i = 0; i < 10; i++) {
            SigningMember m = new ControlledIdentifierMember(stereotomy.newIdentifier());
            members.add(m);
            context.activate(m);
        }

        int diameter10 = context.dynamicDiameter();
        int ttl10 = context.dynamicTimeToLive();
        assertTrue(diameter10 >= 1, "Diameter should be at least 1");
        assertEquals((context.getRingCount() * diameter10) + 1, ttl10);

        // Add more members - diameter should increase
        for (int i = 0; i < 90; i++) {
            SigningMember m = new ControlledIdentifierMember(stereotomy.newIdentifier());
            members.add(m);
            context.activate(m);
        }

        int diameter100 = context.dynamicDiameter();
        int ttl100 = context.dynamicTimeToLive();
        assertTrue(diameter100 >= diameter10, "Diameter should increase or stay same with more members");
        assertEquals((context.getRingCount() * diameter100) + 1, ttl100);

        // Take some members offline - diameter should decrease
        for (int i = 0; i < 80; i++) {
            context.offline(members.get(i));
        }

        int diameter20 = context.dynamicDiameter();
        assertTrue(diameter20 <= diameter100, "Diameter should decrease with fewer active members");
    }

    /**
     * Test that dynamicDiameter handles edge cases properly.
     */
    @Test
    public void dynamicDiameterEdgeCases() throws Exception {
        var context = new DynamicContextImpl<>(DigestAlgorithm.DEFAULT.getOrigin().prefix(1), 10, 0.2, 2);
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        // Empty context
        assertEquals(1, context.dynamicDiameter());

        // Single member
        SigningMember m = new ControlledIdentifierMember(stereotomy.newIdentifier());
        context.activate(m);
        assertEquals(1, context.dynamicDiameter());

        // Two members
        SigningMember m2 = new ControlledIdentifierMember(stereotomy.newIdentifier());
        context.activate(m2);
        assertTrue(context.dynamicDiameter() >= 1);
    }

    /**
     * Test that static diameter uses cardinality while dynamic diameter uses activeCount.
     */
    @Test
    public void staticVsDynamicDiameter() throws Exception {
        var context = new DynamicContextImpl<>(DigestAlgorithm.DEFAULT.getOrigin().prefix(1), 100, 0.2, 2);
        List<SigningMember> members = new ArrayList<>();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        // Add 10 members
        for (int i = 0; i < 10; i++) {
            SigningMember m = new ControlledIdentifierMember(stereotomy.newIdentifier());
            members.add(m);
            context.activate(m);
        }

        // Static diameter uses cardinality (100), dynamic uses activeCount (10)
        int staticDiameter = context.diameter();
        int dynamicDiameter = context.dynamicDiameter();

        // With cardinality=100 and activeCount=10, static should be >= dynamic
        // because log(100)/log(k) >= log(10)/log(k)
        assertTrue(staticDiameter >= dynamicDiameter,
                   "Static diameter (based on cardinality) should be >= dynamic (based on activeCount)");

        // Static TTL vs dynamic TTL
        int staticTTL = context.timeToLive();
        int dynamicTTL = context.dynamicTimeToLive();
        assertTrue(staticTTL >= dynamicTTL,
                   "Static TTL should be >= dynamic TTL when activeCount < cardinality");
    }

    /**
     * Test that predecessors(ring, start, predicate) returns non-null iterable.
     * Regression test for Delos-gph: DynamicContextImpl was returning null instead of empty collection.
     */
    @Test
    public void predecessorsWithPredicateNonNull() throws Exception {
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

        // Test predecessors with predicate - should never return null
        for (int ring = 0; ring < context.getRingCount(); ring++) {
            var result = context.predecessors(ring, members.get(0), m -> true);
            assertNotNull(result, "predecessors() should never return null");

            // Should be able to iterate without NPE
            var count = 0;
            for (var member : result) {
                count++;
                assertNotNull(member);
            }
            assertTrue(count >= 0, "Should be able to iterate predecessors");
        }

        // Test with predicate that filters everything out
        for (int ring = 0; ring < context.getRingCount(); ring++) {
            var result = context.predecessors(ring, members.get(0), m -> false);
            assertNotNull(result, "predecessors() should return empty iterable, not null");

            // Empty but not null
            var iterator = result.iterator();
            assertNotNull(iterator);
        }
    }
}
