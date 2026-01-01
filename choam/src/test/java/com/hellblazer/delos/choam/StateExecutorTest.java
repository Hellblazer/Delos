/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.CHOAM.TransactionExecutor;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.joou.ULong;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the StateExecutor abstraction
 *
 * @author hal.hildebrand
 */
public class StateExecutorTest {

    @Test
    public void testDefaultStateExecutor() {
        var beginBlockCalls = new AtomicInteger(0);
        var endBlockCalls = new AtomicInteger(0);
        var executeCalls = new AtomicInteger(0);
        var genesisCalls = new AtomicInteger(0);

        TransactionExecutor processor = new TransactionExecutor() {
            @Override
            public void beginBlock(ULong height, Digest hash) {
                beginBlockCalls.incrementAndGet();
            }

            @Override
            public void endBlock(ULong height, Digest hash) {
                endBlockCalls.incrementAndGet();
            }

            @Override
            @SuppressWarnings("rawtypes")
            public void execute(int index, Digest hash, Transaction tx, CompletableFuture onComplete) {
                executeCalls.incrementAndGet();
                if (onComplete != null) {
                    onComplete.complete(null);
                }
            }

            @Override
            public void genesis(Digest hash, List<Transaction> initialization) {
                genesisCalls.incrementAndGet();
            }
        };

        StateExecutor executor = new DefaultStateExecutor(processor);

        var height = ULong.valueOf(1);
        var hash = DigestAlgorithm.DEFAULT.getOrigin();
        var tx = Transaction.getDefaultInstance();

        executor.beginBlock(height, hash);
        assertEquals(1, beginBlockCalls.get(), "beginBlock should be called once");

        executor.execute(0, hash, tx, null);
        assertEquals(1, executeCalls.get(), "execute should be called once");

        executor.endBlock(height, hash);
        assertEquals(1, endBlockCalls.get(), "endBlock should be called once");

        executor.genesis(hash, List.of(tx));
        assertEquals(1, genesisCalls.get(), "genesis should be called once");
    }

    @Test
    public void testStateExecutorBackwardCompatibility() {
        var executedTransactions = new ArrayList<Digest>();

        TransactionExecutor processor = new TransactionExecutor() {
            @Override
            @SuppressWarnings("rawtypes")
            public void execute(int index, Digest hash, Transaction tx, CompletableFuture onComplete) {
                executedTransactions.add(hash);
                if (onComplete != null) {
                    onComplete.complete(null);
                }
            }
        };

        StateExecutor executor = new DefaultStateExecutor(processor);

        var hash1 = DigestAlgorithm.DEFAULT.digest("tx1".getBytes());
        var hash2 = DigestAlgorithm.DEFAULT.digest("tx2".getBytes());
        var tx = Transaction.getDefaultInstance();

        executor.execute(0, hash1, tx, null);
        executor.execute(1, hash2, tx, null);

        assertEquals(2, executedTransactions.size(), "Should execute 2 transactions");
        assertEquals(hash1, executedTransactions.get(0), "First transaction hash should match");
        assertEquals(hash2, executedTransactions.get(1), "Second transaction hash should match");
    }

    @Test
    public void testStateExecutorAsyncCompletion() throws Exception {
        var future = new CompletableFuture<String>();

        TransactionExecutor processor = new TransactionExecutor() {
            @Override
            @SuppressWarnings("rawtypes")
            public void execute(int index, Digest hash, Transaction tx, CompletableFuture onComplete) {
                if (onComplete != null) {
                    onComplete.complete("done");
                }
            }
        };

        StateExecutor executor = new DefaultStateExecutor(processor);
        executor.execute(0, DigestAlgorithm.DEFAULT.getOrigin(), Transaction.getDefaultInstance(), future);

        assertEquals("done", future.get(), "Future should complete with expected value");
    }
}
