/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import com.codahale.metrics.Timer;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.cryptography.Digest;
import org.joou.ULong;

import java.util.concurrent.CompletableFuture;

/**
 * @author hal.hildebrand
 */
@SuppressWarnings("rawtypes")
public record SubmittedTransaction(ULong view, Digest hash, Transaction transaction, CompletableFuture onCompletion,
                                   Timer.Context timer) {
}
