/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.ethereal.memberships.comm;

import com.hellblazer.delos.ethereal.proto.ContextUpdate;
import com.hellblazer.delos.ethereal.proto.Gossip;
import com.hellblazer.delos.ethereal.proto.Update;
import com.hellblazer.delos.archipelago.Link;
import com.hellblazer.delos.membership.Member;

import java.io.IOException;

/**
 * @author hal.hildebrand
 */
public interface Gossiper extends Link {

    static <S extends Member> Gossiper getLocalLoopback(S member) {
        return new Gossiper() {

            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return member;
            }

            @Override
            public Update gossip(Gossip request) {
                return null;
            }

            @Override
            public void update(ContextUpdate update) {
            }
        };
    }

    Update gossip(Gossip request);

    void update(ContextUpdate update);
}
