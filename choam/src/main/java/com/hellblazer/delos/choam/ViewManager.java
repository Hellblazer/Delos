/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.CHOAM.PendingView;
import com.hellblazer.delos.choam.proto.Views;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;

/**
 * Manages pending views and view lifecycle for CHOAM consensus.
 *
 * @author hal.hildebrand
 */
public interface ViewManager {

    /**
     * Add a pending view with the given diadem and context.
     */
    void add(Digest diadem, Context<Member> context);

    /**
     * Advance to the next view, clearing all but the last pending view.
     *
     * @return the last pending view, or null if no views exist
     */
    PendingView advance();

    /**
     * Clear all pending views.
     */
    void clear();

    /**
     * Get a specific pending view by diadem.
     *
     * @param diadem the view identifier
     * @return the pending view, or null if not found
     */
    PendingView get(Digest diadem);

    /**
     * Get all pending views as a Views builder.
     *
     * @param hash the hash used to generate views
     * @return builder containing all views
     */
    Views.Builder getViews(Digest hash);

    /**
     * Get the last pending view.
     *
     * @return the last pending view, or null if no views exist
     */
    PendingView last();
}
