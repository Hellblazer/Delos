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
import com.hellblazer.delos.cryptography.QualifiedBase64;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.Verifier;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Committee.identityValidatorsOf() security fix (Delos-izm.1.2).
 *
 * Validates that:
 * 1. The identityValidatorsOf unconditional NO_VERIFIER bypass is closed when VERIFIER_VALIDATION is ON
 * 2. VERIFIER_VALIDATION now defaults to true (secure by default)
 * 3. System property override allows disabling for development/testing
 * 4. Both NO_VERIFIER bypass paths in Committee are closed when flag is ON
 *
 * @author hal.hildebrand
 */
public class CommitteeIdentityValidationTest {
    private static final Logger log = LoggerFactory.getLogger(CommitteeIdentityValidationTest.class);

    private FeatureFlagManager manager;
    private StaticContext<Member> context;
    private List<ControlledIdentifierMember> members;
    private SecureRandom entropy;

    @BeforeEach
    public void setUp() throws Exception {
        manager = FeatureFlagManager.getInstance();
        // Reset all flags to default before each test
        for (FeatureFlags flag : FeatureFlags.values()) {
            manager.resetToDefault(flag.name());
        }
        // Also clear system properties that might affect flags
        System.clearProperty(FeatureFlags.VERIFIER_VALIDATION.getSystemProperty());

        entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{1, 2, 3, 4});

        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        members = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            members.add(new ControlledIdentifierMember(stereotomy.newIdentifier()));
        }

        // Build context with all 4 members
        List<Member> memberList = new ArrayList<>(members);
        context = new StaticContext<>(DigestAlgorithm.DEFAULT.getOrigin(), 0.1, memberList, 3);
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(FeatureFlags.VERIFIER_VALIDATION.getSystemProperty());
        // Reset flags to clean state
        for (FeatureFlags flag : FeatureFlags.values()) {
            manager.resetToDefault(flag.name());
        }
    }

    /**
     * VERIFIER_VALIDATION must default to true (secure by default).
     * This is the core of the security fix.
     */
    @Test
    public void testVerifierValidationDefaultsToTrue() {
        assertTrue(FeatureFlags.VERIFIER_VALIDATION.isEnabled(),
                   "VERIFIER_VALIDATION must default to true for secure-by-default behavior");
        assertTrue(FeatureFlags.VERIFIER_VALIDATION.isDefaultEnabled(),
                   "VERIFIER_VALIDATION.isDefaultEnabled() must return true");
    }

    /**
     * System property override must allow disabling VERIFIER_VALIDATION.
     * Required for development/testing scenarios where validators may lack keys.
     */
    @Test
    public void testSystemPropertyOverrideAllowsDisabling() {
        // Default is true
        assertTrue(FeatureFlags.VERIFIER_VALIDATION.isEnabled());

        // Set system property to disable
        System.setProperty(FeatureFlags.VERIFIER_VALIDATION.getSystemProperty(), "false");
        manager.resetToDefault(FeatureFlags.VERIFIER_VALIDATION.name());

        assertFalse(FeatureFlags.VERIFIER_VALIDATION.isEnabled(),
                    "System property override should allow disabling VERIFIER_VALIDATION");
    }

    /**
     * System property override must allow enabling VERIFIER_VALIDATION explicitly.
     */
    @Test
    public void testSystemPropertyOverrideAllowsEnabling() {
        // Explicitly disable via runtime
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(false);
        assertFalse(FeatureFlags.VERIFIER_VALIDATION.isEnabled());

        // Reset to system property (clear runtime override)
        System.setProperty(FeatureFlags.VERIFIER_VALIDATION.getSystemProperty(), "true");
        manager.resetToDefault(FeatureFlags.VERIFIER_VALIDATION.name());

        assertTrue(FeatureFlags.VERIFIER_VALIDATION.isEnabled(),
                   "System property true should enable VERIFIER_VALIDATION");
    }

    /**
     * With VERIFIER_VALIDATION=true, identityValidatorsOf excludes members missing
     * from context. With 4 members and 1 excluded, 3 remaining is below BFT → throws.
     */
    @Test
    public void testIdentityValidatorsOf_throwsWhenExclusionDropsBelowBft() {
        // Default is now true (secure)
        assertTrue(FeatureFlags.VERIFIER_VALIDATION.isEnabled());

        // Create a member that is NOT in the context
        var outsiderEntropy = new SecureRandom();
        var outsider = createMemberNotInContext(outsiderEntropy);

        // Build reconfigure that includes the outsider (not in context)
        var reconfigure = buildReconfigureWithMember(outsider);

        // identityValidatorsOf excludes the outsider; 3 remaining is below BFT threshold
        assertThrows(IllegalStateException.class,
                     () -> Committee.identityValidatorsOf(reconfigure, context,
                                                          members.get(0).getId(), log),
                     "identityValidatorsOf must throw when exclusions drop below BFT threshold");
    }

    /**
     * With VERIFIER_VALIDATION=false, identityValidatorsOf returns NO_VERIFIER
     * for missing members (backward compatibility preserved).
     */
    @Test
    public void testIdentityValidatorsOf_returnsNoVerifierWhenFlagDisabled() {
        // Explicitly disable
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(false);
        assertFalse(FeatureFlags.VERIFIER_VALIDATION.isEnabled());

        // Create a member that is NOT in the context
        var outsiderEntropy = new SecureRandom();
        var outsider = createMemberNotInContext(outsiderEntropy);

        // Build reconfigure including all context members plus outsider
        var reconfigure = buildReconfigureWithMember(outsider);

        // Should not throw - returns NO_VERIFIER for backward compatibility
        var validators = assertDoesNotThrow(
                () -> Committee.identityValidatorsOf(reconfigure, context,
                                                     members.get(0).getId(), log),
                "identityValidatorsOf must not throw when VERIFIER_VALIDATION is disabled");

        assertNotNull(validators);
        // Find the outsider's validator - should be NO_VERIFIER
        var outsiderValidator = validators.entrySet().stream()
                .filter(e -> e.getKey().getId().equals(outsider.getId()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);

        assertNotNull(outsiderValidator, "Should have an entry for the outsider member");
        assertSame(Verifier.NO_VERIFIER, outsiderValidator,
                   "Should return NO_VERIFIER for missing member when flag is disabled");
    }

    /**
     * With VERIFIER_VALIDATION=true, identityValidatorsOf succeeds when all
     * members are present in the context.
     */
    @Test
    public void testIdentityValidatorsOf_succeedsWhenAllMembersPresent() {
        // Default is true (secure)
        assertTrue(FeatureFlags.VERIFIER_VALIDATION.isEnabled());

        // Build reconfigure with only context members (all present)
        var reconfigure = buildReconfigureWithContextMembers();

        // Should succeed - all members are in context
        var validators = assertDoesNotThrow(
                () -> Committee.identityValidatorsOf(reconfigure, context,
                                                     members.get(0).getId(), log),
                "identityValidatorsOf must succeed when all members are in context");

        assertNotNull(validators);
        assertFalse(validators.isEmpty());
        // All validators should NOT be NO_VERIFIER
        validators.values().forEach(v ->
                assertNotSame(Verifier.NO_VERIFIER, v,
                              "Validators must not be NO_VERIFIER when member is present in context"));
    }

    /**
     * Integration test: BOTH NO_VERIFIER bypass paths in Committee are closed when flag is ON.
     * Path 1: validatorsOf - missing consensus key → excluded → below BFT → throws
     * Path 2: identityValidatorsOf - missing member → excluded → below BFT → throws
     */
    @Test
    public void testBothNoVerifierBypassPathsClosedWhenFlagEnabled() {
        // Default is true
        assertTrue(FeatureFlags.VERIFIER_VALIDATION.isEnabled());

        // Path 1: validatorsOf excludes missing key, 3 remaining < BFT threshold
        var reconfigureNoKey = buildReconfigureWithMissingConsensusKey();
        assertThrows(IllegalStateException.class,
                     () -> Committee.validatorsOf(reconfigureNoKey, context,
                                                  members.get(0).getId(), log),
                     "Path 1 (validatorsOf): must fail BFT check when excluding validator");

        // Path 2: identityValidatorsOf excludes missing member, 3 remaining < BFT threshold
        var outsider = createMemberNotInContext(new SecureRandom());
        var reconfigureWithOutsider = buildReconfigureWithMember(outsider);
        assertThrows(IllegalStateException.class,
                     () -> Committee.identityValidatorsOf(reconfigureWithOutsider, context,
                                                          members.get(0).getId(), log),
                     "Path 2 (identityValidatorsOf): must fail BFT check when excluding member");
    }

    /**
     * Integration test: BOTH NO_VERIFIER bypass paths return NO_VERIFIER when flag is OFF.
     * Backward compatibility preserved when flag is disabled.
     */
    @Test
    public void testBothBypassPathsReturnNoVerifierWhenFlagDisabled() {
        // Explicitly disable
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(false);
        assertFalse(FeatureFlags.VERIFIER_VALIDATION.isEnabled());

        // Path 1: validatorsOf returns NO_VERIFIER for missing consensus key
        var reconfigureNoKey = buildReconfigureWithMissingConsensusKey();
        var validators1 = assertDoesNotThrow(
                () -> Committee.validatorsOf(reconfigureNoKey, context,
                                             members.get(0).getId(), log));
        assertNotNull(validators1);
        boolean hasNoVerifier1 = validators1.values().stream()
                .anyMatch(v -> v == Verifier.NO_VERIFIER);
        assertTrue(hasNoVerifier1, "Path 1: must return NO_VERIFIER when flag disabled");

        // Path 2: identityValidatorsOf returns NO_VERIFIER for missing member
        var outsider = createMemberNotInContext(new SecureRandom());
        var reconfigureWithOutsider = buildReconfigureWithMember(outsider);
        var validators2 = assertDoesNotThrow(
                () -> Committee.identityValidatorsOf(reconfigureWithOutsider, context,
                                                     members.get(0).getId(), log));
        assertNotNull(validators2);
        boolean hasNoVerifier2 = validators2.values().stream()
                .anyMatch(v -> v == Verifier.NO_VERIFIER);
        assertTrue(hasNoVerifier2, "Path 2: must return NO_VERIFIER when flag disabled");
    }

    // ---- helper methods ----

    private ControlledIdentifierMember createMemberNotInContext(SecureRandom rng) {
        try {
            var outsiderStereotomy = new StereotomyImpl(new MemKeyStore(),
                                                        new MemKERL(DigestAlgorithm.DEFAULT), rng);
            return new ControlledIdentifierMember(outsiderStereotomy.newIdentifier());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create outsider member", e);
        }
    }

    /**
     * Build a reconfigure containing all context members (all have proper identity keys).
     */
    private Reconfigure buildReconfigureWithContextMembers() {
        var builder = Reconfigure.newBuilder();
        for (var m : members) {
            var consensusKey = SignatureAlgorithm.DEFAULT.generateKeyPair(entropy);
            var vm = ViewMember.newBuilder()
                    .setId(m.getId().toDigeste())
                    .setConsensusKey(QualifiedBase64.bs(consensusKey.getPublic()))
                    .build();
            builder.addJoins(Join.newBuilder()
                                 .setMember(SignedViewMember.newBuilder().setVm(vm).build())
                                 .build());
        }
        return builder.build();
    }

    /**
     * Build a reconfigure that includes an outsider member (not in context) plus enough
     * context members to meet BFT requirements.
     */
    private Reconfigure buildReconfigureWithMember(ControlledIdentifierMember outsider) {
        var builder = Reconfigure.newBuilder();
        // Add 3 context members (meets BFT 3f+1 with the outsider as 4th)
        for (int i = 0; i < 3; i++) {
            var m = members.get(i);
            var consensusKey = SignatureAlgorithm.DEFAULT.generateKeyPair(entropy);
            var vm = ViewMember.newBuilder()
                    .setId(m.getId().toDigeste())
                    .setConsensusKey(QualifiedBase64.bs(consensusKey.getPublic()))
                    .build();
            builder.addJoins(Join.newBuilder()
                                 .setMember(SignedViewMember.newBuilder().setVm(vm).build())
                                 .build());
        }
        // Add the outsider (not in context)
        var consensusKey = SignatureAlgorithm.DEFAULT.generateKeyPair(entropy);
        var outsiderVm = ViewMember.newBuilder()
                .setId(outsider.getId().toDigeste())
                .setConsensusKey(QualifiedBase64.bs(consensusKey.getPublic()))
                .build();
        builder.addJoins(Join.newBuilder()
                             .setMember(SignedViewMember.newBuilder().setVm(outsiderVm).build())
                             .build());
        return builder.build();
    }

    /**
     * Build a reconfigure where the first member is missing its consensus key.
     * Remaining 3 members have valid consensus keys.
     */
    private Reconfigure buildReconfigureWithMissingConsensusKey() {
        var builder = Reconfigure.newBuilder();
        for (int i = 0; i < members.size(); i++) {
            var m = members.get(i);
            var vmBuilder = ViewMember.newBuilder().setId(m.getId().toDigeste());
            if (i != 0) {
                // Only first member lacks consensus key
                var consensusKey = SignatureAlgorithm.DEFAULT.generateKeyPair(entropy);
                vmBuilder.setConsensusKey(QualifiedBase64.bs(consensusKey.getPublic()));
            }
            builder.addJoins(Join.newBuilder()
                                 .setMember(SignedViewMember.newBuilder().setVm(vmBuilder.build()).build())
                                 .build());
        }
        return builder.build();
    }
}
