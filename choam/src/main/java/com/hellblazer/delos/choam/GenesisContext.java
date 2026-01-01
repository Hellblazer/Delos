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
 * ViewContext for Genesis block creation. During Genesis, consensus keys are not yet exchanged,
 * so we use member identity keys for Ethereal signature validation.
 *
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

    /**
     * Build verifiers array using member identity keys. During Genesis, consensus keys are not yet
     * available (they are exchanged via Join messages during Genesis consensus), so we use
     * member identity keys for Ethereal commit/prevote signature validation. Members implement
     * the Verifier interface.
     *
     * @return Verifier[] indexed by PID, with member identity verifiers
     */
    @Override
    public Verifier[] verifiersByPid() {
        var result = new Verifier[roster().size()];
        context().stream(1).forEach(m -> {
            var pid = roster().get(m.getId());
            if (pid != null && pid >= 0 && pid < result.length) {
                result[pid] = m; // Member implements Verifier
            }
        });
        return result;
    }
}
