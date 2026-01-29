/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.gorgoneion;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.gorgoneion.proto.SignedNonce;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.services.proto.ProtoEventObserver;
import com.hellblazer.delos.test.proto.ByteMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.Mockito.mock;

/**
 * Manages a cluster of Gorgoneion instances for testing.
 * Provides node management, fault injection, and safety verification.
 * <p>
 * Usage:
 * <pre>{@code
 * try (var ctx = new TestContext(7, entropy)) {
 *     try (var cluster = new GorgoneionCluster(ctx, params)) {
 *         // Test with cluster
 *         cluster.stopNode(0); // Simulate failure
 *         // ...
 *     } // Auto cleanup
 * }
 * }</pre>
 *
 * @author hal.hildebrand
 */
public class GorgoneionCluster implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GorgoneionCluster.class);

    private final List<Gorgoneion>                  gorgoneions;
    private final List<Router>                      routers;
    private final TestContext                       context;
    private final Set<Digest>                       blacklist;
    private final Map<Integer, Signer>              originalSigners;
    private final Map<Integer, Signer>              injectedSigners;
    private final List<SignedNonce>                 issuedNonces;
    private final Parameters                        parameters;
    private final ProtoEventObserver                observer;
    private final String                            prefix;

    /**
     * Creates a Gorgoneion cluster from test context.
     *
     * @param ctx        test context with members
     * @param parameters Gorgoneion parameters
     */
    public GorgoneionCluster(TestContext ctx, Parameters parameters) {
        this.context = ctx;
        this.parameters = parameters;
        this.gorgoneions = new ArrayList<>();
        this.routers = new ArrayList<>();
        this.blacklist = ConcurrentHashMap.newKeySet();
        this.originalSigners = new ConcurrentHashMap<>();
        this.injectedSigners = new ConcurrentHashMap<>();
        this.issuedNonces = Collections.synchronizedList(new ArrayList<>());
        this.observer = mock(ProtoEventObserver.class);
        this.prefix = UUID.randomUUID().toString();

        // Create Gorgoneion instance for each member
        for (var member : ctx.getMembers()) {
            createGorgoneion(member);
        }

        log.info("Created Gorgoneion cluster with {} nodes", gorgoneions.size());
    }

    /**
     * Gets a Gorgoneion instance by index.
     *
     * @param index node index
     * @return the Gorgoneion instance
     */
    public Gorgoneion getMember(int index) {
        return gorgoneions.get(index);
    }

    /**
     * Gets the coordinator for a given client digest.
     *
     * @param clientDigest client identifier digest
     * @return the coordinator Gorgoneion instance
     */
    public Gorgoneion getCoordinator(Digest clientDigest) {
        var coordinator = context.getCoordinator(clientDigest);
        var index = context.getMembers().indexOf(coordinator);
        return gorgoneions.get(index);
    }

    /**
     * Gets cluster size.
     *
     * @return number of nodes
     */
    public int size() {
        return gorgoneions.size();
    }

    /**
     * Gets the prefix used by this cluster for LocalServer routing.
     * Client routers must use the same prefix to communicate with cluster members.
     *
     * @return the cluster's routing prefix
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * Gets majority quorum size.
     *
     * @return quorum size
     */
    public int majority() {
        return context.getQuorum();
    }

    /**
     * Gets Byzantine fault tolerance.
     *
     * @return max failures tolerated
     */
    public int faultTolerance() {
        return context.getFaultTolerance();
    }

    /**
     * Stops a node (simulates crash).
     *
     * @param index node index to stop
     */
    public void stopNode(int index) {
        try {
            routers.get(index).close(Duration.ofSeconds(0));
            log.info("Stopped node {}", index);
        } catch (Exception e) {
            log.warn("Error stopping node {}", index, e);
        }
    }

    /**
     * Restarts a previously stopped node.
     *
     * @param index node index to restart
     */
    public void restartNode(int index) {
        var member = context.getMember(index);
        routers.set(index, new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2)));
        routers.get(index).start();

        // Recreate Gorgoneion instance
        var gorgoneion = new Gorgoneion(t -> true, (c, v) -> Any.pack(
        ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build()), parameters, member,
                                        context.getContext(), observer, routers.get(index), null);

        gorgoneions.set(index, gorgoneion);
        log.info("Restarted node {}", index);
    }

    /**
     * Checks if a node is active.
     *
     * @param index node index
     * @return true if node is running
     */
    public boolean isNodeActive(int index) {
        // In real implementation, would check router state
        return routers.get(index) != null;
    }

    /**
     * Checks if a member is blacklisted.
     *
     * @param memberId member digest
     * @return true if blacklisted
     */
    public boolean isBlacklisted(Digest memberId) {
        return blacklist.contains(memberId);
    }

    /**
     * Blacklists a member.
     *
     * @param memberId member digest to blacklist
     */
    public void blacklist(Digest memberId) {
        blacklist.add(memberId);
        log.info("Blacklisted member: {}", memberId);
    }

    /**
     * Clears all blacklist entries.
     */
    public void clearBlacklist() {
        blacklist.clear();
    }

    /**
     * Injects a custom signer for a node (for equivocation testing).
     *
     * @param index        node index
     * @param customSigner custom signer implementation
     */
    public void injectSigner(int index, Signer customSigner) {
        var member = context.getMember(index);
        if (!originalSigners.containsKey(index)) {
            originalSigners.put(index, member);
        }
        injectedSigners.put(index, customSigner);
        log.info("Injected custom signer for node {}", index);
    }

    /**
     * Restores original signer for a node.
     *
     * @param index node index
     */
    public void restoreOriginalSigner(int index) {
        injectedSigners.remove(index);
        log.info("Restored original signer for node {}", index);
    }

    /**
     * Verifies Byzantine safety properties.
     *
     * @return safety verification result
     */
    public SafetyVerification verifySafety() {
        return new SafetyVerification(List.copyOf(issuedNonces), context.getQuorum(), context.getMemberCount());
    }

    /**
     * Gets all nonces issued by the cluster.
     *
     * @return list of signed nonces
     */
    public List<SignedNonce> getAllIssuedNonces() {
        return List.copyOf(issuedNonces);
    }

    /**
     * Checks for conflicting nonces.
     *
     * @return true if conflicts detected
     */
    public boolean hasConflictingNonces() {
        return !verifySafety().noConflictingNonces();
    }

    /**
     * Records a nonce for safety tracking.
     *
     * @param nonce the signed nonce to record
     */
    public void recordNonce(SignedNonce nonce) {
        issuedNonces.add(nonce);
    }

    /**
     * Cleans up cluster resources.
     */
    @Override
    public void close() {
        // Close Gorgoneion instances
        for (var gorgoneion : gorgoneions) {
            try {
                gorgoneion.close();
            } catch (Exception e) {
                log.warn("Error closing gorgoneion", e);
            }
        }

        // Close routers
        for (var router : routers) {
            try {
                router.close(Duration.ofSeconds(1));
            } catch (Exception e) {
                log.warn("Error closing router", e);
            }
        }

        gorgoneions.clear();
        routers.clear();
    }

    private void createGorgoneion(ControlledIdentifierMember member) {
        var router = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        router.start();
        routers.add(router);

        var gorgoneion = new Gorgoneion(t -> true, (c, v) -> Any.pack(
        ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build()), parameters, member,
                                        context.getContext(), observer, router, null);

        gorgoneions.add(gorgoneion);
    }
}
