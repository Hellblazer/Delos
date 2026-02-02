/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.leyden.comm.reconcile;

import com.hellblazer.delos.protocols.MicrometerEndpointMetrics;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer implementation of ReconciliationMetrics.
 *
 * @author hal.hildebrand
 */
public class MicrometerReconciliationMetrics extends MicrometerEndpointMetrics implements ReconciliationMetrics {

    private final DistributionSummary inboundReconcileSize;
    private final Timer               inboundReconcileTimer;
    private final Timer               inboundUpdateTimer;
    private final DistributionSummary reconcileReplySize;

    public MicrometerReconciliationMetrics(MeterRegistry registry) {
        super(registry, "reconciliation");

        inboundReconcileTimer = Timer.builder("reconciliation.reconcile.inbound.duration")
                                     .description("Inbound reconcile processing duration")
                                     .register(registry);

        inboundReconcileSize = DistributionSummary.builder("reconciliation.reconcile.inbound.bytes")
                                                  .description("Inbound reconcile request size")
                                                  .baseUnit("bytes")
                                                  .register(registry);

        inboundUpdateTimer = Timer.builder("reconciliation.update.inbound.duration")
                                  .description("Inbound update processing duration")
                                  .register(registry);

        reconcileReplySize = DistributionSummary.builder("reconciliation.reconcile.reply.bytes")
                                                .description("Reconcile reply size")
                                                .baseUnit("bytes")
                                                .register(registry);
    }

    @Override
    public void recordInboundReconcileSize(int bytes) {
        inboundReconcileSize.record(bytes);
    }

    @Override
    public void recordInboundReconcileDuration(long nanos) {
        inboundReconcileTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundUpdateDuration(long nanos) {
        inboundUpdateTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordReconcileReplySize(int bytes) {
        reconcileReplySize.record(bytes);
    }
}
