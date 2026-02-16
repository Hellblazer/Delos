/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.archipelago;

import com.hellblazer.delos.comm.grpc.ClientContextSupplier;
import com.hellblazer.delos.comm.grpc.ServerContextSupplier;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.ssl.CertificateValidator;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MtlsServer certificate cache eviction policy.
 *
 * @author hal.hildebrand
 */
public class MtlsServerCacheTest {

    private static final DigestAlgorithm DIGEST_ALGO = DigestAlgorithm.DEFAULT;

    /**
     * Test that cache has stats recording enabled
     */
    @Test
    public void testCacheStatsRecorded() throws Exception {
        var server = createTestServer();

        var member1 = createTestMember();
        var member2 = createTestMember();

        var cert1 = member1.getCertificateWithPrivateKey(Instant.now(), Duration.ofDays(1),
                                                         SignatureAlgorithm.ED_25519).getX509Certificate();
        var cert2 = member2.getCertificateWithPrivateKey(Instant.now(), Duration.ofDays(1),
                                                         SignatureAlgorithm.ED_25519).getX509Certificate();

        // First access - cache miss, should load
        var digest1 = getCachedDigest(server, cert1);
        assertNotNull(digest1);

        // Second access to same cert - cache hit
        var digest1b = getCachedDigest(server, cert1);
        assertEquals(digest1, digest1b);

        // Access different cert - cache miss
        var digest2 = getCachedDigest(server, cert2);
        assertNotNull(digest2);
        assertNotEquals(digest1, digest2);

        var stats = server.cachedMembershipStats();
        assertEquals(2, stats.loadCount(), "Should have 2 loads");
        assertEquals(1, stats.hitCount(), "Should have 1 hit");
        assertEquals(3, stats.requestCount(), "Should have 3 total requests");
        assertTrue(stats.hitRate() > 0, "Hit rate should be > 0");
    }

    /**
     * Test that cache evicts entries when max size (1000) is exceeded
     */
    @Test
    public void testCacheMaxSizeEviction() throws Exception {
        var server = createTestServer();

        // Create 1001 unique certificates
        List<X509Certificate> certs = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            var member = createTestMember();
            var cert = member.getCertificateWithPrivateKey(Instant.now(), Duration.ofDays(1),
                                                           SignatureAlgorithm.ED_25519).getX509Certificate();
            certs.add(cert);
        }

        // Load all certificates into cache
        for (var cert : certs) {
            getCachedDigest(server, cert);
        }

        // Cache should have evicted at least 1 entry (LRU eviction)
        var stats = server.cachedMembershipStats();
        assertTrue(stats.evictionCount() > 0,
                   "Should have evicted entries when exceeding max size of 1000");
        // Cache size should be at most 1000
        assertTrue(stats.loadCount() - stats.evictionCount() <= 1000,
                   "Cache size should not exceed maximum of 1000");
    }

    // ===== Helper Methods =====

    private MtlsServerWithStats createTestServer() {
        var from = createTestMember();

        EndpointProvider epProvider = new EndpointProvider() {
            @Override
            public java.net.SocketAddress getBindAddress() {
                return new java.net.InetSocketAddress("localhost", 0);
            }

            @Override
            public String getAlias() {
                return "test-alias";
            }

            @Override
            public CertificateValidator getValidator() {
                return new CertificateValidator() {
                    @Override
                    public void validateClient(X509Certificate[] chain) {
                        // Accept all for testing
                    }

                    @Override
                    public void validateServer(X509Certificate[] chain) {
                        // Accept all for testing
                    }
                };
            }

            @Override
            public ClientAuth getClientAuth() {
                return ClientAuth.REQUIRE;
            }

            @Override
            public java.net.SocketAddress addressFor(Member to) {
                return new java.net.InetSocketAddress("localhost", 12345);
            }
        };

        ServerContextSupplier supplier = new ServerContextSupplier() {
            @Override
            public Digest getMemberId(X509Certificate key) {
                return DIGEST_ALGO.digest(key.getPublicKey().getEncoded());
            }

            @Override
            public SslContext forServer(ClientAuth clientAuth, String alias,
                                       CertificateValidator validator,
                                       java.security.Provider provider) {
                // Not used in this test - cache is tested directly
                return null;
            }
        };

        return new MtlsServerWithStats(from, epProvider, m -> null, supplier);
    }

    private ControlledIdentifierMember createTestMember() {
        try {
            var entropy = SecureRandom.getInstance("SHA1PRNG");
            entropy.setSeed(new byte[] { (byte) entropy.nextLong() });
            var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DIGEST_ALGO), entropy);
            var identifier = stereotomy.newIdentifier();
            return new ControlledIdentifierMember(identifier);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create test member", e);
        }
    }

    private Digest getCachedDigest(MtlsServerWithStats server, X509Certificate cert) {
        return server.getCachedDigestForTest(cert);
    }

    /**
     * Test subclass that exposes cache stats for testing
     */
    private static class MtlsServerWithStats extends MtlsServer {
        public MtlsServerWithStats(Member from, EndpointProvider epProvider,
                                   java.util.function.Function<Member, ClientContextSupplier> contextSupplier,
                                   ServerContextSupplier supplier) {
            super(from, epProvider, contextSupplier, supplier);
        }

        public com.google.common.cache.CacheStats cachedMembershipStats() {
            // Use reflection to access private cachedMembership field
            try {
                var field = MtlsServer.class.getDeclaredField("cachedMembership");
                field.setAccessible(true);
                var cache = (com.google.common.cache.LoadingCache<?, ?>) field.get(this);
                return cache.stats();
            } catch (Exception e) {
                throw new RuntimeException("Failed to access cache stats", e);
            }
        }

        public Digest getCachedDigestForTest(X509Certificate cert) {
            try {
                var field = MtlsServer.class.getDeclaredField("cachedMembership");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                var cache = (com.google.common.cache.LoadingCache<X509Certificate, Digest>) field.get(this);
                return cache.get(cert);
            } catch (Exception e) {
                throw new RuntimeException("Failed to access cache", e);
            }
        }
    }
}
