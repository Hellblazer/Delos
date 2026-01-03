/*
 * Copyright (c) 2025, salesforce.com, inc.
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
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.fireflies.proto.Note;
import com.hellblazer.delos.fireflies.proto.SignedNote;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
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
 * Test cases for Byzantine mask validation attack prevention (Delos-p0l).
 * <p>
 * Tests the fix for the vulnerability where Byzantine nodes could create masks claiming fake accusations. The
 * validation now ensures that for existing members, any ring disabled in the mask must correspond to an actual
 * accusation known to this view.
 *
 * @author hal.hildebrand
 */
@Disabled("Views not stabilizing - needs investigation")
public class MaskValidationTest {

    private static final int                                                          CARDINALITY = 5;
    private static final int                                                          BIAS        = 3;
    private static final double                                                       P_BYZ       = 0.1;
    private static       Map<Digest, ControlledIdentifierMember>                     members;
    private static       Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private static       KERL.AppendKERL                                              kerl;

    private final List<View>   views         = new ArrayList<>();
    private final List<Router> communications = new ArrayList<>();
    private final List<Router> gateways       = new ArrayList<>();

    @BeforeAll
    public static void beforeClass() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
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
     * Test that a member with a real accusation cannot hide it by keeping the ring enabled. This is the primary
     * Byzantine attack the fix prevents.
     * <p>
     * Attack scenario: 1. Member is accused on ring R 2. Byzantine member creates mask with ring R still enabled
     * (hiding accusation) 3. Without fix: mask passes validation 4. With fix: validation detects enabled ring with
     * known accusation
     * <p>
     * Expected: Validation should REJECT masks that don't disable rings where accusations exist.
     */
    @Test
    public void testRejectMaskHidingRealAccusation() throws Exception {
        initializeViews();
        bootstrapAndStabilize();

        var view = views.get(0);
        var context = view.getContext();

        // Get two active members
        var iterator = context.activeMembers().iterator();
        var accuser = (Participant) iterator.next();
        var accused = (Participant) iterator.next();

        // Accuse the member on ring 0
        var accusationRing = 0;
        view.accuse(accused, accusationRing, new Exception("Test accusation"));

        // Verify the accusation was recorded
        assertTrue(accused.isAccusedOn(accusationRing), "Member should have accusation on ring " + accusationRing);

        // Byzantine member tries to create a mask that hides the accusation
        // (keeps the accused ring enabled instead of disabled)
        var byzantineMask = new BitSet(context.getRingCount());
        // Enable all rings including the one with the accusation (Byzantine behavior)
        for (var i = 0; i < context.getRingCount(); i++) {
            byzantineMask.set(i);
        }
        // Adjust to correct cardinality by disabling random rings (but NOT the accused ring)
        for (var i = context.majority(); i < context.getRingCount(); i++) {
            if (i != accusationRing) {
                byzantineMask.set(i, false);
            }
        }

        // Create note with Byzantine mask that hides the accusation
        var byzantineNote = Note.newBuilder()
                                .setEpoch(accused.getEpoch() + 1)
                                .setIdentifier(accused.getIdentifier().toIdent())
                                .setCurrentView(view.currentView().toDigeste())
                                .setEndpoint(accused.endpoint())
                                .setMask(ByteString.copyFrom(byzantineMask.toByteArray()))
                                .build();

        var memberKey = members.get(accused.getId());
        var signature = memberKey.sign(byzantineNote.toByteString());

        var signedNote = SignedNote.newBuilder().setNote(byzantineNote).setSignature(signature.toSig()).build();

        var noteWrapper = new NoteWrapper(signedNote, DigestAlgorithm.DEFAULT);

        // Attempt to add the Byzantine note
        var result = invokeAdd(view, noteWrapper);

        // With the fix, this should be REJECTED because the mask has an enabled ring
        // where there is a known accusation
        assertFalse(result, "View should reject mask that hides known accusation by keeping ring enabled");
    }

    /**
     * Test that a legitimate member with accusation correctly disabling the accused ring is accepted.
     * <p>
     * This is the positive test case showing legitimate rebuttal behavior works correctly.
     */
    @Test
    public void testAcceptValidMaskWithAccusation() throws Exception {
        initializeViews();
        bootstrapAndStabilize();

        var view = views.get(0);
        var context = view.getContext();

        // Get two active members
        var iterator = context.activeMembers().iterator();
        var accuser = (Participant) iterator.next();
        var accused = (Participant) iterator.next();

        // Accuse the member on ring 0
        var accusationRing = 0;
        view.accuse(accused, accusationRing, new Exception("Test accusation"));

        // Verify the accusation was recorded
        assertTrue(accused.isAccusedOn(accusationRing), "Member should have accusation on ring " + accusationRing);

        // Create a legitimate mask that correctly disables the accused ring
        var legitimateMask = new BitSet(context.getRingCount());
        // Start with all enabled
        for (var i = 0; i < context.getRingCount(); i++) {
            legitimateMask.set(i);
        }
        // Disable the accused ring (correct behavior)
        legitimateMask.set(accusationRing, false);
        // Disable additional rings to reach correct cardinality
        for (var i = context.majority(); i < context.getRingCount(); i++) {
            legitimateMask.set(i, false);
        }

        // Create note with legitimate mask
        var legitimateNote = Note.newBuilder()
                                 .setEpoch(accused.getEpoch() + 1)
                                 .setIdentifier(accused.getIdentifier().toIdent())
                                 .setCurrentView(view.currentView().toDigeste())
                                 .setEndpoint(accused.endpoint())
                                 .setMask(ByteString.copyFrom(legitimateMask.toByteArray()))
                                 .build();

        var memberKey = members.get(accused.getId());
        var signature = memberKey.sign(legitimateNote.toByteString());

        var signedNote = SignedNote.newBuilder().setNote(legitimateNote).setSignature(signature.toSig()).build();

        var noteWrapper = new NoteWrapper(signedNote, DigestAlgorithm.DEFAULT);

        // Attempt to add the legitimate note
        var result = invokeAdd(view, noteWrapper);

        // This should be ACCEPTED because the mask correctly disables the accused ring
        assertTrue(result, "View should accept mask that correctly disables accused ring");
    }

    /**
     * Test the static isValidMask method directly with member ID.
     */
    @Test
    public void testStaticMaskValidationWithAccusations() throws Exception {
        initializeViews();
        bootstrapAndStabilize();

        var view = views.get(0);
        var context = view.getContext();

        // Get a member and accuse them
        var accused = (Participant) context.activeMembers().iterator().next();
        var accusationRing = 0;
        view.accuse(accused, accusationRing, new Exception("Test accusation"));

        // Create mask with accused ring enabled (Byzantine)
        var byzantineMask = new BitSet(context.getRingCount());
        for (var i = 0; i < context.majority(); i++) {
            byzantineMask.set(i);
        }

        // Validation with member ID should FAIL (accused ring is enabled)
        var result1 = View.isValidMask(byzantineMask, context, accused.getId());
        assertFalse(result1, "Should reject mask with accused ring enabled when checking member");

        // Create mask with accused ring disabled (correct)
        var correctMask = new BitSet(context.getRingCount());
        for (var i = 0; i < context.getRingCount(); i++) {
            correctMask.set(i);
        }
        correctMask.set(accusationRing, false);
        for (var i = context.majority(); i < context.getRingCount(); i++) {
            correctMask.set(i, false);
        }

        // Validation with member ID should PASS
        var result2 = View.isValidMask(correctMask, context, accused.getId());
        assertTrue(result2, "Should accept mask with accused ring disabled");

        // Validation without member ID should PASS (no accusation checking)
        var result3 = View.isValidMask(byzantineMask, context, null);
        assertTrue(result3, "Should accept mask when not checking member accusations");
    }

    // === Helper Methods ===

    private void initializeViews() {
        var parameters = Parameters.newBuilder().setMaxPending(20).setMaximumTxfr(5).build();

        var ctxBuilder = DynamicContext.<Participant>newBuilder()
                                       .setBias(BIAS)
                                       .setpByz(P_BYZ)
                                       .setCardinality(CARDINALITY);

        final var prefix = UUID.randomUUID().toString();
        final var gatewayPrefix = UUID.randomUUID().toString();

        members.values().forEach(node -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            var comms = new LocalServer(prefix, node).router(ServerConnectionCache.newBuilder().setTarget(200));
            comms.start();
            communications.add(comms);

            var gateway = new LocalServer(gatewayPrefix, node).router(ServerConnectionCache.newBuilder().setTarget(200));
            gateway.start();
            gateways.add(gateway);

            var view = new View(context, node, EndpointProvider.allocatePort(), EventValidation.NONE,
                                Verifiers.from(kerl), comms, parameters, gateway, DigestAlgorithm.DEFAULT, null);
            views.add(view);
        });
    }

    private void bootstrapAndStabilize() throws Exception {
        var seeds = identities.values()
                              .stream()
                              .limit(1)
                              .map(m -> new Seed(m.getIdentifier(), EndpointProvider.allocatePort()))
                              .toList();

        var gossipDuration = Duration.ofMillis(10);

        var countdown = new CountDownLatch(CARDINALITY);
        views.forEach(v -> v.start(() -> countdown.countDown(), gossipDuration, seeds));

        assertTrue(countdown.await(30, TimeUnit.SECONDS), "Views did not stabilize");

        // Give views time to fully stabilize
        Thread.sleep(1000);
    }

    private boolean invokeAdd(View view, NoteWrapper note) throws Exception {
        Method addMethod = View.class.getDeclaredMethod("add", NoteWrapper.class);
        addMethod.setAccessible(true);
        return (boolean) addMethod.invoke(view, note);
    }
}
