/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.stereotomy.services.grpc.binder;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Empty;
import com.hellblazer.delos.archipelago.ManagedServerChannel;
import com.hellblazer.delos.archipelago.ServerConnectionCache.CreateClientCommunications;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.event.proto.Ident;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;
import com.hellblazer.delos.stereotomy.services.grpc.proto.BinderGrpc;
import com.hellblazer.delos.stereotomy.services.grpc.proto.BinderGrpc.BinderFutureStub;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * @author hal.hildebrand
 */
public class BinderClient implements BinderService {

    private final ManagedServerChannel channel;
    private final BinderFutureStub     client;
    private final StereotomyMetrics    metrics;

    public BinderClient(ManagedServerChannel channel, StereotomyMetrics metrics) {
        this.channel = channel;
        this.client = channel.wrap(BinderGrpc.newFutureStub(channel));
        this.metrics = metrics;
    }

    public static CreateClientCommunications<BinderService> getCreate(StereotomyMetrics metrics) {
        return (c) -> {
            return new BinderClient(c, metrics);
        };

    }

    @Override
    public CompletableFuture<Boolean> bind(com.hellblazer.delos.stereotomy.event.proto.Binding binding) {
        var start = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            metrics.recordOutboundBandwidth(binding.getSerializedSize());
            metrics.recordOutboundBindRequest(binding.getSerializedSize());
        }
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        ListenableFuture<Empty> result = client.bind(binding);
        result.addListener(() -> {
            try {
                result.get();
            } catch (InterruptedException e) {
                f.completeExceptionally(e);
                return;
            } catch (ExecutionException e) {
                f.completeExceptionally(e.getCause());
                return;
            }
            if (metrics != null) {
                metrics.recordBindClientDuration(System.nanoTime() - start);
            }
            f.complete(true);
        }, r -> r.run());
        return f;
    }

    @Override
    public void close() {
        channel.release();
    }

    @Override
    public Member getMember() {
        return channel.getMember();
    }

    @Override
    public CompletableFuture<Boolean> unbind(Ident identifier) {
        var start = metrics == null ? 0L : System.nanoTime();
        if (metrics != null) {
            metrics.recordOutboundBandwidth(identifier.getSerializedSize());
            metrics.recordOutboundUnbindRequest(identifier.getSerializedSize());
        }
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        ListenableFuture<Empty> result = client.unbind(identifier);
        result.addListener(() -> {
            try {
                result.get();
            } catch (InterruptedException e) {
                f.completeExceptionally(e);
                return;
            } catch (ExecutionException e) {
                f.completeExceptionally(e.getCause());
                return;
            }
            if (metrics != null) {
                metrics.recordUnbindClientDuration(System.nanoTime() - start);
            }
            f.complete(true);
        }, r -> r.run());
        return f;
    }

}
