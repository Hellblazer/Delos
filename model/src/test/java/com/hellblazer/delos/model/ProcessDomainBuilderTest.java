/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.model;

import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.proto.FoundationSeal;
import com.hellblazer.delos.context.DynamicContextImpl;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.ethereal.Config;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.model.ProcessDomain.ProcessDomainParameters;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Entropy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ProcessDomainBuilder
 *
 * @author hal.hildebrand
 */
public class ProcessDomainBuilderTest {

    @TempDir
    Path testDir;

    private Router router;

    @AfterEach
    public void cleanup() {
        if (router != null) {
            router.close(Duration.ofSeconds(1));
        }
    }

    private Router createRouter(ControlledIdentifierMember member) {
        var prefix = UUID.randomUUID().toString();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var localRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(30),
                                                                  executor);
        localRouter.start();
        return localRouter;
    }

    @Test
    public void testMinimalBuilder() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var group = DigestAlgorithm.DEFAULT.getOrigin();
        router = createRouter(member);

        var builder = ProcessDomainBuilder.newBuilder()
                                          .setGroup(group)
                                          .setMember(member)
                                          .setCheckpointBaseDir(testDir)
                                          .setEntropy(entropy)
                                          .setCommunications(router);

        var domain = builder.build();
        assertNotNull(domain);
        assertEquals(member, domain.getMember());
    }

    @Test
    public void testBuilderWithCustomParameters() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var group = DigestAlgorithm.DEFAULT.getOrigin();
        router = createRouter(member);

        var customTimeout = Duration.ofSeconds(10);
        var customBias = 3;
        var customFpr = 0.01;

        var builder = ProcessDomainBuilder.newBuilder()
                                          .setGroup(group)
                                          .setMember(member)
                                          .setCheckpointBaseDir(testDir)
                                          .setEntropy(entropy)
                                          .setCommunications(router)
                                          .setDhtOperationsTimeout(customTimeout)
                                          .setDhtBias(customBias)
                                          .setDhtFpr(customFpr);

        var domain = builder.build();
        assertNotNull(domain);
    }

    @Test
    public void testBuilderFluentChaining() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var group = DigestAlgorithm.DEFAULT.getOrigin();
        router = createRouter(member);

        var domain = ProcessDomainBuilder.newBuilder()
                                         .setGroup(group)
                                         .setMember(member)
                                         .setCheckpointBaseDir(testDir)
                                         .setEntropy(entropy)
                                         .setCommunications(router)
                                         .setDhtBias(2)
                                         .setDhtPbyz(0.1)
                                         .setDhtFpr(0.001)
                                         .setJdbcMaxConnections(20)
                                         .build();

        assertNotNull(domain);
        assertEquals(member, domain.getMember());
    }

    @Test
    public void testBuilderRequiredParameterValidation() {
        var builder = ProcessDomainBuilder.newBuilder();

        assertThrows(NullPointerException.class, () -> builder.build(),
                     "Should require group");
    }

    @Test
    public void testBuilderNullCheckOnGroup() {
        assertThrows(NullPointerException.class,
                     () -> ProcessDomainBuilder.newBuilder().setGroup(null));
    }

    @Test
    public void testBuilderNullCheckOnMember() {
        assertThrows(NullPointerException.class,
                     () -> ProcessDomainBuilder.newBuilder().setMember(null));
    }

    @Test
    public void testBuilderNullCheckOnCheckpointDir() {
        assertThrows(NullPointerException.class,
                     () -> ProcessDomainBuilder.newBuilder().setCheckpointBaseDir(null));
    }

    @Test
    public void testBuilderWithDefaultEndpoint() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var group = DigestAlgorithm.DEFAULT.getOrigin();
        router = createRouter(member);

        var domain = ProcessDomainBuilder.newBuilder()
                                         .setGroup(group)
                                         .setMember(member)
                                         .setCheckpointBaseDir(testDir)
                                         .setEntropy(entropy)
                                         .setCommunications(router)
                                         .build();

        assertNotNull(domain);
    }

    @Test
    public void testBuilderWithCustomEndpoint() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var group = DigestAlgorithm.DEFAULT.getOrigin();
        router = createRouter(member);

        var customEndpoint = "192.168.1.100:8080";

        var domain = ProcessDomainBuilder.newBuilder()
                                         .setGroup(group)
                                         .setMember(member)
                                         .setCheckpointBaseDir(testDir)
                                         .setEntropy(entropy)
                                         .setCommunications(router)
                                         .setEndpoint(customEndpoint)
                                         .build();

        assertNotNull(domain);
    }

    @Test
    public void testStaticFactoryMethod() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var group = DigestAlgorithm.DEFAULT.getOrigin();
        router = createRouter(member);

        var domain = ProcessDomainBuilder.newBuilder()
                                         .setGroup(group)
                                         .setMember(member)
                                         .setCheckpointBaseDir(testDir)
                                         .setEntropy(entropy)
                                         .setCommunications(router)
                                         .build();

        assertNotNull(domain);
        assertEquals(member, domain.getMember());
    }
}
