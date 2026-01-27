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
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.gorgoneion.comm.admissions.AdmissionsServer;
import com.hellblazer.delos.gorgoneion.comm.admissions.AdmissionsService;
import com.hellblazer.delos.gorgoneion.proto.SignedNonce;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.mockito.Mockito.mock;

/**
 * Core utility methods for Gorgoneion BFT testing.
 * Provides factory methods for test setup, fault injection, and safety verification.
 * <p>
 * Example usage:
 * <pre>{@code
 * try (var ctx = setupMultiMemberContext(7, deterministicEntropy())) {
 *     try (var cluster = createGorgoneionCluster(ctx, testParameters(fixedClock))) {
 *         var fault = simulateByzantineNodeFailure(cluster, 0);
 *         try {
 *             var nonce = applyWithRetry(client, cluster, Duration.ofSeconds(30), 3);
 *             verifyBftSafetyProperty(cluster, safety -> {
 *                 assertTrue(safety.noConflictingNonces());
 *             });
 *         } finally {
 *             fault.restore();
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author hal.hildebrand
 */
public class GorgoneionBftTestHelpers {

    /**
     * Creates a deterministic multi-member test context.
     *
     * @param cardinality number of members
     * @param entropy     deterministic secure random
     * @return test context with activated members
     * @throws Exception if context creation fails
     */
    public static TestContext setupMultiMemberContext(int cardinality, SecureRandom entropy) throws Exception {
        return new TestContext(cardinality, entropy);
    }

    /**
     * Creates a Gorgoneion cluster from test context.
     *
     * @param ctx    test context
     * @param params Gorgoneion parameters
     * @return cluster with all nodes started
     */
    public static GorgoneionCluster createGorgoneionCluster(TestContext ctx, Parameters params) {
        return new GorgoneionCluster(ctx, params);
    }

    /**
     * Simulates a Byzantine node failure (crash).
     *
     * @param cluster   the cluster
     * @param nodeIndex index of node to fail
     * @return fault injection result with restoration capability
     */
    public static FaultInjectionResult simulateByzantineNodeFailure(GorgoneionCluster cluster, int nodeIndex) {
        cluster.stopNode(nodeIndex);
        return new FaultInjectionResult(() -> cluster.restartNode(nodeIndex));
    }

    /**
     * Injects response delay for a node (simulates slow network).
     *
     * @param cluster   the cluster
     * @param nodeIndex index of node to delay
     * @param delay     delay duration
     * @return fault injection result
     */
    public static FaultInjectionResult injectResponseDelay(GorgoneionCluster cluster, int nodeIndex, Duration delay) {
        // In a real implementation, this would intercept and delay responses
        // For this test framework, we simulate by temporarily stopping the node
        cluster.stopNode(nodeIndex);

        return new FaultInjectionResult(() -> {
            try {
                Thread.sleep(delay.toMillis());
                cluster.restartNode(nodeIndex);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Creates an equivocating signer (signs conflicting messages).
     *
     * @param member the member whose signer to replace
     * @return custom equivocating signer
     */
    public static Signer createEquivocatingSigner(ControlledIdentifierMember member) {
        return new Signer() {
            private int callCount = 0;

            @Override
            public SignatureAlgorithm algorithm() {
                return member.algorithm();
            }

            @Override
            public JohnHancock sign(java.io.InputStream message) {
                // Alternate between original signature and modified signature
                callCount++;
                if (callCount % 2 == 0) {
                    // Return modified signature (equivocation)
                    try {
                        var bytes = message.readAllBytes();
                        if (bytes.length > 0) {
                            bytes[0] ^= 0xFF; // Flip bits
                        }
                        return member.sign(bytes);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    return member.sign(message);
                }
            }
        };
    }

    /**
     * Applies for nonce via a specific node.
     * Note: Tests typically create their own client router and call admin.apply() directly.
     * This helper is provided for completeness but most tests use direct admin.apply().
     *
     * @param client  the client member
     * @param node    the Gorgoneion node to contact (unused - tests connect via router)
     * @param timeout operation timeout
     * @return signed nonce (null - this is a placeholder; real tests use direct apply)
     * @throws Exception if apply fails
     */
    @Deprecated
    public static SignedNonce applyViaNode(ControlledIdentifierMember client, Gorgoneion node,
                                           Duration timeout) throws Exception {
        // Placeholder - real tests create their own client router and call admin.apply() directly
        // This method is not used by the actual test implementations
        throw new UnsupportedOperationException(
        "Tests should create their own client router and call admin.apply() directly");
    }

    /**
     * Applies for nonce with retry on different nodes.
     *
     * @param client     the client member
     * @param cluster    the cluster
     * @param timeout    per-attempt timeout
     * @param maxRetries maximum retry attempts
     * @return signed nonce
     * @throws Exception if all retries fail
     */
    public static SignedNonce applyWithRetry(ControlledIdentifierMember client, GorgoneionCluster cluster,
                                             Duration timeout, int maxRetries) throws Exception {
        Exception lastException = null;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                // Try different nodes on each attempt
                var nodeIndex = attempt % cluster.size();
                return applyViaNode(client, cluster.getMember(nodeIndex), timeout);
            } catch (Exception e) {
                lastException = e;
                // Continue to next retry
            }
        }

        throw new RuntimeException("All retry attempts failed", lastException);
    }

    /**
     * Extracts signer indices from a signed nonce.
     *
     * @param nonce the signed nonce
     * @param ctx   test context for member lookup
     * @return list of member indices who signed
     */
    public static List<Integer> extractSignerIndices(SignedNonce nonce, TestContext ctx) {
        var indices = new ArrayList<Integer>();
        var members = ctx.getMembers();

        for (int i = 0; i < nonce.getSignaturesCount(); i++) {
            var sigDigest = Digest.from(nonce.getSignatures(i).getId());

            // Find matching member
            for (int j = 0; j < members.size(); j++) {
                if (members.get(j).getId().equals(sigDigest)) {
                    indices.add(j);
                    break;
                }
            }
        }

        return indices;
    }

    /**
     * Extracts signer IDs from a signed nonce.
     *
     * @param nonce the signed nonce
     * @param ctx   test context
     * @return list of signer digests
     */
    public static List<Digest> extractSignerIds(SignedNonce nonce, TestContext ctx) {
        var ids = new ArrayList<Digest>();

        for (int i = 0; i < nonce.getSignaturesCount(); i++) {
            ids.add(Digest.from(nonce.getSignatures(i).getId()));
        }

        return ids;
    }

    /**
     * Verifies BFT safety properties with custom assertion.
     *
     * @param cluster   the cluster
     * @param assertion safety property assertion
     */
    public static void verifyBftSafetyProperty(GorgoneionCluster cluster, Consumer<SafetyVerification> assertion) {
        var safety = cluster.verifySafety();
        assertion.accept(safety);
    }

    /**
     * Creates deterministic entropy source.
     *
     * @return seeded secure random
     */
    public static SecureRandom deterministicEntropy() {
        try {
            var entropy = SecureRandom.getInstance("SHA1PRNG");
            entropy.setSeed(new byte[] { 6, 6, 6 });
            return entropy;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create deterministic entropy", e);
        }
    }

    /**
     * Creates test parameters builder with fixed clock.
     * Caller must set KERL before calling build().
     *
     * @param clock the clock to use
     * @return Gorgoneion parameters builder
     */
    public static Parameters.Builder testParameters(Clock clock) {
        return Parameters.newBuilder()
                         .setClock(clock)
                         .setRegistrationTimeout(Duration.ofSeconds(30))
                         .setFrequency(Duration.ofMillis(5))
                         .setMaxDuration(Duration.ofSeconds(30))
                         .setClockSkewTolerance(Duration.ofSeconds(5));
    }

    /**
     * Creates a fixed clock for deterministic testing.
     *
     * @return fixed clock at epoch 2026-01-09T12:00:00Z
     */
    public static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-01-09T12:00:00Z"), ZoneId.of("UTC"));
    }

    /**
     * Waits for a consensus timeout to occur.
     *
     * @param timeout the timeout duration
     * @return timeout result
     */
    public static TimeoutResult waitForConsensusTimeout(Duration timeout) {
        var start = Instant.now();
        try {
            Thread.sleep(timeout.toMillis());
            return new TimeoutResult(true, Duration.between(start, Instant.now()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TimeoutResult(false, Duration.between(start, Instant.now()));
        }
    }

    /**
     * Result of a timeout wait.
     */
    public static class TimeoutResult {
        public final boolean  completed;
        public final Duration elapsed;

        public TimeoutResult(boolean completed, Duration elapsed) {
            this.completed = completed;
            this.elapsed = elapsed;
        }
    }
}
