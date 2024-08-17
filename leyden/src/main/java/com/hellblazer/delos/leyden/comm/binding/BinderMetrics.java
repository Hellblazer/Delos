package com.hellblazer.delos.leyden.comm.binding;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Timer;
import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * @author hal.hildebrand
 **/
public interface BinderMetrics extends EndpointMetrics {
    Histogram inboundBind();

    Timer inboundBindTimer();

    Histogram inboundGet();

    Timer inboundGetTimer();

    Histogram inboundUnbind();

    Timer inboundUnbindTimer();
}
