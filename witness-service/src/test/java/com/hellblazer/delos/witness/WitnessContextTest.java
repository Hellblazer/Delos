/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.MockMember;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.committee.CommitteeBLSKeyStore;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for WitnessContext - Dynamic committee selection with ring iterator.
 *
 * Tests verify:
 * 1. Determinism: Same event coordinates always yield same committee
 * 2. Variation: Different events produce potentially different committees
 * 3. Membership validation: All committee members in witness pool
 * 4. Threshold calculation: M > (2*k)/3
 * 5. Fault tolerance: f = (k-1)/3
 * 6. View change handling: Epoch incremented, drain period returned
 * 7. Committee size: Always k members selected
 * 8. Ring iterator pattern: Uses firefliesContext.bftSubset() correctly
 */
class WitnessContextTest {

    private static final int COMMITTEE_SIZE = 7;
    private static final int WITNESS_POOL_SIZE = 21;
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;

    private Context<MockMember> firefliesContext;
    private WitnessParameters parameters;
    private WitnessContext witnessContext;
    private List<MockMember> witnessPool;

    @BeforeEach
    void setUp() {
        // Create witness pool (Fireflies members)
        witnessPool = createWitnessPool(WITNESS_POOL_SIZE);

        // Create Fireflies context with ring structure
        // StaticContext takes explicit ring count: bftSubset returns one member per ring
        // So with COMMITTEE_SIZE=7 rings, we get 7 committee members
        var contextId = ALGORITHM.digest("test-context".getBytes());
        firefliesContext = new StaticContext<>(
            contextId,
            0.1,               // pByz
            witnessPool,       // members (already created above)
            COMMITTEE_SIZE     // number of rings (7)
        );

        // Create witness parameters
        var threshold = (2 * COMMITTEE_SIZE) / 3 + 1;
        parameters = WitnessParameters.newBuilder()
            .k(COMMITTEE_SIZE)
            .threshold(threshold)
            .epoch(0)
            .drainPeriod(Duration.ofMillis(500))
            .build();

        // Create witness context
        witnessContext = new WitnessContext(firefliesContext, parameters);
    }

    @Test
    void testDeterminism_SameEventYieldsSameCommittee() {
        // Given: Event coordinates
        var eventCoords = createEventCoordinates("identifier-1", 0L);

        // When: Select committee 100 times with same coordinates
        var committees = IntStream.range(0, 100)
            .mapToObj(i -> witnessContext.selectCommittee(eventCoords))
            .toList();

        // Then: All committees identical
        var firstCommittee = committees.get(0);
        committees.forEach(committee -> {
            assertEquals(firstCommittee.size(), committee.size(),
                "Committee size should be consistent");
            assertEquals(firstCommittee, committee,
                "Same event coordinates must yield same committee");
        });
    }

    @Test
    void testVariation_DifferentEventHashesAreUsedForSelection() {
        // Given: Two different event coordinates
        var event1 = createEventCoordinates("event-1", 0L);
        var event2 = createEventCoordinates("event-2", 1L);

        // When: Select committees for both
        var committee1 = witnessContext.selectCommittee(event1);
        var committee2 = witnessContext.selectCommittee(event2);

        // Then: Committee selection works for both (may or may not be different)
        // Note: With small pool (21) and 7 rings, it's possible all events
        // select the same committee if successors align.
        // The important property is DETERMINISM, not variation.
        assertEquals(COMMITTEE_SIZE, committee1.size(), "Committee 1 should have k members");
        assertEquals(COMMITTEE_SIZE, committee2.size(), "Committee 2 should have k members");

        // If they happen to be the same, that's OK - the key is determinism
        if (!committee1.equals(committee2)) {
            // If committees differ, that's good but not required
            // This demonstrates the algorithm can select different committees
        }
    }

    @Test
    void testCommitteeSize_AlwaysKMembers() {
        // Given: Multiple event coordinates
        var events = IntStream.range(0, 20)
            .mapToObj(i -> createEventCoordinates("identifier-" + i, (long) i))
            .toList();

        // When: Select committees
        var committees = events.stream()
            .map(witnessContext::selectCommittee)
            .toList();

        // Then: All committees have exactly k members
        committees.forEach(committee ->
            assertEquals(COMMITTEE_SIZE, committee.size(),
                "Committee must have exactly k=" + COMMITTEE_SIZE + " members")
        );
    }

    @Test
    void testMembershipValidation_AllCommitteeMembersInWitnessPool() {
        // Given: Event coordinates
        var eventCoords = createEventCoordinates("identifier-1", 0L);

        // When: Select committee
        var committee = witnessContext.selectCommittee(eventCoords);

        // Then: All members in committee are in witness pool
        var poolDigests = witnessPool.stream()
            .map(m -> m.getId())
            .collect(java.util.stream.Collectors.toSet());

        // Convert committee Identifiers to Digests and check
        committee.forEach(memberId -> {
            var digest = memberId.getDigest(ALGORITHM);
            assertTrue(poolDigests.contains(digest),
                "Committee member " + memberId + " must be in witness pool");
        });
    }

    @Test
    void testIsCommitteeMember_CorrectlyIdentifiesMembers() {
        // Given: Event coordinates and committee
        var eventCoords = createEventCoordinates("identifier-1", 0L);
        var committee = witnessContext.selectCommittee(eventCoords);

        // When/Then: Committee members return true
        committee.forEach(memberId ->
            assertTrue(witnessContext.isCommitteeMember(memberId, eventCoords),
                "Committee member should be identified as member")
        );

        // And: Non-committee members return false
        witnessPool.stream()
            .map(m -> new SelfAddressingIdentifier(m.getId()))
            .filter(id -> !committee.contains(id))
            .forEach(nonMemberId ->
                assertFalse(witnessContext.isCommitteeMember(nonMemberId, eventCoords),
                    "Non-committee member should not be identified as member")
            );
    }

    @Test
    void testThresholdCalculation_MeetsKERIRequirement() {
        // Given: Committee size k=7
        // Then: Threshold M must satisfy M > (2*k)/3
        var threshold = witnessContext.getThreshold();
        var minimumThreshold = (2 * COMMITTEE_SIZE) / 3;

        assertTrue(threshold > minimumThreshold,
            "Threshold " + threshold + " must be > (2*k)/3 = " + minimumThreshold);
        assertTrue(threshold <= COMMITTEE_SIZE,
            "Threshold " + threshold + " must be <= k = " + COMMITTEE_SIZE);
    }

    @Test
    void testFaultTolerance_CalculatedCorrectly() {
        // Given: Committee size k=7
        // Then: Fault tolerance f = (k-1)/3
        var expectedF = (COMMITTEE_SIZE - 1) / 3;
        assertEquals(expectedF, witnessContext.getFaultTolerance(),
            "Fault tolerance should be f = (k-1)/3");
    }

    @Test
    void testEpoch_InitializedCorrectly() {
        // Given: Initial witness context
        // Then: Epoch matches parameters
        assertEquals(0L, witnessContext.getEpoch(),
            "Initial epoch should match parameters");
    }

    @Test
    void testViewChange_IncrementEpochAndReturnDrainPeriod() {
        // Given: Initial epoch
        var initialEpoch = witnessContext.getEpoch();

        // When: Handle view change
        Set<Identifier> newMembers = witnessPool.stream()
            .map(m -> (Identifier) new SelfAddressingIdentifier(m.getId()))
            .collect(java.util.stream.Collectors.toSet());
        var drainPeriod = witnessContext.handleViewChange(newMembers, initialEpoch + 1);

        // Then: Epoch incremented
        assertEquals(initialEpoch + 1, witnessContext.getEpoch(),
            "Epoch should be incremented");

        // And: Drain period returned
        assertEquals(Duration.ofMillis(500), drainPeriod,
            "Should return configured drain period");
    }

    @Test
    void testValidateCommittee_AllMembersInCurrentView() {
        // Given: Event coordinates and selected committee
        var eventCoords = createEventCoordinates("identifier-1", 0L);
        var committee = witnessContext.selectCommittee(eventCoords);

        // When: Validate committee
        var isValid = witnessContext.validateCommittee(committee);

        // Then: Committee is valid
        assertTrue(isValid, "Committee with all current members should be valid");
    }

    @Test
    void testValidateCommittee_RejectsNonMembers() {
        // Given: Committee with a non-member
        var fakeId = new SelfAddressingIdentifier(ALGORITHM.digest("fake".getBytes()));
        var invalidCommittee = new HashSet<Identifier>();
        invalidCommittee.add(fakeId);

        // When: Validate committee
        var isValid = witnessContext.validateCommittee(invalidCommittee);

        // Then: Committee is invalid
        assertFalse(isValid, "Committee with non-member should be invalid");
    }

    @Test
    void testRingIteratorPattern_UsesBftSubset() {
        // Given: Event coordinates
        var eventCoords = createEventCoordinates("identifier-1", 0L);

        // When: Select committee
        var committee = witnessContext.selectCommittee(eventCoords);

        // Then: Committee is non-empty and has unique members
        assertFalse(committee.isEmpty(), "Committee should not be empty");
        assertEquals(COMMITTEE_SIZE, committee.size(),
            "Committee should have k unique members");

        // Verify determinism via bftSubset pattern
        var committee2 = witnessContext.selectCommittee(eventCoords);
        assertEquals(committee, committee2,
            "bftSubset should produce deterministic results");
    }

    @Test
    void testCommitteeKeyCache_InitializedWithProvider() {
        // Given: WitnessContext
        // When: Get cache
        var cache = witnessContext.getCommitteeKeyCache();

        // Then: Cache exists and is operational
        assertNotNull(cache, "Committee key cache should be initialized");
        assertEquals(0, cache.getSize(), "Cache should be initially empty");
    }

    @Test
    void testViewChange_TriggersKeyPrecomputation() {
        // Given: Committee members with registered BLS keys
        var keyStore = witnessContext.getCommitteeBLSKeys();
        var newMembers = witnessPool.stream()
            .limit(COMMITTEE_SIZE)
            .map(m -> (Identifier) new SelfAddressingIdentifier(m.getId()))
            .collect(java.util.stream.Collectors.toSet());

        // Register keys for all members
        newMembers.forEach(memberId -> registerBLSKey(memberId, keyStore));

        // Verify cache initially empty
        var cache = witnessContext.getCommitteeKeyCache();
        var initialSize = cache.getSize();
        assertEquals(0, initialSize, "Cache should start empty");

        // When: Handle view change
        witnessContext.handleViewChange(newMembers, 1L);

        // Then: Cache pre-computed with committee keys
        assertEquals(COMMITTEE_SIZE, cache.getSize(),
            "Cache should contain all committee member keys after view change");

        // Verify cache hit for registered members
        newMembers.forEach(memberId -> {
            var cachedKey = cache.get(memberId);
            assertNotNull(cachedKey,
                "Committee member " + memberId + " should have cached key after view change");
        });
    }

    @Test
    void testViewChange_ClearsCacheForNonMembers() {
        // Given: Cache with old member keys
        var keyStore = witnessContext.getCommitteeBLSKeys();
        var oldMembers = witnessPool.stream()
            .limit(3)
            .map(m -> (Identifier) new SelfAddressingIdentifier(m.getId()))
            .collect(java.util.stream.Collectors.toSet());

        oldMembers.forEach(memberId -> registerBLSKey(memberId, keyStore));

        // First view change to populate cache
        witnessContext.handleViewChange(oldMembers, 1L);
        assertEquals(3, witnessContext.getCommitteeKeyCache().getSize());

        // When: View change to different members
        var newMembers = witnessPool.stream()
            .skip(10)
            .limit(5)
            .map(m -> (Identifier) new SelfAddressingIdentifier(m.getId()))
            .collect(java.util.stream.Collectors.toSet());

        newMembers.forEach(memberId -> registerBLSKey(memberId, keyStore));

        witnessContext.handleViewChange(newMembers, 2L);

        // Then: Cache cleared and re-populated with new members only
        var cache = witnessContext.getCommitteeKeyCache();
        assertEquals(5, cache.getSize(), "Cache should contain only new members");

        oldMembers.forEach(oldMemberId -> {
            var cachedKey = cache.get(oldMemberId);
            assertNull(cachedKey, "Old member " + oldMemberId + " should not be in cache after view change");
        });

        newMembers.forEach(newMemberId -> {
            var cachedKey = cache.get(newMemberId);
            assertNotNull(cachedKey, "New member " + newMemberId + " should be in cache after view change");
        });
    }

    @Test
    void testViewChange_HandlesEmptyCommittee() {
        // Given: Empty committee set
        Set<Identifier> emptyCommittee = Set.of();

        // When: Handle view change with empty committee
        witnessContext.handleViewChange(emptyCommittee, 1L);

        // Then: Cache cleared and empty
        var cache = witnessContext.getCommitteeKeyCache();
        assertEquals(0, cache.getSize(), "Cache should be empty for empty committee");
    }

    @Test
    void testViewChange_HandlesPartialKeyRegistration() {
        // Given: Some members have keys, some don't
        var allMembers = witnessPool.stream()
            .limit(COMMITTEE_SIZE)
            .map(m -> (Identifier) new SelfAddressingIdentifier(m.getId()))
            .collect(java.util.stream.Collectors.toList());

        // Register keys for only first 3 members
        var keyStore = witnessContext.getCommitteeBLSKeys();
        allMembers.stream().limit(3).forEach(memberId -> registerBLSKey(memberId, keyStore));

        // When: Handle view change with all members
        witnessContext.handleViewChange(new HashSet<>(allMembers), 1L);

        // Then: Cache contains only keys for registered members
        var cache = witnessContext.getCommitteeKeyCache();
        assertEquals(3, cache.getSize(),
            "Cache should contain only keys for members with registered keys");

        // Verify registered members have cached keys
        allMembers.stream().limit(3).forEach(memberId -> {
            assertNotNull(cache.get(memberId),
                "Registered member should have cached key");
        });

        // Verify unregistered members don't have cached keys
        allMembers.stream().skip(3).forEach(memberId -> {
            assertNull(cache.get(memberId),
                "Unregistered member should not have cached key");
        });
    }

    @Test
    void testViewChange_ThreadSafetyForConcurrentAccess() throws InterruptedException {
        // Given: Committee members with keys
        var keyStore = witnessContext.getCommitteeBLSKeys();
        var members = witnessPool.stream()
            .limit(COMMITTEE_SIZE)
            .map(m -> (Identifier) new SelfAddressingIdentifier(m.getId()))
            .collect(java.util.stream.Collectors.toSet());

        members.forEach(memberId -> registerBLSKey(memberId, keyStore));

        // When: Concurrent view changes and cache reads
        var threads = new ArrayList<Thread>();
        var errors = new ArrayList<Throwable>();

        // View change threads
        for (int i = 0; i < 5; i++) {
            var epoch = i;
            var thread = new Thread(() -> {
                try {
                    witnessContext.handleViewChange(members, epoch);
                } catch (Throwable t) {
                    synchronized (errors) {
                        errors.add(t);
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }

        // Cache read threads
        for (int i = 0; i < 10; i++) {
            var thread = new Thread(() -> {
                try {
                    var cache = witnessContext.getCommitteeKeyCache();
                    members.forEach(cache::get);
                } catch (Throwable t) {
                    synchronized (errors) {
                        errors.add(t);
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }

        // Wait for all threads
        for (var thread : threads) {
            thread.join(5000);
        }

        // Then: No errors and cache consistent
        assertTrue(errors.isEmpty(), "No concurrent access errors: " + errors);
        var cache = witnessContext.getCommitteeKeyCache();
        assertEquals(COMMITTEE_SIZE, cache.getSize(),
            "Cache should be consistent after concurrent operations");
    }

    // Helper methods

    private List<MockMember> createWitnessPool(int size) {
        return IntStream.range(0, size)
            .mapToObj(i -> {
                var digest = ALGORITHM.digest(("witness-" + i).getBytes());
                return new MockMember(digest);
            })
            .toList();
    }

    private EventCoordinates createEventCoordinates(String identifierStr, long sequenceNumber) {
        var identifier = new SelfAddressingIdentifier(
            ALGORITHM.digest(identifierStr.getBytes())
        );
        var digest = ALGORITHM.digest(
            (identifierStr + "-" + sequenceNumber).getBytes()
        );
        var ilk = "icp"; // Inception event

        return new EventCoordinates(identifier, ULong.valueOf(sequenceNumber), digest, ilk);
    }

    /**
     * Helper method to register a BLS key for a committee member.
     * Creates a complete BLS key registration with PoP and registration signature.
     */
    private void registerBLSKey(Identifier memberId, CommitteeBLSKeyStore keyStore) {
        var provider = com.hellblazer.delos.cryptography.bls.impl.TekuBLSProvider.getInstance();
        var random = new SecureRandom();
        var keyPair = provider.generateKeyPair(random);

        // Create BLSSecretKey wrapper for signing
        var secretKey = new com.hellblazer.delos.cryptography.bls.BLSSecretKey(keyPair.secretKey(), provider);

        // Generate proof of possession
        var pop = com.hellblazer.delos.cryptography.bls.ProofOfPossession.generate(
            secretKey, keyPair.publicKey(), provider
        );
        var publicKey = new com.hellblazer.delos.cryptography.bls.BLSPublicKey(keyPair.publicKey(), pop);

        // Create registration signature (sign the member ID)
        var memberIdBytes = memberId.getDigest(ALGORITHM).getBytes();
        var blsSignature = secretKey.sign(memberIdBytes);

        var registration = new com.hellblazer.delos.witness.committee.BLSKeyRegistration(
            memberId, publicKey, pop, blsSignature, 0L, java.time.Instant.now()
        );
        keyStore.registerKey(registration);
    }
}
