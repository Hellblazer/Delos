/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.model;

import com.hellblazer.delos.archipelago.EndpointProvider;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.Parameters.RuntimeParameters;
import com.hellblazer.delos.choam.proto.FoundationSeal;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.context.DynamicContextImpl;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.ethereal.Config;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.model.ProcessDomain.ProcessDomainParameters;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Fluent builder for ProcessDomain construction with sensible defaults
 *
 * @author hal.hildebrand
 */
public class ProcessDomainBuilder {

    private Digest                                    group;
    private ControlledIdentifierMember                member;
    private ProcessDomainParameters                   processDomainParameters;
    private Parameters.Builder                        choamParametersBuilder;
    private RuntimeParameters.Builder                 runtimeParametersBuilder;
    private String                                    endpoint;
    private com.hellblazer.delos.fireflies.Parameters.Builder fireflyParametersBuilder;
    private StereotomyMetrics                         stereotomyMetrics;
    private SecureRandom                              entropy;
    private DynamicContext<Participant>               context;
    private Router                                    communications;

    private String   dbURL;
    private Path     checkpointBaseDir;
    private Duration dhtOperationsTimeout;
    private String   dhtDbUrl;
    private Duration dhtOpsFrequency;
    private double   dhtFpr;
    private Duration dhtEventValidTO;
    private int      dhtBias;
    private Duration kerlSpaceDuration;
    private int      jdbcMaxConnections;
    private double   dhtPbyz;

    public ProcessDomainBuilder() {
        setDefaults();
    }

    public static ProcessDomainBuilder newBuilder() {
        return new ProcessDomainBuilder();
    }

    public static ProcessDomain defaultProcess(Digest group, ControlledIdentifierMember member,
                                                Path checkpointBaseDir, SecureRandom entropy) {
        return newBuilder().setGroup(group)
                           .setMember(member)
                           .setCheckpointBaseDir(checkpointBaseDir)
                           .setEntropy(entropy)
                           .build();
    }

    private void setDefaults() {
        dhtOperationsTimeout = Duration.ofMinutes(1);
        dhtOpsFrequency = Duration.ofMillis(10);
        dhtFpr = 0.00125;
        dhtEventValidTO = Duration.ofMinutes(1);
        dhtBias = 3;
        kerlSpaceDuration = Duration.ofMillis(100);
        jdbcMaxConnections = 10;
        dhtPbyz = 0.1;
        fireflyParametersBuilder = com.hellblazer.delos.fireflies.Parameters.newBuilder();
        stereotomyMetrics = null;
    }

    public ProcessDomainBuilder setGroup(Digest group) {
        this.group = Objects.requireNonNull(group, "group must not be null");
        return this;
    }

    public ProcessDomainBuilder setMember(ControlledIdentifierMember member) {
        this.member = Objects.requireNonNull(member, "member must not be null");
        return this;
    }

    public ProcessDomainBuilder setProcessDomainParameters(ProcessDomainParameters processDomainParameters) {
        this.processDomainParameters = Objects.requireNonNull(processDomainParameters,
                                                               "processDomainParameters must not be null");
        return this;
    }

    public ProcessDomainBuilder setChoamParametersBuilder(Parameters.Builder choamParametersBuilder) {
        this.choamParametersBuilder = choamParametersBuilder;
        return this;
    }

    public ProcessDomainBuilder setRuntimeParametersBuilder(RuntimeParameters.Builder runtimeParametersBuilder) {
        this.runtimeParametersBuilder = runtimeParametersBuilder;
        return this;
    }

    public ProcessDomainBuilder setEndpoint(String endpoint) {
        this.endpoint = endpoint;
        return this;
    }

    public ProcessDomainBuilder setFireflyParametersBuilder(
    com.hellblazer.delos.fireflies.Parameters.Builder fireflyParametersBuilder) {
        this.fireflyParametersBuilder = fireflyParametersBuilder;
        return this;
    }

    public ProcessDomainBuilder setStereotomyMetrics(StereotomyMetrics stereotomyMetrics) {
        this.stereotomyMetrics = stereotomyMetrics;
        return this;
    }

    public ProcessDomainBuilder setEntropy(SecureRandom entropy) {
        this.entropy = entropy;
        return this;
    }

    public ProcessDomainBuilder setContext(DynamicContext<Participant> context) {
        this.context = context;
        return this;
    }

    public ProcessDomainBuilder setDbURL(String dbURL) {
        this.dbURL = dbURL;
        return this;
    }

    public ProcessDomainBuilder setCheckpointBaseDir(Path checkpointBaseDir) {
        this.checkpointBaseDir = Objects.requireNonNull(checkpointBaseDir, "checkpointBaseDir must not be null");
        return this;
    }

    public ProcessDomainBuilder setDhtOperationsTimeout(Duration dhtOperationsTimeout) {
        this.dhtOperationsTimeout = dhtOperationsTimeout;
        return this;
    }

    public ProcessDomainBuilder setDhtDbUrl(String dhtDbUrl) {
        this.dhtDbUrl = dhtDbUrl;
        return this;
    }

    public ProcessDomainBuilder setDhtOpsFrequency(Duration dhtOpsFrequency) {
        this.dhtOpsFrequency = dhtOpsFrequency;
        return this;
    }

    public ProcessDomainBuilder setDhtFpr(double dhtFpr) {
        this.dhtFpr = dhtFpr;
        return this;
    }

    public ProcessDomainBuilder setDhtEventValidTO(Duration dhtEventValidTO) {
        this.dhtEventValidTO = dhtEventValidTO;
        return this;
    }

    public ProcessDomainBuilder setDhtBias(int dhtBias) {
        this.dhtBias = dhtBias;
        return this;
    }

    public ProcessDomainBuilder setKerlSpaceDuration(Duration kerlSpaceDuration) {
        this.kerlSpaceDuration = kerlSpaceDuration;
        return this;
    }

    public ProcessDomainBuilder setJdbcMaxConnections(int jdbcMaxConnections) {
        this.jdbcMaxConnections = jdbcMaxConnections;
        return this;
    }

    public ProcessDomainBuilder setDhtPbyz(double dhtPbyz) {
        this.dhtPbyz = dhtPbyz;
        return this;
    }

    public ProcessDomainBuilder setCommunications(Router communications) {
        this.communications = communications;
        return this;
    }

    public ProcessDomain build() {
        validate();
        buildDefaults();

        var pdParams = processDomainParameters != null ? processDomainParameters
                                                        : new ProcessDomainParameters(dbURL, dhtOperationsTimeout,
                                                                                      dhtDbUrl, checkpointBaseDir,
                                                                                      dhtOpsFrequency, dhtFpr,
                                                                                      dhtEventValidTO, dhtBias,
                                                                                      kerlSpaceDuration,
                                                                                      jdbcMaxConnections, dhtPbyz);

        var choamParams = choamParametersBuilder != null ? choamParametersBuilder : defaultChoamParameters();

        var runtimeParams = runtimeParametersBuilder != null ? runtimeParametersBuilder
                                                              : defaultRuntimeParameters();

        var endpointValue = endpoint != null ? endpoint : EndpointProvider.allocatePort();

        return new ProcessDomain(group, member, pdParams, choamParams, runtimeParams, endpointValue,
                                 fireflyParametersBuilder, stereotomyMetrics);
    }

    private void validate() {
        Objects.requireNonNull(group, "group is required");
        Objects.requireNonNull(member, "member is required");
        Objects.requireNonNull(checkpointBaseDir, "checkpointBaseDir is required");
        Objects.requireNonNull(communications, "communications router is required");
    }

    private void buildDefaults() {
        if (dbURL == null) {
            dbURL = "jdbc:h2:mem:sql-%s-%s;DB_CLOSE_DELAY=-1".formatted(member.getId(), UUID.randomUUID());
        }

        if (dhtDbUrl == null) {
            dhtDbUrl = "jdbc:h2:mem:%s-state;DB_CLOSE_DELAY=-1".formatted(member.getIdentifier().getDigest());
        }

        if (context == null && entropy != null) {
            var cardinality = 5;
            var bias = dhtBias;
            var pbyz = dhtPbyz;
            context = new DynamicContextImpl<>(group, cardinality, pbyz, bias);
        }
    }

    private Parameters.Builder defaultChoamParameters() {
        var genesisViewId = group;
        return Parameters.newBuilder()
                         .setGenerateGenesis(true)
                         .setGenesisViewId(genesisViewId)
                         .setBootstrap(Parameters.BootstrapParameters.newBuilder()
                                                                      .setGossipDuration(Duration.ofMillis(5))
                                                                      .build())
                         .setGossipDuration(Duration.ofMillis(5))
                         .setProducer(Parameters.ProducerParameters.newBuilder()
                                                                    .setGossipDuration(Duration.ofMillis(5))
                                                                    .setBatchInterval(Duration.ofMillis(50))
                                                                    .setMaxBatchByteSize(1024 * 1024)
                                                                    .setMaxBatchCount(10_000)
                                                                    .setEthereal(
                                                                    Config.newBuilder().setNumberOfEpochs(12).setEpochLength(
                                                                    33))
                                                                    .build())
                         .setCheckpointBlockDelta(200);
    }

    private RuntimeParameters.Builder defaultRuntimeParameters() {
        var sealed = FoundationSeal.newBuilder().build();
        var builder = RuntimeParameters.newBuilder().setFoundation(sealed);

        if (context != null) {
            builder.setContext(context);
        }

        if (communications != null) {
            builder.setCommunications(communications);
        }

        return builder;
    }
}
