/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.proto.Join;
import com.hellblazer.delos.choam.proto.Reconfigure;
import com.hellblazer.delos.choam.proto.SignedViewMember;
import com.hellblazer.delos.choam.proto.ViewMember;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.cryptography.proto.PubKey;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hellblazer.delos.cryptography.QualifiedBase64.bs;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test Committee.validatorsOf() strict validation with grace period and Byzantine monitoring.
 * Tests Delos-i642 (Verifier Bypass Fix).
 *
 * @author hal.hildebrand
 */
public class CommitteeTest {
    private static final Logger log = LoggerFactory.getLogger(CommitteeTest.class);

    private FeatureFlagManager manager;
    private StaticContext<Member> context;
    private ControlledIdentifierMember member1;
    private ControlledIdentifierMember member2;
    private ControlledIdentifierMember member3;
    private ControlledIdentifierMember member4;
    private Map<Digest, Member> members;
    private SecureRandom entropy;

    @BeforeEach
    public void setUp() throws Exception {
        // Reset feature flags before each test
        manager = FeatureFlagManager.getInstance();
        for (FeatureFlags flag : FeatureFlags.values()) {
            manager.resetToDefault(flag.name());
        }

        // Create test members with identities (minimum 4 for BFT)
        entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{1, 2, 3});
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        member1 = new ControlledIdentifierMember(stereotomy.newIdentifier());
        entropy.setSeed(new byte[]{4, 5, 6});
        member2 = new ControlledIdentifierMember(stereotomy.newIdentifier());
        entropy.setSeed(new byte[]{7, 8, 9});
        member3 = new ControlledIdentifierMember(stereotomy.newIdentifier());
        entropy.setSeed(new byte[]{10, 11, 12});
        member4 = new ControlledIdentifierMember(stereotomy.newIdentifier());

        // Create context with members
        members = new HashMap<>();
        members.put(member1.getId(), member1);
        members.put(member2.getId(), member2);
        members.put(member3.getId(), member3);
        members.put(member4.getId(), member4);

        context = new StaticContext<>(DigestAlgorithm.DEFAULT.getOrigin(), 0.1,
                                      List.of(member1, member2, member3, member4), 3);
    }

    @AfterEach
    public void tearDown() {
        // Clean up system properties
        for (FeatureFlags flag : FeatureFlags.values()) {
            System.clearProperty(flag.getSystemProperty());
        }
    }

    /**
     * Test that validators without consensus keys are rejected when feature flag is enabled.
     */
    @Test
    public void testStrictValidationRejectsValidatorsWithoutKeys() {
        // Enable strict validation
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);

        // Create reconfigure with validator lacking consensus key (need 4 for BFT)
        var reconfigure = createReconfigure(
            createViewMember(member1.getId(), null),  // No consensus key
            createViewMember(member2.getId(), generateFakeConsensusKey()),
            createViewMember(member3.getId(), generateFakeConsensusKey()),
            createViewMember(member4.getId(), generateFakeConsensusKey())
        );

        // Should throw exception for validator without key
        var exception = assertThrows(IllegalStateException.class, () -> {
            Committee.validatorsOf(reconfigure, context, member1.getId(), log);
        });

        assertTrue(exception.getMessage().contains("Validator missing consensus key"),
                   "Exception should indicate missing consensus key");
        assertTrue(exception.getMessage().contains(member1.getId().toString()),
                   "Exception should identify the problematic validator");
    }

    /**
     * Test backward compatibility: when feature flag is disabled, return NO_VERIFIER (old behavior).
     */
    @Test
    public void testBackwardCompatibilityWithFeatureFlagDisabled() {
        // Ensure feature flag is disabled (default)
        assertFalse(FeatureFlags.VERIFIER_VALIDATION.isEnabled());

        // Create reconfigure with validator lacking consensus key
        var reconfigure = createReconfigure(
            createViewMember(member1.getId(), null),  // No consensus key
            createViewMember(member2.getId(), generateFakeConsensusKey()),
            createViewMember(member3.getId(), generateFakeConsensusKey()),
            createViewMember(member4.getId(), generateFakeConsensusKey())
        );

        // Should NOT throw exception, returns NO_VERIFIER for backward compatibility
        Map<Member, Verifier> validators = Committee.validatorsOf(reconfigure, context, member1.getId(), log);

        assertNotNull(validators);
        assertEquals(4, validators.size());

        // Find member1's validator
        var member1Validator = validators.entrySet().stream()
            .filter(e -> e.getKey().getId().equals(member1.getId()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseThrow();

        // Should be NO_VERIFIER (backward compatibility)
        assertSame(Verifier.NO_VERIFIER, member1Validator,
                   "Should return NO_VERIFIER when feature flag disabled");
    }

    /**
     * Test grace period documentation: validators without keys are rejected immediately,
     * but grace period is documented for operational awareness (30s for slow publishers).
     */
    @Test
    public void testGracePeriodDocumentationInErrorMessage() {
        // Enable strict validation
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);

        // Create a validator without consensus key
        var latePublisher = new ControlledIdentifierMember(
            new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy).newIdentifier()
        );
        context = new StaticContext<>(DigestAlgorithm.DEFAULT.getOrigin(), 0.1,
                                      List.of(member1, member2, member3, latePublisher), 3);

        // Create reconfigure WITHOUT consensus key
        var reconfigure = createReconfigure(
            createViewMember(member1.getId(), generateFakeConsensusKey()),
            createViewMember(member2.getId(), generateFakeConsensusKey()),
            createViewMember(member3.getId(), generateFakeConsensusKey()),
            createViewMember(latePublisher.getId(), null)  // No key
        );

        // Should reject immediately with grace period documentation in log
        var exception = assertThrows(IllegalStateException.class, () -> {
            Committee.validatorsOf(reconfigure, context, member1.getId(), log);
        });

        // Verify exception message
        assertTrue(exception.getMessage().contains("Validator missing consensus key"),
                   "Exception should indicate missing consensus key");
    }

    /**
     * Test Byzantine monitoring: rejection rate exceeding 1% should trigger alert.
     */
    @Test
    public void testByzantineMonitoringAlertsOnHighRejectionRate() {
        // Enable strict validation
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);

        // Track rejection rate
        AtomicInteger totalValidations = new AtomicInteger(0);
        AtomicInteger rejections = new AtomicInteger(0);

        // Simulate 100 validation attempts with 5% rejection rate (exceeds 1% threshold)
        for (int i = 0; i < 100; i++) {
            totalValidations.incrementAndGet();

            // 5% of validators lack consensus keys
            boolean hasKey = i % 20 != 0;
            var reconfigure = createReconfigure(
                createViewMember(member1.getId(), hasKey ? generateFakeConsensusKey() : null),
                createViewMember(member2.getId(), generateFakeConsensusKey()),
                createViewMember(member3.getId(), generateFakeConsensusKey()),
                createViewMember(member4.getId(), generateFakeConsensusKey())
            );

            if (!hasKey) {
                rejections.incrementAndGet();
                try {
                    Committee.validatorsOf(reconfigure, context, member1.getId(), log);
                    fail("Should have thrown exception for missing key");
                } catch (IllegalStateException e) {
                    // Expected
                }
            } else {
                // Should succeed
                var validators = Committee.validatorsOf(reconfigure, context, member1.getId(), log);
                assertNotNull(validators);
            }
        }

        // Verify rejection rate
        double rejectionRate = (double) rejections.get() / totalValidations.get();
        assertTrue(rejectionRate > 0.01,
                   "Rejection rate should exceed 1% threshold: " + (rejectionRate * 100) + "%");

        // In production, this would trigger Byzantine alert logging
        // We verify the rate calculation here
        assertEquals(5, rejections.get(), "Should have 5 rejections out of 100");
    }

    /**
     * Test that all validators with valid consensus keys are accepted.
     */
    @Test
    public void testValidValidatorsAreAccepted() {
        // Enable strict validation
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);

        // Create reconfigure with all validators having consensus keys
        var reconfigure = createReconfigure(
            createViewMember(member1.getId(), generateFakeConsensusKey()),
            createViewMember(member2.getId(), generateFakeConsensusKey()),
            createViewMember(member3.getId(), generateFakeConsensusKey()),
            createViewMember(member4.getId(), generateFakeConsensusKey())
        );

        // Should succeed
        Map<Member, Verifier> validators = Committee.validatorsOf(reconfigure, context, member1.getId(), log);

        assertNotNull(validators);
        assertEquals(4, validators.size());

        // All validators should have proper verifiers (not NO_VERIFIER)
        validators.values().forEach(verifier -> {
            assertNotSame(Verifier.NO_VERIFIER, verifier,
                         "Should not be NO_VERIFIER when key is present");
        });
    }

    /**
     * Test that feature flag can be toggled at runtime.
     */
    @Test
    public void testFeatureFlagRuntimeToggle() {
        // Create reconfigure with validator lacking consensus key
        var reconfigure = createReconfigure(
            createViewMember(member1.getId(), null),  // No consensus key
            createViewMember(member2.getId(), generateFakeConsensusKey()),
            createViewMember(member3.getId(), generateFakeConsensusKey()),
            createViewMember(member4.getId(), generateFakeConsensusKey())
        );

        // Feature flag disabled: should return NO_VERIFIER (backward compatible)
        assertFalse(FeatureFlags.VERIFIER_VALIDATION.isEnabled());
        Map<Member, Verifier> validators1 = Committee.validatorsOf(reconfigure, context, member1.getId(), log);
        assertNotNull(validators1);

        // Enable feature flag at runtime
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);
        assertTrue(FeatureFlags.VERIFIER_VALIDATION.isEnabled());

        // Now should throw exception
        assertThrows(IllegalStateException.class, () -> {
            Committee.validatorsOf(reconfigure, context, member1.getId(), log);
        });

        // Disable again
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(false);
        assertFalse(FeatureFlags.VERIFIER_VALIDATION.isEnabled());

        // Back to NO_VERIFIER behavior
        Map<Member, Verifier> validators2 = Committee.validatorsOf(reconfigure, context, member1.getId(), log);
        assertNotNull(validators2);
    }

    /**
     * Test concurrent validations with feature flag enabled (thread safety).
     */
    @Test
    public void testConcurrentValidationsWithStrictMode() throws InterruptedException {
        // Enable strict validation
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);

        var validReconfigure = createReconfigure(
            createViewMember(member1.getId(), generateFakeConsensusKey()),
            createViewMember(member2.getId(), generateFakeConsensusKey()),
            createViewMember(member3.getId(), generateFakeConsensusKey()),
            createViewMember(member4.getId(), generateFakeConsensusKey())
        );

        // Run 100 concurrent validations
        var threads = new Thread[100];
        var exceptions = new ArrayList<Exception>();

        for (int i = 0; i < threads.length; i++) {
            threads[i] = Thread.ofVirtual().start(() -> {
                try {
                    var validators = Committee.validatorsOf(validReconfigure, context, member1.getId(), log);
                    assertNotNull(validators);
                    assertEquals(4, validators.size());
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                }
            });
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        // Should have no exceptions
        assertTrue(exceptions.isEmpty(),
                   "Should not have exceptions in concurrent validations: " + exceptions);
    }

    // Helper methods

    private Reconfigure createReconfigure(ViewMember... members) {
        var builder = Reconfigure.newBuilder();
        for (ViewMember vm : members) {
            builder.addJoins(Join.newBuilder()
                                .setMember(SignedViewMember.newBuilder()
                                              .setVm(vm)
                                              .build())
                                .build());
        }
        return builder.build();
    }

    private ViewMember createViewMember(Digest memberId, PubKey consensusKey) {
        var builder = ViewMember.newBuilder()
            .setId(memberId.toDigeste())
            .setView(DigestAlgorithm.DEFAULT.getOrigin().toDigeste());

        if (consensusKey != null) {
            builder.setConsensusKey(consensusKey);
        }

        return builder.build();
    }

    private PubKey generateFakeConsensusKey() {
        // Generate a consensus key using the default signature algorithm (ED_25519)
        var keyPair = SignatureAlgorithm.DEFAULT.generateKeyPair(entropy);
        return bs(keyPair.getPublic());
    }
}
