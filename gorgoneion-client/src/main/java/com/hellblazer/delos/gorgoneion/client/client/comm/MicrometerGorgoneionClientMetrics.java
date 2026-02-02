/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.gorgoneion.client.client.comm;

import com.hellblazer.delos.protocols.MicrometerEndpointMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer implementation of GorgoneionClientMetrics.
 *
 * @author hal.hildebrand
 */
public class MicrometerGorgoneionClientMetrics extends MicrometerEndpointMetrics implements GorgoneionClientMetrics {

    private final Timer   enrollDuration;
    private final Counter inboundApplication;
    private final Counter inboundCredentials;
    private final Counter inboundCredentialValidation;
    private final Counter inboundEndorse;
    private final Counter inboundEnroll;
    private final Counter inboundInvitation;
    private final Counter inboundValidateCredentials;
    private final Counter inboundValidation;
    private final Counter outboundApplication;
    private final Counter outboundCredentials;
    private final Counter outboundEndorseNonce;
    private final Counter outboundNotarization;
    private final Counter outboundValidateCredentials;
    private final Timer   registerDuration;

    public MicrometerGorgoneionClientMetrics(MeterRegistry registry) {
        this(registry, "gorgoneion.client");
    }

    public MicrometerGorgoneionClientMetrics(MeterRegistry registry, String prefix) {
        super(registry, prefix);

        this.enrollDuration = Timer.builder(prefix + ".enroll.duration")
                                    .description("Duration of enrollment operations")
                                    .register(registry);

        this.registerDuration = Timer.builder(prefix + ".register.duration")
                                      .description("Duration of registration operations")
                                      .register(registry);

        this.inboundApplication = Counter.builder(prefix + ".inbound.application.bytes")
                                         .description("Inbound application message bytes")
                                         .baseUnit("bytes")
                                         .register(registry);

        this.inboundCredentials = Counter.builder(prefix + ".inbound.credentials.bytes")
                                         .description("Inbound credentials message bytes")
                                         .baseUnit("bytes")
                                         .register(registry);

        this.inboundCredentialValidation = Counter.builder(prefix + ".inbound.credential.validation.bytes")
                                                  .description("Inbound credential validation message bytes")
                                                  .baseUnit("bytes")
                                                  .register(registry);

        this.inboundEndorse = Counter.builder(prefix + ".inbound.endorse.bytes")
                                     .description("Inbound endorse message bytes")
                                     .baseUnit("bytes")
                                     .register(registry);

        this.inboundEnroll = Counter.builder(prefix + ".inbound.enroll.bytes")
                                    .description("Inbound enroll message bytes")
                                    .baseUnit("bytes")
                                    .register(registry);

        this.inboundInvitation = Counter.builder(prefix + ".inbound.invitation.bytes")
                                        .description("Inbound invitation message bytes")
                                        .baseUnit("bytes")
                                        .register(registry);

        this.inboundValidateCredentials = Counter.builder(prefix + ".inbound.validate.credentials.bytes")
                                                 .description("Inbound validate credentials message bytes")
                                                 .baseUnit("bytes")
                                                 .register(registry);

        this.inboundValidation = Counter.builder(prefix + ".inbound.validation.bytes")
                                        .description("Inbound validation message bytes")
                                        .baseUnit("bytes")
                                        .register(registry);

        this.outboundApplication = Counter.builder(prefix + ".outbound.application.bytes")
                                          .description("Outbound application message bytes")
                                          .baseUnit("bytes")
                                          .register(registry);

        this.outboundCredentials = Counter.builder(prefix + ".outbound.credentials.bytes")
                                          .description("Outbound credentials message bytes")
                                          .baseUnit("bytes")
                                          .register(registry);

        this.outboundEndorseNonce = Counter.builder(prefix + ".outbound.endorse.nonce.bytes")
                                           .description("Outbound endorse nonce message bytes")
                                           .baseUnit("bytes")
                                           .register(registry);

        this.outboundNotarization = Counter.builder(prefix + ".outbound.notarization.bytes")
                                           .description("Outbound notarization message bytes")
                                           .baseUnit("bytes")
                                           .register(registry);

        this.outboundValidateCredentials = Counter.builder(prefix + ".outbound.validate.credentials.bytes")
                                                  .description("Outbound validate credentials message bytes")
                                                  .baseUnit("bytes")
                                                  .register(registry);
    }

    @Override
    public void recordEnrollDuration(long nanos) {
        enrollDuration.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInboundApplication(int bytes) {
        inboundApplication.increment(bytes);
    }

    @Override
    public void recordInboundCredentials(int bytes) {
        inboundCredentials.increment(bytes);
    }

    @Override
    public void recordInboundCredentialValidation(int bytes) {
        inboundCredentialValidation.increment(bytes);
    }

    @Override
    public void recordInboundEndorse(int bytes) {
        inboundEndorse.increment(bytes);
    }

    @Override
    public void recordInboundEnroll(int bytes) {
        inboundEnroll.increment(bytes);
    }

    @Override
    public void recordInboundInvitation(int bytes) {
        inboundInvitation.increment(bytes);
    }

    @Override
    public void recordInboundValidateCredentials(int bytes) {
        inboundValidateCredentials.increment(bytes);
    }

    @Override
    public void recordInboundValidation(int bytes) {
        inboundValidation.increment(bytes);
    }

    @Override
    public void recordOutboundApplication(int bytes) {
        outboundApplication.increment(bytes);
    }

    @Override
    public void recordOutboundCredentials(int bytes) {
        outboundCredentials.increment(bytes);
    }

    @Override
    public void recordOutboundEndorseNonce(int bytes) {
        outboundEndorseNonce.increment(bytes);
    }

    @Override
    public void recordOutboundNotarization(int bytes) {
        outboundNotarization.increment(bytes);
    }

    @Override
    public void recordOutboundValidateCredentials(int bytes) {
        outboundValidateCredentials.increment(bytes);
    }

    @Override
    public void recordRegisterDuration(long nanos) {
        registerDuration.record(nanos, TimeUnit.NANOSECONDS);
    }
}
