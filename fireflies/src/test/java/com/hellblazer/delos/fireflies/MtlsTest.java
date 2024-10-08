/*
 * Copyright (c) 2019, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.archipelago.*;
import com.hellblazer.delos.comm.grpc.ClientContextSupplier;
import com.hellblazer.delos.comm.grpc.ServerContextSupplier;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.cert.CertificateWithPrivateKey;
import com.hellblazer.delos.cryptography.ssl.CertificateValidator;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.View.Seed;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Utils;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 * @since 220
 */
public class MtlsTest {
    private static final int                                                         CARDINALITY;
    private static final Map<Digest, CertificateWithPrivateKey>                      certs       = new HashMap<>();
    private static final Map<Digest, String>                                         endpoints   = new HashMap<>();
    private static final boolean                                                     LARGE_TESTS = Boolean.getBoolean(
    "large_tests");
    private static       Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;

    static {
        CARDINALITY = LARGE_TESTS ? 20 : 10;
    }

    private final List<Router>    communications = new ArrayList<>();
    private       List<View>      views;
    private       ExecutorService executor;

    @BeforeAll
    public static void beforeClass() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        identities = IntStream.range(0, CARDINALITY).mapToObj(i -> {
            return stereotomy.newIdentifier();
        }).collect(Collectors.toMap(controlled -> controlled.getIdentifier().getDigest(), controlled -> controlled));
        identities.entrySet().forEach(e -> {
            certs.put(e.getKey(),
                      e.getValue().provision(Instant.now(), Duration.ofDays(1), SignatureAlgorithm.DEFAULT));
            endpoints.put(e.getKey(), EndpointProvider.allocatePort());
        });
    }

    private static String endpoint(Member m) {
        return ((Participant) m).endpoint();
    }

    @AfterEach
    public void after() {
        if (views != null) {
            views.forEach(e -> e.stop());
            views.clear();
        }
        if (communications != null) {
            communications.forEach(e -> e.close(Duration.ofSeconds(1)));
            communications.clear();
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    public void smoke() throws Exception {
        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();
        var parameters = Parameters.newBuilder().setMaximumTxfr(20).build();
        final Duration duration = Duration.ofMillis(50);
        var registry = new MetricRegistry();
        var node0Registry = new MetricRegistry();

        var members = identities.values().stream().map(identity -> new ControlledIdentifierMember(identity)).toList();
        var ctxBuilder = DynamicContext.<Participant>newBuilder().setCardinality(CARDINALITY);

        var seeds = members.stream()
                           .map(m -> new Seed(m.getIdentifier().getIdentifier(), endpoints.get(m.getId())))
                           .limit(LARGE_TESTS ? 24 : 3)
                           .toList();

        var builder = ServerConnectionCache.newBuilder().setTarget(30);
        var frist = new AtomicBoolean(true);

        var clientContextSupplier = clientContextSupplier();
        views = members.stream().map(node -> {
            DynamicContext<Participant> context = ctxBuilder.build();
            FireflyMetricsImpl metrics = new FireflyMetricsImpl(context.getId(),
                                                                frist.getAndSet(false) ? node0Registry : registry);
            EndpointProvider ep = new StandardEpProvider(endpoints.get(node.getId()), ClientAuth.REQUIRE,
                                                         CertificateValidator.NONE, MtlsTest::endpoint);
            builder.setMetrics(new ServerConnectionCacheMetricsImpl(frist.getAndSet(false) ? node0Registry : registry));
            CertificateWithPrivateKey certWithKey = certs.get(node.getId());
            Router comms = new MtlsServer(node, ep, clientContextSupplier, serverContextSupplier(certWithKey)).router(
            builder, executor);
            communications.add(comms);
            return new View(context, node, endpoints.get(node.getId()), EventValidation.NONE, Verifiers.NONE, comms,
                            parameters, DigestAlgorithm.DEFAULT, metrics);
        }).collect(Collectors.toList());

        var then = System.currentTimeMillis();
        communications.forEach(e -> e.start());

        var countdown = new AtomicReference<>(new CountDownLatch(1));

        views.get(0).start(() -> countdown.get().countDown(), duration, Collections.emptyList());

        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "KERNEL did not stabilize");

        var seedlings = views.subList(1, seeds.size());
        var kernel = seeds.subList(0, 1);

        countdown.set(new CountDownLatch(seedlings.size()));

        seedlings.forEach(view -> view.start(() -> countdown.get().countDown(), duration, kernel));

        assertTrue(countdown.get().await(30, TimeUnit.SECONDS), "Seeds did not stabilize");

        countdown.set(new CountDownLatch(views.size() - seeds.size()));
        views.forEach(view -> view.start(() -> countdown.get().countDown(), duration, seeds));

        assertTrue(Utils.waitForCondition(120_000, 1_000, () -> {
            return views.stream()
                        .map(view -> view.getContext().activeCount() != views.size() ? view : null)
                        .filter(view -> view != null)
                        .count() == 0;
        }), "view did not stabilize: " + views.stream()
                                              .map(view -> view.getContext().activeCount())
                                              .collect(Collectors.toList()));
        System.out.println(
        "View has stabilized in " + (System.currentTimeMillis() - then) + " Ms across all " + views.size()
        + " members");

        System.out.println("Checking views for consistency");
        var failed = views.stream()
                          .filter(e -> e.getContext().activeCount() != views.size())
                          .map(v -> String.format("%s : %s ", v.getNode().getId(), v.getContext().activeCount()))
                          .toList();
        assertEquals(0, failed.size(),
                     " expected: " + views.size() + " failed: " + failed.size() + " views: " + failed);

        System.out.println("Stoping views");
        views.forEach(view -> view.stop());

        if (Boolean.getBoolean("reportMetrics")) {
            ConsoleReporter.forRegistry(node0Registry)
                           .convertRatesTo(TimeUnit.SECONDS)
                           .convertDurationsTo(TimeUnit.MILLISECONDS)
                           .build()
                           .report();
        }
    }

    private Function<Member, ClientContextSupplier> clientContextSupplier() {
        return m -> {
            return new ClientContextSupplier() {
                @Override
                public SslContext forClient(ClientAuth clientAuth, String alias, CertificateValidator validator,
                                            String tlsVersion) {
                    CertificateWithPrivateKey certWithKey = certs.get(m.getId());
                    return MtlsServer.forClient(clientAuth, alias, certWithKey.getX509Certificate(),
                                                certWithKey.getPrivateKey(), validator);
                }
            };
        };
    }

    private ServerContextSupplier serverContextSupplier(CertificateWithPrivateKey certWithKey) {
        return new ServerContextSupplier() {
            @Override
            public SslContext forServer(ClientAuth clientAuth, String alias, CertificateValidator validator,
                                        Provider provider) {
                return MtlsServer.forServer(clientAuth, alias, certWithKey.getX509Certificate(),
                                            certWithKey.getPrivateKey(), validator);
            }

            @Override
            public Digest getMemberId(X509Certificate key) {
                return ((SelfAddressingIdentifier) Stereotomy.decode(key).get().identifier()).getDigest();
            }
        };
    }
}
