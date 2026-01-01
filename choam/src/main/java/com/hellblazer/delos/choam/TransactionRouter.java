/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.proto.SubmitResult;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.choam.support.SubmittedTransaction;
import com.hellblazer.delos.cryptography.Digest;

/**
 * Routes transaction submissions from clients to the CHOAM committee.
 * Handles client validation, rate limiting, and routing to committee members.
 *
 * @author hal.hildebrand
 */
public interface TransactionRouter {

    /**
     * Submit a transaction from an external client.
     * Validates the client, enforces rate limits, and routes to the committee.
     *
     * @param request Transaction to submit
     * @param from Client identity (digest)
     * @return Result of the submission
     */
    SubmitResult submitFromClient(Transaction request, Digest from);

    /**
     * Route a transaction to the current committee for consensus.
     * This is used for internal routing within the committee.
     *
     * @param transaction Transaction to route
     * @return Result of the routing
     */
    SubmitResult routeToCommittee(Transaction transaction);

    /**
     * Service method for wrapping transaction submission to committee.
     * Used by Session to submit transactions through the current committee.
     *
     * @param stx Submitted transaction with metadata
     * @return Result of the submission
     */
    SubmitResult service(SubmittedTransaction stx);
}
