/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.membership;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.*;
import java.util.stream.Collector;

import static java.lang.Math.exp;
import static java.lang.Math.log;

/**
 * @author hal.hildebrand
 **/
public class ReservoirSampler<T> implements Collector<T, List<T>, List<T>> {
    private final    int          capacity;
    private final    Predicate<T> ignore;
    private volatile double       w;
    private volatile long         counter;
    private volatile long         next;

    public ReservoirSampler(int capacity, T ignore) {
        this(capacity, t -> t.equals(ignore));
    }

    public ReservoirSampler(int capacity, Predicate<T> ignore) {
        this.capacity = capacity;
        w = exp(log(ThreadLocalRandom.current().nextDouble()) / capacity);
        skip();
        this.ignore = ignore == null ? t -> false : ignore;
    }

    public ReservoirSampler(int capacity) {
        this(capacity, (Predicate<T>) null);
    }

    @Override
    public BiConsumer<List<T>, T> accumulator() {
        return this::addIt;
    }

    @Override
    public Set<Characteristics> characteristics() {
        return EnumSet.of(Collector.Characteristics.UNORDERED, Collector.Characteristics.IDENTITY_FINISH);
    }

    @Override
    public BinaryOperator<List<T>> combiner() {
        return (left, right) -> {
            left.addAll(right);
            return left;
        };
    }

    @Override
    public Function<List<T>, List<T>> finisher() {
        return (i) -> i;
    }

    @Override
    public Supplier<List<T>> supplier() {
        return () -> new ArrayList<T>(capacity);
    }

    private void addIt(final List<T> reservoir, T s) {
        if (ignore.test(s)) {
            return;
        }

        if (reservoir.size() < capacity) {
            // Fill phase: add elements until we reach capacity
            reservoir.add(s);
        } else {
            // Sampling phase: use Algorithm L skip-ahead optimization
            if (counter == next) {
                reservoir.set(ThreadLocalRandom.current().nextInt(capacity), s);
                skip();
            }
        }
        ++counter;
    }

    private void skip() {
        next += (long) (log(ThreadLocalRandom.current().nextDouble()) / log(1 - w)) + 1;
        w *= exp(log(ThreadLocalRandom.current().nextDouble()) / capacity);
    }
}
