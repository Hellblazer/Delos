/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset.Entry;
import com.google.common.collect.Ordering;
import com.hellblazer.delos.membership.Member;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks quorum responses with member provenance for Byzantine detection.
 * <p>
 * Replaces raw HashMultiset usage to enable tracking which member provided
 * which response value. This allows identifying Byzantine members that
 * return divergent (minority) responses or fail quorum validation.
 * </p>
 * <p>
 * Thread Safety: Used within SliceIterator handler callbacks which are
 * called sequentially. No concurrent access within a single quorum operation.
 * Safe without synchronization.
 * </p>
 *
 * @param <T> Response type (KeyState_, Empty, etc.)
 * @author hal.hildebrand
 */
public class QuorumResponseTracker<T> {
    private final Map<T, Set<Member>>  responseProviders = new HashMap<>();
    private final Map<Member, T>       memberResponses   = new HashMap<>();
    private final HashMultiset<T>      responseCounts    = HashMultiset.create();

    /**
     * Add a response from a member.
     *
     * @param response Response value
     * @param provider Member that provided this response (null safe)
     */
    public void add(T response, Member provider) {
        responseCounts.add(response);
        if (provider != null) {
            responseProviders.computeIfAbsent(response, _ -> new HashSet<>()).add(provider);
            memberResponses.put(provider, response);
        }
    }

    /**
     * Add a response from a member with Byzantine equivocation detection.
     * <p>
     * Detects when a member provides DIFFERENT responses for the SAME query
     * within a single quorum operation (equivocation). Records Byzantine signal
     * but still adds the response to allow quorum to complete.
     * </p>
     *
     * @param response          Response value
     * @param provider          Member that provided this response (null safe)
     * @param byzantineProvider Provider to record equivocation signals
     */
    public void add(T response, Member provider, ThothByzantineStateProvider byzantineProvider) {
        if (provider != null && byzantineProvider != null) {
            var previousResponse = memberResponses.get(provider);
            if (previousResponse != null && !previousResponse.equals(response)) {
                // EQUIVOCATION DETECTED: Same member, different response
                byzantineProvider.recordValidationFailure(provider.getId(),
                                                          "EQUIVOCATION: Member provided conflicting responses");
            }
        }
        add(response, provider);
    }

    /**
     * Check if this tracker has received a response from a specific member.
     *
     * @param member Member to check
     * @return true if member has provided at least one response
     */
    public boolean hasResponse(Member member) {
        return memberResponses.containsKey(member);
    }

    /**
     * Get the response provided by a specific member.
     *
     * @param member Member to query
     * @return The response from this member, or null if no response
     */
    public T getResponse(Member member) {
        return memberResponses.get(member);
    }

    /**
     * Get the response with the maximum count.
     *
     * @return Entry with max count, or null if no responses
     */
    public Entry<T> maxEntry() {
        return responseCounts.entrySet()
                             .stream()
                             .max(Ordering.natural().onResultOf(Entry::getCount))
                             .orElse(null);
    }

    /**
     * Get the count of the most common response.
     *
     * @return Max count, or 0 if no responses
     */
    public int maxCount() {
        var max = maxEntry();
        return max == null ? 0 : max.getCount();
    }

    /**
     * Get members that provided a specific response value.
     *
     * @param value Response value to query
     * @return Immutable set of members (empty if no providers)
     */
    public Set<Member> providersOf(T value) {
        return Set.copyOf(responseProviders.getOrDefault(value, Set.of()));
    }

    /**
     * Get all responses with their member provenance.
     *
     * @return Immutable map of response -> members
     */
    public Map<T, Set<Member>> allResponsesWithProviders() {
        return Map.copyOf(responseProviders);
    }

    /**
     * Get the count of a specific response value.
     *
     * @param value Response value to query
     * @return Count of this response
     */
    public int count(T value) {
        return responseCounts.count(value);
    }

    /**
     * Check if tracker is empty.
     *
     * @return true if no responses recorded
     */
    public boolean isEmpty() {
        return responseCounts.isEmpty();
    }

    /**
     * Get total number of responses recorded.
     *
     * @return Total response count
     */
    public int size() {
        return responseCounts.size();
    }
}
