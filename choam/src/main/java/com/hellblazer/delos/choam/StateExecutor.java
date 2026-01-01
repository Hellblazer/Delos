/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.cryptography.Digest;
import org.joou.ULong;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Abstraction for executing state transitions in CHOAM.
 * Allows different execution strategies (synchronous, asynchronous, batched).
 *
 * @author hal.hildebrand
 */
public interface StateExecutor {

    /**
     * Begin processing a new block.
     *
     * @param height block height
     * @param hash   block hash
     */
    default void beginBlock(ULong height, Digest hash) {
    }

    /**
     * End processing a block.
     *
     * @param height block height
     * @param hash   block hash
     */
    default void endBlock(ULong height, Digest hash) {
    }

    /**
     * Execute a single transaction.
     *
     * @param index        transaction index within the block
     * @param hash         transaction hash
     * @param transaction  the transaction to execute
     * @param onCompletion future to complete when execution finishes
     */
    @SuppressWarnings("rawtypes")
    void execute(int index, Digest hash, Transaction transaction, CompletableFuture onCompletion);

    /**
     * Execute genesis initialization transactions.
     *
     * @param hash           genesis block hash
     * @param initialization list of initialization transactions
     */
    default void genesis(Digest hash, List<Transaction> initialization) {
    }
}
