/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.proto.Views;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for ViewAssemblyTestHarness to verify deterministic view assembly testing.
 *
 * @author hal.hildebrand
 */
@DisplayName("ViewAssemblyTestHarness Contract Tests")
class ViewAssemblyTestHarnessContractTest {
    private ViewAssemblyTestHarness harness;
    private Member                  member1;
    private Member                  member2;
    private Member                  member3;

    @BeforeEach
    void setUp() {
        var currentView = DigestAlgorithm.DEFAULT.digest("current-view");
        harness = new ViewAssemblyTestHarness(currentView);

        // Create test members
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), new SecureRandom());
        member1 = new ControlledIdentifierMember(stereotomy.newIdentifier());
        member2 = new ControlledIdentifierMember(stereotomy.newIdentifier());
        member3 = new ControlledIdentifierMember(stereotomy.newIdentifier());
    }

    @Test
    @DisplayName("setMemberState changes member availability")
    void setMemberState_ChangesAvailability() {
        harness.setMemberState(member1, ViewAssemblyTestHarness.MemberState.UNAVAILABLE);

        assertThat(harness.getMemberState(member1))
            .isEqualTo(ViewAssemblyTestHarness.MemberState.UNAVAILABLE);
    }

    @Test
    @DisplayName("proposeView accepts proposals from available members")
    void proposeView_AcceptsFromAvailable() {
        var viewId = DigestAlgorithm.DEFAULT.digest("proposed-view");
        var views = Views.getDefaultInstance();

        var accepted = harness.proposeView(member1, viewId, views);

        assertThat(accepted).isTrue();
        assertThat(harness.getViewProposals()).hasSize(1);
        assertThat(harness.getViewProposals().get(0).proposer).isEqualTo(member1);
        assertThat(harness.getViewProposals().get(0).viewId).isEqualTo(viewId);
    }

    @Test
    @DisplayName("proposeView rejects proposals from unavailable members")
    void proposeView_RejectsFromUnavailable() {
        harness.setMemberState(member1, ViewAssemblyTestHarness.MemberState.UNAVAILABLE);
        var viewId = DigestAlgorithm.DEFAULT.digest("proposed-view");

        var accepted = harness.proposeView(member1, viewId, Views.getDefaultInstance());

        assertThat(accepted).isFalse();
        assertThat(harness.getViewProposals()).isEmpty();
    }

    @Test
    @DisplayName("proposeView accepts from Byzantine members (for testing Byzantine scenarios)")
    void proposeView_AcceptsFromByzantine() {
        harness.setMemberState(member1, ViewAssemblyTestHarness.MemberState.BYZANTINE);
        var viewId = DigestAlgorithm.DEFAULT.digest("proposed-view");

        var accepted = harness.proposeView(member1, viewId, Views.getDefaultInstance());

        assertThat(accepted).isTrue();
    }

    @Test
    @DisplayName("vote records votes from available members")
    void vote_RecordsFromAvailable() {
        var viewId = DigestAlgorithm.DEFAULT.digest("view");

        var counted = harness.vote(member1, viewId, true);

        assertThat(counted).isTrue();
        assertThat(harness.getMemberVotes()).hasSize(1);
        assertThat(harness.getMemberVotes().get(0).voter).isEqualTo(member1);
        assertThat(harness.getMemberVotes().get(0).accept).isTrue();
    }

    @Test
    @DisplayName("vote rejects from unavailable members")
    void vote_RejectsFromUnavailable() {
        harness.setMemberState(member1, ViewAssemblyTestHarness.MemberState.LEFT);
        var viewId = DigestAlgorithm.DEFAULT.digest("view");

        var counted = harness.vote(member1, viewId, true);

        assertThat(counted).isFalse();
        assertThat(harness.getMemberVotes()).isEmpty();
    }

    @Test
    @DisplayName("getVotesFor filters votes by view ID")
    void getVotesFor_FiltersByViewId() {
        var view1 = DigestAlgorithm.DEFAULT.digest("view1");
        var view2 = DigestAlgorithm.DEFAULT.digest("view2");

        harness.vote(member1, view1, true);
        harness.vote(member2, view2, true);
        harness.vote(member3, view1, false);

        var view1Votes = harness.getVotesFor(view1);
        var view2Votes = harness.getVotesFor(view2);

        assertThat(view1Votes).hasSize(2);
        assertThat(view2Votes).hasSize(1);
    }

    @Test
    @DisplayName("countAcceptVotes counts only accept votes")
    void countAcceptVotes_CountsOnlyAccept() {
        var viewId = DigestAlgorithm.DEFAULT.digest("view");

        harness.vote(member1, viewId, true);
        harness.vote(member2, viewId, false);
        harness.vote(member3, viewId, true);

        assertThat(harness.countAcceptVotes(viewId)).isEqualTo(2);
    }

    @Test
    @DisplayName("transitionTo triggers listener notifications")
    void transitionTo_TriggersListeners() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var capturedTransition = new AtomicReference<ViewAssemblyTestHarness.ViewTransition>();

        harness.onViewTransition(transition -> {
            capturedTransition.set(transition);
            latch.countDown();
        });

        var newViewId = DigestAlgorithm.DEFAULT.digest("new-view");
        harness.transitionTo(newViewId, Set.of(member1), Set.of(member2));

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(capturedTransition.get()).isNotNull();
        assertThat(capturedTransition.get().toViewId).isEqualTo(newViewId);
        assertThat(capturedTransition.get().newMembers).containsExactly(member1);
        assertThat(capturedTransition.get().departedMembers).containsExactly(member2);
    }

    @Test
    @DisplayName("transitionTo updates current view")
    void transitionTo_UpdatesCurrentView() {
        var newViewId = DigestAlgorithm.DEFAULT.digest("new-view");

        harness.transitionTo(newViewId, Set.of(), Set.of());

        harness.assertTransitionTo(newViewId);  // Should not throw
    }

    @Test
    @DisplayName("injectConflictingVote requires Byzantine mode")
    void injectConflictingVote_RequiresByzantineMode() {
        var view1 = DigestAlgorithm.DEFAULT.digest("view1");
        var view2 = DigestAlgorithm.DEFAULT.digest("view2");

        assertThatThrownBy(() -> harness.injectConflictingVote(member1, view1, view2))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Byzantine mode must be enabled");
    }

    @Test
    @DisplayName("injectConflictingVote works when Byzantine mode enabled")
    void injectConflictingVote_WorksInByzantineMode() {
        harness.setByzantineMode(true);
        var view1 = DigestAlgorithm.DEFAULT.digest("view1");
        var view2 = DigestAlgorithm.DEFAULT.digest("view2");

        var success = harness.injectConflictingVote(member1, view1, view2);

        assertThat(success).isTrue();
        assertThat(harness.getMemberState(member1))
            .isEqualTo(ViewAssemblyTestHarness.MemberState.BYZANTINE);

        // Should have votes for both views
        assertThat(harness.getVotesFor(view1)).hasSize(1);
        assertThat(harness.getVotesFor(view2)).hasSize(1);
    }

    @Test
    @DisplayName("assertTransitionTo passes for correct view")
    void assertTransitionTo_PassesForCorrect() {
        var newViewId = DigestAlgorithm.DEFAULT.digest("new-view");
        harness.transitionTo(newViewId, Set.of(), Set.of());

        harness.assertTransitionTo(newViewId);  // Should not throw
    }

    @Test
    @DisplayName("assertTransitionTo fails for wrong view")
    void assertTransitionTo_FailsForWrong() {
        var wrongViewId = DigestAlgorithm.DEFAULT.digest("wrong-view");

        assertThatThrownBy(() -> harness.assertTransitionTo(wrongViewId))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Expected transition to");
    }

    @Test
    @DisplayName("assertCommitteeFormed counts available members")
    void assertCommitteeFormed_CountsAvailable() {
        harness.setMemberState(member1, ViewAssemblyTestHarness.MemberState.AVAILABLE);
        harness.setMemberState(member2, ViewAssemblyTestHarness.MemberState.AVAILABLE);
        harness.setMemberState(member3, ViewAssemblyTestHarness.MemberState.UNAVAILABLE);

        harness.assertCommitteeFormed(2);  // Should not throw
    }

    @Test
    @DisplayName("assertCommitteeFormed fails for wrong size")
    void assertCommitteeFormed_FailsForWrongSize() {
        harness.setMemberState(member1, ViewAssemblyTestHarness.MemberState.AVAILABLE);

        assertThatThrownBy(() -> harness.assertCommitteeFormed(5))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Expected committee size 5");
    }

    @Test
    @DisplayName("reset clears all history")
    void reset_ClearsHistory() {
        var viewId = DigestAlgorithm.DEFAULT.digest("view");
        harness.proposeView(member1, viewId, Views.getDefaultInstance());
        harness.vote(member1, viewId, true);
        harness.setMemberState(member1, ViewAssemblyTestHarness.MemberState.UNAVAILABLE);
        harness.setByzantineMode(true);

        harness.reset();

        assertThat(harness.getViewProposals()).isEmpty();
        assertThat(harness.getMemberVotes()).isEmpty();
        assertThat(harness.getMemberState(member1))
            .isEqualTo(ViewAssemblyTestHarness.MemberState.AVAILABLE);  // Defaults to AVAILABLE
    }

    @Test
    @DisplayName("awaitTransition waits for transition")
    void awaitTransition_WaitsForTransition() throws InterruptedException {
        var newViewId = DigestAlgorithm.DEFAULT.digest("new-view");

        // Transition in background thread
        new Thread(() -> {
            try {
                Thread.sleep(100);
                harness.transitionTo(newViewId, Set.of(), Set.of());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();

        var completed = harness.awaitTransition(2, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
    }

    @Test
    @DisplayName("onViewTransition supports multiple listeners")
    void onViewTransition_SupportsMultipleListeners() throws InterruptedException {
        var latch = new CountDownLatch(2);

        harness.onViewTransition(t -> latch.countDown());
        harness.onViewTransition(t -> latch.countDown());

        harness.transitionTo(DigestAlgorithm.DEFAULT.digest("new-view"), Set.of(), Set.of());

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
    }
}
