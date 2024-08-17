/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.CHOAM.BlockProducer;
import com.hellblazer.delos.choam.proto.Validate;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.membership.Member;

import java.util.Collections;
import java.util.function.Supplier;

/**
 * @author hal.hildebrand
 */
public class GenesisContext extends ViewContext {

    public GenesisContext(Context<Member> context, Supplier<CHOAM.PendingViews> pendingView, Parameters params,
                          Signer signer, BlockProducer blockProducer) {
        super(context, params, pendingView, signer, Collections.emptyMap(), blockProducer);
    }

    @Override
    protected Verifier verifierOf(Validate validate) {
        return new Verifier.MockVerifier();
    }
}
