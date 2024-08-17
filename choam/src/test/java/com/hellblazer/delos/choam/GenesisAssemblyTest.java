/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.google.protobuf.Empty;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.choam.CHOAM.BlockProducer;
import com.hellblazer.delos.choam.Parameters.ProducerParameters;
import com.hellblazer.delos.choam.Parameters.RuntimeParameters;
import com.hellblazer.delos.choam.comm.Concierge;
import com.hellblazer.delos.choam.comm.Terminal;
import com.hellblazer.delos.choam.comm.TerminalClient;
import com.hellblazer.delos.choam.comm.TerminalServer;
import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.choam.support.HashedBlock;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.cryptography.proto.PubKey;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.joou.ULong;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.hellblazer.delos.cryptography.QualifiedBase64.bs;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author hal.hildebrand
 */
public class GenesisAssemblyTest {
    static {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            LoggerFactory.getLogger(GenesisAssemblyTest.class).error("Error on thread: {}", t.getName(), e);
        });
    }

    @Test
    public void genesis() throws Exception {
        Digest viewId = DigestAlgorithm.DEFAULT.getOrigin().prefix(2);
        int cardinality = 5;
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        List<Member> members = IntStream.range(0, cardinality)
                                        .mapToObj(i -> stereotomy.newIdentifier())
                                        .map(ControlledIdentifierMember::new)
                                        .map(e -> (Member) e)
                                        .toList();
        var base = new StaticContext<>(viewId, 0.2, members, 3);
        var committee = Committee.viewFor(viewId, base);

        Parameters.Builder params = Parameters.newBuilder()
                                              .setGenesisViewId(DigestAlgorithm.DEFAULT.getLast())
                                              .setGenerateGenesis(true)
                                              .setProducer(ProducerParameters.newBuilder()
                                                                             .setGossipDuration(Duration.ofMillis(100))
                                                                             .build())
                                              .setGossipDuration(Duration.ofMillis(10));

        Map<Member, GenesisAssembly> genii = new HashMap<>();

        Map<Member, Concierge> servers = members.stream().collect(Collectors.toMap(m -> m, m -> mock(Concierge.class)));

        servers.forEach((m, s) -> {
            when(s.join(any(SignedViewMember.class), any(Digest.class))).then((Answer<Empty>) invocation -> {
                KeyPair keyPair = params.getViewSigAlgorithm().generateKeyPair();
                final PubKey consensus = bs(keyPair.getPublic());
                return Empty.getDefaultInstance();
            });
        });

        final var prefix = UUID.randomUUID().toString();
        Map<Member, Router> communications = members.stream()
                                                    .collect(Collectors.toMap(m -> m,
                                                                              m -> new LocalServer(prefix, m).router(
                                                                              ServerConnectionCache.newBuilder())));
        CountDownLatch complete = new CountDownLatch(committee.memberCount());
        var comms = members.stream()
                           .collect(Collectors.toMap(m -> m, m -> communications.get(m)
                                                                                .create(m, base.getId(), servers.get(m),
                                                                                        servers.get(m)
                                                                                               .getClass()
                                                                                               .getCanonicalName(),
                                                                                        r -> new TerminalServer(
                                                                                        communications.get(m)
                                                                                                      .getClientIdentityProvider(),
                                                                                        null, r),
                                                                                        TerminalClient.getCreate(null),
                                                                                        Terminal.getLocalLoopback(
                                                                                        (SigningMember) m,
                                                                                        servers.get(m)))));
        committee.getAllMembers().forEach(m -> {
            SigningMember sm = (SigningMember) m;
            Router router = communications.get(m);
            params.getProducer().ethereal().setSigner(sm);
            var built = params.build(
            RuntimeParameters.newBuilder().setContext(base).setMember(sm).setCommunications(router).build());
            BlockProducer reconfigure = new BlockProducer() {

                @Override
                public Block checkpoint() {
                    return null;
                }

                @Override
                public Block genesis(Map<Digest, Join> joining, Digest nextViewId, HashedBlock previous) {
                    return CHOAM.genesis(viewId, joining, previous, previous, built, previous, Collections.emptyList());
                }

                @Override
                public void onFailure() {
                    // do nothing
                }

                @Override
                public Block produce(ULong height, Digest prev, Assemble assemble, HashedBlock checkpoint) {
                    return null;
                }

                @Override
                public Block produce(ULong height, Digest prev, Executions executions, HashedBlock checkpoint) {
                    return null;
                }

                @Override
                public void publish(Digest hash, CertifiedBlock cb, boolean beacon) {
                    complete.countDown();
                }

                @Override
                public Block reconfigure(Map<Digest, Join> joining, Digest nextViewId, HashedBlock previous,
                                         HashedBlock checkpoint) {
                    return null;
                }
            };
            var pending = new CHOAM.PendingViews();
            pending.add(base.getId(), base);
            var view = new GenesisContext(committee, () -> pending, built, sm, reconfigure);

            KeyPair keyPair = params.getViewSigAlgorithm().generateKeyPair();
            final PubKey consensus = bs(keyPair.getPublic());
            var vm = ViewMember.newBuilder()
                               .setId(m.getId().toDigeste())
                               .setView(params.getGenesisViewId().toDigeste())
                               .setConsensusKey(consensus)
                               .setSignature(((Signer) m).sign(consensus.toByteString()).toSig())
                               .build();
            var svm = SignedViewMember.newBuilder()
                                      .setVm(vm)
                                      .setSignature(((SigningMember) m).sign(vm.toByteString()).toSig())
                                      .build();
            genii.put(m, new GenesisAssembly(view, comms.get(m), svm, m.getId().toString(),
                                             Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory())));
        });

        try {
            communications.values().forEach(Router::start);
            genii.values().forEach(GenesisAssembly::start);
            complete.await(15, TimeUnit.SECONDS);
        } finally {
            communications.values().forEach(r -> r.close(Duration.ofSeconds(0)));
            genii.values().forEach(GenesisAssembly::stop);
        }
    }
}
