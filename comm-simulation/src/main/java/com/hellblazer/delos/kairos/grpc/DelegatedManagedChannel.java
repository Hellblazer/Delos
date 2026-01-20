/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.kairos.grpc;

import java.util.concurrent.TimeUnit;

import com.google.common.base.MoreObjects;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;

/**
 * @author hal.hildebrand
 *
 */
public class DelegatedManagedChannel extends ManagedChannel {
    private volatile SimulatedManagedChannel delegate;

    public DelegatedManagedChannel(SimulatedManagedChannel delegate) {
        this.delegate = delegate;
    }

    @Override
    public String authority() {
        return delegate.authority();
    }

    @Override
    public final boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public void enterIdle() {
        delegate.enterIdle();
    }

    @Override
    public ConnectivityState getState(boolean requestConnection) {
        return delegate.getState(requestConnection);
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public final <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(MethodDescriptor<RequestT, ResponseT> methodDescriptor,
                                                                               CallOptions callOptions) {
        return delegate.newCall(methodDescriptor, callOptions);
    }

    @Override
    public void notifyWhenStateChanged(ConnectivityState source, Runnable callback) {
        delegate.notifyWhenStateChanged(source, callback);
    }

    @Override
    public void resetConnectBackoff() {
        delegate.resetConnectBackoff();
    }

    @Override
    public final ManagedChannel shutdown() {
        return delegate.shutdown();
    }

    @Override
    public final ManagedChannel shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("delegate", delegate).toString();
    }
}
