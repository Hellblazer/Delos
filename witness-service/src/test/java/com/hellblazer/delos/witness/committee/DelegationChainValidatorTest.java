/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.integration.WitnessKerlIntegration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test suite for DelegationChainValidator - Phase 1A-3 Task B.4.
 * <p>
 * Tests:
 * 1. Valid delegation chain verification
 * 2. Expired delegation rejection
 * 3. Unauthorized root identifier rejection
 * </p>
 */
class DelegationChainValidatorTest {

    @Mock
    private WitnessKerlIntegration mockKerlIntegration;
    @Mock
    private KeyState mockKeyState1;
    @Mock
    private KeyState mockKeyState2;
    @Mock
    private KeyState mockRootKeyState;

    private DelegationChainValidator validator;
    private Identifier delegatedId;
    private Identifier intermediateId;
    private Identifier rootId;
    private Identifier unauthorizedRootId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Create test identifiers
        var digest1 = DigestAlgorithm.DEFAULT.digest("delegated".getBytes());
        var digest2 = DigestAlgorithm.DEFAULT.digest("intermediate".getBytes());
        var digest3 = DigestAlgorithm.DEFAULT.digest("root".getBytes());
        var digest4 = DigestAlgorithm.DEFAULT.digest("unauthorized".getBytes());

        delegatedId = new SelfAddressingIdentifier(digest1);
        intermediateId = new SelfAddressingIdentifier(digest2);
        rootId = new SelfAddressingIdentifier(digest3);
        unauthorizedRootId = new SelfAddressingIdentifier(digest4);

        // Setup validator with authorized root
        validator = new DelegationChainValidator(mockKerlIntegration, Set.of(rootId));
    }

    /**
     * Test 1: Verify valid delegation chain.
     * <p>
     * Chain: delegatedId -> intermediateId -> rootId
     * Verifies:
     * - Each link follows delegation
     * - Chain terminates at authorized root
     * - Result cached
     * </p>
     */
    @Test
    void testVerifyValidDelegationChain() {
        // Arrange - Setup 2-level delegation chain
        var epoch = 1L;

        // DelegatedId delegates to intermediateId
        when(mockKerlIntegration.verifyIdentifier(delegatedId, epoch))
            .thenReturn(Optional.of(mockKeyState1));
        when(mockKeyState1.isDelegated()).thenReturn(true);
        when(mockKeyState1.getDelegatingIdentifier()).thenReturn(Optional.of(intermediateId));

        // IntermediateId delegates to rootId
        when(mockKerlIntegration.verifyIdentifier(intermediateId, epoch))
            .thenReturn(Optional.of(mockKeyState2));
        when(mockKeyState2.isDelegated()).thenReturn(true);
        when(mockKeyState2.getDelegatingIdentifier()).thenReturn(Optional.of(rootId));

        // RootId is non-delegated (root)
        when(mockKerlIntegration.verifyIdentifier(rootId, epoch))
            .thenReturn(Optional.of(mockRootKeyState));
        when(mockRootKeyState.isDelegated()).thenReturn(false);

        // Act
        var result = validator.verifyDelegationChain(delegatedId, rootId, epoch);

        // Assert
        assertThat(result).isTrue();

        // Verify KERL called for each link
        verify(mockKerlIntegration).verifyIdentifier(delegatedId, epoch);
        verify(mockKerlIntegration).verifyIdentifier(intermediateId, epoch);
        verify(mockKerlIntegration).verifyIdentifier(rootId, epoch);

        // Verify cached
        assertThat(validator.getCacheSize()).isEqualTo(1);
    }

    /**
     * Test 2: Reject expired delegation.
     * <p>
     * Simulates delegation chain where intermediate KeyState expired.
     * Verifies:
     * - Missing KeyState detected
     * - Delegation chain invalid
     * </p>
     */
    @Test
    void testRejectExpiredDelegation() {
        // Arrange - IntermediateId KeyState expired (returns empty)
        var epoch = 1L;

        when(mockKerlIntegration.verifyIdentifier(delegatedId, epoch))
            .thenReturn(Optional.of(mockKeyState1));
        when(mockKeyState1.isDelegated()).thenReturn(true);
        when(mockKeyState1.getDelegatingIdentifier()).thenReturn(Optional.of(intermediateId));

        // IntermediateId KeyState unavailable (expired)
        when(mockKerlIntegration.verifyIdentifier(intermediateId, epoch))
            .thenReturn(Optional.empty());

        // Act
        var result = validator.verifyDelegationChain(delegatedId, rootId, epoch);

        // Assert
        assertThat(result).isFalse();

        // Verify KERL called for delegatedId and intermediateId only
        verify(mockKerlIntegration).verifyIdentifier(delegatedId, epoch);
        verify(mockKerlIntegration).verifyIdentifier(intermediateId, epoch);
        verify(mockKerlIntegration, never()).verifyIdentifier(rootId, epoch);

        // Verify cached (negative result)
        assertThat(validator.getCacheSize()).isEqualTo(1);
    }

    /**
     * Test 3: Reject unauthorized root identifier.
     * <p>
     * Verifies:
     * - Root identifier not in authorized roots
     * - Delegation chain rejected immediately
     * - KERL not called (fails fast)
     * </p>
     */
    @Test
    void testRejectUnauthorizedRootIdentifier() {
        // Arrange
        var epoch = 1L;

        // Act - Try to verify chain with unauthorized root
        var result = validator.verifyDelegationChain(delegatedId, unauthorizedRootId, epoch);

        // Assert
        assertThat(result).isFalse();

        // Verify KERL NOT called (fails fast)
        verify(mockKerlIntegration, never()).verifyIdentifier(any(), anyLong());

        // Verify cached (negative result)
        assertThat(validator.getCacheSize()).isEqualTo(1);
    }

    /**
     * Test: Verify direct delegation (1-level chain).
     * <p>
     * Chain: delegatedId -> rootId (direct)
     * Verifies:
     * - Single-level delegation works
     * - No intermediate required
     * </p>
     */
    @Test
    void testVerifyDirectDelegation() {
        // Arrange - Direct delegation to root
        var epoch = 1L;

        when(mockKerlIntegration.verifyIdentifier(delegatedId, epoch))
            .thenReturn(Optional.of(mockKeyState1));
        when(mockKeyState1.isDelegated()).thenReturn(true);
        when(mockKeyState1.getDelegatingIdentifier()).thenReturn(Optional.of(rootId));

        when(mockKerlIntegration.verifyIdentifier(rootId, epoch))
            .thenReturn(Optional.of(mockRootKeyState));
        when(mockRootKeyState.isDelegated()).thenReturn(false);

        // Act
        var result = validator.verifyDelegationChain(delegatedId, rootId, epoch);

        // Assert
        assertThat(result).isTrue();

        // Verify KERL called for delegatedId and rootId
        verify(mockKerlIntegration).verifyIdentifier(delegatedId, epoch);
        verify(mockKerlIntegration).verifyIdentifier(rootId, epoch);
    }

    /**
     * Test: Reject self-delegation.
     * <p>
     * Verifies:
     * - Self-delegated identifier detected
     * - Delegation chain invalid
     * </p>
     */
    @Test
    void testRejectSelfDelegation() {
        // Arrange - Self-delegation (delegates to itself)
        var epoch = 1L;

        when(mockKerlIntegration.verifyIdentifier(delegatedId, epoch))
            .thenReturn(Optional.of(mockKeyState1));
        when(mockKeyState1.isDelegated()).thenReturn(true);
        when(mockKeyState1.getDelegatingIdentifier()).thenReturn(Optional.of(delegatedId)); // Self-delegation

        // Act
        var result = validator.verifyDelegationChain(delegatedId, rootId, epoch);

        // Assert
        assertThat(result).isFalse();

        // Verify KERL called only once
        verify(mockKerlIntegration, times(1)).verifyIdentifier(delegatedId, epoch);
    }

    /**
     * Test: Reject delegation chain with cycle.
     * <p>
     * Chain: delegatedId -> intermediateId -> delegatedId (cycle)
     * Verifies:
     * - Cycle detected
     * - Delegation chain invalid
     * </p>
     */
    @Test
    void testRejectDelegationCycle() {
        // Arrange - Cycle: delegatedId -> intermediateId -> delegatedId
        var epoch = 1L;

        when(mockKerlIntegration.verifyIdentifier(delegatedId, epoch))
            .thenReturn(Optional.of(mockKeyState1));
        when(mockKeyState1.isDelegated()).thenReturn(true);
        when(mockKeyState1.getDelegatingIdentifier()).thenReturn(Optional.of(intermediateId));

        when(mockKerlIntegration.verifyIdentifier(intermediateId, epoch))
            .thenReturn(Optional.of(mockKeyState2));
        when(mockKeyState2.isDelegated()).thenReturn(true);
        when(mockKeyState2.getDelegatingIdentifier()).thenReturn(Optional.of(delegatedId)); // Cycle

        // Act
        var result = validator.verifyDelegationChain(delegatedId, rootId, epoch);

        // Assert
        assertThat(result).isFalse();

        // Verify KERL called for delegatedId and intermediateId
        // Note: delegatedId only called once before cycle detected (visited set prevents second lookup)
        verify(mockKerlIntegration, times(1)).verifyIdentifier(delegatedId, epoch);
        verify(mockKerlIntegration, times(1)).verifyIdentifier(intermediateId, epoch);
    }

    /**
     * Test: Verify caching behavior.
     * <p>
     * Verifies:
     * - First call validates and caches
     * - Second call hits cache
     * - KERL called only once
     * </p>
     */
    @Test
    void testCachingBehavior() {
        // Arrange
        var epoch = 1L;

        when(mockKerlIntegration.verifyIdentifier(delegatedId, epoch))
            .thenReturn(Optional.of(mockKeyState1));
        when(mockKeyState1.isDelegated()).thenReturn(true);
        when(mockKeyState1.getDelegatingIdentifier()).thenReturn(Optional.of(rootId));

        when(mockKerlIntegration.verifyIdentifier(rootId, epoch))
            .thenReturn(Optional.of(mockRootKeyState));
        when(mockRootKeyState.isDelegated()).thenReturn(false);

        // Act - First call (cache miss)
        var result1 = validator.verifyDelegationChain(delegatedId, rootId, epoch);

        // Act - Second call (cache hit)
        var result2 = validator.verifyDelegationChain(delegatedId, rootId, epoch);

        // Assert - Both return same result
        assertThat(result1).isTrue();
        assertThat(result2).isTrue();

        // Verify KERL called only once (cache hit on second call)
        verify(mockKerlIntegration, times(1)).verifyIdentifier(delegatedId, epoch);
        verify(mockKerlIntegration, times(1)).verifyIdentifier(rootId, epoch);

        // Verify cache size
        assertThat(validator.getCacheSize()).isEqualTo(1);
    }

    /**
     * Test: Verify cache invalidation on view change.
     * <p>
     * Verifies:
     * - Cache populated
     * - View change clears cache
     * - Next validation hits KERL again
     * </p>
     */
    @Test
    void testCacheInvalidationOnViewChange() {
        // Arrange - Populate cache
        var epoch = 1L;

        when(mockKerlIntegration.verifyIdentifier(delegatedId, epoch))
            .thenReturn(Optional.of(mockKeyState1));
        when(mockKeyState1.isDelegated()).thenReturn(true);
        when(mockKeyState1.getDelegatingIdentifier()).thenReturn(Optional.of(rootId));

        when(mockKerlIntegration.verifyIdentifier(rootId, epoch))
            .thenReturn(Optional.of(mockRootKeyState));
        when(mockRootKeyState.isDelegated()).thenReturn(false);

        validator.verifyDelegationChain(delegatedId, rootId, epoch);
        assertThat(validator.getCacheSize()).isEqualTo(1);

        // Act - Invalidate cache
        validator.invalidateCacheOnViewChange();

        // Assert - Cache cleared
        assertThat(validator.getCacheSize()).isEqualTo(0);

        // Act - Next validation hits KERL again
        validator.verifyDelegationChain(delegatedId, rootId, epoch);

        // Verify KERL called twice (before and after invalidation)
        verify(mockKerlIntegration, times(2)).verifyIdentifier(delegatedId, epoch);
        verify(mockKerlIntegration, times(2)).verifyIdentifier(rootId, epoch);
    }

    /**
     * Test: Verify authorized roots immutable.
     * <p>
     * Verifies:
     * - Authorized roots set is immutable
     * - Contains expected root
     * </p>
     */
    @Test
    void testAuthorizedRootsImmutable() {
        // Act
        var authorizedRoots = validator.getAuthorizedRoots();

        // Assert
        assertThat(authorizedRoots).contains(rootId);
        assertThat(authorizedRoots).doesNotContain(unauthorizedRootId);

        // Verify immutable (cannot modify)
        assertThat(authorizedRoots).isUnmodifiable();
    }
}
