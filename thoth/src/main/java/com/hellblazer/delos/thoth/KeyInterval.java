/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.thoth.proto.Interval;
import com.hellblazer.delos.cryptography.Digest;

import java.util.function.Predicate;

/**
 * @author hal.hildebrand
 */
public class KeyInterval implements Predicate<Digest> {
    private final Digest begin;
    private final Digest end;

    public KeyInterval(Digest begin, Digest end) {
        assert begin.compareTo(end) < 0 : begin + " >= " + end;
        this.begin = begin;
        this.end = end;
    }

    public KeyInterval(Interval interval) {
        this(Digest.from(interval.getStart()), Digest.from(interval.getEnd()));
    }

    public Digest getBegin() {
        return begin;
    }

    public Digest getEnd() {
        return end;
    }

    @Override
    public boolean test(Digest t) {
        return begin.compareTo(t) <= 0 && end.compareTo(t) > 0;
    }

    public Interval toInterval() {
        return Interval.newBuilder().setStart(begin.toDigeste()).setEnd(end.toDigeste()).build();
    }

    @Override
    public String toString() {
        return String.format("KeyInterval [begin=%s, end=%s]", begin, end);
    }
}
