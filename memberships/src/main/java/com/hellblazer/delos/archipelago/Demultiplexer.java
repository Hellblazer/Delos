/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.archipelago;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * GRPC demultiplexer. Maps from one inbound endpoint to multiple outbound
 * servers via a routing function. Supplied Metadata key provides the routing
 * key.
 *
 * @author hal.hildebrand
 *
 */
public class Demultiplexer {
    private static final Logger              log              = LoggerFactory.getLogger(Demultiplexer.class);
    private static final Context.Key<String> ROUTE_TARGET_KEY = Context.key(UUID.randomUUID().toString());

    private final CachedChannelPool channelPool; // Optional, nullable for backward compatibility
    private final Server            server;
    private final AtomicBoolean     started = new AtomicBoolean();

    /**
     * Create a Demultiplexer with channel caching.
     *
     * @param serverBuilder The gRPC server builder
     * @param routing       Metadata key containing the route target
     * @param dmux          Factory function to create channels for route targets
     * @param cacheConfig   Channel cache configuration (use ChannelCacheConfig.disabled() to disable caching)
     */
    public Demultiplexer(ServerBuilder<?> serverBuilder, Metadata.Key<String> routing,
                         Function<String, ManagedChannel> dmux, ChannelCacheConfig cacheConfig) {
        // Enable caching if maxCachedChannels > 0
        this.channelPool = cacheConfig.maxCachedChannels() > 0
            ? new CachedChannelPool(dmux, cacheConfig)
            : null;

        var serverInterceptor = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                         final Metadata requestHeaders,
                                                                         ServerCallHandler<ReqT, RespT> next) {
                String route = requestHeaders.get(routing);
                if (route == null) {
                    log.error("No route in call header: {}", routing.name());
                    throw new StatusRuntimeException(Status.UNKNOWN.withDescription("No route ID in call, missing header: "
                    + routing.name()));
                }
                return Contexts.interceptCall(Context.current().withValue(ROUTE_TARGET_KEY, route), call,
                                              requestHeaders, next);
            }
        };
        server = serverBuilder.intercept(serverInterceptor).fallbackHandlerRegistry(new GrpcProxy() {
            @Override
            protected ManagedChannel getChannel() {
                var route = ROUTE_TARGET_KEY.get();
                if (channelPool != null) {
                    return channelPool.borrowChannel(route);
                }
                return dmux.apply(route); // Fallback: per-request (existing behavior)
            }
        }.newRegistry()).build();
    }

    /**
     * Create a Demultiplexer without channel caching (backward compatibility).
     *
     * @param serverBuilder The gRPC server builder
     * @param routing       Metadata key containing the route target
     * @param dmux          Factory function to create channels for route targets
     */
    public Demultiplexer(ServerBuilder<?> serverBuilder, Metadata.Key<String> routing,
                         Function<String, ManagedChannel> dmux) {
        this(serverBuilder, routing, dmux, ChannelCacheConfig.disabled());
    }

    public void close(Duration await) {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        try {
            server.shutdown();
            try {
                server.awaitTermination(await.toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (RejectedExecutionException e) {
            // eat
        }

        // Close channel pool if present
        if (channelPool != null) {
            channelPool.close();
        }
    }

    public void start() throws IOException {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        server.start();
    }
}
