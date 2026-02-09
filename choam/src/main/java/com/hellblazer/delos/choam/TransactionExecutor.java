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
 * Executor interface for CHOAM transaction processing. Implementations receive transaction
 * execution callbacks at various points in the block lifecycle (begin block, execute, end block, genesis).
 *
 * @author hal.hildebrand
 */
@FunctionalInterface
public interface TransactionExecutor {
    default void beginBlock(ULong height, Digest hash) {
    }

    default void endBlock(ULong height, Digest hash) {
    }

    @SuppressWarnings("rawtypes")
    void execute(int index, Digest hash, Transaction tx, CompletableFuture onComplete);

    default void genesis(Digest hash, List<Transaction> initialization) {
    }
}
