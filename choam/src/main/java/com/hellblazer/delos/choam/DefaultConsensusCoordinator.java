/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.CHOAM.PendingViews;
import com.hellblazer.delos.choam.CHOAM.nextView;
import com.hellblazer.delos.choam.fsm.Combine.Transitions;
import com.hellblazer.delos.choam.proto.Reconfigure;
import com.hellblazer.delos.choam.proto.SignedViewMember;
import com.hellblazer.delos.choam.proto.ViewMember;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.cryptography.proto.PubKey;
import com.hellblazer.delos.ethereal.Dag;
import com.hellblazer.delos.membership.Member;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyPair;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static com.hellblazer.delos.choam.Committee.validatorsOf;
import static com.hellblazer.delos.cryptography.QualifiedBase64.bs;
import static io.grpc.Status.FAILED_PRECONDITION;

@FunctionalInterface
interface TriFunction<T, U, V, R> {
    R apply(T t, U u, V v);
}

/**
 * Default implementation of ConsensusCoordinator that manages consensus state
 * across committee transitions and view changes.
 *
 * This coordinator encapsulates the committee lifecycle state machine, handling
 * transitions between Formation, Associate, Client, and Synchronizer committees
 * as the system reconfigures and members join/leave views.
 *
 * Thread-safety is provided via viewStateLock for all state mutations.
 *
 * @author hal.hildebrand
 */
public class DefaultConsensusCoordinator implements ConsensusCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DefaultConsensusCoordinator.class);

    private final Parameters                               params;
    private final Supplier<PendingViews>                   pendingViews;
    private final AtomicReference<Transitions>             transitions = new AtomicReference<>();
    private final AtomicReference<Session>                 session = new AtomicReference<>();
    private final AtomicBoolean                            ongoingJoin;
    private final TriFunction<HashedCertifiedBlock, Map<Member, Verifier>, nextView, Committee> associateFactory;
    private final BiFunction<Map<Member, Verifier>, Digest, Committee> clientFactory;
    private final Supplier<HashedCertifiedBlock>           headSupplier;

    private final AtomicReference<Committee>               current       = new AtomicReference<>();
    private final AtomicReference<HashedCertifiedBlock>    view          = new AtomicReference<>();
    private final AtomicReference<Digest>                  nextViewId    = new AtomicReference<>();
    private final AtomicReference<nextView>                next          = new AtomicReference<>();
    private final ReentrantLock                            viewStateLock = new ReentrantLock();

    /**
     * Factory function for creating Associate committee instances.
     * Parameters: (viewChangeBlock, validators, nextView) -> Associate
     */
    @FunctionalInterface
    public interface AssociateFactory extends TriFunction<HashedCertifiedBlock, Map<Member, Verifier>, nextView, Committee> {
    }

    /**
     * Factory function for creating Client committee instances.
     * Parameters: (validators, viewId) -> Client
     */
    @FunctionalInterface
    public interface ClientFactory extends BiFunction<Map<Member, Verifier>, Digest, Committee> {
    }

    public DefaultConsensusCoordinator(Parameters params,
                                       Supplier<PendingViews> pendingViews,
                                       AtomicBoolean ongoingJoin,
                                       TriFunction<HashedCertifiedBlock, Map<Member, Verifier>, nextView, Committee> associateFactory,
                                       BiFunction<Map<Member, Verifier>, Digest, Committee> clientFactory,
                                       Supplier<HashedCertifiedBlock> headSupplier) {
        this.params = Objects.requireNonNull(params, "params cannot be null");
        this.pendingViews = Objects.requireNonNull(pendingViews, "pendingViews cannot be null");
        this.ongoingJoin = Objects.requireNonNull(ongoingJoin, "ongoingJoin cannot be null");
        this.associateFactory = Objects.requireNonNull(associateFactory, "associateFactory cannot be null");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory cannot be null");
        this.headSupplier = Objects.requireNonNull(headSupplier, "headSupplier cannot be null");
    }

    /**
     * Set the transitions FSM reference. Must be called before reconfigure().
     */
    public void setTransitions(Transitions transitions) {
        this.transitions.set(Objects.requireNonNull(transitions, "transitions cannot be null"));
    }

    /**
     * Set the session reference. Must be called before reconfigure().
     */
    public void setSession(Session session) {
        this.session.set(Objects.requireNonNull(session, "session cannot be null"));
    }

    @Override
    public Committee getCurrentCommittee() {
        return current.get();
    }

    @Override
    public void setCommittee(Committee committee) {
        current.set(committee);
    }

    @Override
    public void reconfigure(Digest hash, Reconfigure reconfigure, HashedCertifiedBlock head) {
        viewStateLock.lock();
        try {
            log.info("Setting next view id: {} on: {}", hash, params.member().getId());
            nextViewId.set(hash);
            var pv = pendingViews.get().advance();
            if (pv != null) {
                params.context().setContext(pv.context());
            }
            final var c = current.get();
            c.complete();
            var validators = validatorsOf(reconfigure, params.context(), params.member().getId(), log);
            final var currentView = next.get();
            transitions.get().rotateViewKeys();
            view.set(head);
            session.get().setView(head);
            if (validators.containsKey(params.member())) {
                if (Dag.validate(validators.size())) {
                    current.set(associateFactory.apply(head, validators, currentView));
                } else {
                    log.warn("Reconfiguration to associate failed: {} committee: {} in view: {} on:{}",
                             validators.size(), new Digest(reconfigure.getId()),
                             current.get().getClass().getSimpleName(), params.member().getId());
                    transitions.get().fail();
                }
            } else {
                current.set(clientFactory.apply(validators, getViewId()));
            }
            if (ongoingJoin.compareAndSet(true, false)) {
                log.trace("Halting ongoing join on: {}", params.member().getId());
            }
            log.info("Reconfigured to view: {} committee: {} validators: {} on: {}", new Digest(reconfigure.getId()),
                     current.get().getClass().getSimpleName(), validators.entrySet()
                                                                         .stream()
                                                                         .map(e -> String.format("id: %s key: %s",
                                                                                                 e.getKey().getId(),
                                                                                                 params.digestAlgorithm()
                                                                                                       .digest(
                                                                                                       e.toString())))
                                                                         .toList(), params.member().getId());
        } finally {
            viewStateLock.unlock();
        }
    }

    @Override
    public void join(SignedViewMember nextView, Digest from) {
        var c = current.get();
        if (c == null) {
            log.trace("No committee for: {} to join: {} diadem: {} on: {}", from,
                      Digest.from(nextView.getVm().getView()), Digest.from(nextView.getVm().getDiadem()),
                      params.member().getId());
            throw new StatusRuntimeException(FAILED_PRECONDITION);
        }
        c.join(nextView, from);
    }

    @Override
    public void rotateViewKeys() {
        var keyPair = params.viewSigAlgorithm().generateKeyPair();
        var pubKey = bs(keyPair.getPublic());
        var signed = params.member().sign(pubKey.toByteString());
        if (signed == null) {
            log.error("Unable to generate and sign consensus key on: {}", params.member().getId());
            return;
        }
        var committee = current.get();
        log.trace("Generated next view consensus key: {} sig: {} committee: {} on: {}",
                  params.digestAlgorithm().digest(pubKey.getEncoded()),
                  params.digestAlgorithm().digest(signed.toSig().toByteString()),
                  committee == null ? "<no formation>" : committee.getClass().getSimpleName(), params.member().getId());
        next.set(new nextView(ViewMember.newBuilder()
                                        .setId(params.member().getId().toDigeste())
                                        .setConsensusKey(pubKey)
                                        .setSignature(signed.toSig())
                                        .build(), keyPair));
    }

    @Override
    public Digest getNextViewId() {
        return nextViewId.get();
    }

    @Override
    public void setNextViewId(Digest id) {
        nextViewId.set(id);
    }

    @Override
    public Digest getViewId() {
        final var viewChange = view.get();
        if (viewChange == null) {
            return null;
        }
        return new Digest(viewChange.block.hasGenesis() ? viewChange.block.getGenesis().getInitialView().getId()
                                                        : viewChange.block.getReconfigure().getId());
    }

    @Override
    public HashedCertifiedBlock getView() {
        return view.get();
    }

    @Override
    public void setView(HashedCertifiedBlock view) {
        this.view.set(view);
    }

    @Override
    public nextView getNextView() {
        return next.get();
    }
}
