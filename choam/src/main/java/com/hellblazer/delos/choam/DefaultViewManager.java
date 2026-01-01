/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.CHOAM.PendingView;
import com.hellblazer.delos.choam.proto.Views;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;

import java.util.LinkedHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Default implementation of ViewManager that manages pending views using a thread-safe LinkedHashMap.
 *
 * @author hal.hildebrand
 */
public class DefaultViewManager implements ViewManager {

    private final ReadWriteLock                      lock  = new ReentrantReadWriteLock();
    private final LinkedHashMap<Digest, PendingView> views = new LinkedHashMap<>();

    @Override
    public void add(Digest diadem, Context<Member> context) {
        var l = lock.writeLock();
        try {
            l.lock();
            views.putIfAbsent(diadem, new PendingView(diadem, context));
        } finally {
            l.unlock();
        }
    }

    @Override
    public PendingView advance() {
        var l = lock.writeLock();
        try {
            l.lock();
            var last = views.lastEntry();
            if (last == null) {
                return null;
            }
            views.clear();
            views.put(last.getKey(), last.getValue());
            return last.getValue();
        } finally {
            l.unlock();
        }
    }

    @Override
    public void clear() {
        var l = lock.writeLock();
        try {
            l.lock();
            views.clear();
        } finally {
            l.unlock();
        }
    }

    @Override
    public PendingView get(Digest diadem) {
        var l = lock.readLock();
        try {
            l.lock();
            return views.get(diadem);
        } finally {
            l.unlock();
        }
    }

    @Override
    public Views.Builder getViews(Digest hash) {
        var l = lock.readLock();
        try {
            l.lock();
            var builder = Views.newBuilder();
            views.values().stream().map(pv -> pv.getView(hash)).forEach(builder::addViews);
            return builder;
        } finally {
            l.unlock();
        }
    }

    @Override
    public PendingView last() {
        var l = lock.readLock();
        try {
            l.lock();
            var last = views.lastEntry();
            return last == null ? null : last.getValue();
        } finally {
            l.unlock();
        }
    }
}
