/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import com.netflix.concurrency.limits.MetricRegistry;
import com.hellblazer.delos.ethereal.memberships.comm.EtherealMetrics;
import com.hellblazer.delos.membership.messaging.rbc.RbcMetrics;
import com.hellblazer.delos.protocols.EndpointMetrics;

/**
 * Framework-agnostic metrics interface for CHOAM state machine replication.
 * <p>
 * Note: This interface uses semantic method names to decouple from metrics implementation.
 * The implementation (currently Dropwizard) will be replaced with Micrometer in Phase 2.
 *
 * @author hal.hildebrand
 */
public interface ChoamMetrics extends EndpointMetrics {

    void dropped(int transactions, int validations, int reassemblies);

    RbcMetrics getCombineMetrics();

    EtherealMetrics getGensisMetrics();

    MetricRegistry getMetricRegistry(String prefix);

    EtherealMetrics getProducerMetrics();

    void publishedBatch(int batchSize, int byteSize, int validations, int joins);

    void transactionCancelled();

    void transactionComplete(Throwable t);

    void recordTransactionLatencyDuration(long nanos);

    void transactionSubmissionError();

    void transactionSubmitRateLimited();

    void transactionSubmitRetriesExhausted();

    void transactionSubmitRetry();

    void transactionSubmittedBufferFull();

    void transactionSubmittedFail();

    void transactionSubmittedInvalidCommittee();

    void transactionSubmittedInvalidResult();

    void transactionSubmittedSuccess();

    void transactionSubmittedUnavailable();

    void transactionTimeout();
}
