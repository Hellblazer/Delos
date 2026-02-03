/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.messaging.rbc.comms;

import com.hellblazer.delos.archipelago.ManagedServerChannel;
import com.hellblazer.delos.archipelago.ServerConnectionCache.CreateClientCommunications;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.messaging.rbc.RbcMetrics;
import com.hellblazer.delos.messaging.proto.MessageBff;
import com.hellblazer.delos.messaging.proto.RBCGrpc;
import com.hellblazer.delos.messaging.proto.Reconcile;
import com.hellblazer.delos.messaging.proto.ReconcileContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * @author hal.hildebrand
 * @since 220
 */
public class RbcClient implements ReliableBroadcast {
    private static final Logger   log             = LoggerFactory.getLogger(RbcClient.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final ManagedServerChannel    channel;
    private final RBCGrpc.RBCBlockingStub client;
    private final RbcMetrics              metrics;
    private final Duration                timeout;

    public RbcClient(ManagedServerChannel c, RbcMetrics metrics) {
        this(c, metrics, DEFAULT_TIMEOUT);
    }

    public RbcClient(ManagedServerChannel c, RbcMetrics metrics, Duration timeout) {
        this.channel = c;
        this.client = c.wrap(RBCGrpc.newBlockingStub(c));
        this.metrics = metrics;
        this.timeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
    }

    public static CreateClientCommunications<ReliableBroadcast> getCreate(RbcMetrics metrics) {
        return getCreate(metrics, DEFAULT_TIMEOUT);
    }

    public static CreateClientCommunications<ReliableBroadcast> getCreate(RbcMetrics metrics, Duration timeout) {
        return (c) -> new RbcClient(c, metrics, timeout);
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
    public Reconcile gossip(MessageBff request) {
        long start = metrics != null ? System.nanoTime() : 0;
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundGossipSize(serializedSize);
        }
        // Apply RPC timeout to prevent hanging calls (Delos-l03r)
        var result = client.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS).gossip(request);
        if (metrics != null) {
            metrics.recordOutboundGossipDuration(System.nanoTime() - start);
            var serializedSize = result.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordGossipResponseSize(serializedSize);
        }
        return result;
    }

    public void start() {

    }

    @Override
    public String toString() {
        return String.format("->[%s]", channel.getMember());
    }

    @Override
    public void update(ReconcileContext request) {
        long start = metrics != null ? System.nanoTime() : 0;
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordOutboundBandwidth(serializedSize);
            metrics.recordOutboundUpdateSize(serializedSize);
        }
        try {
            // Apply RPC timeout to prevent hanging calls (Delos-l03r)
            client.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS).update(request);
        } catch (Throwable e) {
            // Log failures for debugging (Delos-3nen)
            log.debug("Update failed to {}: {}", channel.getMember().getId(), e.getMessage());
            log.trace("Update failure details", e);
        } finally {
            // Record duration on both success and failure for SLA monitoring
            if (metrics != null) {
                metrics.recordOutboundUpdateDuration(System.nanoTime() - start);
            }
        }
    }
}
