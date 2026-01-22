/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.integration;

import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.archipelago.UnsafeExecutors;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.DynamicContextImpl;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.View;
import com.hellblazer.delos.fireflies.View.DrainPolicy;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Utils;
import com.hellblazer.delos.witness.WitnessCHOAM;
import com.hellblazer.delos.witness.WitnessCHOAM.NoGenesis;
import com.hellblazer.delos.witness.WitnessCHOAMParameters;
import com.hellblazer.delos.witness.detection.ByzantineDetector;
import com.hellblazer.delos.witness.detection.ByzantineDetectorImpl;
import com.hellblazer.delos.witness.detection.ByzantineDetectorConfig;
import com.hellblazer.delos.witness.validation.BLSAdversarialTestHelpers;
import com.hellblazer.delos.witness.validation.WitnessReceiptTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Base infrastructure for Phase 1C end-to-end integration tests.
 *
 * Provides reusable 7-node Byzantine-resilient cluster setup with:
 * - Fireflies membership protocol
 * - WitnessCHOAM for BLS signature aggregation
 * - Byzantine detection framework
 * - Helper methods for test execution
 *
 * Cluster Configuration:
 * - 7 nodes (committee size = 7)
 * - Byzantine tolerance: f=2 (supports 2 Byzantine nodes)
 * - Threshold: 5 signatures (2f+1 quorum)
 *
 * @author hal.hildebrand
 */
abstract public class Phase1CTestBase {

    protected static final int COMMITTEE_SIZE = 7;
    protected static final int THRESHOLD = 5;
    protected static final double PBYZ = 0.1;  // 10% Byzantine tolerance for 7 nodes
    protected static final Duration GOSSIP_DURATION = Duration.ofMillis(5);
    protected static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    // Infrastructure
    protected SecureRandom entropy;
    protected StereotomyImpl stereotomy;
    protected Map<Digest, ControlledIdentifier> identities;
    protected List<SigningMember> members;
    protected Map<Digest, Router> routers;
    protected List<View> views;
    protected Map<Digest, WitnessCHOAM> witnesses;
    protected ExecutorService executor;
    protected ScheduledExecutorService scheduler;
    protected Context<Member> firefliesContext;

    // Test helpers
    protected BLSAdversarialTestHelpers adversarialHelpers;
    protected WitnessReceiptTestHelper receiptHelper;
    protected ByzantineDetector byzantineDetector;
    protected Map<Digest, ByzantineDetectorImpl> detectorInstances;

    @BeforeEach
    public void setUp() throws Exception {
        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();
        scheduler = java.util.concurrent.Executors.newScheduledThreadPool(10, Thread.ofVirtual().factory());
        entropy = new SecureRandom();

        // Initialize identities
        stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(ALGORITHM), entropy);
        identities = new HashMap<>();
        members = new ArrayList<>();

        for (int i = 0; i < COMMITTEE_SIZE; i++) {
            var identifier = stereotomy.newIdentifier();
            var controlledId = new ControlledIdentifierMember(identifier);
            identities.put(controlledId.getId(), identifier);
            members.add(controlledId);
        }

        // Initialize Fireflies context
        Digest contextId = ALGORITHM.getOrigin();
        firefliesContext = new DynamicContextImpl<>(contextId, COMMITTEE_SIZE, PBYZ, 3);
        members.forEach(m -> firefliesContext.activate(m));

        // Initialize communications
        String prefix = UUID.randomUUID().toString();
        routers = new HashMap<>();
        for (SigningMember member : members) {
            var server = new LocalServer(prefix, member);
            var router = server.router(ServerConnectionCache.newBuilder().setTarget(30), executor);
            routers.put(member.getId(), router);
        }

        // Initialize Fireflies views
        views = new ArrayList<>();
        for (SigningMember member : members) {
            View view = new View(
                    member,
                    firefliesContext,
                    routers.get(member.getId()),
                    scheduler,
                    executor,
                    DrainPolicy.TAPERED
            );
            views.add(view);
        }

        // Bootstrap Fireflies: kernel + seeds pattern
        var callback = new View.ViewLifecycleHandler() {
            @Override
            public void onViewChange(View.ViewBlock newView) {
            }
        };

        // Start kernel node
        views.get(0).start(callback, GOSSIP_DURATION, Collections.emptyList());

        // Collect seeds from kernel
        List<View.Seed> seeds = new ArrayList<>();
        assertTrue(Utils.waitForCondition(30_000, 500, () -> views.get(0).getSeeds().size() > 0),
                "Kernel failed to generate seeds");
        seeds.addAll(views.get(0).getSeeds());

        // Start remaining nodes with seeds
        for (int i = 1; i < views.size(); i++) {
            views.get(i).start(callback, GOSSIP_DURATION, seeds);
        }

        // Wait for stabilization
        assertTrue(Utils.waitForCondition(60_000, 1_000,
                () -> views.stream().allMatch(v -> v.getContext().activeCount() == COMMITTEE_SIZE)),
                "Fireflies cluster failed to stabilize");

        // Initialize WitnessCHOAM instances
        witnesses = new HashMap<>();
        detectorInstances = new HashMap<>();
        for (SigningMember member : members) {
            var witnessParams = new WitnessCHOAMParameters();
            try {
                var witness = new WitnessCHOAM(member, witnessParams, views.stream()
                        .filter(v -> v.getMember().equals(member))
                        .findFirst()
                        .orElseThrow());
                witnesses.put(member.getId(), witness);

                // Initialize Byzantine detector for this member
                var config = ByzantineDetectorConfig.defaults();
                var detector = new ByzantineDetectorImpl(config);
                detectorInstances.put(member.getId(), detector);
            } catch (NoGenesis e) {
                // Handle genesis not yet available
                throw new RuntimeException("Failed to initialize WitnessCHOAM for member " + member.getId(), e);
            }
        }

        // Initialize test helpers
        adversarialHelpers = new BLSAdversarialTestHelpers();
        receiptHelper = new WitnessReceiptTestHelper();
    }

    @AfterEach
    public void tearDown() throws Exception {
        // Stop Fireflies views
        if (views != null) {
            for (View view : views) {
                try {
                    view.stop();
                } catch (Exception e) {
                    // Continue cleanup even if one fails
                }
            }
            views.clear();
            views = null;
        }

        // Close routers
        if (routers != null) {
            for (Router router : routers.values()) {
                try {
                    router.close(Duration.ofSeconds(0));
                } catch (Exception e) {
                    // Continue cleanup even if one fails
                }
            }
            routers.clear();
            routers = null;
        }

        // Stop WitnessCHOAM instances
        if (witnesses != null) {
            witnesses.values().forEach(w -> {
                try {
                    w.stop();
                } catch (Exception e) {
                    // Continue cleanup even if one fails
                }
            });
            witnesses.clear();
            witnesses = null;
        }

        // Shutdown executors
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        identities.clear();
        members.clear();
        detectorInstances.clear();
    }

    /**
     * Wait for cluster stabilization with configurable timeout
     */
    protected void waitForStabilization(Duration timeout) {
        long timeoutMs = timeout.toMillis();
        long startTime = System.currentTimeMillis();

        assertTrue(Utils.waitForCondition(timeoutMs, 500,
                () -> views.stream().allMatch(v -> v.getContext().activeCount() == COMMITTEE_SIZE)),
                "Cluster failed to stabilize within " + timeout);

        assertTrue(Utils.waitForCondition(timeoutMs - (System.currentTimeMillis() - startTime), 500,
                () -> witnesses.values().stream().allMatch(w -> w.isActive())),
                "Witnesses failed to become active within " + timeout);
    }

    /**
     * Inject Byzantine behavior into a node
     */
    protected void injectByzantineNode(SigningMember member) {
        var detector = detectorInstances.get(member.getId());
        if (detector != null) {
            detector.markAsSuspicious(member.getId(), 0.95);
        }
    }

    /**
     * Trigger a view change in the Fireflies cluster
     */
    protected void triggerViewChange(List<SigningMember> joining, List<SigningMember> leaving) {
        // Record initial state
        var initialView = views.get(0).getCurrentView();

        // Notify Fireflies of membership change
        for (View view : views) {
            view.suspend();
        }

        // Remove leaving members from context
        for (SigningMember member : leaving) {
            firefliesContext.removeMember(member);
        }

        // Resume views to trigger rebalancing
        for (View view : views) {
            view.resume();
        }

        // Wait for new view to stabilize
        assertTrue(Utils.waitForCondition(30_000, 1_000,
                () -> views.stream().allMatch(v -> v.getCurrentView() > initialView)),
                "View change failed to complete");
    }

    /**
     * Rotate keys for a member (simulated)
     */
    protected void rotateKeys(SigningMember member) {
        // In production, this would trigger KERI-based key rotation
        // For tests, we simulate successful completion
    }

    /**
     * Verify threshold was achieved for an event
     */
    protected void verifyThresholdAchieved(Digest eventId, Duration timeout) {
        long timeoutMs = timeout.toMillis();
        assertTrue(Utils.waitForCondition(timeoutMs, 100,
                () -> witnesses.values().stream()
                        .anyMatch(w -> w.hasThreshold(eventId))),
                "Threshold not achieved for event " + eventId + " within " + timeout);
    }

    /**
     * Get the witness for a specific member
     */
    protected WitnessCHOAM getWitness(SigningMember member) {
        return witnesses.get(member.getId());
    }

    /**
     * Get the Byzantine detector for a specific member
     */
    protected ByzantineDetector getDetector(SigningMember member) {
        return detectorInstances.get(member.getId());
    }

    /**
     * Get a random member from the cluster
     */
    protected SigningMember getRandomMember() {
        return members.get(entropy.nextInt(members.size()));
    }

    /**
     * Get n random members (excluding a specific member if provided)
     */
    protected List<SigningMember> getRandomMembers(int count, SigningMember exclude) {
        return members.stream()
                .filter(m -> exclude == null || !m.getId().equals(exclude.getId()))
                .collect(Collectors.collectingAndThen(
                        Collectors.toList(),
                        list -> {
                            Collections.shuffle(list, entropy);
                            return list.stream().limit(Math.min(count, list.size())).collect(Collectors.toList());
                        }
                ));
    }
}
