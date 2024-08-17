/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies.comm.entrance;

import com.google.common.util.concurrent.ListenableFuture;
import com.hellblazer.delos.archipelago.Link;
import com.hellblazer.delos.fireflies.View.Node;
import com.hellblazer.delos.fireflies.proto.Gateway;
import com.hellblazer.delos.fireflies.proto.Join;
import com.hellblazer.delos.fireflies.proto.Redirect;
import com.hellblazer.delos.fireflies.proto.Registration;
import com.hellblazer.delos.membership.Member;

import java.io.IOException;
import java.time.Duration;

/**
 * @author hal.hildebrand
 */
public interface Entrance extends Link {

    static Entrance getLocalLoopback(Node node, EntranceService service) {
        return new Entrance() {

            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return node;
            }

            @Override
            public ListenableFuture<Gateway> join(Join join, Duration timeout) {
                return null;
            }

            @Override
            public Redirect seed(Registration registration) {
                return null;
            }
        };
    }

    ListenableFuture<Gateway> join(Join join, Duration timeout);

    Redirect seed(Registration registration);
}
