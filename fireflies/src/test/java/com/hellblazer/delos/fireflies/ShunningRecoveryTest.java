/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.archipelago.EndpointProvider;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContextImpl;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.stereotomy.Verifiers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for shunning recovery mechanism in Fireflies.
 * <p>
 * Verifies that:
 * - Shunned members can recover after configured duration
 * - Recovery requires valid identity proof
 * - Rate limiting prevents abuse
 * - Recovered members start with clean state
 *
 * @author hal.hildebrand
 */
public class ShunningRecoveryTest {

    private static final int    CARDINALITY     = 5;
    private static final int    BIAS            = 2;
    private static final double P_BYZ           = 0.1;
    private static final long   RECOVERY_MILLIS = 2000; // 2 seconds for testing

    private Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private KERL.AppendKERL                                             kerl;
    private Map<Digest, ControlledIdentifierMember>                     members;
    private List<Router>                                                communications;
    private List<Router>                                                gateways;
    private List<View>                                                  views;
    private DigestAlgorithm                                             digestAlgo;

    @BeforeEach
    public void setup() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        digestAlgo = DigestAlgorithm.DEFAULT;
        kerl = new MemKERL(digestAlgo);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);

        identities = IntStream.range(0, CARDINALITY)
                              .mapToObj(i -> stereotomy.newIdentifier())
                              .collect(Collectors.toMap(controlled -> controlled.getIdentifier().getDigest(),
                                                        controlled -> controlled, (a, b) -> a, TreeMap::new));

        members = new HashMap<>();
        identities.forEach((d, id) -> members.put(d, new ControlledIdentifierMember(id)));

        communications = new ArrayList<>();
        gateways = new ArrayList<>();
        views = new ArrayList<>();

        var parameters = Parameters.newBuilder()
                                   .setShunRecoveryDuration(Duration.ofMillis(RECOVERY_MILLIS))
                                   .setMaxJoinAttemptsPerMinute(5)
                                   .setJoinRateLimitWindow(Duration.ofMinutes(1))
                                   .setPendingJoinTtl(Duration.ofMinutes(5))
                                   .build();

        instantiate(parameters);
    }

    @AfterEach
    public void teardown() {
        if (views != null) {
            views.forEach(View::stop);
            views.clear();
        }
        if (communications != null) {
            communications.forEach(r -> r.close(Duration.ofSeconds(1)));
            communications.clear();
        }
        if (gateways != null) {
            gateways.forEach(r -> r.close(Duration.ofSeconds(1)));
            gateways.clear();
        }
    }

    /**
     * Test basic shunning recovery after configured duration.
     * <p>
     * 1. Bootstrap cluster
     * 2. Shun a member
     * 3. Verify member cannot rejoin immediately
     * 4. Wait for recovery period
     * 5. Verify member can rejoin successfully
     */
    @Test
    public void testBasicRecovery() throws Exception {
        // Bootstrap cluster - first node bootstraps alone
        var gossipDuration = Duration.ofMillis(10);
        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Bootstrap node did not start");

        // Remaining nodes join using bootstrap node as seed
        var bootstrapMember = views.get(0).getNode().getId();
        var bootstrapIdentity = members.get(bootstrapMember).getIdentifier().getIdentifier();
        var seeds = List.of(new Seed(bootstrapIdentity, EndpointProvider.allocatePort()));
        countdown.set(new CountDownLatch(CARDINALITY - 1));
        views.subList(1, views.size()).forEach(v -> v.start(() -> countdown.get().countDown(), gossipDuration, seeds));
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Cluster did not join");

        // Verify all members joined
        assertEquals(CARDINALITY, views.get(0).getContext().activeCount());

        // Shun a member (simulate failed rebuttal)
        var victimView = views.get(1);
        var victimId = victimView.getNode().getId();

        // Shun the victim from all other views
        views.stream().filter(v -> !v.getNode().getId().equals(victimId)).forEach(v -> {
            v.shun(victimId);
        });

        // Wait for gossip to propagate shunning
        Thread.sleep(100);

        // Verify victim is shunned
        assertTrue(views.get(0).streamShunned().anyMatch(d -> d.equals(victimId)));

        // Attempt immediate recovery should fail (within recovery window)
        var canRecoverImmediate = views.get(0).canRecover(victimId);
        assertFalse(canRecoverImmediate, "Should not be able to recover immediately");

        // Wait for recovery period
        Thread.sleep(RECOVERY_MILLIS + 500);

        // Verify recovery is now allowed
        var canRecoverAfterWait = views.get(0).canRecover(victimId);
        assertTrue(canRecoverAfterWait, "Should be able to recover after recovery period");

        // Attempt recovery with valid note
        victimView.getNode().nextNote();
        var recoveryNote = victimView.getNode().getNote();
        var recovered = views.get(0).attemptRecovery(recoveryNote);
        assertTrue(recovered, "Recovery should succeed with valid note");

        // Verify victim is no longer shunned
        assertFalse(views.get(0).streamShunned().anyMatch(d -> d.equals(victimId)));
    }

    /**
     * Test that recovery requires valid identity proof.
     * <p>
     * 1. Shun a member
     * 2. Wait for recovery period
     * 3. Attempt recovery with invalid signature
     * 4. Verify recovery fails
     */
    @Test
    public void testRecoveryRequiresValidIdentity() throws Exception {
        // Bootstrap single node view
        var seeds = members.values()
                           .stream()
                           .map(m -> new Seed(m.getIdentifier().getIdentifier(), EndpointProvider.allocatePort()))
                           .limit(1)
                           .toList();

        var gossipDuration = Duration.ofMillis(10);
        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS));

        var view = views.get(0);
        var victimId = members.values().stream().skip(1).findFirst().get().getId();

        // Shun the victim
        view.shun(victimId);

        // Wait for recovery period
        Thread.sleep(RECOVERY_MILLIS + 500);

        // Create a note from a different member (identity mismatch)
        var wrongMember = members.values().stream().skip(2).findFirst().get();
        var wrongNote = views.get(2).getNode().getNote();

        // Attempt recovery with wrong identity should fail
        var recovered = view.attemptRecovery(wrongNote);
        assertFalse(recovered, "Recovery should fail with wrong identity");

        // Verify victim still shunned
        assertTrue(view.streamShunned().anyMatch(d -> d.equals(victimId)));
    }

    /**
     * Test rate limiting prevents rapid shun/recover cycles.
     * <p>
     * 1. Shun a member
     * 2. Recover the member
     * 3. Shun again immediately
     * 4. Verify immediate recovery is blocked by rate limiting
     */
    @Test
    public void testRecoveryRateLimiting() throws Exception {
        // Bootstrap single node
        var gossipDuration = Duration.ofMillis(10);
        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS));

        var view = views.get(0);
        var victimId = members.values().stream().skip(1).findFirst().get().getId();

        // First shun/recover cycle
        view.shun(victimId);
        Thread.sleep(RECOVERY_MILLIS + 500);
        var victimView = views.get(1);
        victimView.getNode().nextNote();
        var recoveryNote = victimView.getNode().getNote();
        assertTrue(view.attemptRecovery(recoveryNote));

        // Second shun immediately after recovery
        view.shun(victimId);

        // Immediate recovery attempt (within min recovery interval)
        var immediateRecovery = view.canRecover(victimId);
        assertFalse(immediateRecovery, "Should not allow immediate re-recovery (rate limiting)");

        // After recovery period, should be allowed
        Thread.sleep(RECOVERY_MILLIS + 500);
        assertTrue(view.canRecover(victimId));
    }

    /**
     * Test that recovered member starts with clean state (no lingering accusations).
     */
    @Test
    public void testRecoveredMemberCleanState() throws Exception {
        // Bootstrap cluster - first node bootstraps alone
        var gossipDuration = Duration.ofMillis(10);
        var countdown = new AtomicReference<>(new CountDownLatch(1));
        views.get(0).start(() -> countdown.get().countDown(), gossipDuration, Collections.emptyList());
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Bootstrap node did not start");

        // Remaining nodes join
        var bootstrapMember = views.get(0).getNode().getId();
        var bootstrapIdentity = members.get(bootstrapMember).getIdentifier().getIdentifier();
        var seeds = List.of(new Seed(bootstrapIdentity, EndpointProvider.allocatePort()));
        countdown.set(new CountDownLatch(CARDINALITY - 1));
        views.subList(1, views.size()).forEach(v -> v.start(() -> countdown.get().countDown(), gossipDuration, seeds));
        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Cluster did not join");

        var view = views.get(0);
        var victimView = views.get(1);
        var victimId = victimView.getNode().getId();

        // Shun victim
        view.shun(victimId);

        // Wait for recovery
        Thread.sleep(RECOVERY_MILLIS + 500);

        // Recover
        victimView.getNode().nextNote();
        var recoveryNote = victimView.getNode().getNote();
        assertTrue(view.attemptRecovery(recoveryNote));

        // Verify member is no longer shunned
        assertFalse(view.isShunned(victimId));

        // Verify member can participate in the view again
        var participant = view.getContext().getMember(victimId);
        assertNotNull(participant);
        assertFalse(participant.isAccused(), "Recovered member should not have accusations");
    }

    private void instantiate(Parameters parameters) {
        var prefix = UUID.randomUUID().toString();
        var gatewayPrefix = UUID.randomUUID().toString();
        identities.forEach((d, id) -> {
            var context = new DynamicContextImpl<Participant>(d, CARDINALITY, P_BYZ, BIAS);
            var localRouter = new LocalServer(prefix, members.get(d)).router(
            ServerConnectionCache.newBuilder().setTarget(30), null);
            localRouter.start();
            communications.add(localRouter);
            var gateway = new LocalServer(gatewayPrefix, members.get(d)).router(
            ServerConnectionCache.newBuilder().setTarget(30), null);
            gateway.start();
            gateways.add(gateway);

            var view = new View(context, members.get(d), EndpointProvider.allocatePort(), EventValidation.NONE,
                                Verifiers.from(kerl), localRouter, parameters, gateway, digestAlgo, null);
            views.add(view);
        });
    }
}
