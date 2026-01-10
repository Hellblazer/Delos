/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.proto.Views;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable pending views with Copy-on-Write semantics.
 *
 * PURPOSE: Eliminate nested locking (viewStateLock → pendingViews.lock) by making pending views immutable.
 * All mutation operations return new instances rather than modifying state in-place.
 *
 * KEY INSIGHT: No internal locks needed - immutability guarantees thread safety. The caller (CHOAM) protects
 * the AtomicReference that holds the current ImmutablePendingViews instance with viewStateLock.
 *
 * DESIGN PATTERN: Copy-on-Write
 * - add() returns a new ImmutablePendingViews with the new entry
 * - advance() returns a new ImmutablePendingViews with only the last entry
 * - clear() returns an empty ImmutablePendingViews
 * - Reads (get, last, size, isEmpty) are lock-free on immutable state
 *
 * @author hal.hildebrand
 */
public record ImmutablePendingViews(Map<Digest, PendingView> views) {

    /**
     * Empty immutable pending views.
     */
    public static final ImmutablePendingViews EMPTY = new ImmutablePendingViews(Map.of());

    /**
     * Create with defensive copy to ensure immutability.
     */
    public ImmutablePendingViews {
        views = Map.copyOf(views);
    }

    /**
     * Add a pending view (returns new instance with addition).
     *
     * @param diadem  the view's diadem digest
     * @param context the membership context
     * @return new ImmutablePendingViews with the added entry (or same if already present)
     */
    public ImmutablePendingViews add(Digest diadem, Context<Member> context) {
        if (views.containsKey(diadem)) {
            return this; // Already present, no change
        }
        var newViews = new LinkedHashMap<>(views);
        newViews.put(diadem, new PendingView(diadem, context));
        return new ImmutablePendingViews(newViews);
    }

    /**
     * Advance to next view (returns new instance with only last entry).
     *
     * @return new ImmutablePendingViews with only the last entry, or EMPTY if no entries
     */
    public ImmutablePendingViews advance() {
        if (views.isEmpty()) {
            return EMPTY;
        }
        // LinkedHashMap maintains insertion order, so lastEntry is the last inserted
        var entries = views.entrySet().iterator();
        Map.Entry<Digest, PendingView> last = null;
        while (entries.hasNext()) {
            last = entries.next();
        }
        if (last == null) {
            return EMPTY;
        }
        return new ImmutablePendingViews(Map.of(last.getKey(), last.getValue()));
    }

    /**
     * Get the last pending view (for reconfigure).
     *
     * @return the last PendingView, or null if empty
     */
    public PendingView last() {
        if (views.isEmpty()) {
            return null;
        }
        var entries = views.entrySet().iterator();
        Map.Entry<Digest, PendingView> last = null;
        while (entries.hasNext()) {
            last = entries.next();
        }
        return last == null ? null : last.getValue();
    }

    /**
     * Get a specific pending view by diadem.
     *
     * @param diadem the view's diadem digest
     * @return the PendingView, or null if not found
     */
    public PendingView get(Digest diadem) {
        return views.get(diadem);
    }

    /**
     * Clear all pending views (returns empty instance).
     *
     * @return EMPTY instance
     */
    public ImmutablePendingViews clear() {
        return EMPTY;
    }

    /**
     * Get views builder for all pending views.
     *
     * @param hash the hash for view construction
     * @return Views.Builder with all pending views
     */
    public Views.Builder getViews(Digest hash) {
        var builder = Views.newBuilder();
        views.values().stream().map(pv -> pv.getView(hash)).forEach(builder::addViews);
        return builder;
    }

    /**
     * Check if empty.
     *
     * @return true if no pending views
     */
    public boolean isEmpty() {
        return views.isEmpty();
    }

    /**
     * Get number of pending views.
     *
     * @return size
     */
    public int size() {
        return views.size();
    }

    /**
     * Immutable pending view record.
     *
     * @param diadem  the view's diadem digest
     * @param context the membership context
     */
    public record PendingView(Digest diadem, Context<Member> context) {
        /**
         * Answer the view created by finding the successors of the supplied hash on this Context
         *
         * @param hash - the "cut" across the rings of the context, determining the successors and thus the committee
         *             members of the view
         * @return the Vue determined by this Context and the supplied hash value
         */
        public com.hellblazer.delos.choam.proto.View getView(Digest hash) {
            var builder = com.hellblazer.delos.choam.proto.View.newBuilder()
                                                                .setDiadem(diadem.toDigeste())
                                                                .setMajority(context.majority());
            ((Context<? super Member>) context).bftSubset(hash)
                                               .forEach(d -> builder.addCommittee(d.getId().toDigeste()));
            return builder.build();
        }
    }
}
