/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.kairos.grpc;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.google.common.base.MoreObjects;
import com.hellblazer.delos.kairos.KairosFuture;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;

/**
 * @author hal.hildebrand
 *
 */
abstract public class SimulatedManagedChannel extends ManagedChannel {
    protected final ManagedChannel concrete;

    public SimulatedManagedChannel(ManagedChannel concrete) {
        this.concrete = concrete;
    }

    @Override
    public String authority() {
        return concrete.authority();
    }

    @Override
    public final boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return concrete.awaitTermination(timeout, unit);
    }

    @Override
    public boolean isShutdown() {
        return concrete.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return concrete.isTerminated();
    }

    @Override
    public final <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(MethodDescriptor<RequestT, ResponseT> methodDescriptor,
                                                                               CallOptions callOptions) {
        return createNewCall(methodDescriptor, callOptions);
    }

    @Override
    public final ManagedChannel shutdown() {
        try {
            return scheduleShutdown().get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Error in simulation", e.getCause());
        }
    }

    @Override
    public final ManagedChannel shutdownNow() {
        try {
            return scheduleShutdownNow().get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Error in simulation", e.getCause());
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("concrete", concrete).toString();
    }

    protected abstract <RequestT, ResponseT> ClientCall<RequestT, ResponseT> createNewCall(MethodDescriptor<RequestT, ResponseT> methodDescriptor,
                                                                                           CallOptions callOptions);

    protected abstract KairosFuture<ClientCall<?, ?>> scheduleNewCall();

    protected abstract KairosFuture<ManagedChannel> scheduleShutdown();

    protected abstract KairosFuture<ManagedChannel> scheduleShutdownNow();
}
