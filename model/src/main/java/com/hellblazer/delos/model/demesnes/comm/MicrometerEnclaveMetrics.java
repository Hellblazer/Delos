/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model.demesnes.comm;

import com.hellblazer.delos.protocols.MicrometerEndpointMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer implementation of EnclaveMetrics.
 *
 * @author hal.hildebrand
 */
public class MicrometerEnclaveMetrics extends MicrometerEndpointMetrics implements EnclaveMetrics {

    private final Timer   deregisterDuration;
    private final Timer   registerDuration;
    private final Counter outboundDeregister;
    private final Counter outboundRegister;
    private final Counter outboundViewChange;

    public MicrometerEnclaveMetrics(MeterRegistry registry) {
        this(registry, "enclave");
    }

    public MicrometerEnclaveMetrics(MeterRegistry registry, String prefix) {
        super(registry, prefix);
        this.deregisterDuration = Timer.builder(prefix + ".deregister.duration")
                                       .description("Time to process deregister operations")
                                       .register(registry);
        this.registerDuration = Timer.builder(prefix + ".register.duration")
                                     .description("Time to process register operations")
                                     .register(registry);
        this.outboundDeregister = Counter.builder(prefix + ".outbound.deregister.bytes")
                                         .description("Outbound deregister message size in bytes")
                                         .baseUnit("bytes")
                                         .register(registry);
        this.outboundRegister = Counter.builder(prefix + ".outbound.register.bytes")
                                       .description("Outbound register message size in bytes")
                                       .baseUnit("bytes")
                                       .register(registry);
        this.outboundViewChange = Counter.builder(prefix + ".outbound.view_change.bytes")
                                         .description("Outbound view change message size in bytes")
                                         .baseUnit("bytes")
                                         .register(registry);
    }

    @Override
    public void recordDeregisterDuration(long nanos) {
        deregisterDuration.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordRegisterDuration(long nanos) {
        registerDuration.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordOutboundDeregister(int bytes) {
        outboundDeregister.increment(bytes);
    }

    @Override
    public void recordOutboundRegister(int bytes) {
        outboundRegister.increment(bytes);
    }

    @Override
    public void recordOutboundViewChange(int bytes) {
        outboundViewChange.increment(bytes);
    }
}
