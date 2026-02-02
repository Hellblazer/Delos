/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.ring;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.hellblazer.delos.archipelago.Link;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.utils.Entropy;
import com.hellblazer.delos.utils.Utils;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * @author hal.hildebrand
 */
public class SliceIterator<Comm extends Link> {
    private static final Logger log = LoggerFactory.getLogger(SliceIterator.class);

    private final    CommonCommunications<Comm, ?> comm;
    private final    String                        label;
    private final    SigningMember                 member;
    private final    List<? extends Member>        slice;
    private final    ScheduledExecutorService      scheduler;
    private final    int                           majority;
    private volatile Member                        current;
    private volatile Iterator<? extends Member>    currentIteration;
    private volatile int                           i;
    private volatile boolean                       majoritySucceed = false;
    private volatile boolean                       majorityFailed;

    public SliceIterator(String label, SigningMember member, Collection<? extends Member> slice,
                         CommonCommunications<Comm, ?> comm) {
        this(label, member, slice, comm, Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory()));
    }

    public SliceIterator(String label, SigningMember member, Collection<? extends Member> s,
                         CommonCommunications<Comm, ?> comm, ScheduledExecutorService scheduler) {
        this(label, member, s, comm, scheduler, -1);
    }

    public SliceIterator(String label, SigningMember member, Collection<? extends Member> s,
                         CommonCommunications<Comm, ?> comm, ScheduledExecutorService scheduler, int majority) {
        assert member != null && s != null && comm != null;
        assert !s.stream().filter(Objects::nonNull).toList().isEmpty() : "All elements must be non-null: " + s;
        this.label = label;
        this.member = member;
        this.slice = new CopyOnWriteArrayList<>(s);
        this.comm = comm;
        this.scheduler = scheduler;
        this.majority = majority;
        Entropy.secureShuffle(this.slice);
        this.currentIteration = slice.iterator();
        log.debug("Slice for: <{}> is: {} on: {}", label, slice.stream().map(Member::getId).toList(), member.getId());
    }

    public <T> void iterate(Function<Comm, T> round, SlicePredicateHandler<T, Comm> handler, Runnable onComplete,
                            Duration frequency) {
        iterate(null, round, handler, onComplete, frequency, null);
    }

    public <T> void iterate(Runnable onMajority, Function<Comm, T> round, SlicePredicateHandler<T, Comm> handler,
                            Runnable onComplete, Duration frequency, Runnable failedMajority) {
        log.trace("Starting iteration of: <{}> on: {}", label, member.getId());
        var tally = new AtomicInteger(0);
        Thread.ofVirtual()
              .start(Utils.wrapped(
              () -> internalIterate(round, onMajority, handler, onComplete, tally, failedMajority, frequency), log));
    }

    public <T> void iterate(Function<Comm, T> round, SlicePredicateHandler<T, Comm> handler, Duration frequency) {
        iterate(round, handler, null, frequency);
    }

    /**
     * Asynchronously collect valid responses from slice members until the required count is reached.
     *
     * @param round         function to invoke on each member's communication link
     * @param isValid       predicate to determine if a response should be collected
     * @param requiredCount minimum number of valid responses needed for success
     * @param frequency     delay between iteration attempts
     * @param timeout       maximum time to wait for quorum
     * @param <T>           the response type
     * @return a CompletableFuture that completes with the collected responses on success,
     *         or completes exceptionally with QuorumException if quorum not reached,
     *         or TimeoutException if timeout exceeded
     */
    public <T> CompletableFuture<Set<T>> collectAsync(Function<Comm, T> round, Predicate<T> isValid, int requiredCount,
                                                      Duration frequency, Duration timeout) {
        if (requiredCount <= 0) {
            throw new IllegalArgumentException("requiredCount must be positive: " + requiredCount);
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }

        var future = new CompletableFuture<Set<T>>();
        var collected = ConcurrentHashMap.<T>newKeySet();

        // Set up timeout
        var timeoutTask = scheduler.schedule(() -> {
            if (!future.isDone()) {
                future.completeExceptionally(
                new TimeoutException("Collect timed out after " + timeout));
            }
        }, timeout.toNanos(), TimeUnit.NANOSECONDS);

        // Cancel timeout when future completes
        future.whenComplete((result, error) -> timeoutTask.cancel(false));

        SlicePredicateHandler<T, Comm> handler = (result, tally, link, m) -> {
            if (future.isDone()) {
                return false; // Stop iteration
            }

            result.filter(isValid).ifPresent(r -> {
                if (collected.add(r)) {
                    tally.incrementAndGet();
                }
            });

            if (collected.size() >= requiredCount) {
                future.complete(Set.copyOf(collected));
                return false; // Stop iteration
            }

            return true; // Continue
        };

        Runnable onComplete = () -> {
            if (!future.isDone()) {
                future.completeExceptionally(new QuorumException(requiredCount, collected.size()));
            }
        };

        iterate(round, handler, onComplete, frequency);

        return future;
    }

    /**
     * Asynchronously collect votes from slice members and return the winner by plurality.
     *
     * @param round         function to invoke on each member's communication link
     * @param requiredCount minimum number of votes needed before determining winner
     * @param frequency     delay between iteration attempts
     * @param timeout       maximum time to wait for votes
     * @param <T>           the vote type (must have proper equals/hashCode)
     * @return a CompletableFuture that completes with the winning vote on success,
     *         or completes exceptionally with QuorumException if quorum not reached,
     *         or TimeoutException if timeout exceeded
     */
    public <T> CompletableFuture<T> voteAsync(Function<Comm, T> round, int requiredCount, Duration frequency,
                                              Duration timeout) {
        if (requiredCount <= 0) {
            throw new IllegalArgumentException("requiredCount must be positive: " + requiredCount);
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }

        var future = new CompletableFuture<T>();
        // HashMultiset is not thread-safe. Synchronization is required because
        // iterations run concurrently in virtual threads (see internalIterate line 331).
        Multiset<T> votes = HashMultiset.create();
        var lock = new Object();

        // Set up timeout
        var timeoutTask = scheduler.schedule(() -> {
            if (!future.isDone()) {
                future.completeExceptionally(new TimeoutException("Vote timed out after " + timeout));
            }
        }, timeout.toNanos(), TimeUnit.NANOSECONDS);

        // Cancel timeout when future completes
        future.whenComplete((result, error) -> timeoutTask.cancel(false));

        SlicePredicateHandler<T, Comm> handler = (result, tally, link, m) -> {
            if (future.isDone()) {
                return false; // Stop iteration
            }

            result.ifPresent(vote -> {
                int totalVotes;
                synchronized (lock) {
                    votes.add(vote);
                    totalVotes = votes.size();
                }
                tally.incrementAndGet();

                if (totalVotes >= requiredCount) {
                    // Find the winner by plurality
                    T winner;
                    synchronized (lock) {
                        winner = votes.entrySet()
                                      .stream()
                                      .max(Comparator.comparingInt(Multiset.Entry::getCount))
                                      .map(Multiset.Entry::getElement)
                                      .orElse(null);
                    }
                    if (winner != null) {
                        future.complete(winner);
                    }
                }
            });

            return !future.isDone(); // Continue if not done
        };

        Runnable onComplete = () -> {
            if (!future.isDone()) {
                synchronized (lock) {
                    // Quorum not reached - fail consistently with collectAsync behavior
                    future.completeExceptionally(new QuorumException(requiredCount, votes.size()));
                }
            }
        };

        iterate(round, handler, onComplete, frequency);

        return future;
    }

    private <T> void internalIterate(Function<Comm, T> round, Runnable onMajority,
                                     SlicePredicateHandler<T, Comm> handler, Runnable onComplete, AtomicInteger tally,
                                     Runnable failedMajority, Duration frequency) {
        Runnable proceed = () -> internalIterate(round, onMajority, handler, onComplete, tally, failedMajority, frequency);

        var c = i + 1;
        i = c;
        Consumer<Boolean> allowed = allow -> proceed(onMajority, allow, proceed, tally, onComplete, frequency,
                                                     failedMajority);
        try (Comm link = next()) {
            if (link == null || link.getMember() == null) {
                log.trace("No link for iteration: {} of: <{}> on: {}", c, label, member.getId());
                allowed.accept(handler.handle(Optional.empty(), tally, null, null));
                return;
            }
            log.trace("Iteration: {} of: <{}> to: {} on: {}", c, label, link.getMember().getId(), member.getId());
            T result = null;
            try {
                result = round.apply(link);
            } catch (StatusRuntimeException e) {
                switch (e.getStatus().getCode()) {
                case UNAVAILABLE:
                    log.trace("Unhandled: {} applying: <{}> slice to: {} iteration: {} on: {}", e, label,
                              link.getMember().getId(), c, member.getId());
                    break;
                default:
                    log.debug("Unhandled: {} applying: <{}> slice to: {} iteration: {} on: {}", e, label,
                              link.getMember().getId(), c, member.getId());
                    break;
                }
            } catch (Throwable e) {
                log.debug("Unhandled: {} applying: <{}> slice to: {} iteration: {} on: {}", e, label,
                          link.getMember().getId(), c, member.getId());
            }
            allowed.accept(handler.handle(Optional.ofNullable(result), tally, link, link.getMember()));
        } catch (IOException e) {
            log.debug("Error closing", e);
        }
    }

    private Comm linkFor(Member m) {
        try {
            return comm.connect(m);
        } catch (Throwable e) {
            log.error("error opening connection of: <{}> to {}: {} on: {}", label, m.getId(),
                      (e.getCause() != null ? e.getCause() : e).getMessage(), member.getId());
        }
        return null;
    }

    private Comm next() {
        if (!currentIteration.hasNext()) {
            Entropy.secureShuffle(slice);
            currentIteration = slice.iterator();
        }
        if (!currentIteration.hasNext()) {
            return null;
        }
        current = currentIteration.next();
        return linkFor(current);
    }

    private void proceed(Runnable onMajority, final boolean allow, Runnable proceed, AtomicInteger tally,
                         Runnable onComplete, Duration frequency, Runnable failedMajority) {
        log.trace("Determining continuation for: <{}> final itr: {} allow: {} on: {}", label,
                  !currentIteration.hasNext(), allow, member.getId());
        if (!currentIteration.hasNext() && allow) {
            if (failedMajority != null && !majorityFailed) {
                if (tally.get() < majority) {
                    majorityFailed = true;
                    log.debug("Failed to obtain majority for: {} tally: {} required: {} on: {}", label, tally.get(),
                              majority, member.getId());
                    failedMajority.run();
                }
            }
            log.trace("Final iteration of: <{}> on: {}", label, member.getId());
            if (onComplete != null) {
                log.trace("Completing iteration for: {} on: {}", label, member.getId());
                onComplete.run();
            }
        } else if (allow) {
            if (onMajority != null && !majoritySucceed) {
                if (tally.get() >= majority) {
                    majoritySucceed = true;
                    log.debug("Obtained: {} majority of: {} tally: {} on: {}", current, label, tally.get(),
                              member.getId());
                    onMajority.run();
                }
            }
            log.trace("Proceeding for: <{}> on: {}", label, member.getId());
            try {
                scheduler.schedule(() -> Thread.ofVirtual().start(Utils.wrapped(proceed, log)), frequency.toNanos(),
                                   TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException e) {
                // ignore
            }
        } else {
            log.trace("Termination for: <{}> on: {}", label, member.getId());
        }
    }

    @FunctionalInterface
    public interface SlicePredicateHandler<T, Comm> {
        boolean handle(Optional<T> result, AtomicInteger tally, Comm communications, Member member);
    }
}
