/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.model;

import com.hellblazer.delos.archipelago.*;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.Parameters.Builder;
import com.hellblazer.delos.choam.Parameters.RuntimeParameters;
import com.hellblazer.delos.choam.proto.FoundationSeal;
import com.hellblazer.delos.context.DynamicContextImpl;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.delphinius.Oracle;
import com.hellblazer.delos.ethereal.Config;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Entropy;
import com.hellblazer.delos.utils.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 */
public class ContainmentDomainTest {
    private static final int               CARDINALITY     = 5;
    private static final Digest            GENESIS_VIEW_ID = DigestAlgorithm.DEFAULT.digest(
    "Give me food or give me slack or kill me".getBytes());
    private final        ArrayList<Domain> domains         = new ArrayList<>();
    private final        ArrayList<Router> routers         = new ArrayList<>();
    private              ExecutorService   executor;

    @AfterEach
    public void after() {
        domains.forEach(Domain::stop);
        domains.clear();
        routers.forEach(r -> r.close(Duration.ofSeconds(0)));
        routers.clear();
        if (executor != null) {
            executor.shutdown();
        }
    }

    @BeforeEach
    public void before() throws Exception {
        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();
        final var commsDirectory = Path.of("target/comms");
        commsDirectory.toFile().mkdirs();

        var ffParams = com.hellblazer.delos.fireflies.Parameters.newBuilder();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        final var prefix = UUID.randomUUID().toString();
        Path checkpointDirBase = Path.of("target", "ct-chkpoints-" + Entropy.nextBitsStreamLong());
        Utils.clean(checkpointDirBase.toFile());
        var context = new DynamicContextImpl<>(DigestAlgorithm.DEFAULT.getOrigin(), CARDINALITY, 0.2, 3);
        var params = params();
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(params.getDigestAlgorithm()), entropy);

        var identities = IntStream.range(0, CARDINALITY)
                                  .mapToObj(i -> stereotomy.newIdentifier())
                                  .collect(Collectors.toMap(controlled -> controlled.getIdentifier().getDigest(),
                                                            controlled -> controlled));

        var sealed = FoundationSeal.newBuilder().build();
        final var group = DigestAlgorithm.DEFAULT.getOrigin();
        identities.forEach((d, id) -> {
            final var member = new ControlledIdentifierMember(id);
            var localRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(30),
                                                                     executor);
            routers.add(localRouter);
            var dbUrl = String.format("jdbc:h2:mem:sql-%s-%s;DB_CLOSE_DELAY=-1", member.getId(), UUID.randomUUID());
            var pdParams = new ProcessDomain.ProcessDomainParameters(dbUrl, Duration.ofMinutes(1),
                                                                     "jdbc:h2:mem:%s-state".formatted(d),
                                                                     checkpointDirBase, Duration.ofMillis(10), 0.00125,
                                                                     Duration.ofMinutes(1), 3, Duration.ofMillis(100),
                                                                     10, 0.1);
            var domain = new ProcessContainerDomain(group, member, pdParams, params.clone(),
                                                    RuntimeParameters.newBuilder()
                                                                     .setFoundation(sealed)
                                                                     .setContext(context)
                                                                     .setCommunications(localRouter),
                                                    EndpointProvider.allocatePort(), commsDirectory, ffParams,
                                                    IdentifierSpecification.newBuilder(), null);
            domains.add(domain);
            localRouter.start();
        });

        domains.forEach(domain -> context.activate(domain.getMember()));
    }

    @Test
    public void smoke() throws Exception {
        domains.forEach(e -> Thread.ofVirtual().start(e::start));
        final var activated = Utils.waitForCondition(60_000, 1_000, () -> domains.stream().allMatch(Domain::active));
        assertTrue(activated, "Domains did not fully activate: " + (domains.stream()
                                                                           .filter(c -> !c.active())
                                                                           .map(Domain::logState)
                                                                           .toList()));
        var oracle = domains.getFirst().getDelphi();
        oracle.add(new Oracle.Namespace("test")).get();
        DomainTest.smoke(oracle);
    }

    private Builder params() {
        return Parameters.newBuilder()
                         .setGenerateGenesis(true)
                         .setGenesisViewId(GENESIS_VIEW_ID)
                         .setBootstrap(
                         Parameters.BootstrapParameters.newBuilder().setGossipDuration(Duration.ofMillis(5)).build())
                         .setGenesisViewId(DigestAlgorithm.DEFAULT.getOrigin())
                         .setGossipDuration(Duration.ofMillis(5))
                         .setProducer(Parameters.ProducerParameters.newBuilder()
                                                                   .setGossipDuration(Duration.ofMillis(5))
                                                                   .setBatchInterval(Duration.ofMillis(50))
                                                                   .setMaxBatchByteSize(1024 * 1024)
                                                                   .setMaxBatchCount(10_000)
                                                                   .setEthereal(Config.newBuilder()
                                                                                      .setNumberOfEpochs(3)
                                                                                      .setEpochLength(20))
                                                                   .build())
                         .setCheckpointBlockDelta(200);
    }
}
