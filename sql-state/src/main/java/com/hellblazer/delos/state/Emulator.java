/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.state;

import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.CHOAM.TransactionExecutor;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.Parameters.RuntimeParameters;
import com.hellblazer.delos.choam.Session;
import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.choam.proto.SubmitResult.Result;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.context.DynamicContextImpl;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.state.proto.Txn;
import com.hellblazer.delos.stereotomy.ControlledIdentifier;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Entropy;
import org.joou.ULong;

import java.io.IOException;
import java.nio.file.Files;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single node emulation of the SQL State Machine for testing, development, etc.
 *
 * @author hal.hildebrand
 */
public class Emulator {

    private final AtomicReference<Digest>  hash;
    private final AtomicLong               height   = new AtomicLong(0);
    private final ReentrantLock            lock     = new ReentrantLock();
    private final Mutator                  mutator;
    private final Parameters               params;
    private final SqlStateMachine          ssm;
    private final AtomicBoolean            started  = new AtomicBoolean();
    private final TransactionExecutor      txnExec;
    private final AtomicInteger            txnIndex = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler;

    public Emulator() throws IOException {
        this(DigestAlgorithm.DEFAULT.getOrigin().prefix(Entropy.nextBitsStreamLong()));
    }

    public Emulator(Digest base) throws IOException {
        this(new SqlStateMachine(DigestAlgorithm.DEFAULT.getOrigin(),
                                 String.format("jdbc:h2:mem:emulation-%s-%s", base, Entropy.nextBitsStreamLong()),
                                 new Properties(), Files.createTempDirectory("emulation").toFile()), base,
             Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory()));
    }

    public Emulator(SqlStateMachine ssm, Digest base, ScheduledExecutorService scheduler) throws IOException {
        this.ssm = ssm;
        this.scheduler = scheduler;
        txnExec = this.ssm.getExecutor();
        hash = new AtomicReference<>(base);
        SecureRandom entropy;
        try {
            entropy = SecureRandom.getInstance("SHA1PRNG");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        entropy.setSeed(new byte[] { 6, 6, 6 });
        ControlledIdentifier<SelfAddressingIdentifier> identifier;
        identifier = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT),
                                        entropy).newIdentifier();
        params = Parameters.newBuilder()
                           .setGenerateGenesis(true)
                           .build(RuntimeParameters.newBuilder()
                                                   .setMember(new ControlledIdentifierMember(identifier))
                                                   .setContext(new DynamicContextImpl<>(base, 5, 0.01, 3))
                                                   .build());
        var algorithm = base.getAlgorithm();
        Session session = new Session(params, st -> {
            lock.lock();
            try {
                Transaction txn = st.transaction();
                txnExec.execute(txnIndex.incrementAndGet(), CHOAM.hashOf(txn, algorithm), txn, st.onCompletion());
                return SubmitResult.newBuilder().setResult(Result.PUBLISHED).build();
            } finally {
                lock.unlock();
            }
        }, scheduler);
        session.setView(new HashedCertifiedBlock(DigestAlgorithm.DEFAULT, CertifiedBlock.newBuilder()
                                                                                        .setBlock(Block.newBuilder()
                                                                                                       .setHeader(
                                                                                                       Header.newBuilder()
                                                                                                             .setHeight(
                                                                                                             100)))
                                                                                        .build()));
        mutator = ssm.getMutator(session);
    }

    public Mutator getMutator() {
        if (!started.get()) {
            throw new IllegalStateException("Emulation has not been started");
        }
        return mutator;
    }

    public void newBlock() {
        lock.lock();
        try {
            var h = height.incrementAndGet();
            txnIndex.set(0);
            txnExec.beginBlock(ULong.valueOf(h), hash.updateAndGet(d -> d.prefix(h)));
        } finally {
            lock.unlock();
        }
    }

    public Connection newConnector() {
        if (!started.get()) {
            throw new IllegalStateException("Emulation has not been started");
        }
        return ssm.newConnection();
    }

    public void start(Txn... genesisTransactions) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        txnExec.genesis(hash.updateAndGet(d -> d.prefix(0)), CHOAM.toGenesisData(Arrays.asList(genesisTransactions)));
    }
}
