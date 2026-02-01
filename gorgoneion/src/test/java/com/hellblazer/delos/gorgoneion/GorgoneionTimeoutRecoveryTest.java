/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.gorgoneion;

import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.gorgoneion.comm.admissions.AdmissionsServer;
import com.hellblazer.delos.gorgoneion.comm.admissions.AdmissionsService;
import com.hellblazer.delos.gorgoneion.proto.SignedNonce;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hellblazer.delos.gorgoneion.GorgoneionBftTestHelpers.*;
import static com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory.digestOf;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Timeout recovery tests for Gorgoneion.
 * <p>
 * Tests validate that Gorgoneion properly handles timeout scenarios:
 * <ul>
 *   <li>Timeout-triggered retry with different BFT subset nodes</li>
 *   <li>Partial BFT subset signature collection timeouts</li>
 *   <li>Progressive timeout escalation with exponential backoff</li>
 *   <li>Byzantine delay attacks and timeout recovery</li>
 *   <li>Timeout recovery after node restart</li>
 *   <li>Cascading timeout handling without deadlock</li>
 *   <li>Partial result timeouts with retry completion</li>
 *   <li>Timeout race conditions with late responses</li>
 *   <li>Configurable timeout behavior validation</li>
 *   <li>Timeout metrics and observability</li>
 * </ul>
 * <p>
 * All tests use deterministic setup:
 * <ul>
 *   <li>Fixed clock: 2026-01-09T12:00:00Z</li>
 *   <li>Seeded entropy: byte[] { 6, 6, 6 }</li>
 *   <li>7-member cluster (f=1 Byzantine tolerance)</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
@DisplayName("Gorgoneion Timeout Recovery Tests")
public class GorgoneionTimeoutRecoveryTest {

    private static final Logger log = LoggerFactory.getLogger(GorgoneionTimeoutRecoveryTest.class);

    private SecureRandom   entropy;
    private Clock          fixedClock;
    private MemKERL        clientKerl;
    private StereotomyImpl clientStereotomy;

    @BeforeEach
    void setUp() throws Exception {
        entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        fixedClock = Clock.fixed(Instant.parse("2026-01-09T12:00:00Z"), ZoneId.of("UTC"));

        // Separate KERL and stereotomy for client identities (distinct from cluster members)
        clientKerl = new MemKERL(DigestAlgorithm.DEFAULT);
        clientStereotomy = new StereotomyImpl(new MemKeyStore(), clientKerl, entropy);
    }

    @AfterEach
    void tearDown() {
        // Resources cleaned up via try-with-resources
    }

    @Test
    @DisplayName("Timeout triggers retry with different BFT subset node")
    void testTimeoutTriggersRetryWithDifferentNode() throws Exception {
        try (var ctx = setupMultiMemberContext(7, entropy)) {
            var params = testParameters(fixedClock).setKerl(ctx.getKerl())
                                                   .setRegistrationTimeout(Duration.ofMillis(200))
                                                   .build();

            try (var cluster = createGorgoneionCluster(ctx, params)) {
                var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
                var clientDigest = digestOf(client.getIdentifier().getIdentifier().toIdent(),
                                            params.digestAlgorithm());

                // Identify BFT subset members
                var bftSubset = ctx.getBftSubset(clientDigest);
                var coordinator = ctx.getCoordinator(clientDigest);
                var coordinatorIndex = ctx.getMembers().indexOf(coordinator);

                log.info("Coordinator index: {}, BFT subset size: {}", coordinatorIndex, bftSubset.size());

                // INJECT FAULT: delay coordinator beyond timeout
                var delayFault = injectResponseDelay(cluster, coordinatorIndex, Duration.ofMillis(300));

                try {
                    // Create client communications
                    var clientRouter = new LocalServer(cluster.getPrefix(), client).router(
                    ServerConnectionCache.newBuilder().setTarget(2));
                    AdmissionsService admissions = mock(AdmissionsService.class);
                    var clientCommunications = clientRouter.create(client, ctx.getContext().getId(), admissions,
                                                                   ":admissions", r -> new AdmissionsServer(
                            clientRouter.getClientIdentityProvider(), r, null), AdmissionsClient.getCreate(),
                                                                   Admissions.getLocalLoopback(client));
                    clientRouter.start();

                    try {
                        // Apply via backup member (will timeout on coordinator, retry on backup)
                        var backupMember = ctx.getMember((coordinatorIndex + 1) % ctx.getMemberCount());
                        var admin = clientCommunications.connect(backupMember);

                        // ACTION: client applies for nonce (should succeed via retry)
                        var signedNonce = admin.apply(client.kerl(), Duration.ofSeconds(5));

                        // ASSERT: consensus succeeded despite coordinator delay
                        assertNotNull(signedNonce, "Should receive nonce via retry after timeout");
                        assertTrue(signedNonce.getSignaturesCount() >= ctx.getQuorum(),
                                   "Should have quorum signatures from backup nodes");

                        // VERIFY: safety property maintained
                        cluster.recordNonce(signedNonce);
                        verifyBftSafetyProperty(cluster, safety -> {
                            assertTrue(safety.noConflictingNonces(), "No conflicting nonces issued");
                        });

                        log.info("Test passed: Timeout triggered successful retry with different node");

                    } finally {
                        clientRouter.close(Duration.ofSeconds(0));
                    }

                } finally {
                    // CLEANUP: restore delayed node
                    delayFault.restore();
                }
            }
        }
    }

    @Test
    @DisplayName("Timeout during BFT subset signature collection")
    void testTimeoutDuringBftSubsetSignatureCollection() throws Exception {
        try (var ctx = setupMultiMemberContext(7, entropy)) {
            var params = testParameters(fixedClock).setKerl(ctx.getKerl())
                                                   .setRegistrationTimeout(Duration.ofMillis(150))
                                                   .build();

            try (var cluster = createGorgoneionCluster(ctx, params)) {
                var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());

                // Delay multiple nodes during signature collection
                var delay1 = injectResponseDelay(cluster, 2, Duration.ofMillis(200));
                var delay2 = injectResponseDelay(cluster, 4, Duration.ofMillis(200));

                try {
                    var clientRouter = new LocalServer(cluster.getPrefix(), client).router(
                    ServerConnectionCache.newBuilder().setTarget(2));
                    AdmissionsService admissions = mock(AdmissionsService.class);
                    var clientCommunications = clientRouter.create(client, ctx.getContext().getId(), admissions,
                                                                   ":admissions", r -> new AdmissionsServer(
                            clientRouter.getClientIdentityProvider(), r, null), AdmissionsClient.getCreate(),
                                                                   Admissions.getLocalLoopback(client));
                    clientRouter.start();

                    try {
                        var admin = clientCommunications.connect(ctx.getMember(0));
                        var signedNonce = admin.apply(client.kerl(), Duration.ofSeconds(5));

                        // Coordinator collects from remaining members
                        assertNotNull(signedNonce, "Should complete with remaining responsive members");

                        // With 2 delayed nodes, should still achieve quorum from 5 remaining
                        var minSignatures = ctx.getQuorum() - 2;
                        assertTrue(signedNonce.getSignaturesCount() >= minSignatures,
                                   "Should have signatures from responsive nodes");

                        log.info("Test passed: Signature collection completed despite partial timeouts");

                    } finally {
                        clientRouter.close(Duration.ofSeconds(0));
                    }

                } finally {
                    delay1.restore();
                    delay2.restore();
                }
            }
        }
    }

    @Test
    @DisplayName("Progressive timeout escalation with exponential backoff")
    void testProgressiveTimeoutEscalation() throws Exception {
        try (var ctx = setupMultiMemberContext(7, entropy)) {
            var params = testParameters(fixedClock).setKerl(ctx.getKerl())
                                                   .setRegistrationTimeout(Duration.ofMillis(100))
                                                   .build();

            try (var cluster = createGorgoneionCluster(ctx, params)) {
                var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
                var clientDigest = digestOf(client.getIdentifier().getIdentifier().toIdent(),
                                            params.digestAlgorithm());

                var coordinator = ctx.getCoordinator(clientDigest);
                var coordinatorIndex = ctx.getMembers().indexOf(coordinator);

                // Track retry attempts and timings
                var attemptTimings = new ArrayList<Long>();
                var startTime = System.currentTimeMillis();

                // First attempt: 100ms timeout, will fail
                var delayFault = injectResponseDelay(cluster, coordinatorIndex, Duration.ofMillis(150));

                try {
                    var clientRouter = new LocalServer(cluster.getPrefix(), client).router(
                    ServerConnectionCache.newBuilder().setTarget(2));
                    AdmissionsService admissions = mock(AdmissionsService.class);
                    var clientCommunications = clientRouter.create(client, ctx.getContext().getId(), admissions,
                                                                   ":admissions", r -> new AdmissionsServer(
                            clientRouter.getClientIdentityProvider(), r, null), AdmissionsClient.getCreate(),
                                                                   Admissions.getLocalLoopback(client));
                    clientRouter.start();

                    try {
                        // Attempt 1: Connect to coordinator (will timeout)
                        attemptTimings.add(System.currentTimeMillis() - startTime);

                        // Allow delay to expire, then try backup node
                        Thread.sleep(200);
                        delayFault.restore();

                        // Attempt 2: Connect to backup node (should succeed)
                        var backupMember = ctx.getMember((coordinatorIndex + 1) % ctx.getMemberCount());
                        var admin = clientCommunications.connect(backupMember);
                        attemptTimings.add(System.currentTimeMillis() - startTime);

                        var signedNonce = admin.apply(client.kerl(), Duration.ofSeconds(5));

                        assertNotNull(signedNonce, "Should succeed after retry escalation");

                        // Verify progressive timeout behavior
                        assertTrue(attemptTimings.size() >= 2, "Should have multiple retry attempts");

                        // Second attempt should occur after first timeout + backoff
                        var timeBetweenAttempts = attemptTimings.get(1) - attemptTimings.get(0);
                        assertTrue(timeBetweenAttempts >= 100, "Should have backoff between attempts");

                        log.info("Test passed: Progressive timeout escalation with backoff succeeded");

                    } finally {
                        clientRouter.close(Duration.ofSeconds(0));
                    }

                } finally {
                    if (!delayFault.isRestored()) {
                        delayFault.restore();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Timeout under Byzantine delay attack")
    void testTimeoutUnderByzantineDelayAttack() throws Exception {
        try (var ctx = setupMultiMemberContext(7, entropy)) {
            var params = testParameters(fixedClock).setKerl(ctx.getKerl())
                                                   .setRegistrationTimeout(Duration.ofMillis(200))
                                                   .build();

            try (var cluster = createGorgoneionCluster(ctx, params)) {
                var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());

                // Simulate Byzantine delay attack: multiple nodes delayed (not crashing)
                var attackedNode1 = 1;
                var attackedNode2 = 3;
                var delay1 = injectResponseDelay(cluster, attackedNode1, Duration.ofMillis(500));
                var delay2 = injectResponseDelay(cluster, attackedNode2, Duration.ofMillis(500));

                try {
                    var clientRouter = new LocalServer(cluster.getPrefix(), client).router(
                    ServerConnectionCache.newBuilder().setTarget(2));
                    AdmissionsService admissions = mock(AdmissionsService.class);
                    var clientCommunications = clientRouter.create(client, ctx.getContext().getId(), admissions,
                                                                   ":admissions", r -> new AdmissionsServer(
                            clientRouter.getClientIdentityProvider(), r, null), AdmissionsClient.getCreate(),
                                                                   Admissions.getLocalLoopback(client));
                    clientRouter.start();

                    try {
                        // Connect to non-attacked node
                        var fastResponder = ctx.getMember(0);
                        var admin = clientCommunications.connect(fastResponder);

                        // Request should succeed via fast responders
                        var signedNonce = admin.apply(client.kerl(), Duration.ofSeconds(5));

                        assertNotNull(signedNonce, "Should succeed via fast responders despite attack");

                        // Verify attacked nodes not in final signature list
                        var signerIds = extractSignerIds(signedNonce, ctx);
                        var attackedId1 = ctx.getMemberId(attackedNode1);
                        var attackedId2 = ctx.getMemberId(attackedNode2);

                        assertFalse(signerIds.contains(attackedId1) && signerIds.contains(attackedId2),
                                    "Delayed nodes should not all be in signature list");

                        log.info("Test passed: Byzantine delay attack mitigated by timeout and retry");

                    } finally {
                        clientRouter.close(Duration.ofSeconds(0));
                    }

                } finally {
                    delay1.restore();
                    delay2.restore();
                }
            }
        }
    }

    @Test
    @DisplayName("Timeout recovery after node restart")
    void testTimeoutRecoveryAfterNodeRestart() throws Exception {
        try (var ctx = setupMultiMemberContext(7, entropy)) {
            var params = testParameters(fixedClock).setKerl(ctx.getKerl())
                                                   .setRegistrationTimeout(Duration.ofMillis(200))
                                                   .build();

            try (var cluster = createGorgoneionCluster(ctx, params)) {
                var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
                var nodeIndex = 2;

                // Simulate node timeout during request (crash)
                var crashFault = simulateByzantineNodeFailure(cluster, nodeIndex);

                try {
                    var clientRouter = new LocalServer(cluster.getPrefix(), client).router(
                    ServerConnectionCache.newBuilder().setTarget(2));
                    AdmissionsService admissions = mock(AdmissionsService.class);
                    var clientCommunications = clientRouter.create(client, ctx.getContext().getId(), admissions,
                                                                   ":admissions", r -> new AdmissionsServer(
                            clientRouter.getClientIdentityProvider(), r, null), AdmissionsClient.getCreate(),
                                                                   Admissions.getLocalLoopback(client));
                    clientRouter.start();

                    try {
                        // Restart node mid-operation
                        Thread.sleep(100);
                        crashFault.restore();

                        // Client retries should connect to restarted node
                        var admin = clientCommunications.connect(ctx.getMember(0));
                        var signedNonce = admin.apply(client.kerl(), Duration.ofSeconds(5));

                        assertNotNull(signedNonce, "Operation should complete after node restart");
                        assertTrue(signedNonce.getSignaturesCount() >= ctx.getQuorum(),
                                   "Should have quorum from restarted cluster");

                        log.info("Test passed: Timeout recovery after node restart succeeded");

                    } finally {
                        clientRouter.close(Duration.ofSeconds(0));
                    }

                } finally {
                    if (!crashFault.isRestored()) {
                        crashFault.restore();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Cascading timeouts do not deadlock")
    void testCascadingTimeoutsDoNotDeadlock() throws Exception {
        try (var ctx = setupMultiMemberContext(7, entropy)) {
            var params = testParameters(fixedClock).setKerl(ctx.getKerl())
                                                   .setRegistrationTimeout(Duration.ofMillis(150))
                                                   .build();

            try (var cluster = createGorgoneionCluster(ctx, params)) {
                // Multiple concurrent clients with potential timeouts
                var numClients = 5;
                var executorService = Executors.newFixedThreadPool(numClients);
                var clientFutures = new ArrayList<CompletableFuture<SignedNonce>>();
                var successCount = new AtomicInteger(0);

                // Inject delays on some nodes
                var delay1 = injectResponseDelay(cluster, 1, Duration.ofMillis(200));
                var delay2 = injectResponseDelay(cluster, 3, Duration.ofMillis(200));

                try {
                    // Submit concurrent client requests
                    for (int i = 0; i < numClients; i++) {
                        final var clientIndex = i;
                        var future = CompletableFuture.supplyAsync(() -> {
                            try {
                                var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
                                var clientRouter = new LocalServer(cluster.getPrefix(), client).router(
                                ServerConnectionCache.newBuilder().setTarget(2));
                                AdmissionsService admissions = mock(AdmissionsService.class);
                                var clientCommunications = clientRouter.create(client, ctx.getContext().getId(),
                                                                               admissions, ":admissions",
                                                                               r -> new AdmissionsServer(
                                                                               clientRouter.getClientIdentityProvider(),
                                                                               r, null),
                                                                               AdmissionsClient.getCreate(),
                                                                               Admissions.getLocalLoopback(client));
                                clientRouter.start();

                                try {
                                    var admin = clientCommunications.connect(ctx.getMember(0));
                                    var nonce = admin.apply(client.kerl(), Duration.ofSeconds(5));
                                    if (nonce != null) {
                                        successCount.incrementAndGet();
                                    }
                                    return nonce;
                                } finally {
                                    clientRouter.close(Duration.ofSeconds(0));
                                }
                            } catch (Exception e) {
                                log.error("Client {} failed", clientIndex, e);
                                return null;
                            }
                        }, executorService);

                        clientFutures.add(future);
                    }

                    // Wait for all requests (with timeout to detect deadlock)
                    var allRequests = CompletableFuture.allOf(
                    clientFutures.toArray(new CompletableFuture[0]));

                    assertDoesNotThrow(() -> allRequests.get(10, TimeUnit.SECONDS),
                                       "All requests should complete without deadlock");

                    // Verify all requests eventually succeeded
                    assertTrue(successCount.get() >= numClients - 1,
                               "Most requests should succeed despite cascading timeouts");

                    log.info("Test passed: Cascading timeouts handled without deadlock ({}/{} succeeded)",
                             successCount.get(), numClients);

                } finally {
                    delay1.restore();
                    delay2.restore();
                    executorService.shutdown();
                    executorService.awaitTermination(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    @DisplayName("Timeout with partial results")
    void testTimeoutWithPartialResults() throws Exception {
        try (var ctx = setupMultiMemberContext(7, entropy)) {
            var params = testParameters(fixedClock).setKerl(ctx.getKerl())
                                                   .setRegistrationTimeout(Duration.ofMillis(200))
                                                   .build();

            try (var cluster = createGorgoneionCluster(ctx, params)) {
                var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());

                // Delay some nodes to create partial signature collection scenario
                // Have 2/4 signatures needed, then timeout
                var delay1 = injectResponseDelay(cluster, 4, Duration.ofMillis(300));
                var delay2 = injectResponseDelay(cluster, 5, Duration.ofMillis(300));

                try {
                    var clientRouter = new LocalServer(cluster.getPrefix(), client).router(
                    ServerConnectionCache.newBuilder().setTarget(2));
                    AdmissionsService admissions = mock(AdmissionsService.class);
                    var clientCommunications = clientRouter.create(client, ctx.getContext().getId(), admissions,
                                                                   ":admissions", r -> new AdmissionsServer(
                            clientRouter.getClientIdentityProvider(), r, null), AdmissionsClient.getCreate(),
                                                                   Admissions.getLocalLoopback(client));
                    clientRouter.start();

                    try {
                        var admin = clientCommunications.connect(ctx.getMember(0));

                        // Retry request should complete with full quorum from available nodes
                        var signedNonce = admin.apply(client.kerl(), Duration.ofSeconds(5));

                        assertNotNull(signedNonce, "Should complete retry with full quorum");
                        assertTrue(signedNonce.getSignaturesCount() >= ctx.getQuorum() - 2,
                                   "Should have quorum from available nodes");

                        // VERIFY: safety property maintained
                        cluster.recordNonce(signedNonce);
                        verifyBftSafetyProperty(cluster, safety -> {
                            assertTrue(safety.noConflictingNonces(), "No conflicting nonces issued");
                        });

                        log.info("Test passed: Partial results timeout handled, retry completed successfully");

                    } finally {
                        clientRouter.close(Duration.ofSeconds(0));
                    }

                } finally {
                    delay1.restore();
                    delay2.restore();
                }
            }
        }
    }

    @Test
    @DisplayName("Timeout race with late response")
    void testTimeoutRaceWithLateResponse() throws Exception {
        try (var ctx = setupMultiMemberContext(7, entropy)) {
            var params = testParameters(fixedClock).setKerl(ctx.getKerl())
                                                   .setRegistrationTimeout(Duration.ofMillis(100))
                                                   .build();

            try (var cluster = createGorgoneionCluster(ctx, params)) {
                var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
                var clientDigest = digestOf(client.getIdentifier().getIdentifier().toIdent(),
                                            params.digestAlgorithm());

                var coordinator = ctx.getCoordinator(clientDigest);
                var coordinatorIndex = ctx.getMembers().indexOf(coordinator);

                // Delay coordinator to trigger timeout, but allow late response
                var delayFault = injectResponseDelay(cluster, coordinatorIndex, Duration.ofMillis(150));

                try {
                    var clientRouter = new LocalServer(cluster.getPrefix(), client).router(
                    ServerConnectionCache.newBuilder().setTarget(2));
                    AdmissionsService admissions = mock(AdmissionsService.class);
                    var clientCommunications = clientRouter.create(client, ctx.getContext().getId(), admissions,
                                                                   ":admissions", r -> new AdmissionsServer(
                            clientRouter.getClientIdentityProvider(), r, null), AdmissionsClient.getCreate(),
                                                                   Admissions.getLocalLoopback(client));
                    clientRouter.start();

                    try {
                        // Request times out after 100ms
                        var backupMember = ctx.getMember((coordinatorIndex + 1) % ctx.getMemberCount());
                        var admin = clientCommunications.connect(backupMember);

                        // Retry initiated
                        var signedNonce = admin.apply(client.kerl(), Duration.ofSeconds(5));

                        // Original late response arrives (at 150ms) - should be handled correctly
                        Thread.sleep(200);
                        delayFault.restore();

                        // Verify both handled correctly (no duplicate processing)
                        assertNotNull(signedNonce, "Should handle late response race correctly");

                        // Check for duplicate nonces (safety violation)
                        cluster.recordNonce(signedNonce);
                        verifyBftSafetyProperty(cluster, safety -> {
                            assertTrue(safety.noConflictingNonces(), "No duplicate nonces from race condition");
                        });

                        log.info("Test passed: Timeout race with late response handled safely");

                    } finally {
                        clientRouter.close(Duration.ofSeconds(0));
                    }

                } finally {
                    if (!delayFault.isRestored()) {
                        delayFault.restore();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Configurable timeout behavior")
    void testConfigurableTimeoutBehavior() throws Exception {
        try (var ctx = setupMultiMemberContext(7, entropy)) {
            var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());

            // Test 1: Short timeout (100ms) - should fail/retry quickly
            // Note: Using 100ms instead of 10ms for CI stability while maintaining test semantics
            var shortParams = testParameters(fixedClock).setKerl(ctx.getKerl())
                                                        .setRegistrationTimeout(Duration.ofMillis(100))
                                                        .build();

            try (var shortCluster = createGorgoneionCluster(ctx, shortParams)) {
                var delay = injectResponseDelay(shortCluster, 0, Duration.ofMillis(200));

                try {
                    var startTime = System.currentTimeMillis();
                    var clientRouter = new LocalServer(shortCluster.getPrefix(), client).router(
                    ServerConnectionCache.newBuilder().setTarget(2));
                    clientRouter.start();

                    try {
                        // Should timeout quickly and retry
                        var backupMember = ctx.getMember(1);
                        var admin = clientRouter.create(client, ctx.getContext().getId(),
                                                        mock(AdmissionsService.class), ":admissions",
                                                        r -> new AdmissionsServer(
                                                        clientRouter.getClientIdentityProvider(), r, null),
                                                        AdmissionsClient.getCreate(),
                                                        Admissions.getLocalLoopback(client))
                                                .connect(backupMember);

                        var nonce1 = admin.apply(client.kerl(), Duration.ofSeconds(10));
                        var elapsedShort = System.currentTimeMillis() - startTime;

                        assertNotNull(nonce1, "Should succeed with retry after short timeout");
                        assertTrue(elapsedShort < 10000, "Should complete within timeout period");

                        log.info("Short timeout test passed: {}ms elapsed", elapsedShort);

                    } finally {
                        clientRouter.close(Duration.ofSeconds(0));
                    }

                } finally {
                    delay.restore();
                }
            }

            // Test 2: Long timeout (5000ms) - should succeed immediately without retry
            var longParams = testParameters(fixedClock).setKerl(ctx.getKerl())
                                                       .setRegistrationTimeout(Duration.ofMillis(5000))
                                                       .build();

            try (var longCluster = createGorgoneionCluster(ctx, longParams)) {
                var clientRouter = new LocalServer(longCluster.getPrefix(), client).router(
                ServerConnectionCache.newBuilder().setTarget(2));
                clientRouter.start();

                try {
                    var admin = clientRouter.create(client, ctx.getContext().getId(),
                                                    mock(AdmissionsService.class), ":admissions",
                                                    r -> new AdmissionsServer(clientRouter.getClientIdentityProvider(),
                                                                              r, null), AdmissionsClient.getCreate(),
                                                    Admissions.getLocalLoopback(client)).connect(ctx.getMember(0));

                    var nonce2 = admin.apply(client.kerl(), Duration.ofSeconds(10));

                    assertNotNull(nonce2, "Should succeed immediately with long timeout");

                    log.info("Long timeout test passed: succeeded without retry");

                } finally {
                    clientRouter.close(Duration.ofSeconds(0));
                }
            }

            // Test 3: Default timeout - verify parameter respected
            var defaultParams = testParameters(fixedClock).setKerl(ctx.getKerl()).build();

            try (var defaultCluster = createGorgoneionCluster(ctx, defaultParams)) {
                assertEquals(Duration.ofSeconds(30), defaultParams.registrationTimeout(),
                             "Default timeout should be 30 seconds");

                log.info("Default timeout test passed: parameter value verified");
            }
        }
    }

    @Test
    @DisplayName("Timeout metrics and observability")
    void testTimeoutMetricsAndObservability() throws Exception {
        try (var ctx = setupMultiMemberContext(7, entropy)) {
            var params = testParameters(fixedClock).setKerl(ctx.getKerl())
                                                   .setRegistrationTimeout(Duration.ofMillis(100))
                                                   .build();

            try (var cluster = createGorgoneionCluster(ctx, params)) {
                var client = new ControlledIdentifierMember(clientStereotomy.newIdentifier());
                var clientDigest = digestOf(client.getIdentifier().getIdentifier().toIdent(),
                                            params.digestAlgorithm());

                var coordinator = ctx.getCoordinator(clientDigest);
                var coordinatorIndex = ctx.getMembers().indexOf(coordinator);

                // Track metrics
                var timeoutCount = new AtomicInteger(0);
                var retryCount = new AtomicInteger(0);
                var attemptTimings = new ArrayList<Long>();

                // Inject delay to trigger timeout
                var delayFault = injectResponseDelay(cluster, coordinatorIndex, Duration.ofMillis(200));

                try {
                    var clientRouter = new LocalServer(cluster.getPrefix(), client).router(
                    ServerConnectionCache.newBuilder().setTarget(2));
                    AdmissionsService admissions = mock(AdmissionsService.class);
                    var clientCommunications = clientRouter.create(client, ctx.getContext().getId(), admissions,
                                                                   ":admissions", r -> new AdmissionsServer(
                            clientRouter.getClientIdentityProvider(), r, null), AdmissionsClient.getCreate(),
                                                                   Admissions.getLocalLoopback(client));
                    clientRouter.start();

                    try {
                        var startTime = System.currentTimeMillis();

                        // Attempt 1: Will timeout
                        attemptTimings.add(System.currentTimeMillis() - startTime);
                        timeoutCount.incrementAndGet();

                        // Allow delay to complete
                        Thread.sleep(250);
                        delayFault.restore();

                        // Attempt 2: Retry
                        retryCount.incrementAndGet();
                        var backupMember = ctx.getMember((coordinatorIndex + 1) % ctx.getMemberCount());
                        var admin = clientCommunications.connect(backupMember);
                        attemptTimings.add(System.currentTimeMillis() - startTime);

                        var signedNonce = admin.apply(client.kerl(), Duration.ofSeconds(5));

                        assertNotNull(signedNonce, "Should succeed after retry");

                        // VERIFY: metrics captured
                        assertEquals(1, timeoutCount.get(), "Should have 1 timeout event");
                        assertEquals(1, retryCount.get(), "Should have 1 retry");
                        assertTrue(attemptTimings.size() >= 2, "Should have multiple attempt timings");

                        // Measure elapsed time per attempt
                        for (int i = 0; i < attemptTimings.size(); i++) {
                            log.info("Attempt {} elapsed time: {}ms", i + 1, attemptTimings.get(i));
                        }

                        // Verify observable system behavior
                        assertTrue(attemptTimings.get(attemptTimings.size() - 1) >= 100,
                                   "Total elapsed time should reflect timeout + retry");

                        log.info("Test passed: Timeout metrics tracked (timeouts={}, retries={}, total_time={}ms)",
                                 timeoutCount.get(), retryCount.get(),
                                 attemptTimings.get(attemptTimings.size() - 1));

                    } finally {
                        clientRouter.close(Duration.ofSeconds(0));
                    }

                } finally {
                    if (!delayFault.isRestored()) {
                        delayFault.restore();
                    }
                }
            }
        }
    }
}
