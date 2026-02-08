/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.proto.Join;
import com.hellblazer.delos.choam.proto.SignedViewMember;
import com.hellblazer.delos.choam.proto.Views;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Test harness for deterministic view assembly testing. Provides utilities to:
 * - Control member availability (simulate joins/leaves)
 * - Inject view proposals in deterministic order
 * - Inject member votes
 * - Verify view transitions
 * - Simulate Byzantine behavior (conflicting votes)
 * <p>
 * Thread Safety: All operations are thread-safe. Listeners may be called from
 * test threads.
 *
 * @author hal.hildebrand
 */
public class ViewAssemblyTestHarness {
    private static final Logger log = LoggerFactory.getLogger(ViewAssemblyTestHarness.class);

    private final Map<Digest, MemberState>                     memberStates    = new ConcurrentHashMap<>();
    private final List<ViewProposal>                            viewProposals   = new CopyOnWriteArrayList<>();
    private final List<MemberVote>                              memberVotes     = new CopyOnWriteArrayList<>();
    private final List<Consumer<ViewTransition>>                transitionListeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean                                 byzantineMode   = new AtomicBoolean(false);
    private volatile Digest                                     currentViewId;
    private volatile Digest                                     nextViewId;
    private final CountDownLatch                                transitionComplete = new CountDownLatch(1);

    /**
     * Member availability state for testing.
     */
    public enum MemberState {
        AVAILABLE,      // Member can participate in view assembly
        UNAVAILABLE,    // Member temporarily unavailable (network partition)
        LEFT,           // Member permanently left
        BYZANTINE       // Member exhibits Byzantine behavior
    }

    /**
     * Record of a view proposal for test verification.
     */
    public static class ViewProposal {
        public final Member proposer;
        public final Digest viewId;
        public final Views  views;
        public final long   timestamp;

        public ViewProposal(Member proposer, Digest viewId, Views views) {
            this.proposer = proposer;
            this.viewId = viewId;
            this.views = views;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * Record of a member vote for test verification.
     */
    public static class MemberVote {
        public final Member voter;
        public final Digest viewId;
        public final boolean accept;
        public final long   timestamp;

        public MemberVote(Member voter, Digest viewId, boolean accept) {
            this.voter = voter;
            this.viewId = viewId;
            this.accept = accept;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * Record of a view transition event.
     */
    public static class ViewTransition {
        public final Digest          fromViewId;
        public final Digest          toViewId;
        public final Set<Member>     newMembers;
        public final Set<Member>     departedMembers;
        public final long            timestamp;

        public ViewTransition(Digest fromViewId, Digest toViewId, Set<Member> newMembers, Set<Member> departedMembers) {
            this.fromViewId = fromViewId;
            this.toViewId = toViewId;
            this.newMembers = Set.copyOf(newMembers);
            this.departedMembers = Set.copyOf(departedMembers);
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * Initialize the harness with starting view.
     *
     * @param currentViewId the current view ID
     */
    public ViewAssemblyTestHarness(Digest currentViewId) {
        this.currentViewId = currentViewId;
    }

    /**
     * Set member availability state.
     *
     * @param member the member
     * @param state  the availability state
     */
    public void setMemberState(Member member, MemberState state) {
        memberStates.put(member.getId(), state);
        log.trace("Member {} state changed to: {}", member.getId(), state);
    }

    /**
     * Get member availability state.
     *
     * @param member the member
     * @return the current state (defaults to AVAILABLE)
     */
    public MemberState getMemberState(Member member) {
        return memberStates.getOrDefault(member.getId(), MemberState.AVAILABLE);
    }

    /**
     * Inject a view proposal in deterministic order.
     *
     * @param proposer the proposing member
     * @param viewId   the proposed view ID
     * @param views    the views being proposed
     * @return true if proposal was accepted (member is available)
     */
    public boolean proposeView(Member proposer, Digest viewId, Views views) {
        var state = getMemberState(proposer);
        if (state != MemberState.AVAILABLE && state != MemberState.BYZANTINE) {
            log.trace("Rejecting proposal from unavailable member: {}", proposer.getId());
            return false;
        }

        var proposal = new ViewProposal(proposer, viewId, views);
        viewProposals.add(proposal);
        log.trace("View proposal from {}: viewId={}", proposer.getId(), viewId);

        return true;
    }

    /**
     * Inject a member vote.
     *
     * @param voter   the voting member
     * @param viewId  the view being voted on
     * @param accept  true to accept, false to reject
     * @return true if vote was counted (member is available)
     */
    public boolean vote(Member voter, Digest viewId, boolean accept) {
        var state = getMemberState(voter);
        if (state != MemberState.AVAILABLE && state != MemberState.BYZANTINE) {
            log.trace("Rejecting vote from unavailable member: {}", voter.getId());
            return false;
        }

        var vote = new MemberVote(voter, viewId, accept);
        memberVotes.add(vote);
        log.trace("Vote from {}: viewId={} accept={}", voter.getId(), viewId, accept);

        return true;
    }

    /**
     * Simulate a view transition.
     *
     * @param toViewId        the new view ID
     * @param newMembers      members joining in new view
     * @param departedMembers members leaving in new view
     */
    public void transitionTo(Digest toViewId, Set<Member> newMembers, Set<Member> departedMembers) {
        var transition = new ViewTransition(currentViewId, toViewId, newMembers, departedMembers);
        currentViewId = toViewId;

        // Notify listeners
        for (var listener : transitionListeners) {
            try {
                listener.accept(transition);
            } catch (Exception e) {
                log.warn("Transition listener threw exception", e);
            }
        }

        transitionComplete.countDown();
        log.info("View transition: {} -> {} (added: {}, departed: {})",
            transition.fromViewId, transition.toViewId,
            newMembers.size(), departedMembers.size());
    }

    /**
     * Register a listener for view transitions.
     *
     * @param listener the transition listener
     */
    public void onViewTransition(Consumer<ViewTransition> listener) {
        transitionListeners.add(listener);
    }

    /**
     * Enable Byzantine behavior mode (allows conflicting votes).
     *
     * @param enabled true to enable Byzantine mode
     */
    public void setByzantineMode(boolean enabled) {
        byzantineMode.set(enabled);
    }

    /**
     * Inject a conflicting vote (Byzantine behavior).
     * Only works if Byzantine mode is enabled.
     *
     * @param voter      the Byzantine voter
     * @param viewId1    first view ID
     * @param viewId2    second conflicting view ID
     * @return true if injection succeeded
     */
    public boolean injectConflictingVote(Member voter, Digest viewId1, Digest viewId2) {
        if (!byzantineMode.get()) {
            log.warn("Cannot inject conflicting vote - Byzantine mode not enabled");
            return false;
        }

        setMemberState(voter, MemberState.BYZANTINE);
        vote(voter, viewId1, true);
        vote(voter, viewId2, true);  // Equivocation!

        log.warn("Byzantine behavior: {} voted for both {} and {}", voter.getId(), viewId1, viewId2);
        return true;
    }

    /**
     * Get all view proposals in order.
     *
     * @return unmodifiable list of proposals
     */
    public List<ViewProposal> getViewProposals() {
        return List.copyOf(viewProposals);
    }

    /**
     * Get all member votes in order.
     *
     * @return unmodifiable list of votes
     */
    public List<MemberVote> getMemberVotes() {
        return List.copyOf(memberVotes);
    }

    /**
     * Get votes for a specific view.
     *
     * @param viewId the view ID
     * @return list of votes for that view
     */
    public List<MemberVote> getVotesFor(Digest viewId) {
        return memberVotes.stream()
                          .filter(v -> v.viewId.equals(viewId))
                          .toList();
    }

    /**
     * Count acceptance votes for a view.
     *
     * @param viewId the view ID
     * @return number of accept votes
     */
    public long countAcceptVotes(Digest viewId) {
        return memberVotes.stream()
                          .filter(v -> v.viewId.equals(viewId) && v.accept)
                          .count();
    }

    /**
     * Wait for view transition to complete.
     *
     * @param timeout  the timeout duration
     * @param unit     the timeout unit
     * @return true if transition completed
     * @throws InterruptedException if interrupted
     */
    public boolean awaitTransition(long timeout, TimeUnit unit) throws InterruptedException {
        return transitionComplete.await(timeout, unit);
    }

    /**
     * Assert that view transition occurred.
     *
     * @param expectedViewId the expected new view ID
     * @throws AssertionError if transition didn't occur or wrong view
     */
    public void assertTransitionTo(Digest expectedViewId) {
        if (!currentViewId.equals(expectedViewId)) {
            throw new AssertionError(
                String.format("Expected transition to %s but current view is %s",
                    expectedViewId, currentViewId));
        }
    }

    /**
     * Assert that a committee was formed.
     *
     * @param expectedSize the expected committee size
     * @throws AssertionError if committee not formed or wrong size
     */
    public void assertCommitteeFormed(int expectedSize) {
        var availableMembers = memberStates.entrySet().stream()
                                           .filter(e -> e.getValue() == MemberState.AVAILABLE)
                                           .count();

        if (availableMembers != expectedSize) {
            throw new AssertionError(
                String.format("Expected committee size %d but have %d available members",
                    expectedSize, availableMembers));
        }
    }

    /**
     * Clear all test history.
     */
    public void reset() {
        viewProposals.clear();
        memberVotes.clear();
        memberStates.clear();
        transitionListeners.clear();
        byzantineMode.set(false);
    }
}
