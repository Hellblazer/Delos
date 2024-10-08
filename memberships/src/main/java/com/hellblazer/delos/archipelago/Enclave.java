/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.archipelago;

import com.netflix.concurrency.limits.Limit;
import com.netflix.concurrency.limits.grpc.server.ConcurrencyLimitServerInterceptor;
import com.netflix.concurrency.limits.grpc.server.GrpcServerLimiterBuilder;
import com.hellblazer.delos.archipelago.server.FernetServerInterceptor;
import com.hellblazer.delos.comm.grpc.DomainSocketServerInterceptor;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.protocols.ClientIdentity;
import com.hellblazer.delos.protocols.LimitsRegistry;
import io.grpc.*;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.netty.DomainSocketNegotiatorHandler.DomainSocketNegotiator;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.hellblazer.delos.comm.grpc.DomainSocketServerInterceptor.IMPL;
import static com.hellblazer.delos.cryptography.QualifiedBase64.digest;
import static com.hellblazer.delos.cryptography.QualifiedBase64.qb64;

/**
 * Enclave Server for routing from a process endpoint in the default Isolate into individual Isolates.
 *
 * @author hal.hildebrand
 */
public class Enclave implements RouterSupplier {
    private final static Class<? extends io.netty.channel.Channel> channelType = IMPL.getChannelType();
    private static final Logger                                    log         = LoggerFactory.getLogger(Enclave.class);

    private final DomainSocketAddress bridge;
    private final Consumer<Digest>    contextRegistration;
    private final DomainSocketAddress endpoint;
    private final EventLoopGroup      eventLoopGroup = IMPL.getEventLoopGroup();
    private final Member              from;
    private final String              fromString;

    public Enclave(Member from, DomainSocketAddress endpoint, DomainSocketAddress bridge,
                   Consumer<Digest> contextRegistration) {
        this.bridge = bridge;
        this.endpoint = endpoint;
        this.contextRegistration = contextRegistration;
        this.from = from;
        this.fromString = qb64(from.getId());
    }

    /**
     * @return the DomainSocketAddress for this Enclave
     */
    public DomainSocketAddress getEndpoint() {
        return endpoint;
    }

    @Override
    public RouterImpl router(ServerConnectionCache.Builder cacheBuilder, Supplier<Limit> serverLimit,
                             LimitsRegistry limitsRegistry, List<ServerInterceptor> interceptors,
                             Predicate<FernetServerInterceptor.HashedToken> validator, ExecutorService executor) {
        if (executor == null) {
            executor = Executors.newVirtualThreadPerTaskExecutor();
        }
        var limitsBuilder = new GrpcServerLimiterBuilder().limit(serverLimit.get());
        if (limitsRegistry != null) {
            limitsBuilder.metricRegistry(limitsRegistry);
        }
        ServerBuilder<?> serverBuilder = NettyServerBuilder.forAddress(endpoint)
                                                           .executor(executor)
                                                           .protocolNegotiator(new DomainSocketNegotiator(IMPL))
                                                           .channelType(IMPL.getServerDomainSocketChannelClass())
                                                           .withChildOption(ChannelOption.TCP_NODELAY, true)
                                                           .workerEventLoopGroup(IMPL.getEventLoopGroup())
                                                           .bossEventLoopGroup(IMPL.getEventLoopGroup())
                                                           .intercept(new DomainSocketServerInterceptor())
                                                           .intercept(ConcurrencyLimitServerInterceptor.newBuilder(
                                                                                                       limitsBuilder.build())
                                                                                                       .statusSupplier(
                                                                                                       () -> Status.RESOURCE_EXHAUSTED.withDescription(
                                                                                                       "Enclave server concurrency limit reached"))
                                                                                                       .build())
                                                           .intercept(serverInterceptor());
        interceptors.forEach(i -> {
            serverBuilder.intercept(i);
        });
        return new RouterImpl(from, serverBuilder, cacheBuilder.setFactory(t -> connectTo(t)),
                              new RoutingClientIdentity() {
                                  @Override
                                  public Digest getAgent() {
                                      return Constants.SERVER_AGENT_ID_KEY.get();
                                  }

                                  @Override
                                  public Digest getFrom() {
                                      return Constants.SERVER_CLIENT_ID_KEY.get();
                                  }
                              }, contextRegistration, validator);
    }

    private ManagedChannel connectTo(Member to) {
        var clientInterceptor = new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                                       CallOptions callOptions, Channel next) {
                ClientCall<ReqT, RespT> newCall = next.newCall(method, callOptions);
                return new SimpleForwardingClientCall<ReqT, RespT>(newCall) {
                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                        headers.put(Constants.METADATA_TARGET_KEY, qb64(to.getId()));
                        headers.put(Constants.METADATA_CLIENT_ID_KEY, fromString);
                        super.start(responseListener, headers);
                    }
                };
            }
        };
        final var builder = NettyChannelBuilder.forAddress(bridge)
                                               .withOption(ChannelOption.TCP_NODELAY, true)
                                               .eventLoopGroup(eventLoopGroup)
                                               .channelType(channelType)
                                               .usePlaintext()
                                               .intercept(clientInterceptor);
        return builder.build();
    }

    private ServerInterceptor serverInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                         final Metadata requestHeaders,
                                                                         ServerCallHandler<ReqT, RespT> next) {
                String id = requestHeaders.get(Constants.METADATA_CLIENT_ID_KEY);
                if (id == null) {
                    log.error("No member id in call headers: {}", requestHeaders.keys());
                    throw new IllegalStateException("No member ID in call");
                }
                String agent = requestHeaders.get(Constants.METADATA_AGENT_KEY);
                if (agent == null) {
                    log.error("No agent id in call headers: {}", requestHeaders.keys());
                    throw new IllegalStateException("No agent ID in call");
                }
                Context ctx = Context.current()
                                     .withValue(Constants.SERVER_AGENT_ID_KEY, digest(agent))
                                     .withValue(Constants.SERVER_CLIENT_ID_KEY, digest(id));
                return Contexts.interceptCall(ctx, call, requestHeaders, next);
            }
        };
    }

    public interface RoutingClientIdentity extends ClientIdentity {
        Digest getAgent();
    }
}
