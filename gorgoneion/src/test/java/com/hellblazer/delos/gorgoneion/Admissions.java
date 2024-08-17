/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion;

import com.hellblazer.delos.archipelago.Link;
import com.hellblazer.delos.gorgoneion.proto.Credentials;
import com.hellblazer.delos.gorgoneion.proto.Establishment;
import com.hellblazer.delos.gorgoneion.proto.SignedNonce;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;

import java.io.IOException;
import java.time.Duration;

/**
 * @author hal.hildebrand
 */
public interface Admissions extends Link {

    static Admissions getLocalLoopback(Member node) {
        return new Admissions() {

            @Override
            public SignedNonce apply(KERL_ application, Duration timeout) {
                return null;
            }

            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return node;
            }

            @Override
            public Establishment register(Credentials credentials, Duration timeout) {
                return null;
            }
        };
    }

    SignedNonce apply(KERL_ application, Duration timeout);

    Establishment register(Credentials credentials, Duration timeout);
}
