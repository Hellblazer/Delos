/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.google.protobuf.Message;
import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.internal.EmptyMetricRegistry;
import com.hellblazer.delos.choam.proto.SubmitResult;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.choam.support.ExponentialBackoffPolicy;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.choam.support.InvalidTransaction;
import com.hellblazer.delos.choam.support.SubmittedTransaction;
import com.hellblazer.delos.choam.support.TransactionFailed;
import com.hellblazer.delos.choam.support.BatchVerificationHelper;
import com.hellblazer.delos.cryptography.*;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @author hal.hildebrand
 */
public class Session {

    private final static Logger log = LoggerFactory.getLogger(Session.class);

    private final Limiter<Void>                                limiter;
    private final Parameters                                   params;
    private final Function<SubmittedTransaction, SubmitResult> service;
    private final Map<Digest, SubmittedTransaction>            submitted = new ConcurrentHashMap<>();
    private final AtomicReference<HashedCertifiedBlock>        view      = new AtomicReference<>();
    private final ScheduledExecutorService                     scheduler;
    private final NonceTracker                                 nonceTracker;

    public Session(Parameters params, Function<SubmittedTransaction, SubmitResult> service,
                   ScheduledExecutorService scheduler) {
        this.params = params;
        this.service = service;
        final var metrics = params.metrics();
        this.limiter = params.txnLimiterBuilder()
                             .build(params.member().getId().shortString(),
                                    metrics == null ? EmptyMetricRegistry.INSTANCE : metrics.getMetricRegistry(
                                    params.context().getId().shortString() + ".txnLimiter"));
        this.scheduler = scheduler;

        // Initialize nonce tracker with persistent storage for replay protection
        var nonceStoreFile = new File(System.getProperty("user.home"),
                                      ".delos/nonces/" + params.member().getId().shortString() + ".mv.db");
        nonceStoreFile.getParentFile().mkdirs();
        this.nonceTracker = NonceTracker.create(nonceStoreFile);
        log.debug("Initialized nonce tracker for member {} (persistence: {})",
                 params.member().getId(), FeatureFlags.NONCE_PERSISTENCE.isEnabled());
    }

    public static Transaction transactionOf(Digest source, int nonce, Message message, Signer signer) {
        ByteBuffer buff = ByteBuffer.allocate(4);
        buff.putInt(nonce);
        buff.flip();
        final var digeste = source.toDigeste();
        var sig = signer.sign(digeste.toByteString().asReadOnlyByteBuffer(), buff,
                              message.toByteString().asReadOnlyByteBuffer());
        return Transaction.newBuilder()
                          .setSource(digeste)
                          .setNonce(nonce)
                          .setContent(message.toByteString())
                          .setSignature(sig.toSig())
                          .build();
    }

    public static boolean verify(Transaction transaction, Verifier verifier) {
        ByteBuffer buff = ByteBuffer.allocate(4);
        buff.putInt(transaction.getNonce());
        buff.flip();
        return verifier.verify(JohnHancock.of(transaction.getSignature()),
                               transaction.getSource().toByteString().asReadOnlyByteBuffer(),
                               buff,
                               transaction.getContent().asReadOnlyByteBuffer());
    }

    /**
     * Batch verify multiple transactions using BLS batch verification where possible.
     * <p>
     * Each transaction is verified against its corresponding verifier. BLS-capable
     * transactions are batched for better performance; others are verified individually.
     * <p>
     * Note: Unlike block certification batch verification (same message, multiple signers),
     * transaction batch verification uses different messages per transaction. BLS supports
     * this via aggregate verification.
     *
     * @param transactions list of transactions to verify
     * @param verifiers    list of verifiers (one per transaction, same order)
     * @return array of verification results (true/false for each transaction)
     * @throws IllegalArgumentException if lists have different sizes
     */
    public static boolean[] verifyBatch(List<Transaction> transactions, List<Verifier> verifiers) {
        if (transactions.size() != verifiers.size()) {
            throw new IllegalArgumentException(
                "Transaction and verifier lists must have same size: " + transactions.size() + " vs " + verifiers.size());
        }

        if (transactions.isEmpty()) {
            return new boolean[0];
        }

        var results = new boolean[transactions.size()];
        var blsEntries = new ArrayList<TransactionEntry>();
        var nonBlsEntries = new ArrayList<TransactionEntry>();

        // Separate BLS-capable from non-BLS
        for (int i = 0; i < transactions.size(); i++) {
            var entry = new TransactionEntry(i, transactions.get(i), verifiers.get(i));
            if (BatchVerificationHelper.isBLSCapable(entry.verifier)) {
                blsEntries.add(entry);
            } else {
                nonBlsEntries.add(entry);
            }
        }

        // Batch verify BLS transactions if there are enough
        if (blsEntries.size() >= 3) {
            var publicKeys = new ArrayList<byte[]>();
            var messages = new ArrayList<byte[]>();
            var signatures = new ArrayList<byte[]>();

            for (var entry : blsEntries) {
                var key = entry.verifier.getKey();
                if (key == null) {
                    nonBlsEntries.add(entry);
                    continue;
                }
                publicKeys.add(key.getEncoded());
                messages.add(buildTransactionMessage(entry.transaction));

                var sig = JohnHancock.of(entry.transaction.getSignature());
                if (sig.getBytes().length > 0) {
                    signatures.add(sig.getBytes()[0]);
                } else {
                    nonBlsEntries.add(entry);
                }
            }

            if (publicKeys.size() == signatures.size() && !publicKeys.isEmpty()) {
                try {
                    boolean allValid = BLSProvider.getDefault().batchVerify(publicKeys, messages, signatures);
                    if (allValid) {
                        // Mark all BLS entries as valid
                        for (var entry : blsEntries) {
                            if (entry.verifier.getKey() != null) {
                                results[entry.index] = true;
                            }
                        }
                    } else {
                        // Batch failed - verify individually
                        for (var entry : blsEntries) {
                            results[entry.index] = verify(entry.transaction, entry.verifier);
                        }
                    }
                } catch (Exception e) {
                    // Batch verification error - fall back to individual
                    log.debug("Batch transaction verification failed, falling back to individual", e);
                    for (var entry : blsEntries) {
                        results[entry.index] = verify(entry.transaction, entry.verifier);
                    }
                }
            } else {
                // Size mismatch - verify individually
                nonBlsEntries.addAll(blsEntries.stream()
                                               .filter(e -> e.verifier.getKey() != null)
                                               .toList());
            }
        } else {
            // Too few for batch
            nonBlsEntries.addAll(blsEntries);
        }

        // Individually verify non-BLS transactions
        for (var entry : nonBlsEntries) {
            results[entry.index] = verify(entry.transaction, entry.verifier);
        }

        return results;
    }

    private static byte[] buildTransactionMessage(Transaction transaction) {
        ByteBuffer nonceBuf = ByteBuffer.allocate(4);
        nonceBuf.putInt(transaction.getNonce());
        var source = transaction.getSource().toByteString().toByteArray();
        var nonce = nonceBuf.array();
        var content = transaction.getContent().toByteArray();

        var message = new byte[source.length + nonce.length + content.length];
        System.arraycopy(source, 0, message, 0, source.length);
        System.arraycopy(nonce, 0, message, source.length, nonce.length);
        System.arraycopy(content, 0, message, source.length + nonce.length, content.length);
        return message;
    }

    private record TransactionEntry(int index, Transaction transaction, Verifier verifier) {}

    public static <T> CompletableFuture<T> retryNesting(Supplier<CompletableFuture<T>> supplier, int maxRetries) {
        CompletableFuture<T> cf = supplier.get();
        for (int i = 0; i < maxRetries; i++) {
            final var attempt = i;
            cf = cf.thenApply(CompletableFuture::completedFuture).exceptionally(e -> {
                if (e instanceof CompletionException ce) {
                    log.info("resubmitting after attempt: {} exception: {}", attempt + 1, ce.toString());
                } else {
                    log.info("resubmitting after attempt: {} exception: {}", attempt + 1, e.toString());
                }
                return supplier.get();
            }).thenCompose(java.util.function.Function.identity());
        }
        return cf;
    }

    /**
     * Cancel all pending transactions
     */
    public void cancelAll() {
        submitted.values().forEach(stx -> stx.onCompletion().cancel(true));
    }

    public void setView(HashedCertifiedBlock v) {
        view.set(v);
        var currentHeight = v.height();
        for (var it = submitted.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue().view().compareTo(currentHeight) < 0) {
                e.getValue().onCompletion().cancel(true);
                it.remove();
            }
        }
    }

    /**
     * Submit a transaction.
     *
     * @param transaction - the Message to submit as a transaction
     * @param timeout     - non-null timeout of the transaction
     * @return onCompletion - the future result of the submitted transaction
     * @throws InvalidTransaction - if the submitted transaction is invalid in any way
     */
    public <T> CompletableFuture<T> submit(Message transaction, Duration timeout) throws InvalidTransaction {
        final var txnView = view.get();
        if (txnView == null) {
            throw new InvalidTransaction("No view available");
        }
        final int n = nonceTracker.getAndIncrement(params.member().getId());

        final var txn = transactionOf(params.member().getId(), n, transaction, params.member());
        if (!txn.hasSource() || !txn.hasSignature()) {
            throw new InvalidTransaction();
        }

        // Verify transaction signature
        if (!verify(txn, params.member())) {
            throw new InvalidTransaction("Transaction signature validation failed");
        }

        var hash = CHOAM.hashOf(txn, params.digestAlgorithm());
        final long startNanos = params.metrics() != null ? System.nanoTime() : 0;

        var result = new CompletableFuture<T>().whenComplete((r, t) -> {
            if (params.metrics() != null) {
                if (t instanceof CancellationException) {
                    params.metrics().transactionCancelled();
                }
            }
        });
        if (timeout == null) {
            timeout = params.submitTimeout();
        }

        var stxn = new SubmittedTransaction(txnView.height(), hash, txn, result, startNanos);
        submitted.put(stxn.hash(), stxn);

        var backoff = params.submitPolicy().build();
        var target = Instant.now().plus(timeout);
        final var timeoutValue = timeout;

        // Use async retry mechanism to avoid blocking virtual threads
        submitWithRetry(stxn, 0, backoff, target, result, hash, startNanos, timeoutValue);
        return result;
    }

    private <T> void submitWithRetry(SubmittedTransaction stxn, int retryCount,
                                     ExponentialBackoffPolicy backoff,
                                     Instant target, CompletableFuture<T> result, Digest hash,
                                     long startNanos, Duration timeout) {
        if (result.isDone() || Instant.now().isAfter(target)) {
            if (!result.isDone()) {
                if (params.metrics() != null) {
                    params.metrics().transactionSubmitRetriesExhausted();
                }
                result.completeExceptionally(new TransactionFailed("Submission retries exhausted"));
            }
            return;
        }

        if (retryCount > 0) {
            if (params.metrics() != null) {
                params.metrics().transactionSubmitRetry();
            }
        }

        log.trace("Submitting: {} retry: {} on: {}", stxn.hash(), retryCount, params.member().getId());
        var submit = submit(stxn);

        switch (submit.result.getResult()) {
        case PUBLISHED -> {
            submit.limiter.get().onSuccess();
            log.trace("Transaction submitted: {} on: {}", stxn.hash(), params.member().getId());
            if (params.metrics() != null) {
                params.metrics().transactionSubmittedSuccess();
            }
            var futureTimeout = scheduler.schedule(() -> Thread.ofVirtual().start(Utils.wrapped(() -> {
                if (result.isDone()) {
                    return;
                }
                log.debug("Timeout of txn: {} on: {}", hash, params.member().getId());
                final var to = new TimeoutException("Transaction timeout");
                result.completeExceptionally(to);
                if (params.metrics() != null) {
                    params.metrics().transactionComplete(to);
                }
            }, log)), timeout.toMillis(), TimeUnit.MILLISECONDS);
            result.whenComplete((r, t) -> {
                futureTimeout.cancel(true);
                complete(hash, startNanos, t);
            });
        }
        case RATE_LIMITED -> {
            if (params.metrics() != null) {
                params.metrics().transactionSubmitRateLimited();
            }
            scheduleRetry(stxn, retryCount + 1, backoff, target, result, hash, startNanos, timeout);
        }
        case BUFFER_FULL -> {
            if (params.metrics() != null) {
                params.metrics().transactionSubmittedBufferFull();
            }
            submit.limiter.get().onDropped();
            scheduleRetry(stxn, retryCount + 1, backoff, target, result, hash, startNanos, timeout);
        }
        case INACTIVE, NO_COMMITTEE -> {
            if (params.metrics() != null) {
                params.metrics().transactionSubmittedInvalidCommittee();
            }
            submit.limiter.get().onDropped();
            scheduleRetry(stxn, retryCount + 1, backoff, target, result, hash, startNanos, timeout);
        }
        case UNAVAILABLE -> {
            if (params.metrics() != null) {
                params.metrics().transactionSubmittedUnavailable();
            }
            submit.limiter.get().onIgnore();
            scheduleRetry(stxn, retryCount + 1, backoff, target, result, hash, startNanos, timeout);
        }
        case INVALID_SUBMIT, ERROR_SUBMITTING -> {
            if (params.metrics() != null) {
                params.metrics().transactionSubmissionError();
            }
            result.completeExceptionally(new TransactionFailed("Invalid submission: " + submit.result.getErrorMsg()));
            submit.limiter.get().onIgnore();
        }
        case UNRECOGNIZED, INVALID_RESULT -> {
            if (params.metrics() != null) {
                params.metrics().transactionSubmittedInvalidResult();
            }
            var ex = new TransactionFailed("Unrecognized or invalid result: " + submit.result.getErrorMsg());
            result.completeExceptionally(ex);
            submit.limiter.get().onIgnore();
        }
        default -> {
            if (params.metrics() != null) {
                params.metrics().transactionSubmittedInvalidResult();
            }
            var ex = new TransactionFailed("Illegal result: " + submit.result.getErrorMsg());
            result.completeExceptionally(ex);
            submit.limiter.get().onIgnore();
        }
        }
    }

    private <T> void scheduleRetry(SubmittedTransaction stxn, int retryCount,
                                   ExponentialBackoffPolicy backoff,
                                   Instant target, CompletableFuture<T> result, Digest hash,
                                   long startNanos, Duration timeout) {
        final var delay = backoff.nextBackoff();
        log.debug("Failed submitting: {} retry: {} delay: {}ms on: {}", stxn.hash(),
                  retryCount, delay.toMillis(), params.member().getId());
        scheduler.schedule(() -> submitWithRetry(stxn, retryCount, backoff, target, result, hash, startNanos, timeout),
                          delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Submit a transaction.
     *
     * @param transaction - the Message to submit as a transaction
     * @param retries     - the number of retries for Cancelled transaction submissions
     * @param timeout     - non-null timeout of the transaction
     * @return onCompletion - the future result of the submitted transaction
     * @throws InvalidTransaction - if the submitted transaction is invalid in any way
     */
    public <T> CompletableFuture<T> submit(Message transaction, int retries, Duration timeout)
    throws InvalidTransaction {
        return retryNesting(() -> {
            try {
                return submit(transaction, timeout);
            } catch (InvalidTransaction e) {
                throw new IllegalStateException("Invalid txn", e);
            }
        }, retries);
    }

    public int submitted() {
        return submitted.size();
    }

    SubmittedTransaction complete(Digest hash) {
        final SubmittedTransaction stxn = submitted.remove(hash);
        if (stxn != null) {
            log.trace("Completed: {} on: {}", hash, params.member().getId());
        }
        return stxn;
    }

    private void complete(Digest hash, final long startNanos, Throwable t) {
        submitted.remove(hash);
        if (params.metrics() != null && startNanos > 0) {
            params.metrics().recordTransactionLatencyDuration(System.nanoTime() - startNanos);
            log.trace("Transaction lifecycle complete: {} error: {} on: {}", hash, t, params.member().getId());
            params.metrics().transactionComplete(t);
        }
    }

    private Submission submit(SubmittedTransaction stx) {
        var listener = limiter.acquire(null);
        if (listener.isEmpty()) {
            log.debug("Transaction submission: {} rejected on: {}", stx.hash(), params.member().getId());
            if (params.metrics() != null) {
                params.metrics().transactionSubmittedFail();
            }
            stx.onCompletion().completeExceptionally(new TransactionFailed("Transaction rate limited"));
            return new Submission(SubmitResult.newBuilder().setResult(SubmitResult.Result.RATE_LIMITED).build(),
                                  listener);
        }
        log.debug("Submitting txn: {} on: {}", stx.hash(), params.member().getId());
        return new Submission(service.apply(stx), listener);
    }

    private record Submission(SubmitResult result, Optional<Limiter.Listener> limiter) {
    }
}
