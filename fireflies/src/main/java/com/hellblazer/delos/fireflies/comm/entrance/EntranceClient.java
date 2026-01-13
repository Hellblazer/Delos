/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies.comm.entrance;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.hellblazer.delos.archipelago.ManagedServerChannel;
import com.hellblazer.delos.archipelago.ServerConnectionCache.CreateClientCommunications;
import com.hellblazer.delos.fireflies.FireflyMetrics;
import com.hellblazer.delos.fireflies.proto.*;
import com.hellblazer.delos.membership.Member;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * @author hal.hildebrand
 */
public class EntranceClient implements Entrance {
    private static final Logger log = LoggerFactory.getLogger(EntranceClient.class);

    private final ManagedServerChannel            channel;
    private final EntranceGrpc.EntranceBlockingStub client;
    private final FireflyMetrics                  metrics;
    private final EntranceGrpc.EntranceStub       asyncClient;

    public EntranceClient(ManagedServerChannel channel, FireflyMetrics metrics) {
        this.channel = channel;
        this.client = channel.wrap(EntranceGrpc.newBlockingStub(channel));
        asyncClient = channel.wrap(EntranceGrpc.newStub(channel));
        this.metrics = metrics;
    }

    public static CreateClientCommunications<Entrance> getCreate(FireflyMetrics metrics) {
        return (c) -> new EntranceClient(c, metrics);

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
    public ListenableFuture<Gateway> join(Join join, Duration timeout) {
        if (metrics != null) {
            var serializedSize = join.getSerializedSize();
            metrics.outboundBandwidth().mark(serializedSize);
            metrics.outboundJoin().update(serializedSize);
        }

        SettableFuture<Gateway> result = SettableFuture.create();

        asyncClient.withDeadlineAfter(timeout.toNanos(), TimeUnit.NANOSECONDS).join(join, new StreamObserver<JoinResponse>() {
            private JoinAcknowledgment ack;
            private Gateway gateway;

            @Override
            public void onNext(JoinResponse response) {
                if (response.hasAck()) {
                    ack = response.getAck();
                    log.debug("Join acknowledged for view: {} estimated wait: {}ms queue position: {}",
                              ack.getView(), ack.getEstimatedWaitTimeMs(), ack.getJoinSequenceNumber());
                } else if (response.hasGateway()) {
                    gateway = response.getGateway();
                    if (metrics != null) {
                        try {
                            var serializedSize = gateway.getSerializedSize();
                            metrics.inboundBandwidth().mark(serializedSize);
                            metrics.inboundGateway().update(serializedSize);
                        } catch (Throwable e) {
                            // ignore
                        }
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                result.setException(t);
            }

            @Override
            public void onCompleted() {
                if (gateway != null) {
                    result.set(gateway);
                } else {
                    result.setException(new IllegalStateException("No Gateway received in join response"));
                }
            }
        });

        return result;
    }

    @Override
    public Redirect seed(Registration registration) {
        if (metrics != null) {
            var serializedSize = registration.getSerializedSize();
            metrics.outboundBandwidth().mark(serializedSize);
            metrics.outboundSeed().update(serializedSize);
        }
        Redirect result = client.seed(registration);
        if (metrics != null) {
            try {
                var serializedSize = result.getSerializedSize();
                metrics.inboundBandwidth().mark(serializedSize);
                metrics.inboundRedirect().update(serializedSize);
            } catch (Throwable e) {
                // nothing
            }
        }
        return result;
    }

}
