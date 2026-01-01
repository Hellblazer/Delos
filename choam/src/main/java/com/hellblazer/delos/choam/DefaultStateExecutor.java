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
import org.joou.ULong;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Default synchronous state executor that delegates to a TransactionExecutor.
 * This is the backward-compatible implementation that maintains existing CHOAM behavior.
 *
 * @author hal.hildebrand
 */
public class DefaultStateExecutor implements StateExecutor {

    private final TransactionExecutor delegate;

    public DefaultStateExecutor(TransactionExecutor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void beginBlock(ULong height, Digest hash) {
        delegate.beginBlock(height, hash);
    }

    @Override
    public void endBlock(ULong height, Digest hash) {
        delegate.endBlock(height, hash);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void execute(int index, Digest hash, Transaction transaction, CompletableFuture onCompletion) {
        delegate.execute(index, hash, transaction, onCompletion);
    }

    @Override
    public void genesis(Digest hash, List<Transaction> initialization) {
        delegate.genesis(hash, initialization);
    }
}
