/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.FeatureFlagManager;
import com.hellblazer.delos.choam.proto.Join;
import com.hellblazer.delos.choam.proto.Reconfigure;
import com.hellblazer.delos.choam.proto.SignedViewMember;
import com.hellblazer.delos.choam.proto.ViewMember;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.*;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for Committee validator verification security fix (Delos-i642).
 *
 * Validates the fix for the Verifier Bypass vulnerability where validators
 * without consensus keys were silently bypassed (returning NO_VERIFIER)
 * instead of failing fast.
 *
 * Security Tests:
 * 1. Vulnerability demonstration (NO_VERIFIER bypass)
 * 2. Strict validation (fail fast when validator not found)
 * 3. Grace period for slow-to-publish validators
 * 4. Byzantine false positive monitoring
 * 5. Retry logic after grace period
 *
 * @author hal.hildebrand
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class CommitteeValidationTest {
    private static final Logger log = LoggerFactory.getLogger(CommitteeValidationTest.class);
    private static final DigestAlgorithm DIGEST_ALGORITHM = DigestAlgorithm.DEFAULT;
    private static final int CARDINALITY = 4; // 3f+1 (f=1 Byzantine tolerance)

    private SecureRandom entropy;
    private StaticContext<Member> context;
    private List<SigningMember> members;
    private StereotomyImpl stereotomy;
    private Clock fixedClock;

    @BeforeEach
    public void setup() throws Exception {
        // Set short grace period for tests (1 second instead of 30)
        System.setProperty("verifier.grace.period.ms", "1000");

        // Deterministic entropy for reproducible tests
        entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{6, 4, 2}); // Delos-i642

        // Fixed clock for deterministic time-based tests
        fixedClock = Clock.fixed(Instant.parse("2026-02-04T10:00:00Z"), ZoneId.of("UTC"));

        // Create members with KERI identities
        var kerl = new MemKERL(DIGEST_ALGORITHM);
        stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);

        members = new ArrayList<>();
        for (int i = 0; i < CARDINALITY; i++) {
            var identifier = stereotomy.newIdentifier();
            members.add(new ControlledIdentifierMember(identifier));
        }

        // Create context with all members
        var ctxId = DIGEST_ALGORITHM.getOrigin().prefix(entropy.nextLong());
        @SuppressWarnings("unchecked")
        List<Member> memberList = (List<Member>) (List<?>) members;
        context = new StaticContext<>(ctxId, 0.2, memberList, 3);
    }

    @AfterEach
    public void tearDown() {
        // Clean up system property
        System.clearProperty("verifier.grace.period.ms");
        FeatureFlagManager.getInstance().resetAll();
    }

    @Test
    public void testValidatorsOf_throwsWhenExclusionDropsBelowBft() {
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);
        try {
            var reconfigure = createReconfigureWithoutConsensusKey(members.get(0));
            var exception = assertThrows(
                IllegalStateException.class,
                () -> Committee.validatorsOf(reconfigure, context, members.get(1).getId(), log),
                "Expected validatorsOf to throw when exclusion drops below BFT"
            );
            var message = exception.getMessage();
            log.info("Exception message: {}", message);
            assertTrue(message.contains("Insufficient BFT validators"),
                      "Exception message should indicate insufficient BFT validators. Actual: " + message);
            log.info("✓ Test 1 passed: Strict validation excludes keyless validators and checks BFT");
        } finally {
            FeatureFlags.VERIFIER_VALIDATION.setEnabled(false);
        }
    }

    @Test
    public void testValidatorsOf_exclusionIsImmediate() {
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);
        try {
            var slowValidator = members.get(1);
            var reconfigureWithoutKey = createReconfigureWithoutConsensusKey(slowValidator);
            var startTime = System.currentTimeMillis();
            try {
                Committee.validatorsOf(reconfigureWithoutKey, context, members.get(2).getId(), log);
                fail("Should have thrown when exclusion drops below BFT");
            } catch (IllegalStateException e) {
                var elapsed = System.currentTimeMillis() - startTime;
                assertTrue(elapsed < 5000, "Exclusion and BFT check should be immediate");
            }
            log.info("✓ Test 2 passed: Validator exclusion is immediate");
        } finally {
            FeatureFlags.VERIFIER_VALIDATION.setEnabled(false);
        }
    }

    @Test
    public void testValidatorsOf_byzantineFalsePositiveMonitoring() {
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);
        try {
            int totalAttempts = 100;
            int rejections = 0;
            for (int i = 0; i < totalAttempts; i++) {
                try {
                    var reconfigure = createReconfigureWithValidConsensusKey(members.get(i % CARDINALITY));
                    Committee.validatorsOf(reconfigure, context, members.get((i + 1) % CARDINALITY).getId(), log);
                } catch (IllegalStateException e) {
                    rejections++;
                }
            }
            double rejectionRate = (double) rejections / totalAttempts;
            assertEquals(0.0, rejectionRate, 0.01,
                        "Valid validators should not be rejected");
            log.info("✓ Test 3 passed: No false positives for valid validators (rejection rate: {}%)",
                    rejectionRate * 100);
        } finally {
            FeatureFlags.VERIFIER_VALIDATION.setEnabled(false);
        }
    }

    @Test
    public void testValidatorsOf_retryAfterGracePeriod() {
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(true);
        try {
            var validator = members.get(1);
            var reconfigureWithoutKey = createReconfigureWithoutConsensusKey(validator);
            assertThrows(IllegalStateException.class,
                        () -> Committee.validatorsOf(reconfigureWithoutKey, context, members.get(2).getId(), log),
                        "Should fail when validator lacks consensus key");
            var reconfigureWithKey = createReconfigureWithValidConsensusKey(validator);
            var validators = Committee.validatorsOf(reconfigureWithKey, context, members.get(2).getId(), log);
            assertNotNull(validators, "Should return validators after key published");
            assertFalse(validators.isEmpty(), "Validators map should not be empty");
            assertTrue(validators.containsKey(validator), "Should contain the validator");
            var verifier = validators.get(validator);
            assertNotNull(verifier, "Verifier should not be null");
            assertNotEquals(Verifier.NO_VERIFIER, verifier,
                          "Should not use NO_VERIFIER for valid validator");
            log.info("✓ Test 4 passed: Retry succeeds after validator publishes key");
        } finally {
            FeatureFlags.VERIFIER_VALIDATION.setEnabled(false);
        }
    }

    @Test
    public void testValidatorsOf_legacyBehaviorWhenFlagDisabled() {
        FeatureFlags.VERIFIER_VALIDATION.setEnabled(false);
        try {
            var reconfigure = createReconfigureWithoutConsensusKey(members.get(0));
            var validators = Committee.validatorsOf(reconfigure, context, members.get(1).getId(), log);
            assertNotNull(validators, "Should return validators map");
            assertTrue(validators.containsKey(members.get(0)), "Should contain the member");
            var verifier = validators.get(members.get(0));
            assertEquals(Verifier.NO_VERIFIER, verifier,
                        "Legacy behavior should use NO_VERIFIER");
            log.info("✓ Test 5 passed: Legacy behavior preserved when flag disabled");
        } finally {
            FeatureFlags.VERIFIER_VALIDATION.setEnabled(false);
        }
    }

    private Reconfigure createReconfigureWithoutConsensusKey(SigningMember memberWithoutKey) {
        // BFT requires 4 members (3f+1, f=1)
        var builder = Reconfigure.newBuilder();
        for (var member : members) {
            var vmBuilder = ViewMember.newBuilder()
                .setId(member.getId().toDigeste());

            // Only add consensus key if not the target member
            if (!member.equals(memberWithoutKey)) {
                var consensusKey = SignatureAlgorithm.ED_25519.generateKeyPair(entropy);
                vmBuilder.setConsensusKey(QualifiedBase64.bs(consensusKey.getPublic()));
            }

            var signedViewMember = SignedViewMember.newBuilder()
                .setVm(vmBuilder.build())
                .build();
            var join = Join.newBuilder()
                .setMember(signedViewMember)
                .build();
            builder.addJoins(join);
        }
        return builder.build();
    }

    private Reconfigure createReconfigureWithValidConsensusKey(SigningMember member) {
        // BFT requires 4 members (3f+1, f=1) - all with consensus keys
        var builder = Reconfigure.newBuilder();
        for (var m : members) {
            var consensusKey = SignatureAlgorithm.ED_25519.generateKeyPair(entropy);
            var viewMember = ViewMember.newBuilder()
                .setId(m.getId().toDigeste())
                .setConsensusKey(QualifiedBase64.bs(consensusKey.getPublic()))
                .build();
            var signedViewMember = SignedViewMember.newBuilder()
                .setVm(viewMember)
                .build();
            var join = Join.newBuilder()
                .setMember(signedViewMember)
                .build();
            builder.addJoins(join);
        }
        return builder.build();
    }
}
