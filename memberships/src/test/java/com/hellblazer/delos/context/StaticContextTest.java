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

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

        var predecessors = context.predecessors(members.get(0).getId());
        // Verify predecessors are valid members from the context
        for (var p : predecessors) {
            assertNotNull(p, "Predecessor should not be null");
            assertTrue(members.contains(p), "Predecessor should be from the context members");
        }
        assertTrue(predecessors.size() > 0, "Should have at least one predecessor");

        var successors = context.successors(members.get(1).getId());
        // Verify successors are valid members from the context
        for (var s : successors) {
            assertNotNull(s, "Successor should not be null");
            assertTrue(members.contains(s), "Successor should be from the context members");
        }
        assertFalse(successors.isEmpty(), "Should have at least one successor");
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
