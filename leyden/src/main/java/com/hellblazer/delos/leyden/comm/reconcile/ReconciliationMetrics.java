package com.hellblazer.delos.leyden.comm.reconcile;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Timer;
import com.hellblazer.delos.protocols.EndpointMetrics;

public interface ReconciliationMetrics extends EndpointMetrics {
    Histogram inboundReconcile();

    Timer inboundReconcileTimer();

    Timer inboundUpdateTimer();

    Histogram reconcileReply();
}
