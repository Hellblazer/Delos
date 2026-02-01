/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.coordination;

import com.hellblazer.delos.archipelago.Link;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.ring.SliceIterator;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.Objects;

/**
 * BftCoordinator provides BFT-tolerant quorum operations.
 * <p>
 * Lifecycle: The scheduler is caller-owned. The caller is responsible
 * for creating and shutting down the ScheduledExecutorService.
 * BftCoordinator does not manage scheduler lifecycle.
 *
 * @param <Comm> The communication link type
 * @author hal.hildebrand
 */
public class BftCoordinator<Comm extends Link> {
    private static final Logger log = LoggerFactory.getLogger(BftCoordinator.class);

    private final Context<?>                     context;
    private final SigningMember                  member;
    private final CommonCommunications<Comm, ?>  comm;
    private final ScheduledExecutorService       scheduler;

    /**
     * Create a BftCoordinator.
     *
     * @param context   The membership context for BFT subset selection
     * @param member    The signing member (self)
     * @param comm      Communication layer for connecting to other members
     * @param scheduler Caller-owned scheduler for retry scheduling.
     *                  Caller is responsible for lifecycle management.
     */
    public BftCoordinator(Context<?> context, SigningMember member, CommonCommunications<Comm, ?> comm,
                          ScheduledExecutorService scheduler) {
        this.context = context;
        this.member = member;
        this.comm = comm;
        this.scheduler = scheduler;
    }

    /**
     * Collect responses from BFT subset until majority reached.
     *
     * @param identifier Digest for BFT subset selection
     * @param label      Human-readable label for logging
     * @param requestFn  Function to call on each member's link
     * @param frequency  Delay between requests
     * @param timeout    Overall timeout (null = no overall timeout, per-RPC only)
     * @param <T>        Type of response
     * @return Future completing with QuorumResult on success, or exceptionally on failure
     * @throws StatusRuntimeException (ABORTED) if majority not reached after slice pass
     * @throws TimeoutException       if overall timeout exceeded
     */
    public <T> CompletableFuture<QuorumResult<Set<T>>> collectQuorum(
        Digest identifier,
        String label,
        Function<Comm, T> requestFn,
        Duration frequency,
        Duration timeout
    ) {
        Objects.requireNonNull(identifier, "identifier cannot be null");
        Objects.requireNonNull(label, "label cannot be null");
        Objects.requireNonNull(requestFn, "requestFn cannot be null");
        Objects.requireNonNull(frequency, "frequency cannot be null");

        var bftSubset = context.bftSubset(identifier);
        var majority = context.majority();

        // Validate subset
        if (bftSubset == null || bftSubset.isEmpty()) {
            var failedFuture = new CompletableFuture<QuorumResult<Set<T>>>();
            failedFuture.completeExceptionally(
                new StatusRuntimeException(
                    Status.ABORTED.withDescription("Empty BFT subset for: " + label)
                )
            );
            return failedFuture;
        }

        log.debug("Starting quorum collection for: {} subset size: {} majority: {} on: {}",
                  label, bftSubset.size(), majority, member.getId());

        var result = new CompletableFuture<QuorumResult<Set<T>>>();
        var responses = Collections.newSetFromMap(new ConcurrentHashMap<T, Boolean>());

        var iterator = new SliceIterator<>(label, member, bftSubset, comm, scheduler, majority);

        // Set up overall timeout if specified
        ScheduledFuture<?> timeoutTask = null;
        if (timeout != null) {
            timeoutTask = scheduler.schedule(() -> {
                if (!result.isDone()) {
                    result.completeExceptionally(
                        new TimeoutException("Quorum collection timeout for: " + label)
                    );
                }
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        var finalTimeoutTask = timeoutTask;

        // Start iteration
        iterator.iterate(
            requestFn,
            (response, tally, link, m) -> {
                // Handle response
                if (response.isPresent()) {
                    var value = response.get();
                    responses.add(value);
                    tally.incrementAndGet();
                    log.trace("Collected response from: {} tally: {} for: {} on: {}",
                              m.getId(), tally.get(), label, member.getId());
                }
                return true; // Continue iteration
            },
            () -> {
                // Completion callback
                if (finalTimeoutTask != null) {
                    finalTimeoutTask.cancel(false);
                }

                if (result.isDone()) {
                    log.trace("Quorum collection already completed for: {} on: {}", label, member.getId());
                    return;
                }

                if (responses.size() >= majority) {
                    log.debug("Quorum reached for: {} responses: {} majority: {} on: {}",
                              label, responses.size(), majority, member.getId());
                    result.complete(new QuorumResult<>(
                        Set.copyOf(responses),
                        responses.size(),
                        majority
                    ));
                } else {
                    log.warn("Quorum not reached for: {} responses: {} required: {} on: {}",
                             label, responses.size(), majority, member.getId());
                    result.completeExceptionally(
                        new StatusRuntimeException(
                            Status.ABORTED.withDescription(
                                "Cannot gather required majority for: %s responses: %s required: %s on: %s"
                                .formatted(label, responses.size(), majority, member.getId())
                            )
                        )
                    );
                }
            },
            frequency
        );

        return result;
    }
}
