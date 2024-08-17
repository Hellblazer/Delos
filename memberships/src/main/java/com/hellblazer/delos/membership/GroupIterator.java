/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.membership;

import com.hellblazer.delos.utils.Entropy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Simple iterator on a group of members, randomly shuffling the membership list after each complete iteration
 *
 * @author hal.hildebrand
 */
public class GroupIterator {
    private final    List<Member> group;
    private final    Semaphore    exclusive = new Semaphore(1);
    private volatile int          current   = 0;

    public GroupIterator(Collection<Member> group) {
        this.group = new ArrayList<>(group);
    }

    public boolean hasNext() {
        return !group.isEmpty();
    }

    public Member next() {
        exclusive.acquireUninterruptibly();
        try {
            final var c = current;
            var m = group.get(c);
            current = (c + 1) % group.size();
            if (current == 0) {
                Entropy.secureShuffle(group);
            }
            return m;
        } finally {
            exclusive.release();
        }
    }
}
