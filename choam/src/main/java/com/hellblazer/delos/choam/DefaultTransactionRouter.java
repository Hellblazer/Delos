/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.choam.comm.Submitter;
import com.hellblazer.delos.choam.comm.TxnSubmission;
import com.hellblazer.delos.choam.proto.SubmitResult;
import com.hellblazer.delos.choam.proto.SubmitResult.Result;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.choam.support.SubmittedTransaction;
import com.hellblazer.delos.cryptography.Digest;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.hellblazer.delos.choam.CHOAM.hashOf;

/**
 * Default implementation of TransactionRouter that handles client validation,
 * rate limiting, and routing to committee members.
 *
 * @author hal.hildebrand
 */
public class DefaultTransactionRouter implements TransactionRouter {
    private static final Logger log = LoggerFactory.getLogger(DefaultTransactionRouter.class);

    private final Parameters params;
    private final ConsensusCoordinator consensusCoordinator;
    private final CommonCommunications<TxnSubmission, Submitter> submissionComm;
    private final Map<Digest, ClientRateLimiter> clientRateLimiters = new ConcurrentHashMap<>();

    public DefaultTransactionRouter(
        Parameters params,
        ConsensusCoordinator consensusCoordinator,
        CommonCommunications<TxnSubmission, Submitter> submissionComm
    ) {
        this.params = params;
        this.consensusCoordinator = consensusCoordinator;
        this.submissionComm = submissionComm;
    }

    @Override
    public SubmitResult submitFromClient(Transaction request, Digest from) {
        if (from == null) {
            return SubmitResult.getDefaultInstance();
        }
        if (params.context().getMember(from) == null) {
            log.debug("Invalid transaction submission from non member: {} on: {}", from, params.member().getId());
            return SubmitResult.newBuilder().setResult(Result.INVALID_SUBMIT).build();
        }

        // Check per-client rate limit
        var rateLimiter = clientRateLimiters.computeIfAbsent(from,
            k -> new ClientRateLimiter(10, Duration.ofSeconds(1)));
        if (!rateLimiter.tryAcquire()) {
            log.debug("Transaction submission rate limited for client: {} on: {}", from, params.member().getId());
            return SubmitResult.newBuilder().setResult(Result.RATE_LIMITED).build();
        }

        final var committee = consensusCoordinator.getCurrentCommittee();
        if (committee == null) {
            log.debug("No committee to submit txn from: {} on: {}", from, params.member().getId());
            return SubmitResult.newBuilder().setResult(Result.NO_COMMITTEE).build();
        }
        return committee.submit(request);
    }

    @Override
    public SubmitResult routeToCommittee(Transaction transaction) {
        final var committee = consensusCoordinator.getCurrentCommittee();
        if (committee == null) {
            return SubmitResult.newBuilder().setResult(Result.NO_COMMITTEE).build();
        }
        try {
            return committee.submitTxn(transaction);
        } catch (StatusRuntimeException e) {
            return SubmitResult.newBuilder()
                               .setResult(Result.ERROR_SUBMITTING)
                               .setErrorMsg(e.getStatus().toString())
                               .build();
        }
    }

    @Override
    public SubmitResult service(SubmittedTransaction stx) {
        final var committee = consensusCoordinator.getCurrentCommittee();
        if (committee == null) {
            return SubmitResult.newBuilder().setResult(Result.NO_COMMITTEE).build();
        }
        try {
            return committee.submitTxn(stx.transaction());
        } catch (StatusRuntimeException e) {
            return SubmitResult.newBuilder()
                               .setResult(Result.ERROR_SUBMITTING)
                               .setErrorMsg(e.getStatus().toString())
                               .build();
        }
    }
}
