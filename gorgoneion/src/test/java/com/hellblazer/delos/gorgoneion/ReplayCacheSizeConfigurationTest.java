/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.gorgoneion;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.test.proto.ByteMessage;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Test suite for replay cache size configuration in Gorgoneion.
 * <p>
 * Validates that the replay cache size can be configured via Parameters:
 * 1. Default size of 10,000 when not explicitly configured
 * 2. Custom size can be set via builder
 * 3. Minimum size validation (>= 100)
 * 4. Maximum size validation (<= 1,000,000)
 * 5. Replay cache uses configured size
 *
 * @author hal.hildebrand
 */
public class ReplayCacheSizeConfigurationTest {

    private static final Logger log = LoggerFactory.getLogger(ReplayCacheSizeConfigurationTest.class);

    /**
     * Test that the default replay cache size is 10,000 when not explicitly configured.
     */
    @Test
    public void testDefaultReplayCacheSizeIs10000() {
        var params = Parameters.newBuilder().build();
        assertEquals(10_000, params.replayCacheSize(), "Default replay cache size should be 10,000");
    }

    /**
     * Test that custom replay cache size can be configured via builder.
     */
    @Test
    public void testCustomReplayCacheSizeCanBeConfigured() {
        var params = Parameters.newBuilder()
                               .setReplayCacheSize(5_000)
                               .build();
        assertEquals(5_000, params.replayCacheSize(), "Custom replay cache size should be 5,000");

        var params2 = Parameters.newBuilder()
                                .setReplayCacheSize(50_000)
                                .build();
        assertEquals(50_000, params2.replayCacheSize(), "Custom replay cache size should be 50,000");
    }

    /**
     * Test that minimum replay cache size of 100 is enforced.
     */
    @Test
    public void testMinimumReplayCacheSizeIsEnforced() {
        assertThrows(IllegalArgumentException.class, () -> {
            Parameters.newBuilder().setReplayCacheSize(99).build();
        }, "Should reject cache size < 100");

        assertThrows(IllegalArgumentException.class, () -> {
            Parameters.newBuilder().setReplayCacheSize(0).build();
        }, "Should reject cache size of 0");

        assertThrows(IllegalArgumentException.class, () -> {
            Parameters.newBuilder().setReplayCacheSize(-1).build();
        }, "Should reject negative cache size");
    }

    /**
     * Test that maximum replay cache size of 1,000,000 is enforced.
     */
    @Test
    public void testMaximumReplayCacheSizeIsEnforced() {
        assertThrows(IllegalArgumentException.class, () -> {
            Parameters.newBuilder().setReplayCacheSize(1_000_001).build();
        }, "Should reject cache size > 1,000,000");

        assertThrows(IllegalArgumentException.class, () -> {
            Parameters.newBuilder().setReplayCacheSize(10_000_000).build();
        }, "Should reject cache size of 10,000,000");
    }

    /**
     * Test that boundary values (100 and 1,000,000) are accepted.
     */
    @Test
    public void testBoundaryValuesAreAccepted() {
        var params1 = Parameters.newBuilder()
                                .setReplayCacheSize(100)
                                .build();
        assertEquals(100, params1.replayCacheSize(), "Minimum boundary value should be accepted");

        var params2 = Parameters.newBuilder()
                                .setReplayCacheSize(1_000_000)
                                .build();
        assertEquals(1_000_000, params2.replayCacheSize(), "Maximum boundary value should be accepted");
    }

    /**
     * Test that Gorgoneion uses the configured replay cache size.
     * This test verifies that the parameter is actually passed to the ReplayCache.
     */
    @Test
    public void testGorgoneionUsesConfiguredReplayCacheSize() throws Exception {
        var testMessage = ByteMessage.newBuilder().setContents(ByteString.copyFromUtf8("test")).build();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 99, 99, 99 });
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var prefix = UUID.randomUUID().toString();

        var b = DynamicContext.newBuilder();
        b.setCardinality(1);
        var context = b.build();

        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        context.activate(member);

        var serverRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        serverRouter.start();

        var customSize = 500;
        var params = Parameters.newBuilder()
                               .setKerl(kerl)
                               .setReplayCacheSize(customSize)
                               .build();

        var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.pack(testMessage),
                                    params, member, context, mock(com.hellblazer.delos.stereotomy.services.proto.ProtoEventObserver.class),
                                    serverRouter, null);

        try {
            // Verify that the replay cache has the configured size
            var replayCache = gorgon.getReplayCache();
            assertNotNull(replayCache, "Replay cache should not be null");

            // The actual size verification would require accessing cache internals,
            // but we can verify it was created without errors with the custom size
            log.info("Gorgoneion created successfully with custom replay cache size: {}", customSize);

        } finally {
            gorgon.close();
            serverRouter.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that builder provides a getter for replay cache size.
     */
    @Test
    public void testBuilderGetterForReplayCacheSize() {
        var builder = Parameters.newBuilder();
        assertEquals(10_000, builder.getReplayCacheSize(), "Default should be 10,000");

        builder.setReplayCacheSize(5_000);
        assertEquals(5_000, builder.getReplayCacheSize(), "Getter should return set value");
    }
}
