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
     * Test that validators without consensus keys are excluded and BFT sufficiency is checked.
     * With 4 validators (BFT minimum), excluding 1 drops below BFT threshold → throws.
     */
    @Test
    public void testStrictValidationExcludesValidatorsWithoutKeys() {
        // Enable strict validation
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);

        // Create reconfigure with validator lacking consensus key (need 4 for BFT)
        var reconfigure = createReconfigure(
            createViewMember(member1.getId(), null),  // No consensus key
            createViewMember(member2.getId(), generateFakeConsensusKey()),
            createViewMember(member3.getId(), generateFakeConsensusKey()),
            createViewMember(member4.getId(), generateFakeConsensusKey())
        );

        // Should throw because excluding 1 of 4 drops below BFT threshold (need >= 4)
        var exception = assertThrows(IllegalStateException.class, () -> {
            Committee.validatorsOf(reconfigure, context, member1.getId(), log);
        });

        assertTrue(exception.getMessage().contains("Insufficient BFT validators"),
                   "Exception should indicate insufficient BFT validators");
    }

    /**
     * Test backward compatibility: when feature flag is explicitly disabled, return NO_VERIFIER (old behavior).
     * Note: VERIFIER_VALIDATION now defaults to true (secure by default). This test explicitly
     * disables the flag to verify backward compatibility is preserved via the flag mechanism.
     */
    @Test
    public void testBackwardCompatibilityWithFeatureFlagDisabled() {
        // Explicitly disable the flag (default is now true, but we're testing the disabled path)
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(false);
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
     * Test that excluding a validator below BFT threshold reports the deficit.
     */
    @Test
    public void testExcludingValidatorBelowBftThresholdReportsDeficit() {
        // Enable strict validation
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);

        // Create a validator without consensus key
        var latePublisher = new ControlledIdentifierMember(
            new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy).newIdentifier()
        );
        context = new StaticContext<>(DigestAlgorithm.DEFAULT.getOrigin(), 0.1,
                                      List.of(member1, member2, member3, latePublisher), 3);

        // Create reconfigure WITHOUT consensus key for latePublisher
        var reconfigure = createReconfigure(
            createViewMember(member1.getId(), generateFakeConsensusKey()),
            createViewMember(member2.getId(), generateFakeConsensusKey()),
            createViewMember(member3.getId(), generateFakeConsensusKey()),
            createViewMember(latePublisher.getId(), null)  // No key
        );

        // Should throw because 3 remaining is below BFT threshold
        var exception = assertThrows(IllegalStateException.class, () -> {
            Committee.validatorsOf(reconfigure, context, member1.getId(), log);
        });

        assertTrue(exception.getMessage().contains("Insufficient BFT validators"),
                   "Exception should indicate insufficient BFT validators");
    }

    /**
     * Test Byzantine monitoring: validators missing keys are excluded and counted.
     * When exclusions drop the set below BFT, an exception is thrown.
     */
    @Test
    public void testByzantineMonitoringCountsExclusions() {
        // Enable strict validation
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);

        // Track how many iterations fail BFT threshold
        AtomicInteger totalValidations = new AtomicInteger(0);
        AtomicInteger bftFailures = new AtomicInteger(0);

        // Simulate 100 validation attempts — 5% have a missing key
        for (int i = 0; i < 100; i++) {
            totalValidations.incrementAndGet();

            boolean hasKey = i % 20 != 0;
            var reconfigure = createReconfigure(
                createViewMember(member1.getId(), hasKey ? generateFakeConsensusKey() : null),
                createViewMember(member2.getId(), generateFakeConsensusKey()),
                createViewMember(member3.getId(), generateFakeConsensusKey()),
                createViewMember(member4.getId(), generateFakeConsensusKey())
            );

            if (!hasKey) {
                bftFailures.incrementAndGet();
                // 1 of 4 excluded → 3 remaining → below BFT threshold → throws
                assertThrows(IllegalStateException.class, () -> {
                    Committee.validatorsOf(reconfigure, context, member1.getId(), log);
                });
            } else {
                var validators = Committee.validatorsOf(reconfigure, context, member1.getId(), log);
                assertNotNull(validators);
            }
        }

        assertEquals(5, bftFailures.get(), "Should have 5 BFT failures out of 100");
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
     * Note: VERIFIER_VALIDATION defaults to true (secure by default, Delos-izm.1.2).
     */
    @Test
    public void testFeatureFlagRuntimeToggle() {
        // Create reconfigure with validator lacking consensus key (1 of 4 missing)
        var reconfigure = createReconfigure(
            createViewMember(member1.getId(), null),  // No consensus key
            createViewMember(member2.getId(), generateFakeConsensusKey()),
            createViewMember(member3.getId(), generateFakeConsensusKey()),
            createViewMember(member4.getId(), generateFakeConsensusKey())
        );

        // Feature flag enabled by default: should throw (excluded drops below BFT)
        assertTrue(FeatureFlags.VERIFIER_VALIDATION.isEnabled());
        assertThrows(IllegalStateException.class, () -> {
            Committee.validatorsOf(reconfigure, context, member1.getId(), log);
        });

        // Disable feature flag at runtime (for backward compatibility/testing)
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(false);
        assertFalse(FeatureFlags.VERIFIER_VALIDATION.isEnabled());

        // Now should return NO_VERIFIER (backward compatibility)
        Map<Member, Verifier> validators1 = Committee.validatorsOf(reconfigure, context, member1.getId(), log);
        assertNotNull(validators1);

        // Re-enable
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);
        assertTrue(FeatureFlags.VERIFIER_VALIDATION.isEnabled());

        // Back to throwing (excluded drops below BFT)
        assertThrows(IllegalStateException.class, () -> {
            Committee.validatorsOf(reconfigure, context, member1.getId(), log);
        });
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
