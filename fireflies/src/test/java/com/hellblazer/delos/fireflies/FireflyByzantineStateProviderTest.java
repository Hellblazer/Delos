/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.membership.byzantine.IntelligenceConfig;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FireflyByzantineStateProvider.
 */
class FireflyByzantineStateProviderTest {

    private static final short RING_COUNT = 5;

    @Mock
    private View view;

    @Mock
    private DynamicContext<Participant> context;

    private FireflyByzantineStateProvider provider;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(view.getContext()).thenReturn(context);
        when(context.getRingCount()).thenReturn(RING_COUNT);
        when(view.getShunnedMembers()).thenReturn(Set.of());
        // Return fresh stream each time (streams can only be consumed once)
        when(view.getAccusedMembers()).thenAnswer(inv -> Stream.empty());

        provider = new FireflyByzantineStateProvider(view);
    }

    @Test
    void shouldReturnFirefliesLayerName() {
        assertThat(provider.getLayerName()).isEqualTo(IntelligenceConfig.LAYER_FIREFLIES);
    }

    @Test
    void shouldReturnEmptyMapWhenNoAnomalies() {
        var states = provider.getMemberAnomalyStates();

        assertThat(states).isEmpty();
    }

    @Test
    void shouldReturnShunnedMembersWithMaxScore() {
        var shunnedDigest = DigestAlgorithm.DEFAULT.digest("shunned-member");
        when(view.getShunnedMembers()).thenReturn(Set.of(shunnedDigest));

        var states = provider.getMemberAnomalyStates();

        assertThat(states).hasSize(1);
        var identifier = new SelfAddressingIdentifier(shunnedDigest);
        assertThat(states).containsKey(identifier);

        var state = states.get(identifier);
        assertThat(state.anomalyScore()).isEqualTo(1.0);
        assertThat(state.activeSignals()).contains("SHUNNED");
        assertThat(state.layerName()).isEqualTo(IntelligenceConfig.LAYER_FIREFLIES);
    }

    @Test
    void shouldReturnAccusedMembersWithProportionalScore() {
        var accusedDigest = DigestAlgorithm.DEFAULT.digest("accused-member");
        var accusedParticipant = mockParticipant(accusedDigest, 2); // 2 accusations

        when(view.getAccusedMembers()).thenAnswer(inv -> Stream.of(accusedParticipant));

        var states = provider.getMemberAnomalyStates();

        assertThat(states).hasSize(1);
        var identifier = new SelfAddressingIdentifier(accusedDigest);
        assertThat(states).containsKey(identifier);

        var state = states.get(identifier);
        // Score = 2 accusations / 5 rings = 0.4
        assertThat(state.anomalyScore()).isCloseTo(0.4, within(0.001));
        assertThat(state.activeSignals()).contains("ACCUSED");
        assertThat(state.activeSignals()).contains("MULTIPLE_ACCUSATIONS:2");
    }

    @Test
    void shouldCapScoreAtOne() {
        var accusedDigest = DigestAlgorithm.DEFAULT.digest("heavily-accused");
        var accusedParticipant = mockParticipant(accusedDigest, RING_COUNT + 2); // More than ring count

        when(view.getAccusedMembers()).thenAnswer(inv -> Stream.of(accusedParticipant));

        var states = provider.getMemberAnomalyStates();

        var identifier = new SelfAddressingIdentifier(accusedDigest);
        var state = states.get(identifier);
        assertThat(state.anomalyScore()).isEqualTo(1.0);
    }

    @Test
    void shouldPrioritizeShunnedOverAccused() {
        var memberDigest = DigestAlgorithm.DEFAULT.digest("member");
        var participant = mockParticipant(memberDigest, 3);

        // Member is both shunned AND accused
        when(view.getShunnedMembers()).thenReturn(Set.of(memberDigest));
        when(view.getAccusedMembers()).thenAnswer(inv -> Stream.of(participant));

        var states = provider.getMemberAnomalyStates();

        // Should only have one entry (shunned takes priority)
        assertThat(states).hasSize(1);
        var identifier = new SelfAddressingIdentifier(memberDigest);
        var state = states.get(identifier);

        // Should be shunned state (score 1.0), not accused
        assertThat(state.anomalyScore()).isEqualTo(1.0);
        assertThat(state.activeSignals()).contains("SHUNNED");
    }

    @Test
    void shouldReturnMemberStateForShunnedMember() {
        var shunnedDigest = DigestAlgorithm.DEFAULT.digest("shunned");
        when(view.getShunnedMembers()).thenReturn(Set.of(shunnedDigest));

        var identifier = new SelfAddressingIdentifier(shunnedDigest);
        var state = provider.getMemberState(identifier);

        assertThat(state).isPresent();
        assertThat(state.get().anomalyScore()).isEqualTo(1.0);
        assertThat(state.get().activeSignals()).contains("SHUNNED");
    }

    @Test
    void shouldReturnMemberStateForAccusedMember() {
        var accusedDigest = DigestAlgorithm.DEFAULT.digest("accused");
        var participant = mockParticipant(accusedDigest, 1);

        when(context.getMember(accusedDigest)).thenReturn(participant);

        var identifier = new SelfAddressingIdentifier(accusedDigest);
        var state = provider.getMemberState(identifier);

        assertThat(state).isPresent();
        assertThat(state.get().anomalyScore()).isCloseTo(0.2, within(0.001)); // 1/5
        assertThat(state.get().activeSignals()).contains("ACCUSED");
    }

    @Test
    void shouldReturnEmptyForCleanMember() {
        var cleanDigest = DigestAlgorithm.DEFAULT.digest("clean");
        var participant = mockParticipant(cleanDigest, 0);

        when(context.getMember(cleanDigest)).thenReturn(participant);

        var identifier = new SelfAddressingIdentifier(cleanDigest);
        var state = provider.getMemberState(identifier);

        assertThat(state).isEmpty();
    }

    @Test
    void shouldReturnEmptyForUnknownMember() {
        var unknownDigest = DigestAlgorithm.DEFAULT.digest("unknown");
        when(context.getMember(unknownDigest)).thenReturn(null);

        var identifier = new SelfAddressingIdentifier(unknownDigest);
        var state = provider.getMemberState(identifier);

        assertThat(state).isEmpty();
    }

    @Test
    void shouldCountTrackedMembers() {
        var shunned1 = DigestAlgorithm.DEFAULT.digest("shunned1");
        var shunned2 = DigestAlgorithm.DEFAULT.digest("shunned2");
        var accused = DigestAlgorithm.DEFAULT.digest("accused");

        // Create mock before stubbing to avoid Mockito nesting issues
        var accusedParticipant = mockParticipant(accused, 1);

        when(view.getShunnedMembers()).thenReturn(Set.of(shunned1, shunned2));
        when(view.getAccusedMembers()).thenAnswer(inv -> Stream.of(accusedParticipant));

        assertThat(provider.getTrackedMemberCount()).isEqualTo(3);
    }

    @Test
    void shouldRejectNullView() {
        assertThatThrownBy(() -> new FireflyByzantineStateProvider(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("view");
    }

    @Test
    void shouldResetClearCache() {
        // Add some state
        var shunnedDigest = DigestAlgorithm.DEFAULT.digest("shunned");
        when(view.getShunnedMembers()).thenReturn(Set.of(shunnedDigest));
        when(view.getAccusedMembers()).thenAnswer(inv -> Stream.empty());

        // Populate cache
        provider.getMemberAnomalyStates();

        // Reset
        provider.reset();

        // After reset, should still work (cache is just cleared)
        when(view.getShunnedMembers()).thenReturn(Set.of());
        var states = provider.getMemberAnomalyStates();
        assertThat(states).isEmpty();
    }

    private Participant mockParticipant(Digest id, int accusationCount) {
        var participant = mock(Participant.class);
        when(participant.getId()).thenReturn(id);
        when(participant.getIdentifier()).thenReturn(new SelfAddressingIdentifier(id));
        when(participant.getAccusationCount()).thenReturn(accusationCount);
        return participant;
    }
}
