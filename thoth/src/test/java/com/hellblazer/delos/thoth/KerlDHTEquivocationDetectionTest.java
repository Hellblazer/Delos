/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.event.proto.KeyState_;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests Byzantine equivocation detection in KerlDHT quorum operations.
 * <p>
 * Equivocation occurs when a Byzantine member returns DIFFERENT responses
 * for the SAME query within a single quorum operation. This is a strong
 * Byzantine signal (weight 3) that should be detected and recorded.
 * </p>
 *
 * @author hal.hildebrand
 */
@DisplayName("KerlDHT Equivocation Detection")
class KerlDHTEquivocationDetectionTest {

    @Mock
    private Member member1;

    @Mock
    private Member member2;

    @Mock
    private ThothByzantineStateProvider byzantineProvider;

    private Digest memberId1;
    private Digest memberId2;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Setup member IDs
        memberId1 = DigestAlgorithm.DEFAULT.digest("member1".getBytes());
        memberId2 = DigestAlgorithm.DEFAULT.digest("member2".getBytes());

        when(member1.getId()).thenReturn(memberId1);
        when(member2.getId()).thenReturn(memberId2);
    }

    @Test
    @DisplayName("Equivocation detected when same member provides conflicting responses")
    void testEquivocationDetectedInReadPath() {
        // Given: A QuorumResponseTracker with Byzantine provider
        var tracker = new QuorumResponseTracker<String>();

        // When: Member1 provides first response
        tracker.add("response1", member1, byzantineProvider);

        // Then: No equivocation yet
        verify(byzantineProvider, never()).recordValidationFailure(any(Digest.class), anyString());

        // When: Member1 provides DIFFERENT response (equivocation)
        tracker.add("response2", member1, byzantineProvider);

        // Then: Equivocation detected and recorded
        var reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(byzantineProvider).recordValidationFailure(eq(memberId1), reasonCaptor.capture());

        var reason = reasonCaptor.getValue();
        assertThat(reason).contains("EQUIVOCATION");
        assertThat(reason).contains("conflicting responses");

        // Both responses should still be tracked for debugging
        assertThat(tracker.hasResponse(member1)).isTrue();
        assertThat(tracker.size()).isEqualTo(2);  // Both responses counted
    }

    @Test
    @DisplayName("No equivocation when same member provides identical response (retry/duplicate)")
    void testNonEquivocationIgnored() {
        // Given: A QuorumResponseTracker
        var tracker = new QuorumResponseTracker<String>();

        // When: Member1 provides same response twice (retry/duplicate)
        tracker.add("response1", member1, byzantineProvider);
        tracker.add("response1", member1, byzantineProvider);

        // Then: No equivocation detected (same content)
        verify(byzantineProvider, never()).recordValidationFailure(any(Digest.class), anyString());

        // Only one unique response but counted twice
        assertThat(tracker.count("response1")).isEqualTo(2);
    }

    @Test
    @DisplayName("Equivocation by one member doesn't affect other honest members")
    void testEquivocationIsolatedToMember() {
        // Given: A QuorumResponseTracker
        var tracker = new QuorumResponseTracker<String>();

        // When: Member1 equivocates, Member2 is honest
        tracker.add("response1", member1, byzantineProvider);
        tracker.add("response1", member2, byzantineProvider);  // Member2 agrees with first response
        tracker.add("response2", member1, byzantineProvider);  // Member1 equivocates

        // Then: Only Member1 flagged as Byzantine
        verify(byzantineProvider).recordValidationFailure(eq(memberId1), anyString());
        verify(byzantineProvider, never()).recordValidationFailure(eq(memberId2), anyString());

        // response1 has majority (member2 + first member1 response)
        assertThat(tracker.count("response1")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Quorum succeeds with majority consensus despite equivocation")
    void testQuorumSucceedsWithEquivocation() {
        // Given: 5 nodes - 3 honest, 1 equivocating, 1 offline
        var tracker = new QuorumResponseTracker<String>();

        var member3 = mock(Member.class);
        var member4 = mock(Member.class);
        var memberId3 = DigestAlgorithm.DEFAULT.digest("member3".getBytes());
        var memberId4 = DigestAlgorithm.DEFAULT.digest("member4".getBytes());
        when(member3.getId()).thenReturn(memberId3);
        when(member4.getId()).thenReturn(memberId4);

        // When: 3 honest members agree, 1 equivocates
        tracker.add("correctResponse", member2, byzantineProvider);  // Honest
        tracker.add("correctResponse", member3, byzantineProvider);  // Honest
        tracker.add("correctResponse", member4, byzantineProvider);  // Honest
        tracker.add("correctResponse", member1, byzantineProvider);  // Byzantine first response
        tracker.add("wrongResponse", member1, byzantineProvider);    // Byzantine equivocation

        // Then: Equivocation detected
        verify(byzantineProvider).recordValidationFailure(eq(memberId1), anyString());

        // Quorum still succeeds with majority (3 honest + 1 Byzantine first response = 4 for correctResponse)
        var maxEntry = tracker.maxEntry();
        assertThat(maxEntry).isNotNull();
        assertThat(maxEntry.getElement()).isEqualTo("correctResponse");
        assertThat(maxEntry.getCount()).isGreaterThanOrEqualTo(3);  // Majority consensus
    }

    @Test
    @DisplayName("Equivocation uses VALIDATION_FAILURE weight (3)")
    void testEquivocationByzantineWeighting() {
        // This is more of a documentation test - we verify the integration
        // The actual weight is applied in ThothByzantineStateProvider.recordValidation()

        var tracker = new QuorumResponseTracker<String>();

        // When: Equivocation occurs
        tracker.add("response1", member1, byzantineProvider);
        tracker.add("response2", member1, byzantineProvider);

        // Then: recordValidationFailure is called (weight 3 applied in provider)
        verify(byzantineProvider).recordValidationFailure(eq(memberId1), contains("EQUIVOCATION"));

        // Verify ThothByzantineStateProvider applies VALIDATION_FAILURE_WEIGHT = 3
        // (This is tested in ThothByzantineStateProviderTest, here we just verify the call)
    }

    @Test
    @DisplayName("Multiple equivocations from same member recorded separately")
    void testMultipleEquivocationsRecorded() {
        // Given: A tracker processing multiple queries
        var tracker1 = new QuorumResponseTracker<String>();
        var tracker2 = new QuorumResponseTracker<String>();

        // When: Member1 equivocates on two different queries
        tracker1.add("query1-response1", member1, byzantineProvider);
        tracker1.add("query1-response2", member1, byzantineProvider);  // Equivocation 1

        tracker2.add("query2-response1", member1, byzantineProvider);
        tracker2.add("query2-response2", member1, byzantineProvider);  // Equivocation 2

        // Then: Both equivocations recorded
        verify(byzantineProvider, times(2)).recordValidationFailure(eq(memberId1), anyString());
    }
}
