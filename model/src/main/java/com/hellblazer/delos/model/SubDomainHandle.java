/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model;

import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import io.grpc.ManagedChannel;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Handle for managing a spawned subdomain's lifecycle and communication.
 * <p>
 * Provides a clean abstraction over subdomain interaction without exposing KERI implementation details.
 * Applications use this handle to send messages, monitor health, and control lifecycle.
 * <p>
 * Thread-safe: All operations are safe to call from multiple threads concurrently.
 *
 * @author hal.hildebrand
 */
public interface SubDomainHandle {

    /**
     * Get the subdomain's KERI identifier.
     *
     * @return unique identifier for this subdomain
     */
    SelfAddressingIdentifier getId();

    /**
     * Send a message to the subdomain asynchronously.
     *
     * @param request message to send
     * @param <T>     request type
     * @param <R>     response type
     * @return future that completes with the response or exceptionally on error
     * @deprecated Use {@code getChannel()} to obtain a gRPC channel and create typed stubs instead. See RDR-002.
     */
    @Deprecated
    <T, R> CompletableFuture<R> send(T request);

    /**
     * Send a message to the subdomain with explicit timeout.
     *
     * @param request message to send
     * @param timeout maximum time to wait for response
     * @param <T>     request type
     * @param <R>     response type
     * @return future that completes with the response or exceptionally on timeout/error
     * @deprecated Use {@code getChannel()} to obtain a gRPC channel and create typed stubs instead. See RDR-002.
     */
    @Deprecated
    <T, R> CompletableFuture<R> send(T request, Duration timeout);

    /**
     * Stop the subdomain immediately.
     * <p>
     * Terminates the subdomain process/isolate, deregisters Portal routes, and releases resources.
     * This is a forceful shutdown that may interrupt in-flight operations.
     */
    void stop();

    /**
     * Stop the subdomain gracefully with timeout.
     * <p>
     * Allows the subdomain to complete in-flight operations before terminating.
     * If the subdomain doesn't stop within the timeout, it will be forcefully terminated.
     *
     * @param timeout maximum time to wait for graceful shutdown
     * @return future that completes when subdomain has stopped
     */
    CompletableFuture<Void> stopGracefully(Duration timeout);

    /**
     * Check if the subdomain is currently active.
     *
     * @return true if subdomain is running and accepting requests
     */
    boolean isActive();

    /**
     * Get the current status of the subdomain.
     *
     * @return status enum indicating lifecycle state
     */
    SubDomainStatus getStatus();

    /**
     * Get metrics for this subdomain.
     * <p>
     * Includes spawn time, request counts, error rates, and latency statistics.
     *
     * @return snapshot of current metrics
     */
    SubDomainMetrics getMetrics();

    /**
     * Set resource limits for this subdomain.
     * <p>
     * Configures maximum memory, thread count, and file descriptor usage.
     * Enforcement depends on isolate implementation (GraalVM isolates vs in-process).
     *
     * @param limits resource constraints to enforce
     */
    void setResourceLimits(ResourceLimits limits);

    /**
     * Get a gRPC channel to this subdomain via Portal routing.
     * <p>
     * Returns a cached {@link ManagedChannel} connected to the Portal's inbound endpoint
     * with {@code METADATA_CONTEXT_KEY} pre-set to this subdomain's context digest.
     * Callers create typed stubs from this channel to invoke subdomain services.
     *
     * @return cached ManagedChannel with routing metadata pre-configured
     * @throws IllegalStateException if subdomain is not yet running (still in STARTING state)
     */
    ManagedChannel getChannel();
}
