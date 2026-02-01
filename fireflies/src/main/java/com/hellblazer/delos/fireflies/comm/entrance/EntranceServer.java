/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.fireflies.comm.entrance;

import com.hellblazer.delos.archipelago.RoutableService;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.FireflyMetrics;
import com.hellblazer.delos.fireflies.View.Service;
import com.hellblazer.delos.fireflies.proto.EntranceGrpc.EntranceImplBase;
import com.hellblazer.delos.fireflies.proto.JoinResponse;
import com.hellblazer.delos.fireflies.proto.Join;
import com.hellblazer.delos.fireflies.proto.Redirect;
import com.hellblazer.delos.fireflies.proto.Registration;
import com.hellblazer.delos.protocols.ClientIdentity;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author hal.hildebrand
 */
public class EntranceServer extends EntranceImplBase {
    private static final Logger log = LoggerFactory.getLogger(EntranceServer.class);

    private final FireflyMetrics           metrics;
    private final RoutableService<Service> router;
    private final ClientIdentity           identity;

    public EntranceServer(ClientIdentity identity, RoutableService<Service> r, FireflyMetrics metrics) {
        this.metrics = metrics;
        this.identity = identity;
        this.router = r;
    }

    @Override
    public void join(Join request, StreamObserver<JoinResponse> responseObserver) {
        long startNanos = System.nanoTime();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundJoinSize(serializedSize);
        }
        Digest from = identity.getFrom();
        log.info("EntranceServer.join() called from: {} (identity)", from);
        if (from == null) {
            log.warn("EntranceServer.join() rejecting - from is null (member removed)");
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        log.info("EntranceServer.join() calling router.evaluate() from: {}", from);
        router.evaluate(responseObserver, s -> {
            log.info("EntranceServer.join() inside router.evaluate() callback from: {}", from);
            try {
                s.join(request, from, responseObserver, startNanos);
                log.info("EntranceServer.join() Service.join() completed from: {}", from);
            } catch (Throwable t) {
                log.error("EntranceServer.join() Service.join() threw exception from: {}", from, t);
                try {
                    responseObserver.onError(t);
                } catch (Throwable throwable) {
                    // ignore as response observer is closed
                }
            }
        });
    }

    @Override
    public void seed(Registration request, StreamObserver<Redirect> responseObserver) {
        long startNanos = System.nanoTime();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.recordInboundBandwidth(serializedSize);
            metrics.recordInboundSeedSize(serializedSize);
        }
        Digest from = identity.getFrom();
        log.info("EntranceServer.seed() called from: {} (identity)", from);
        if (from == null) {
            log.warn("EntranceServer.seed() rejecting - from is null (member removed)");
            responseObserver.onError(new IllegalStateException("Member has been removed"));
            return;
        }
        log.info("EntranceServer.seed() calling router.evaluate() from: {}", from);
        router.evaluate(responseObserver, s -> {
            log.info("EntranceServer.seed() inside router.evaluate() callback from: {}", from);
            Redirect r;
            try {
                r = s.seed(request, from);
                log.info("EntranceServer.seed() Service.seed() returned introductions: {} from: {}",
                         r.getIntroductionsCount(), from);
            } catch (Throwable t) {
                log.error("EntranceServer.seed() Service.seed() threw exception from: {}", from, t);
                responseObserver.onError(t);
                return;
            }
            responseObserver.onNext(r);
            responseObserver.onCompleted();
            log.info("EntranceServer.seed() completed successfully from: {}", from);
            if (metrics != null) {
                var serializedSize = r.getSerializedSize();
                metrics.recordOutboundBandwidth(serializedSize);
                metrics.recordOutboundRedirectSize(serializedSize);
                metrics.recordInboundSeedDuration(System.nanoTime() - startNanos);
            }
        });
    }
}
