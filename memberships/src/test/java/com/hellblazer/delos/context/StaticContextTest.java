package com.hellblazer.delos.context;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


/**
 * @author hal.hildebrand
 **/
public class StaticContextTest {
    @Test
    public void consistency() throws Exception {
        var prototype = new DynamicContextImpl<>(DigestAlgorithm.DEFAULT.getOrigin().prefix(1), 10, 0.2, 2);
        List<SigningMember> members = new ArrayList<>();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        for (int i = 0; i < 10; i++) {
            SigningMember m = new ControlledIdentifierMember(stereotomy.newIdentifier());
            members.add(m);
            prototype.activate(m);
        }

        var context = prototype.asStatic();

        // Verify ring structure is consistent (platform-independent)
        var predecessors = context.predecessors(members.get(0).getId());
        assertFalse(predecessors.isEmpty(), "Should have predecessors");
        assertTrue(members.contains(predecessors.get(0)), "Predecessors should be members");

        var successors = context.successors(members.get(1).getId());
        assertFalse(successors.isEmpty(), "Should have successors");
        assertTrue(members.contains(successors.get(0)), "Successors should be members");

        // Verify successor function works
        var successor = context.successor(1, members.get(0).getId());
        assertNotNull(successor, "Should have a successor at ring 1");
        assertTrue(members.contains(successor), "Successor should be a member");
    }

    @Test
    public void lookupMember() throws Exception {
        var prototype = new DynamicContextImpl<>(DigestAlgorithm.DEFAULT.getOrigin().prefix(1), 10, 0.2, 2);
        List<SigningMember> members = new ArrayList<>();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        for (int i = 0; i < 10; i++) {
            SigningMember m = new ControlledIdentifierMember(stereotomy.newIdentifier());
            members.add(m);
            prototype.activate(m);
        }

        var context = prototype.asStatic();

        var m = context.getMember(members.get(0).getId());
        assertNotNull(m);
        assertEquals(members.get(0), m);
    }
}
