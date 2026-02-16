/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.archipelago;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;

import java.util.concurrent.TimeUnit;

/**
 * Wrapper around ManagedChannel that converts shutdown() into a pool release operation.
 * <p>
 * GrpcProxy.startCall() calls channel.shutdown() in its finally block. This wrapper
 * intercepts that call and decrements the reference count instead of actually shutting
 * down the channel. The actual channel shutdown is managed by CachedChannelPool eviction logic.
 * <p>
 * This design allows zero changes to GrpcProxy while enabling channel reuse.
 *
 * @author hal.hildebrand
 */
class PooledManagedChannel extends ManagedChannel {
    private final ManagedChannel delegate;
    private final CachedChannelPool.PoolEntry entry;

    PooledManagedChannel(ManagedChannel delegate, CachedChannelPool.PoolEntry entry) {
        this.delegate = delegate;
        this.entry = entry;
    }

    @Override
    public ManagedChannel shutdown() {
        entry.release();  // Decrement refcount instead of actual shutdown
        return this;
    }

    @Override
    public ManagedChannel shutdownNow() {
        entry.release();  // Same as shutdown for pooled channels
        return this;
    }

    @Override
    public String authority() {
        return delegate.authority();
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions options) {
        return delegate.newCall(method, options);
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
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
