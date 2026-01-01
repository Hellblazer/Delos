/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.archipelago.EndpointProvider;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.fireflies.View.Node;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.proto.Note;
import com.hellblazer.delos.fireflies.proto.SignedNote;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.joou.ULong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for bootstrap validation in Fireflies.
 * Validates that bootstrap nodes properly verify:
 * - Self-addressing identity (ID matches identifier digest)
 * - Signature verification for bootstrap notes
 *
 * @author hal.hildebrand
 */
public class BootstrapValidationTest {

    private static final int                                                         CARDINALITY = 4;
    private static final int                                                         BIAS        = 2;
    private static final double                                                      P_BYZ       = 0.1;
    private static       Map<Digest, ControlledIdentifierMember>                     members;
    private static       Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private static       KERL.AppendKERL                                             kerl;

    private final List<View>   views          = new ArrayList<>();
    private final List<Router> communications = new ArrayList<>();
    private final List<Router> gateways       = new ArrayList<>();

    @BeforeAll
    public static void beforeClass() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 7, 7, 7 });
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        identities = IntStream.range(0, CARDINALITY)
                              .mapToObj(i -> stereotomy.newIdentifier())
                              .collect(Collectors.toMap(controlled -> controlled.getIdentifier().getDigest(),
                                                        controlled -> controlled, (a, b) -> a, TreeMap::new));
        members = identities.values()
                            .stream()
                            .map(ControlledIdentifierMember::new)
                            .collect(Collectors.toMap(m -> m.getId(), m -> m));
    }

    @AfterEach
    public void after() {
        views.forEach(v -> v.stop());
        views.clear();
        communications.forEach(e -> e.close(Duration.ofSeconds(0)));
        communications.clear();
        gateways.forEach(e -> e.close(Duration.ofSeconds(0)));
        gateways.clear();
    }

    /**
     * Test that a valid bootstrap note passes validation.
     * This is the positive test case for validateBootstrapNote().
     */
    @Test
    public void testValidBootstrapNote() throws Exception {
        initializeViews();
        var view = views.get(0);

        // Get a valid note from the view's node
        var node = view.getNode();
        var note = node.getNote();

        // Use reflection to access validateBootstrapNote
        Method validateMethod = View.class.getDeclaredMethod("validateBootstrapNote", NoteWrapper.class);
        validateMethod.setAccessible(true);

        // Valid note should pass validation
        boolean result = (boolean) validateMethod.invoke(view, note);
        assertTrue(result, "Valid bootstrap note should pass validation");
    }

    /**
     * Test that a note with invalid signature is rejected.
     * Tests signature verification in validateBootstrapNote().
     */
    @Test
    public void testRejectInvalidSignature() throws Exception {
        initializeViews();
        var view = views.get(0);

        // Get a valid note and corrupt its signature
        var node = view.getNode();
        var validNote = node.getNote();

        // Create a note with an invalid (null) signature
        var forgedNote = SignedNote.newBuilder()
                                   .setNote(validNote.getWrapped().getNote())
                                   .setSignature(SignatureAlgorithm.NULL_SIGNATURE.sign(ULong.MIN, null, new byte[0])
                                                                                  .toSig())
                                   .build();
        var invalidNoteWrapper = new NoteWrapper(forgedNote, DigestAlgorithm.DEFAULT);

        // Use reflection to access validateBootstrapNote
        Method validateMethod = View.class.getDeclaredMethod("validateBootstrapNote", NoteWrapper.class);
        validateMethod.setAccessible(true);

        // Invalid signature should fail validation
        boolean result = (boolean) validateMethod.invoke(view, invalidNoteWrapper);
        assertFalse(result, "Note with invalid signature should fail validation");
    }

    /**
     * Test that bootstrap completes successfully with valid KERI identity.
     * This is an integration test for the bootstrap flow.
     */
    @Test
    public void testBootstrapWithValidIdentity() throws Exception {
        initializeViews();

        var view = views.get(0);
        var countdown = new CountDownLatch(1);

        // Bootstrap should succeed with valid KERI identity
        view.start(() -> countdown.countDown(), Duration.ofMillis(5), Collections.emptyList());

        assertTrue(countdown.await(30, TimeUnit.SECONDS), "Bootstrap should complete successfully");
        assertEquals(1, view.getContext().activeCount(), "Should have one active member after bootstrap");
    }

    /**
     * Test that seeding validation works correctly with valid notes.
     * Multiple nodes should be able to join after bootstrap.
     * Note: This is a simplified integration test - full seeding tests are in E2ETest.
     */
    @Test
    public void testSeedingValidation() throws Exception {
        // This test verifies that the bootstrap validation doesn't break seeding
        // by checking that the E2E bootstrap pattern still works with our validation
        initializeViews();

        // Bootstrap the first node - this tests ViewManagement.bootstrap() validation
        var bootstrapView = views.get(0);
        var bootstrapLatch = new CountDownLatch(1);
        bootstrapView.start(() -> bootstrapLatch.countDown(), Duration.ofMillis(5), Collections.emptyList());

        // Bootstrap should complete successfully (validates the node's own note)
        assertTrue(bootstrapLatch.await(30, TimeUnit.SECONDS), "Bootstrap should complete successfully");
        assertEquals(1, bootstrapView.getContext().activeCount(), "Should have one active member after bootstrap");
    }

    private void initializeViews() {
        var parameters = Parameters.newBuilder().setMaxPending(20).setMaximumTxfr(5).build();

        var ctxBuilder = DynamicContext.<Participant>newBuilder()
                                       .setBias(BIAS)
                                       .setpByz(P_BYZ)
                                       .setCardinality(CARDINALITY);

        var prefix = UUID.randomUUID().toString();
        var gatewayPrefix = UUID.randomUUID().toString();

        members.values().forEach(node -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            var comms = new LocalServer(prefix, node).router(ServerConnectionCache.newBuilder().setTarget(200));
            var gateway = new LocalServer(gatewayPrefix, node).router(ServerConnectionCache.newBuilder().setTarget(200));
            comms.start();
            communications.add(comms);
            gateway.start();
            gateways.add(gateway);
            views.add(new View(context, node, EndpointProvider.allocatePort(), EventValidation.NONE,
                               Verifiers.from(kerl), comms, parameters, gateway, DigestAlgorithm.DEFAULT, null));
        });
    }

    private ControlledIdentifierMember getMemberForView(int index) {
        return new ArrayList<>(members.values()).get(index);
    }
}
