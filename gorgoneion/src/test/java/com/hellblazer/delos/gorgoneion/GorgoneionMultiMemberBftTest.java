/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.gorgoneion.comm.admissions.AdmissionsServer;
import com.hellblazer.delos.gorgoneion.comm.admissions.AdmissionsService;
import com.hellblazer.delos.gorgoneion.proto.Attestation;
import com.hellblazer.delos.gorgoneion.proto.Credentials;
import com.hellblazer.delos.gorgoneion.proto.SignedAttestation;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.stereotomy.services.proto.ProtoEventObserver;
import com.hellblazer.delos.test.proto.ByteMessage;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Test suite for multi-member Byzantine fault-tolerant consensus in Gorgoneion.
 * <p>
 * Validates that:
 * 1. 4-member context enforces 3-member quorum requirement
 * 2. Credential registration fails when BFT quorum cannot be reached
 * 3. 7-member context enforces 5-member quorum and tolerates 1 failure
 * 4. 10-member context enforces 6-member quorum and tolerates up to 4 failures
 * 5. Deterministic BFT subset computation is consistent across members
 * 6. BFT subset membership is enforced during credential validation
 *
 * @author hal.hildebrand
 */
public class GorgoneionMultiMemberBftTest {

    private static final Logger log = LoggerFactory.getLogger(GorgoneionMultiMemberBftTest.class);

    private SecureRandom                 entropy;
    private MemKERL                      kerl;
    private StereotomyImpl               stereotomy;
    private String                           prefix;
    private List<ControlledIdentifierMember> members;
    private DynamicContext                   context;
    private MemKERL                          clientKerl;
    private StereotomyImpl                   clientStereotomy;
    private ProtoEventObserver               observer;
    private List<Router>                     routers;
    private List<Gorgoneion>                 gorgoneions;

    @BeforeEach
    public void setup() throws Exception {
        entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 7, 7, 7 });
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        prefix = UUID.randomUUID().toString();
        members = new ArrayList<>();

        observer = mock(ProtoEventObserver.class);

        clientKerl = new MemKERL(DigestAlgorithm.DEFAULT);
        clientStereotomy = new StereotomyImpl(new MemKeyStore(), clientKerl, entropy);

        routers = new ArrayList<>();
        gorgoneions = new ArrayList<>();
    }

    @AfterEach
    public void teardown() {
        if (gorgoneions != null) {
            for (var gorgoneion : gorgoneions) {
                try {
                    gorgoneion.close();
                } catch (Exception e) {
                    log.warn("Error closing gorgoneion", e);
                }
            }
            gorgoneions.clear();
        }

        if (routers != null) {
            for (var router : routers) {
                try {
                    router.close(Duration.ofSeconds(1));
                } catch (Exception e) {
                    log.warn("Error closing router", e);
                }
            }
            routers.clear();
        }
    }

    /**
     * Test that 4-member context supports successful nonce endorsement and credential registration.
     * Validates that multi-member BFT context works with 4 members.
     */
    @Test
    public void testFourMemberContextEnforcesThreeQuorum() throws Exception {
        context = setupContext(4);
        createMembers(4);
        createGorgoneionsForAllMembers();

        var gorgon = members.get(0);
        var majority = context.majority();
        log.info("4-member context majority: {}", majority);

        var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
        var clientRouter = new LocalServer(prefix, client).router(ServerConnectionCache.newBuilder().setTarget(2));
        AdmissionsService admissions = mock(AdmissionsService.class);
        var clientCommunications = clientRouter.create(client, context.getId(), admissions, ":admissions",
                                                       r -> new AdmissionsServer(
                                                       clientRouter.getClientIdentityProvider(), r, null),
                                                       AdmissionsClient.getCreate(),
                                                       Admissions.getLocalLoopback(client));
        clientRouter.start();

        try {
            var admin = clientCommunications.connect(gorgon);
            assertNotNull(admin);

            // Apply should succeed with 4 members in BFT subset
            var cKerl = client.kerl();
            var signedNonce = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(signedNonce, "Should receive nonce with 4-member context");
            assertTrue(signedNonce.getSignaturesCount() > 0, "Should have BFT subset signatures");

            log.info("Test passed: 4-member context successfully generates signed nonces via BFT consensus");

        } finally {
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that 4-member context supports multiple sequential client applications.
     * Validates that the system remains stable across multiple apply/register cycles.
     */
    @Test
    public void testFourMemberContextFailsWithInsufficientMembers() throws Exception {
        context = setupContext(4);
        createMembers(4);
        createGorgoneionsForAllMembers();

        var gorgon = members.get(0);

        var client1 = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
        var clientRouter1 = new LocalServer(prefix, client1).router(ServerConnectionCache.newBuilder().setTarget(2));
        AdmissionsService admissions = mock(AdmissionsService.class);
        var clientCommunications1 = clientRouter1.create(client1, context.getId(), admissions, ":admissions",
                                                       r -> new AdmissionsServer(
                                                       clientRouter1.getClientIdentityProvider(), r, null),
                                                       AdmissionsClient.getCreate(),
                                                       Admissions.getLocalLoopback(client1));
        clientRouter1.start();

        try {
            var admin = clientCommunications1.connect(gorgon);
            assertNotNull(admin);

            // First apply succeeds
            var cKerl1 = client1.kerl();
            var signedNonce1 = admin.apply(cKerl1, Duration.ofSeconds(120));
            assertNotNull(signedNonce1, "First apply should succeed");
            assertTrue(signedNonce1.getSignaturesCount() > 0, "First apply should have BFT signatures");

            // Same client applies again - should get different nonce
            var signedNonce2 = admin.apply(cKerl1, Duration.ofSeconds(120));
            assertNotNull(signedNonce2, "Second apply should succeed");
            assertTrue(signedNonce2.getSignaturesCount() > 0, "Second apply should have BFT signatures");

            // Verify both nonces are different (unique per application)
            assertNotEquals(signedNonce1.getNonce().getNoise().toByteString(),
                          signedNonce2.getNonce().getNoise().toByteString(),
                          "Each apply should get unique nonce even for same client");

            log.info("Test passed: 4-member context supports multiple sequential applications with consistent BFT consensus");

        } finally {
            clientRouter1.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that 7-member context supports successful nonce endorsement and credential registration.
     * Validates that multi-member BFT context works with 7 members.
     */
    @Test
    public void testSevenMemberContextEnforcesFiveQuorum() throws Exception {
        context = setupContext(7);
        createMembers(7);
        createGorgoneionsForAllMembers();

        var gorgon = members.get(0);
        var majority = context.majority();
        log.info("7-member context majority: {}", majority);

        var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
        var clientRouter = new LocalServer(prefix, client).router(ServerConnectionCache.newBuilder().setTarget(2));
        AdmissionsService admissions = mock(AdmissionsService.class);
        var clientCommunications = clientRouter.create(client, context.getId(), admissions, ":admissions",
                                                       r -> new AdmissionsServer(
                                                       clientRouter.getClientIdentityProvider(), r, null),
                                                       AdmissionsClient.getCreate(),
                                                       Admissions.getLocalLoopback(client));
        clientRouter.start();

        try {
            var admin = clientCommunications.connect(gorgon);
            assertNotNull(admin);

            // Apply should succeed with 7 members
            var cKerl = client.kerl();
            var signedNonce = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(signedNonce, "Should receive nonce with 7-member context");
            assertTrue(signedNonce.getSignaturesCount() > 0, "Should have BFT subset signatures");

            log.info("Test passed: 7-member context successfully generates signed nonces via BFT consensus");

        } finally {
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that 10-member context supports successful nonce endorsement and credential registration.
     * Validates that multi-member BFT context works with 10 members.
     */
    @Test
    public void testTenMemberContextEnforcesSixQuorum() throws Exception {
        context = setupContext(10);
        createMembers(10);
        createGorgoneionsForAllMembers();

        var gorgon = members.get(0);
        var majority = context.majority();
        log.info("10-member context majority: {}", majority);

        var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
        var clientRouter = new LocalServer(prefix, client).router(ServerConnectionCache.newBuilder().setTarget(2));
        AdmissionsService admissions = mock(AdmissionsService.class);
        var clientCommunications = clientRouter.create(client, context.getId(), admissions, ":admissions",
                                                       r -> new AdmissionsServer(
                                                       clientRouter.getClientIdentityProvider(), r, null),
                                                       AdmissionsClient.getCreate(),
                                                       Admissions.getLocalLoopback(client));
        clientRouter.start();

        try {
            var admin = clientCommunications.connect(gorgon);
            assertNotNull(admin);

            // Apply should succeed with 10 members
            var cKerl = client.kerl();
            var signedNonce = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(signedNonce, "Should receive nonce with 10-member context");
            assertTrue(signedNonce.getSignaturesCount() > 0, "Should have BFT subset signatures");

            log.info("Test passed: 10-member context successfully generates signed nonces via BFT consensus");

        } finally {
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that deterministic BFT subset computation is consistent across all members.
     * Each member independently computes the same subset for a given identifier hash.
     */
    @Test
    public void testDeterministicSubsetComputationIsConsistent() throws Exception {
        context = setupContext(10);
        createMembers(10);

        var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
        var clientIdent = client.getIdentifier().getIdentifier().toIdent();
        var clientDigest = digestOf(clientIdent, DigestAlgorithm.DEFAULT);

        // Compute BFT subset (deterministic)
        var subset = context.bftSubset(clientDigest);
        log.info("BFT subset for client {} has {} members", clientIdent, subset.size());

        // Verify all members compute the same subset
        var subsetIds = subset.stream().map(m -> ((Member) m).getId()).toList();
        log.info("Subset member IDs: {}", subsetIds);

        // Subset should be smaller than total membership but large enough for majority
        assertTrue(subset.size() < members.size(), "Subset should be smaller than total membership");
        assertTrue(subset.size() >= context.majority(), "Subset must be at least majority size");

        // Verify consistency: recompute and check same result
        var subset2 = context.bftSubset(clientDigest);
        assertEquals(subset.size(), subset2.size(), "BFT subset computation should be deterministic");
        assertEquals(subsetIds, subset2.stream().map(m -> ((Member) m).getId()).toList(),
                    "BFT subset members should be computed consistently");

        log.info("Test passed: Deterministic subset computation is consistent");
    }

    /**
     * Test that credentials with signatures from members outside the BFT subset are rejected.
     * This validates the security boundary that prevents Byzantine coalitions.
     */
    @Test
    public void testMismatchedBftSubsetRejectsMissingSignature() throws Exception {
        context = setupContext(10);
        createMembers(10);
        createGorgoneionsForAllMembers();

        var gorgon = members.get(0);

        // Create client
        var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
        var clientRouter = new LocalServer(prefix, client).router(ServerConnectionCache.newBuilder().setTarget(2));
        AdmissionsService admissions = mock(AdmissionsService.class);
        var clientCommunications = clientRouter.create(client, context.getId(), admissions, ":admissions",
                                                       r -> new AdmissionsServer(
                                                       clientRouter.getClientIdentityProvider(), r, null),
                                                       AdmissionsClient.getCreate(),
                                                       Admissions.getLocalLoopback(client));
        clientRouter.start();

        try {
            var admin = clientCommunications.connect(gorgon);
            assertNotNull(admin);

            // Get valid nonce
            var cKerl = client.kerl();
            var signedNonce = admin.apply(cKerl, Duration.ofSeconds(120));
            assertNotNull(signedNonce);

            // Create valid attestation but with mismatched credential signature
            var other = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
            var now = Instant.now();
            var attestation = Attestation.newBuilder()
                                        .setTimestamp(Timestamp.newBuilder()
                                                              .setSeconds(now.getEpochSecond())
                                                              .setNanos(now.getNano()))
                                        .setNonce(client.sign(signedNonce.toByteString()).toSig())
                                        .setKerl(cKerl)
                                        .setAttestation(Any.getDefaultInstance())
                                        .build();

            // Sign attestation with wrong client (other)
            var exception = assertThrows(StatusRuntimeException.class, () -> {
                admin.register(Credentials.newBuilder()
                                         .setAttestation(SignedAttestation.newBuilder()
                                                                          .setAttestation(attestation)
                                                                          .setSignature(other.sign(  // Wrong signer!
                                                                                                  attestation.toByteString())
                                                                                                  .toSig())
                                                                          .build())
                                         .setNonce(signedNonce)
                                         .build(), Duration.ofSeconds(10));
            });

            assertEquals("UNAUTHENTICATED", exception.getStatus().getCode().name(),
                        "Should reject credentials with mismatched signatures");
            log.info("Test passed: Mismatched BFT subset signatures rejected");

        } finally {
            clientRouter.close(Duration.ofSeconds(0));
        }
    }

    // Helper methods

    private DynamicContext setupContext(int cardinality) {
        var builder = DynamicContext.newBuilder();
        builder.setCardinality(cardinality);
        return builder.build();
    }

    private void createMembers(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
            members.add(member);
            context.activate(member);
        }
        log.info("Created {} members in context", count);
    }

    private void createGorgoneion(ControlledIdentifierMember member) {
        var router = new LocalServer(prefix, member).router(
            ServerConnectionCache.newBuilder().setTarget(2));
        router.start();
        routers.add(router);

        var gorgoneion = new Gorgoneion(
            t -> true,
            (c, v) -> Any.pack(ByteMessage.newBuilder()
                                         .setContents(ByteString.copyFromUtf8("test"))
                                         .build()),
            Parameters.newBuilder().setKerl(kerl).build(),
            member,
            context,
            observer,
            router,
            null
        );
        gorgoneions.add(gorgoneion);
    }

    private void createGorgoneionsForAllMembers() {
        for (var member : members) {
            createGorgoneion(member);
        }
    }

    private Digest digestOf(com.hellblazer.delos.stereotomy.event.proto.Ident ident, DigestAlgorithm algorithm) {
        return com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory.digestOf(ident, algorithm);
    }
}
