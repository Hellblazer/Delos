/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.proto.View;
import com.hellblazer.delos.choam.proto.Views;
import com.hellblazer.delos.choam.support.ImmutablePendingViews;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;

/**
 * Lightweight wrapper for ImmutablePendingViews that maintains API compatibility.
 *
 * KEY INSIGHT: This class has NO internal locks. It's just a read-only view of
 * an ImmutablePendingViews instance. Thread safety is provided by immutability.
 *
 * This wrapper exists solely to maintain the existing API for ViewContext and other
 * consumers that expect PendingViews type.
 *
 * @author hal.hildebrand
 */
public class PendingViews {
    private final ImmutablePendingViews delegate;

    public PendingViews(ImmutablePendingViews delegate) {
        this.delegate = delegate;
    }

    public PendingView get(Digest diadem) {
        var immutablePv = delegate.get(diadem);
        return immutablePv == null ? null : new PendingView(immutablePv.diadem(), immutablePv.context());
    }

    public Views.Builder getViews(Digest hash) {
        return delegate.getViews(hash);
    }

    public PendingView last() {
        var immutablePv = delegate.last();
        return immutablePv == null ? null : new PendingView(immutablePv.diadem(), immutablePv.context());
    }

    /**
     * A pending view represents a view that is being assembled but not yet committed.
     *
     * @param diadem  the view identifier
     * @param context the membership context for this view
     */
    public record PendingView(Digest diadem, Context<Member> context) {
        /**
         * Answer the view created by finding the successors of the supplied hash on this Context
         *
         * @param hash - the "cut" across the rings of the context, determining the successors and thus the committee
         *             members of the view
         * @return the Vue determined by this Context and the supplied hash value
         */
        public View getView(Digest hash) {
            var builder = View.newBuilder().setDiadem(diadem.toDigeste()).setMajority(context.majority());
            ((Context<? super Member>) context).bftSubset(hash)
                                               .forEach(d -> builder.addCommittee(d.getId().toDigeste()));
            return builder.build();
        }
    }
}
