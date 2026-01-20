/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
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
