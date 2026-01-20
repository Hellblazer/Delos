/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.WitnessContext;
import com.hellblazer.delos.witness.integration.WitnessKerlIntegration;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Test suite for CommitteeMembershipValidator - Phase 1A-3 Task B.2.
 * <p>
 * Tests:
 * 1. Basic committee membership validation
 * 2. Committee update on view change
 * 3. Key rotation during collection
 * </p>
 */
class CommitteeMembershipValidatorTest {

    @Mock
    private WitnessContext mockWitnessContext;
    @Mock
    private WitnessKerlIntegration mockKerlIntegration;
    @Mock
    private Member mockMember1;
    @Mock
    private Member mockMember2;
    @Mock
    private Member mockMember3;
    @Mock
    private KeyState mockKeyState1;
    @Mock
    private KeyState mockKeyState2;
    @Mock
    private KeyState mockKeyState3;

    private CommitteeMembershipValidator validator;
    private Digest digest1;
    private Digest digest2;
    private Digest digest3;
    private Identifier identifier1;
    private Identifier identifier2;
    private Identifier identifier3;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Create test digests and identifiers
        digest1 = DigestAlgorithm.DEFAULT.digest("member1".getBytes());
        digest2 = DigestAlgorithm.DEFAULT.digest("member2".getBytes());
        digest3 = DigestAlgorithm.DEFAULT.digest("member3".getBytes());

        identifier1 = new SelfAddressingIdentifier(digest1);
        identifier2 = new SelfAddressingIdentifier(digest2);
        identifier3 = new SelfAddressingIdentifier(digest3);

        // Setup member mocks
        when(mockMember1.getId()).thenReturn(digest1);
        when(mockMember2.getId()).thenReturn(digest2);
        when(mockMember3.getId()).thenReturn(digest3);

        // Setup witness context
        when(mockWitnessContext.getEpoch()).thenReturn(1L);
        when(mockWitnessContext.toIdentifier(digest1)).thenReturn(identifier1);
        when(mockWitnessContext.toIdentifier(digest2)).thenReturn(identifier2);
        when(mockWitnessContext.toIdentifier(digest3)).thenReturn(identifier3);

        validator = new CommitteeMembershipValidator(mockWitnessContext, mockKerlIntegration);
    }

    /**
     * Test 1: Validate committee membership.
     * <p>
     * Verifies:
     * - Members in Fireflies are validated
     * - Members with valid KERL KeyState pass
     * - Members without KERL KeyState fail
     * - Intersection of Fireflies ∩ KERL returned
     * </p>
     */
    @Test
    void testValidateCommitteeMembership() {
        // Arrange - All members in Fireflies, only member1 and member2 have valid KERL
        when(mockWitnessContext.getCurrentMembers())
            .thenReturn(Set.of(identifier1, identifier2, identifier3));

        // Member1 has valid KERL KeyState
        when(mockKeyState1.getSequenceNumber()).thenReturn(ULong.valueOf(1));
        when(mockKerlIntegration.verifyIdentifier(identifier1, 1L))
            .thenReturn(Optional.of(mockKeyState1));

        // Member2 has valid KERL KeyState
        when(mockKeyState2.getSequenceNumber()).thenReturn(ULong.valueOf(1));
        when(mockKerlIntegration.verifyIdentifier(identifier2, 1L))
            .thenReturn(Optional.of(mockKeyState2));

        // Member3 has NO KERL KeyState
        when(mockKerlIntegration.verifyIdentifier(identifier3, 1L))
            .thenReturn(Optional.empty());

        var members = Set.of(mockMember1, mockMember2, mockMember3);

        // Act
        var validated = validator.validateCommitteeMembership(members);

        // Assert - Only member1 and member2 validated
        assertThat(validated).hasSize(2);
        assertThat(validated).contains(identifier1, identifier2);
        assertThat(validated).doesNotContain(identifier3);

        // Verify KERL called for each member
        verify(mockKerlIntegration).verifyIdentifier(identifier1, 1L);
        verify(mockKerlIntegration).verifyIdentifier(identifier2, 1L);
        verify(mockKerlIntegration).verifyIdentifier(identifier3, 1L);
    }

    /**
     * Test 2: Committee update on view change.
     * <p>
     * Verifies:
     * - Cache invalidated on view change
     * - New committee members validated
     * - Old committee members removed
     * </p>
     */
    @Test
    void testCommitteeUpdateOnViewChange() {
        // Arrange - Initial validation with member1, member2
        when(mockWitnessContext.getCurrentMembers())
            .thenReturn(Set.of(identifier1, identifier2));
        when(mockKeyState1.getSequenceNumber()).thenReturn(ULong.valueOf(1));
        when(mockKeyState2.getSequenceNumber()).thenReturn(ULong.valueOf(1));
        when(mockKerlIntegration.verifyIdentifier(identifier1, 1L))
            .thenReturn(Optional.of(mockKeyState1));
        when(mockKerlIntegration.verifyIdentifier(identifier2, 1L))
            .thenReturn(Optional.of(mockKeyState2));

        var members = Set.of(mockMember1, mockMember2);
        var validated1 = validator.validateCommitteeMembership(members);
        assertThat(validated1).hasSize(2);

        // Act - View change: member3 added, member1 removed
        validator.invalidateCacheOnViewChange();
        when(mockWitnessContext.getEpoch()).thenReturn(2L);
        when(mockWitnessContext.getCurrentMembers())
            .thenReturn(Set.of(identifier2, identifier3));
        when(mockKeyState2.getSequenceNumber()).thenReturn(ULong.valueOf(2));
        when(mockKeyState3.getSequenceNumber()).thenReturn(ULong.valueOf(2));
        when(mockKerlIntegration.verifyIdentifier(identifier2, 2L))
            .thenReturn(Optional.of(mockKeyState2));
        when(mockKerlIntegration.verifyIdentifier(identifier3, 2L))
            .thenReturn(Optional.of(mockKeyState3));

        var newMembers = Set.of(mockMember2, mockMember3);
        var validated2 = validator.validateCommitteeMembership(newMembers);

        // Assert - New committee has member2 and member3
        assertThat(validated2).hasSize(2);
        assertThat(validated2).contains(identifier2, identifier3);
        assertThat(validated2).doesNotContain(identifier1);

        // Verify cache cleared (size should be 1 after new validation)
        assertThat(validator.getCacheSize()).isEqualTo(1);
    }

    /**
     * Test 3: Handle key rotation during collection.
     * <p>
     * Verifies:
     * - Key rotation detected (sequence > epoch)
     * - Member accepted if sequence >= epoch
     * - Member rejected if sequence < epoch
     * </p>
     */
    @Test
    void testKeyRotationDuringCollection() {
        // Arrange - Member1 has key rotation (sequence=2 > epoch=1)
        when(mockWitnessContext.getCurrentMembers())
            .thenReturn(Set.of(identifier1, identifier2));

        // Member1 has rotated key (sequence=2 > epoch=1) - ACCEPTED
        when(mockKeyState1.getSequenceNumber()).thenReturn(ULong.valueOf(2));
        when(mockKerlIntegration.verifyIdentifier(identifier1, 1L))
            .thenReturn(Optional.of(mockKeyState1));

        // Member2 has old key (sequence=0 < epoch=1) - REJECTED
        when(mockKeyState2.getSequenceNumber()).thenReturn(ULong.valueOf(0));
        when(mockKerlIntegration.verifyIdentifier(identifier2, 1L))
            .thenReturn(Optional.of(mockKeyState2));

        var members = Set.of(mockMember1, mockMember2);

        // Act
        var validated = validator.validateCommitteeMembership(members);

        // Assert - Only member1 accepted (rotated key OK, old key rejected)
        assertThat(validated).hasSize(1);
        assertThat(validated).contains(identifier1);
        assertThat(validated).doesNotContain(identifier2);

        // Verify KERL called for each member
        verify(mockKerlIntegration).verifyIdentifier(identifier1, 1L);
        verify(mockKerlIntegration).verifyIdentifier(identifier2, 1L);
    }

    /**
     * Test: Verify caching behavior (500ms TTL).
     * <p>
     * Verifies:
     * - First call validates and caches
     * - Second call (same epoch) hits cache
     * - KERL called only once
     * </p>
     */
    @Test
    void testCachingBehavior() {
        // Arrange
        when(mockWitnessContext.getCurrentMembers())
            .thenReturn(Set.of(identifier1));
        when(mockKeyState1.getSequenceNumber()).thenReturn(ULong.valueOf(1));
        when(mockKerlIntegration.verifyIdentifier(identifier1, 1L))
            .thenReturn(Optional.of(mockKeyState1));

        var members = Set.of(mockMember1);

        // Act - First call (cache miss)
        var validated1 = validator.validateCommitteeMembership(members);

        // Act - Second call (cache hit)
        var validated2 = validator.validateCommitteeMembership(members);

        // Assert - Both return same result
        assertThat(validated1).hasSize(1);
        assertThat(validated2).hasSize(1);
        assertThat(validated1).isEqualTo(validated2);

        // Verify KERL called only once (cache hit on second call)
        verify(mockKerlIntegration, times(1)).verifyIdentifier(identifier1, 1L);

        // Verify cache size
        assertThat(validator.getCacheSize()).isEqualTo(1);
    }

    /**
     * Test: Verify cache invalidation clears all entries.
     * <p>
     * Verifies:
     * - Cache populated with entry
     * - Invalidation clears cache
     * - Next validation hits KERL again
     * </p>
     */
    @Test
    void testCacheInvalidation() {
        // Arrange - Populate cache
        when(mockWitnessContext.getCurrentMembers())
            .thenReturn(Set.of(identifier1));
        when(mockKeyState1.getSequenceNumber()).thenReturn(ULong.valueOf(1));
        when(mockKerlIntegration.verifyIdentifier(identifier1, 1L))
            .thenReturn(Optional.of(mockKeyState1));

        var members = Set.of(mockMember1);
        validator.validateCommitteeMembership(members);
        assertThat(validator.getCacheSize()).isEqualTo(1);

        // Act - Invalidate cache
        validator.invalidateCacheOnViewChange();

        // Assert - Cache cleared
        assertThat(validator.getCacheSize()).isEqualTo(0);

        // Act - Next validation hits KERL again
        validator.validateCommitteeMembership(members);

        // Verify KERL called twice (before and after invalidation)
        verify(mockKerlIntegration, times(2)).verifyIdentifier(identifier1, 1L);
    }

    /**
     * Test: Verify member not in Fireflies rejected.
     * <p>
     * Verifies:
     * - Member with valid KERL but not in Fireflies rejected
     * - Intersection requires BOTH Fireflies AND KERL
     * </p>
     */
    @Test
    void testMemberNotInFirefliesRejected() {
        // Arrange - Member1 has valid KERL but NOT in Fireflies
        when(mockWitnessContext.getCurrentMembers())
            .thenReturn(Set.of(identifier2)); // Only identifier2 in Fireflies

        when(mockKeyState1.getSequenceNumber()).thenReturn(ULong.valueOf(1));
        when(mockKerlIntegration.verifyIdentifier(identifier1, 1L))
            .thenReturn(Optional.of(mockKeyState1));

        var members = Set.of(mockMember1);

        // Act
        var validated = validator.validateCommitteeMembership(members);

        // Assert - Member1 rejected (not in Fireflies)
        assertThat(validated).isEmpty();

        // Verify KERL NOT called (filtered out by Fireflies check first)
        verify(mockKerlIntegration, never()).verifyIdentifier(any(), anyLong());
    }
}
