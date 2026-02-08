/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Empty;
import com.hellblazer.delos.choam.comm.Terminal;
import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.membership.GroupIterator;
import com.hellblazer.delos.membership.Member;
import io.grpc.StatusRuntimeException;
import io.netty.util.concurrent.ImmediateExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.hellblazer.delos.choam.support.BlockBuilders.hashOf;
import static io.grpc.Status.INVALID_ARGUMENT;

/**
 * Abstract base class for committee administration in CHOAM consensus.
 * Handles common committee operations including view membership, transaction submission,
 * and join protocols.
 * <p>
 * This class is extracted from CHOAM.java (Phase 3.2) and uses a CHOAM reference to access
 * coordinator state. See Phase 3 plan's "Architectural Pattern Rationale" for justification
 * of this pattern vs explicit dependency injection.
 * </p>
 * <p>
 * Thread Safety: Thread-safe by delegation to CHOAM's StateHolder classes.
 * </p>
 *
 * @author hal.hildebrand
 */
abstract class Administration implements Committee {
    private static final Logger log = LoggerFactory.getLogger(Administration.class);  // F2: Own Logger

    protected final CHOAM                     choam;
    protected final Digest                    viewId;
    private final   GroupIterator             servers;
    private final   Map<Member, Verifier>     validators;

    /**
     * Construct an Administration instance.
     *
     * @param choam      CHOAM coordinator instance for accessing state
     * @param validators map of committee members to their verifiers
     * @param viewId     view identifier for this committee
     */
    Administration(CHOAM choam, Map<Member, Verifier> validators, Digest viewId) {
        this.choam = choam;
        this.validators = validators;
        this.viewId = viewId;
        servers = new GroupIterator(validators.keySet());
    }

    @Override
    public void accept(HashedCertifiedBlock hb) {
        choam.process();
    }

    @Override
    public void assemble(Assemble assemble) {
        var mid = choam.params().member().getId();
        var view = assemble.getView();
        if (view.getCommitteeList().stream().map(Digest::from).noneMatch(mid::equals)) {
            log.info("Assemble view: {}; Not associate: {} in diadem: {} on: {}", viewId,
                     getClass().getSimpleName(), Digest.from(view.getDiadem()), mid);
            return;
        }
        log.info("Assemble view: {}; Associate in diadem: {} on: {}", viewId, Digest.from(view.getDiadem()), mid);
        join(view);
    }

    @Override
    public void complete() {
    }

    @Override
    public boolean isMember() {
        return validators.containsKey(choam.params().member());
    }

    @Override
    public Logger log() {
        return log;  // F2: Return own Logger, not CHOAM's
    }

    @Override
    public void nextView(Digest diadem, Context<Member> pendingView) {
        choam.viewStateHolder().setPendingViews(choam.viewStateHolder().getPendingViews().add(diadem, pendingView));
        log.info("Pending context for view: {} size: {} on: {}",
                 choam.viewStateHolder().getNextViewId() == null ? "<null>" : choam.viewStateHolder().getNextViewId(), pendingView.size(),
                 choam.params().member().getId());
    }

    @Override
    public Parameters params() {
        return choam.params();
    }

    @Override
    public SubmitResult submitTxn(Transaction transaction) {
        if (!choam.controlState().isStarted()) {
            log.trace("Failed submitting txn: {} no servers available in: {} on: {}",
                      hashOf(transaction, choam.params().digestAlgorithm()), viewId, choam.params().member().getId());
            return SubmitResult.newBuilder().setResult(SubmitResult.Result.ERROR_SUBMITTING).setErrorMsg("Shutdown").build();
        }
        if (!servers.hasNext()) {
            log.trace("Failed submitting txn: {} no servers available in: {} on: {}",
                      hashOf(transaction, choam.params().digestAlgorithm()), viewId, choam.params().member().getId());
            return SubmitResult.newBuilder()
                               .setResult(SubmitResult.Result.ERROR_SUBMITTING)
                               .setErrorMsg("no servers available")
                               .build();
        }
        Member target = servers.next();
        try (var link = choam.submissionComm().connect(target)) {
            if (link == null) {
                log.debug("No link for: {} for submitting txn on: {}", target.getId(), choam.params().member().getId());
                return SubmitResult.newBuilder().setResult(SubmitResult.Result.UNAVAILABLE).build();
            }
            log.trace("Submitting txn: {} to: {} in view: {} on: {}", hashOf(transaction, choam.params().digestAlgorithm()),
                      link.getMember().getId(), viewId, choam.params().member().getId());
            return link.submit(transaction);
        } catch (StatusRuntimeException e) {
            log.trace("Failed submitting txn: {} status:{} to: {} in: {} on: {}",
                      hashOf(transaction, choam.params().digestAlgorithm()), e.getStatus(), target.getId(), viewId,
                      choam.params().member().getId());
            return SubmitResult.newBuilder()
                               .setResult(SubmitResult.Result.ERROR_SUBMITTING)
                               .setErrorMsg(e.getStatus().toString())
                               .build();
        } catch (Throwable e) {
            log.debug("Failed submitting txn: {} to: {} in: {} on: {}",
                      hashOf(transaction, choam.params().digestAlgorithm()), target.getId(), viewId,
                      choam.params().member().getId(), e);
            return SubmitResult.newBuilder().setResult(SubmitResult.Result.ERROR_SUBMITTING).setErrorMsg(e.toString()).build();
        }
    }

    @Override
    public boolean validate(HashedCertifiedBlock hb) {
        return choam.validate(hb, validators);  // F1 CRITICAL: Must use choam.validate() to avoid infinite recursion
    }

    private void join(View view) {
        if (!choam.controlState().beginJoin()) {
            throw new IllegalStateException("Ongoing join should have been cancelled");
        }
        log.trace("Joining view: {} diadem: {} on: {}", choam.viewStateHolder().getNextViewId(), Digest.from(view.getDiadem()),
                  choam.params().member().getId());
        var servers = new ConcurrentSkipListSet<>(validators.keySet());
        var joined = new AtomicInteger();
        log.trace("Starting join of: {} diadem {} on: {}", choam.viewStateHolder().getNextViewId(), Digest.from(view.getDiadem()),
                  choam.params().member().getId());
        var scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        AtomicReference<Runnable> action = new AtomicReference<>();
        var attempts = new AtomicInteger();
        action.set(() -> {
            log.trace("Join attempt: {} ongoing: {} joined: {} majority: {} on: {}", attempts.incrementAndGet(),
                      choam.controlState().isJoinOngoing(), joined.get(), view.getMajority(), choam.params().member().getId());
            if (choam.controlState().isJoinOngoing() & joined.get() < view.getMajority()) {
                join(view, servers, joined);
                if (joined.get() >= view.getMajority()) {
                    choam.controlState().endJoin();
                    scheduler.shutdown();  // R12: Clean up scheduler when join succeeds
                    log.trace("Finished join of: {} diadem: {} joins: {} on: {}", choam.viewStateHolder().getNextViewId(),
                              Digest.from(view.getDiadem()), joined.get(), choam.params().member().getId());
                } else if (choam.controlState().isJoinOngoing()) {
                    log.trace("Rescheduling join of: {} diadem: {} joins: {} on: {}", choam.viewStateHolder().getNextViewId(),
                              Digest.from(view.getDiadem()), joined.get(), choam.params().member().getId());
                    scheduler.schedule(action.get(), 50, TimeUnit.MILLISECONDS);
                } else {
                    // Join was cancelled while we were waiting - clean up scheduler
                    scheduler.shutdown();  // R12: Clean up scheduler when join cancelled during wait
                }
            } else {
                // Join was cancelled before we even started - clean up scheduler
                scheduler.shutdown();  // R12: Clean up scheduler when join cancelled immediately
            }
        });
        scheduler.schedule(action.get(), 50, TimeUnit.MILLISECONDS);
    }

    private void join(View view, Collection<Member> members, AtomicInteger joined) {
        var sampled = new ArrayList<>(members);
        Collections.shuffle(sampled);
        log.trace("Joining view: {} diadem: {} servers: {} on: {}", viewId, Digest.from(view.getDiadem()),
                  sampled.stream().map(Member::getId).toList(), choam.params().member().getId());
        final var c = (NextView) choam.viewStateHolder().getNext();
        var inView = ViewMember.newBuilder(c.member())
                               .setDiadem(view.getDiadem())
                               .setView(choam.viewStateHolder().getNextViewId().toDigeste())
                               .build();
        var svm = SignedViewMember.newBuilder()
                                  .setVm(inView)
                                  .setSignature(choam.params().member().sign(inView.toByteString()).toSig())
                                  .build();
        var countdown = new CountDownLatch(sampled.size());
        sampled.stream().map(m -> {
            var connection = choam.getComm().connect(m);
            log.trace("connect to: {} is: {} on: {}", m.getId(), connection, choam.params().member().getId());
            return connection;
        }).map(t -> t == null ? null : join(view, t, svm)).forEach(t -> {
            if (t == null) {
                countdown.countDown();
            } else {
                t.fs.addListener(() -> {
                    try {
                        t.fs.get();
                        members.remove(t.m);
                        joined.incrementAndGet();
                        log.trace("Joined with: {} view: {} diadem: {} on: {}", t.m.getId(),
                                  Digest.from(inView.getId()), Digest.from(view.getDiadem()),
                                  choam.params().member().getId());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (ExecutionException e) {
                        log.error("Failed to join with: {} view: {} diadem: {} on: {}", t.m.getId(), viewId,
                                  Digest.from(view.getDiadem()), choam.params().member().getId(), e.getCause());
                    } catch (Throwable e) {
                        log.error("Failed to join with: {} view: {} diadem: {} on: {}", t.m.getId(), viewId,
                                  Digest.from(view.getDiadem()), choam.params().member().getId(), e);
                    } finally {
                        countdown.countDown();
                    }
                }, ImmediateExecutor.INSTANCE);
            }
        });
        try {
            countdown.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Attempt join(View view, Terminal t, SignedViewMember svm) {
        try {
            log.trace("Attempting to join with: {} context: {} diadem: {} on: {}", t.getMember().getId(),
                      choam.context().getId(), Digest.from(view.getDiadem()), choam.params().member().getId());
            return new Attempt(t.getMember(), t.join(svm));
        } catch (StatusRuntimeException sre) {
            log.trace("Failed join attempt: {} with: {} view: {} diadem: {} on: {}", sre.getStatus(),
                      t.getMember().getId(), choam.viewStateHolder().getNextViewId(), Digest.from(view.getDiadem()), choam.params().member().getId(),
                      sre);
        } catch (Throwable throwable) {
            log.error("Failed join attempt with: {} view: {} diadem: {} on: {}", t.getMember().getId(), choam.viewStateHolder().getNextViewId(),
                      Digest.from(view.getDiadem()), choam.params().member().getId(), throwable);
        } finally {
            try {
                t.close();
            } catch (IOException e) {
                // ignored
            }
        }
        return null;
    }

    record Attempt(Member m, ListenableFuture<Empty> fs) {
    }
}
