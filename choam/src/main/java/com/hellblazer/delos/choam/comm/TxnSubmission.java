/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.comm;

import com.hellblazer.delos.archipelago.Link;
import com.hellblazer.delos.choam.proto.SubmitResult;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;

import java.io.IOException;

/**
 * @author hal.hildebrand
 */
public interface TxnSubmission extends Link {
    static TxnSubmission getLocalLoopback(SigningMember member, Submitter service) {
        return new TxnSubmission() {

            @Override
            public void close() throws IOException {

            }

            @Override
            public Member getMember() {
                return member;
            }

            @Override
            public SubmitResult submit(Transaction request) {
                return service.submit(request, member.getId());
            }
        };
    }

    SubmitResult submit(Transaction request);
}
