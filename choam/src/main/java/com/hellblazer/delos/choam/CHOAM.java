/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.chiralbehaviors.tron.Fsm;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.choam.comm.*;
import com.hellblazer.delos.choam.fsm.Combine;
import com.hellblazer.delos.choam.fsm.Combine.Mercantile;
import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.choam.proto.SubmitResult.Result;
import com.hellblazer.delos.choam.support.*;
import com.hellblazer.delos.choam.support.CheckpointManagerImpl;
import com.hellblazer.delos.choam.support.Bootstrapper.SynchronizedState;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock.NullBlock;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.DelegatedContext;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.context.ViewChange;
import com.hellblazer.delos.cryptography.*;
import com.hellblazer.delos.cryptography.Signer.SignerImpl;
import com.hellblazer.delos.cryptography.proto.PubKey;
import com.hellblazer.delos.ethereal.Dag;
import com.hellblazer.delos.membership.GroupIterator;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.RoundScheduler;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.membership.messaging.beg.BoundedEpidemicGossip;
import com.hellblazer.delos.membership.messaging.beg.BoundedEpidemicGossip.MessageAdapter;
import com.hellblazer.delos.membership.messaging.beg.BoundedEpidemicGossip.Msg;
import com.hellblazer.delos.messaging.proto.AgedMessageOrBuilder;
import com.hellblazer.delos.utils.Utils;
import io.grpc.StatusRuntimeException;
import io.netty.util.concurrent.ImmediateExecutor;
import org.h2.mvstore.MVMap;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.security.KeyPair;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.hellblazer.delos.choam.Committee.validatorsOf;
import static com.hellblazer.delos.choam.support.HashedBlock.buildHeader;
import static com.hellblazer.delos.choam.support.HashedBlock.height;
import static com.hellblazer.delos.cryptography.QualifiedBase64.bs;
import static com.hellblazer.delos.cryptography.QualifiedBase64.digest;
import static io.grpc.Status.FAILED_PRECONDITION;
import static io.grpc.Status.INVALID_ARGUMENT;

/**
 * Combine Honnete Ober Advancer Mercantiles.
 *
 * @author hal.hildebrand
 */
public class CHOAM implements ConsensusEngine {
    private static final Logger log = LoggerFactory.getLogger(CHOAM.class);

    private final    CheckpointManager                                    checkpointManager;
    private final    BlockProcessor                                       blockProcessor;
    private final    BoundedEpidemicGossip                                  combine;
    private final    CommonCommunications<Terminal, Concierge>             comm;
    private final    Parameters                                            params;
    private final    RoundScheduler                                        roundScheduler;
    private final    Session                                               session;
    private final    AsyncOperationStateHolder                            asyncOperationState   = new AsyncOperationStateHolder();
    private final    BlockChainStateHolder                                blockChainState;
    private final    CommitteeStateHolder                                 committeeState        = new CommitteeStateHolder();
    private final    ControlStateHolder                                    controlState          = new ControlStateHolder();
    private final    ViewStateHolder                                      viewStateHolder;
    private final    BlockStore                                            store;
    private final    CommonCommunications<TxnSubmission, Submitter>        submissionComm;
    private final    Combine.Transitions                                   transitions;
    private final    TransSubmission                                       txnSubmission         = new TransSubmission(this);
    private final    ScheduledExecutorService                              scheduler;
    private final    com.chiralbehaviors.tron.Fsm<Combine, Combine.Transitions> fsm;
    private final    ByzantineDetectionMapper                             byzantineMapper;
    private final    StallDiagnostics                                     stallDiagnostics;
    private final    BlockConsumer                                        blockConsumer;
    private final    SynchronizedBlockValidator                          syncValidator;
    private final    BlockProducerImpl                                   blockProducer;
    private final    RecoveryCoordinator                                 recoveryCoordinator;
    private final    BlockDispatcher                                     blockDispatcher;
    private final    StateRestorer                                       stateRestorer;
    private final    CheckpointBlockBuilder                              checkpointBlockBuilder;
    private final    ReconfigurationCoordinator                          reconfigCoordinator;
    private final    StateSnapshotCapture                                stateSnapshotCapture;
    public final     ReadWriteLock                                         headLock;
    public final     ReentrantLock                                         viewStateLock;

    public CHOAM(Parameters params) {
        scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        this.store = new MVBlockStore(params.digestAlgorithm(), params.mvBuilder().clone().build());
        this.checkpointManager = new CheckpointManagerImpl(store, params);
        this.params = params;
        this.blockChainState = new BlockChainStateHolder(params.digestAlgorithm(), params.maxPendingBlocks());
        this.headLock = blockChainState.headLock;

        // Initialize ViewCoordinator and ViewStateHolder for two-phase reconfigure pattern
        var viewState = new ViewStateImpl(params.context().getId(), params.context().delegate());
        var coordinator = new ViewCoordinatorImpl(viewState, params.context().getId(), params.context().delegate());
        var initialPendingViews = ImmutablePendingViews.EMPTY.add(params.context().getId(), params.context().delegate());
        this.viewStateHolder = new ViewStateHolder(coordinator, initialPendingViews);
        this.viewStateLock = viewStateHolder.viewStateLock;

        this.blockProcessor = new BlockProcessorImpl(blockChainState.getPendingQueue(), controlState.getStartedRef(), params, blockChainState.getHeadRef(), this::consume);
        // Register for stall detection events
        blockProcessor.setStallListener(this::handleStallDetected);

        rotateViewKeys();
        var bContext = new DelegatedContext<>(params.context());
        var adapter = new MessageAdapter(_ -> true, this::signatureHash, _ -> Collections.emptyList(), (_, any) -> any,
                                         AgedMessageOrBuilder::getContent);

        combine = new BoundedEpidemicGossip(bContext, params.member(), params.combine(), params.communications(),
                                            params.metrics() == null ? null : params.metrics().getCombineMetrics(),
                                            adapter);
        combine.registerHandler((_, messages) -> Thread.ofVirtual().start(() -> {
            if (!controlState.isStarted()) {
                return;
            }
            try {
                combine(messages);
            } catch (Throwable t) {
                log.error("Failed to combine messages on: {}", params.member().getId(), t);
            }
        }));
        blockChainState.setHead(new NullBlock(params.digestAlgorithm()));
        blockChainState.setView(new NullBlock(params.digestAlgorithm()));
        final var service = new ConciergeService(this, log);
        comm = params.communications()
                     .create(params.member(), params.context().getId(), service, service.getClass().getCanonicalName(),
                             r -> new TerminalServer(params.communications().getClientIdentityProvider(),
                                                     params.metrics(), r), TerminalClient.getCreate(params.metrics()),
                             Terminal.getLocalLoopback(params.member(), service));
        submissionComm = params.communications()
                               .create(params.member(), params.context().getId(), txnSubmission,
                                       txnSubmission.getClass().getCanonicalName(),
                                       r -> new TxnSubmitServer(params.communications().getClientIdentityProvider(),
                                                                params.metrics(), r),
                                       TxnSubmitClient.getCreate(params.metrics()),
                                       TxnSubmission.getLocalLoopback(params.member(), txnSubmission));

        // Initialize stall diagnostics for root cause analysis
        this.byzantineMapper = new ByzantineDetectionMapper();
        var diagnosticsMetrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        this.stallDiagnostics = new StallDiagnostics(params.context(), comm, byzantineMapper, diagnosticsMetrics);

        this.fsm = Fsm.construct(new CombinerFSM(this, log), Combine.Transitions.class, Mercantile.INITIAL, true);
        fsm.setName("CHOAM%s on: %s".formatted(params.context().getId(), params.member().getId()));

        // Initialize state snapshot capture for validation
        this.stateSnapshotCapture = new StateSnapshotCapture(
            controlState,
            committeeState,
            blockChainState,
            viewStateHolder,
            asyncOperationState,
            fsm
        );

        // Conditionally wrap transitions with validation decorator
        var rawTransitions = fsm.getTransitions();
        if (com.hellblazer.delos.choam.FeatureFlags.STATE_VALIDATION.isEnabled()) {
            var matrix = com.hellblazer.delos.choam.support.StateTransitionMatrix.getInstance();
            // Use dedicated registry for validation metrics
            var validationMetrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            var validator = new com.hellblazer.delos.choam.support.StateTransitionValidator(matrix, validationMetrics);
            this.transitions = new com.hellblazer.delos.choam.support.ValidatingCombineTransitions(
                rawTransitions,
                validator,
                stateSnapshotCapture::captureStateSnapshot
            );
            log.info("State machine validation ENABLED on: {}", params.member().getId());
        } else {
            this.transitions = rawTransitions;
        }
        this.blockConsumer = new BlockConsumer(blockChainState, committeeState, params.runtime(), transitions, log);
        this.syncValidator = new SynchronizedBlockValidator(controlState, blockChainState, committeeState, params.runtime(), transitions, log, params.digestAlgorithm());
        this.checkpointBlockBuilder = new CheckpointBlockBuilder(params.runtime(), blockChainState, checkpointManager, transitions, log, params.digestAlgorithm());
        this.blockProducer = new BlockProducerImpl(params, blockChainState, checkpointManager, combine, transitions, log, this::checkpoint);
        this.recoveryCoordinator = new RecoveryCoordinator(blockChainState, checkpointManager, store, params.runtime(), transitions, controlState, syncValidator, log, params.digestAlgorithm(), this::restoreFrom);
        this.blockDispatcher = new BlockDispatcher(committeeState, blockChainState, params.runtime(), checkpointManager, store, log, this::cancelSynchronization, this::cancelBootstrap, this::genesisInitialization, this::reconfigure, this::execute);
        this.stateRestorer = new StateRestorer(store, blockChainState, checkpointManager, committeeState, params.runtime(), log, params.digestAlgorithm(), (reconfigure, logger) -> new CommitteeSynchronizer(this, validatorsOf(reconfigure, params.context(), params.member().getId(), logger), logger));
        roundScheduler = new RoundScheduler("CHOAM" + params.member().getId() + params.context().getId(),
                                            params.context().timeToLive());
        combine.register(_ -> roundScheduler.tick());
        session = new Session(params, service(), scheduler);
        this.reconfigCoordinator = new ReconfigurationCoordinator(
            viewStateHolder,
            viewStateLock,
            committeeState,
            blockChainState,
            controlState,
            session,
            params,
            transitions,
            new ReconfigurationCallbacks() {
                @Override
                public Committee createAssociate(HashedCertifiedBlock viewChange, Map<Member, Verifier> validators, NextView nextView) {
                    return new Associate(CHOAM.this, viewChange, validators, nextView);
                }

                @Override
                public Committee createClient(Map<Member, Verifier> validators, Digest viewId) {
                    return new Client(CHOAM.this, validators, viewId);
                }
            }
        );
    }

    /**
     * Handle stall detection events from the block processor.
     * <p>
     * Invoked when the block processor detects a stall condition (MAX_EMPTY_POLLS
     * consecutive empty polls). Diagnoses the root cause using {@link StallDiagnostics}
     * and initiates appropriate recovery via {@link StallRecoveryStrategy}.
     * <p>
     * Diagnosis analyzes three categories of signals:
     * <ul>
     *   <li><b>Partition</b>: Gossip heartbeat failures from Fireflies</li>
     *   <li><b>Consensus Slow</b>: Consensus participation rate from Ethereal</li>
     *   <li><b>Byzantine</b>: Signature failures and timing anomalies</li>
     * </ul>
     * <p>
     * Recovery strategies:
     * <ul>
     *   <li><b>PARTITION</b> → {@link StallRecoveryStrategy.ReconnectRecovery}</li>
     *   <li><b>CONSENSUS_SLOW</b> → {@link StallRecoveryStrategy.ResyncRecovery}</li>
     *   <li><b>BYZANTINE</b> → {@link StallRecoveryStrategy.ViewChangeRecovery}</li>
     * </ul>
     *
     * @param event The stall detection event containing diagnostic information
     */
    private void handleStallDetected(StallDetectedEvent event) {
        log.warn("Stall detected: {} empty polls, last height: {}, duration: {} on: {}",
                 event.emptyPollCount(), event.lastProcessedHeight(), event.stallDuration(),
                 params.member().getId());

        // Diagnose root cause
        var cause = stallDiagnostics.diagnose(event);
        log.info("Stall diagnosed as {} on: {}", cause, params.member().getId());

        // Initiate recovery
        var strategy = StallRecoveryStrategy.forCause(cause);
        strategy.recover(this, event, cause);
    }

    /**
     * Creates a checkpoint protobuf from a state file.
     * <p>
     * This static utility method is exposed for test usage and other components
     * that need to create checkpoint metadata without a full CHOAM instance.
     *
     * @param algo        the digest algorithm
     * @param state       the state file to checkpoint
     * @param segmentSize the size of each checkpoint segment
     * @param initial     the initial digest for the HexBloom
     * @param crowns      the number of crowns in the HexBloom
     * @param id          the member ID (for logging)
     * @return the checkpoint protobuf, or null if creation fails
     */
    public static Checkpoint checkpoint(DigestAlgorithm algo, File state, int segmentSize, Digest initial, int crowns,
                                        Digest id) {
        return BlockBuilders.checkpoint(algo, state, segmentSize, initial, crowns, id);
    }

    public static Block genesis(Digest id, Map<Digest, Join> joins, HashedBlock head, HashedBlock lastViewChange,
                                Parameters params, HashedBlock lastCheckpoint, Iterable<Transaction> initialization) {
        return BlockBuilders.genesis(id, joins, head, lastViewChange, params, lastCheckpoint, initialization);
    }

    public static Digest hashOf(Transaction transaction, DigestAlgorithm digestAlgorithm) {
        return BlockBuilders.hashOf(transaction, digestAlgorithm);
    }

    public static String print(Join join, DigestAlgorithm da) {
        return BlockBuilders.print(join, da);
    }

    public static Reconfigure reconfigure(Digest nextViewId, Map<Digest, Join> joins, int checkpointTarget) {
        return BlockBuilders.reconfigure(nextViewId, joins, checkpointTarget);
    }

    public static Block reconfigure(Digest nextViewId, Map<Digest, Join> joins, HashedBlock head,
                                    HashedBlock lastViewChange, Parameters params, HashedBlock lastCheckpoint) {
        return BlockBuilders.reconfigure(nextViewId, joins, head, lastViewChange, params, lastCheckpoint);
    }

    public static List<Transaction> toGenesisData(List<? extends Message> initializationData) {
        return BlockBuilders.toGenesisData(initializationData);
    }

    public static List<Transaction> toGenesisData(List<? extends Message> initializationData,
                                                  DigestAlgorithm digestAlgo, SignatureAlgorithm sigAlgo) {
        return BlockBuilders.toGenesisData(initializationData, digestAlgo, sigAlgo);
    }

    private static Block assembly(AtomicReference<Digest> nextViewId, View view, HashedBlock head,
                                  HashedBlock lastViewChange, Parameters params, HashedBlock lastCheckpoint) {
        return BlockBuilders.assembly(nextViewId, view, head, lastViewChange, params, lastCheckpoint);
    }

    @Override
    public boolean active() {
        final var c = committeeState.getCommittee();
        HashedCertifiedBlock h = blockChainState.getHead();
        return (c != null && h != null && transitions.fsm().getCurrentState() == Mercantile.OPERATIONAL)
        && c instanceof Administration && h.height().compareTo(ULong.valueOf(0)) >= 0;
    }

    @Override
    public DelegatedContext<Member> context() {
        return params.context();
    }

    public Parameters params() {
        return params;
    }

    public NextView getNextView() {
        return (NextView) viewStateHolder.getNext();
    }

    public void setNextViewId(Digest viewId) {
        viewStateHolder.setNextViewId(viewId);
    }

    public ImmutablePendingViews getPendingViews() {
        return viewStateHolder.getPendingViews();
    }

    public void setPendingViews(ImmutablePendingViews views) {
        viewStateHolder.setPendingViews(views);
    }

    public void transitionsNextView() {
        transitions.nextView();
    }

    public void acceptGenesis(HashedCertifiedBlock hb) {
        final var c = blockChainState.getHead();
        blockChainState.setGenesis(c);
        ((CheckpointManagerImpl) checkpointManager).updateCheckpoint(c);
        blockChainState.setView(c);
        process();
    }

    public CommonCommunications<Terminal, ?> getComm() {
        return comm;
    }

    public ScheduledExecutorService getScheduler() {
        return scheduler;
    }

    public BlockChainStateHolder blockChainState() {
        return blockChainState;
    }

    public ControlStateHolder controlState() {
        return controlState;
    }

    public RoundScheduler roundScheduler() {
        return roundScheduler;
    }

    public AsyncOperationStateHolder asyncOperationState() {
        return asyncOperationState;
    }

    public BlockProcessor blockProcessor() {
        return blockProcessor;
    }

    public CommitteeStateHolder committeeState() {
        return committeeState;
    }

    public ViewStateHolder viewStateHolder() {
        return viewStateHolder;
    }

    public CommonCommunications<TxnSubmission, Submitter> submissionComm() {
        return submissionComm;
    }

    public void transitionsBootstrap(HashedCertifiedBlock anchor) {
        transitions.bootstrap(anchor);
    }

    public void transitionsRegenerate() {
        transitions.regenerate();
    }

    @Override
    public ULong currentHeight() {
        final var c = blockChainState.getHead();
        return c == null ? null : c.height();
    }

    @Override
    public Combine.Transitions getCurrentState() {
        return transitions.fsm().getCurrentState();
    }

    @Override
    public Digest getId() {
        return params.member().getId();
    }

    @Override
    public Session getSession() {
        return session;
    }

    @Override
    public Digest getViewId() {
        final var viewChange = blockChainState.getView();
        if (viewChange == null) {
            return null;
        }
        return new Digest(viewChange.block.hasGenesis() ? viewChange.block.getGenesis().getInitialView().getId()
                                                        : viewChange.block.getReconfigure().getId());
    }

    /**
     * Returns the BlockStore for block lookup and hash operations.
     * <p>
     * Required for witness service block binding and recovery.
     *
     * @return the block store instance
     */
    public BlockStore getBlockStore() {
        return store;
    }

    /**
     * Returns the BlockProcessor for block processing operations.
     * <p>
     * Required for witness service log replay during recovery.
     *
     * @return the block processor instance
     */
    public BlockProcessor getBlockProcessor() {
        return blockProcessor;
    }

    /**
     * Returns the CheckpointManager for checkpoint operations.
     * <p>
     * Required for witness service checkpoint-based recovery.
     *
     * @return the checkpoint manager instance
     */
    public CheckpointManager getCheckpointManager() {
        return checkpointManager;
    }

    public String logState() {
        final var c = committeeState.getCommittee();
        HashedCertifiedBlock h = blockChainState.getHead();
        if (c == null) {
            return "No committee on: %s".formatted(params.member().getId());
        }
        if (h.block == null) {
            return "block is null, committee: %s state: %s on: %s  ".formatted(c.getClass().getSimpleName(),
                                                                               transitions.fsm().getCurrentState(),
                                                                               params.member().getId());
        }
        return "block: %s hash: %s height: %s committee: %s state: %s on: %s  ".formatted(h.block.getBodyCase(), h.hash,
                                                                                          h.height(),
                                                                                          c.getClass().getSimpleName(),
                                                                                          transitions.fsm()
                                                                                                     .getCurrentState(),
                                                                                          params.member().getId());
    }

    /**
     * A view change has occurred
     */
    @Override
    public void rotateViewKeys(ViewChange viewChange) {
        var context = viewChange.context();
        var diadem = viewChange.diadem();
        log.trace("Setting BEG Context to: {} on: {}", context, params.member().getId());
        ((DelegatedContext<Member>) combine.getContext()).setContext(context);
        var c = committeeState.getCommittee();
        if (c != null) {
            c.nextView(viewChange.diadem(), context);
        } else {
            log.info("Acquiring new view of: {}, diadem: {} size: {} on: {}", context.getId(), diadem, context.size(),
                     params.member().getId());
            params.context().setContext(context);
            viewStateHolder.setPendingViews(ImmutablePendingViews.EMPTY.add(diadem, context));
        }
    }

    public void start() {
        if (!controlState.start()) {
            return;
        }
        log.info("CHOAM startup: {} majority: {} on: {}", params.context().getId(), params.majority(),
                 params.member().getId());
        combine.start(params.producer().gossipDuration());
        transitions.fsm().enterStartState();
        transitions.start();
    }

    public void stop() {
        if (!controlState.stopWithCAS()) {
            return;
        }
        blockProcessor.stop();
        try {
            scheduler.shutdownNow();
        } catch (Throwable e) {
            // ignore
        }
        session.cancelAll();
        final var c = committeeState.getCommittee();
        if (c != null) {
            try {
                c.complete();
            } catch (Throwable e) {
                // ignore
            }
        }
        try {
            combine.stop();
        } catch (Throwable e) {
            // ignore
        }
    }

    @Override
    public void accept(HashedCertifiedBlock next) {
        blockChainState.setHead(next);
        store.put(next);
        final Committee c = committeeState.getCommittee();
        if (c == null) {
            log.error("No committee to accept block: {} hash: {} height: {} on: {}", next.block.getBodyCase(),
                      next.hash, next.height(), params.member().getId());
            transitions.fail();
            return;
        }
        c.accept(next);
        log.info("Accepted block: {} hash: {} height: {} body: {} on: {}", next.block.getBodyCase(), next.hash,
                 next.height(), next.block.getBodyCase(), params.member().getId());
    }

    private void cancelBootstrap() {
        final CompletableFuture<SynchronizedState> fb = asyncOperationState.getBootstrapFuture();
        if (fb != null) {
            fb.cancel(true);
            asyncOperationState.clearBootstrapFuture();
        }
    }

    public void cancelSynchronization() {
        final ScheduledFuture<?> fs = asyncOperationState.getSyncFuture();
        if (fs != null) {
            fs.cancel(true);
            asyncOperationState.clearSyncFuture();
        }
    }

    private Block checkpoint() {
        return checkpointBlockBuilder.createCheckpoint();
    }

    private void combine(List<Msg> messages) {
        messages.forEach(this::combine);
        transitions.combine();
    }

    private void combine(Msg m) {
        CertifiedBlock block;
        try {
            block = CertifiedBlock.parseFrom(m.content());
        } catch (InvalidProtocolBufferException e) {
            log.debug("unable to parse block content from {} on: {}", m.source(), params.member().getId());
            return;
        }
        HashedCertifiedBlock hcb = new HashedCertifiedBlock(params.digestAlgorithm(), block);
        log.trace("Received block: {} hash: {} height: {} from {} on: {}", hcb.block.getBodyCase(), hcb.hash,
                  hcb.height(), m.source(), params.member().getId());
        if (!blockChainState.addPending(hcb)) {
            log.warn("Rejected pending block: {} hash: {} height: {} on: {}", hcb.block.getBodyCase(), hcb.hash,
                     hcb.height(), params.member().getId());
        }
    }

    public BlockProducer constructBlock() {
        return blockProducer;
    }

    private void consume(HashedCertifiedBlock next) {
        headLock.writeLock().lock();
        try {
            blockConsumer.consume(next, this::accept, this::isNext);
        } finally {
            headLock.writeLock().unlock();
        }
    }

    private void consume(HashedCertifiedBlock next, HashedCertifiedBlock cur) {
        blockConsumer.consumeWithValidation(next, cur, this::accept, this::isNext);
    }


    private void execute(List<Transaction> execs) {
        final var h = blockChainState.getHead();
        log.info("Executing transactions for block: {} hash: {} height: {} txns: {} on: {}", h.block.getBodyCase(),
                 h.hash, h.height(), execs.size(), params.member().getId());
        for (int i = 0; i < execs.size(); i++) {
            var exec = execs.get(i);
            Digest hash = hashOf(exec, params.digestAlgorithm());
            var stxn = session.complete(hash);
            try {
                params.processor()
                      .execute(i, CHOAM.hashOf(exec, params.digestAlgorithm()), exec,
                               stxn == null ? null : stxn.onCompletion());
            } catch (Throwable t) {
                log.error("Exception processing transaction: {} block: {} height: {} on: {}", hash, h.hash, h.height(),
                          params.member().getId());
            }
        }
    }

    public CheckpointSegments fetch(CheckpointReplication request) {
        CheckpointState state = checkpointManager.getCheckpointState(ULong.valueOf(request.getCheckpoint()));
        if (state == null) {
            log.info("No cached checkpoint for {} on: {}", request.getCheckpoint(), params.member().getId());
            return CheckpointSegments.getDefaultInstance();
        }

        return CheckpointSegments.newBuilder()
                                 .addAllSegments(state.fetchSegments(BloomFilter.from(request.getCheckpointSegments()),
                                                                     params.maxCheckpointSegments()))
                                 .build();
    }

    public Blocks fetchBlocks(BlockReplication rep) {
        BloomFilter<ULong> bff = BloomFilter.from(rep.getBlocksBff());
        Blocks.Builder blocks = Blocks.newBuilder();
        store.fetchBlocks(bff, blocks, 100, ULong.valueOf(rep.getFrom()), ULong.valueOf(rep.getTo()));
        return blocks.build();
    }

    public Blocks fetchViewChain(BlockReplication rep) {
        BloomFilter<ULong> bff = BloomFilter.from(rep.getBlocksBff());
        Blocks.Builder blocks = Blocks.newBuilder();
        store.fetchViewChain(bff, blocks, 100, ULong.valueOf(rep.getFrom()), ULong.valueOf(rep.getTo()));
        return blocks.build();
    }

    private void genesisInitialization(final HashedBlock h, final List<Transaction> initialization) {
        log.info("Executing genesis initialization block: {} on: {}", h.hash, params.member().getId());
        try {
            params.processor().genesis(h.hash, initialization);
        } catch (Throwable t) {
            log.error("Exception processing genesis initialization block: {} on: {}", h.hash, params.member().getId(),
                      t);
        }
    }

    public String getLabel() {
        return "CHOAM" + params.member().getId() + params.context().getId();
    }

    private boolean isNext(HashedBlock next) {
        final var h = blockChainState.getHead();
        if (h.height() == null) {
            // Head is NullBlock - only genesis (height 0) is valid
            return next.height().equals(ULong.valueOf(0));
        }
        return next.height().equals(h.height().add(1));
    }

    public void join(SignedViewMember nextView, Digest from) {
        var c = committeeState.getCommittee();
        if (c == null) {
            log.trace("No committee for: {} to join: {} diadem: {} on: {}", from,
                      Digest.from(nextView.getVm().getView()), Digest.from(nextView.getVm().getDiadem()),
                      params.member().getId());
            throw new StatusRuntimeException(FAILED_PRECONDITION);
        }
        c.join(nextView, from);
    }

    public Supplier<PendingViews> pendingViews() {
        return () -> new PendingViews(viewStateHolder.getPendingViews());
    }

    public void process() {
        blockDispatcher.processBlock();
    }

    /**
     * Collect callbacks for two-phase reconfiguration (Phase 3A.2 pattern).
     *
     * Callback Sequence (STRICT ORDER - do NOT reorder):
     * 1. Complete old committee (CB1) - Stops producer threads
     * 2. Rotate view keys (CB2) - Updates cryptographic state
     * 3. Update view state (CB3) - Changes session context
     * 4. Transition to new committee (CB4) - Creates Associate or Client
     * 5. Log completion (CB5) - Marks join as complete
     *
     * IMPORTANT: Callbacks are NOT atomic. Partial execution is possible:
     * - CB1 can fail while CB2-5 succeed (old producer partially stopped)
     * - CB4 can fail while CB1-3 succeed (new committee not created, old stopped)
     * - If CB4 fails, FSM enters PROTOCOL_FAILURE state (detected by callers)
     *
     * Byzantine Safety: All nodes execute callbacks in same order, so divergence
     * occurs only if specific callback throws (deterministic on all nodes).
     *
     * Recovery: The FSM (Combiner) handles intermediate states via transitions.fail()
     * called from CB4 on creation failures.
     *
     * Phase 3A.2 Reference: Two-phase execution pattern
     * - Phase 1 (locked): This method runs under viewStateLock
     * - Phase 2 (unlocked): reconfigure() executes these callbacks outside lock
     *
     * @param hash View change hash (Reconfigure protobuf)
     * @param reconfigure Reconfiguration metadata
     * @return List of callbacks to execute in order (outside lock)
     */

    /**
     * Check if memory pressure is too high for safe reconfiguration.
     * Delegates to ReconfigurationCoordinator.
     *
     * @return true if heap usage exceeds (1.0 - minFreeMemoryRatio), false otherwise
     */
    public boolean isMemoryPressureHigh() {
        return reconfigCoordinator.isMemoryPressureHigh();
    }

    /**
     * Execute view reconfiguration.
     * Delegates to ReconfigurationCoordinator.
     *
     * @param hash        view identifier hash
     * @param reconfigure reconfiguration proto
     */
    private void reconfigure(Digest hash, Reconfigure reconfigure) {
        reconfigCoordinator.reconfigure(hash, reconfigure);
    }

    @Override
    public void recover(HashedCertifiedBlock anchor) {
        cancelBootstrap();
        log.info("Recovering from: {} height: {} on: {}", anchor.hash, anchor.height(), params.member().getId());
        cancelSynchronization();
        cancelBootstrap();
        asyncOperationState.getFutureBootstrapRef().set(
        new Bootstrapper(anchor, params, store, comm, scheduler).synchronize().whenComplete((s, t) -> {
            if (t == null) {
                try {
                    synchronize(s);
                } catch (Throwable e) {
                    log.error("Cannot synchronize on: {}", params.member().getId(), e);
                    transitions.fail();
                }
            } else {
                log.error("Synchronization failed on: {}", params.member().getId(), t);
                transitions.fail();
            }
        }));
    }

    @Override
    public void restore() throws IllegalStateException {
        stateRestorer.restore();
    }

    private void restoreFrom(HashedCertifiedBlock block, CheckpointState checkpoint) {
        checkpointManager.restoreFromCheckpoint(block, checkpoint);
        restore();
    }

    public void rotateViewKeys() {
        //        if (committeeState.getCommittee() != null && !(committeeState.getCommittee() instanceof Associate)) {
        //            log.info("rotate view calls on: {}", params.member().getId(), new Exception("Rotate view keys"));
        //        }
        KeyPair keyPair = params.viewSigAlgorithm().generateKeyPair();
        PubKey pubKey = bs(keyPair.getPublic());
        JohnHancock signed = params.member().sign(pubKey.toByteString());
        if (signed == null) {
            log.error("Unable to generate and sign consensus key on: {}", params.member().getId());
            return;
        }
        var committee = committeeState.getCommittee();
        log.trace("Generated next view consensus key: {} sig: {} committee: {} on: {}",
                  params.digestAlgorithm().digest(pubKey.getEncoded()),
                  params.digestAlgorithm().digest(signed.toSig().toByteString()),
                  committee == null ? "<no formation>" : committee.getClass().getSimpleName(), params.member().getId());
        viewStateHolder.setNext(new NextView(ViewMember.newBuilder()
                                        .setId(params.member().getId().toDigeste())
                                        .setConsensusKey(pubKey)
                                        .setSignature(signed.toSig())
                                        .build(), keyPair));
    }

    private Function<SubmittedTransaction, SubmitResult> service() {
        return stx -> {
            //            log.trace("Submitting transaction: {} in service() on: {}", stx.hash(), params.member());
            final var c = committeeState.getCommittee();
            if (c == null) {
                return SubmitResult.newBuilder().setResult(Result.NO_COMMITTEE).build();
            }
            try {
                return c.submitTxn(stx.transaction());
            } catch (StatusRuntimeException e) {
                return SubmitResult.newBuilder()
                                   .setResult(Result.ERROR_SUBMITTING)
                                   .setErrorMsg(e.getStatus().toString())
                                   .build();
            }
        };
    }

    private Digest signatureHash(ByteString any) {
        CertifiedBlock cb;
        try {
            cb = CertifiedBlock.parseFrom(any);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
        return cb.getCertificationsList()
                 .stream()
                 .map(cert -> JohnHancock.from(cert.getSignature()))
                 .map(sig -> sig.toDigest(params.digestAlgorithm()))
                 .reduce(Digest.from(cb.getBlock().getHeader().getBodyHash()), Digest::xor);
    }

    /**
     * Submit a transaction from a client
     *
     * @return the SubmitResult describing the outcome
     */
    SubmitResult submit(Transaction request, Digest from) {
        if (from == null) {
            return SubmitResult.getDefaultInstance();
        }
        if (params.context().getMember(from) == null) {
            log.debug("Invalid transaction submission from non member: {} on: {}", from, params.member().getId());
            return SubmitResult.newBuilder().setResult(Result.INVALID_SUBMIT).build();
        }
        final var c = committeeState.getCommittee();
        if (c == null) {
            log.debug("No committee to submit txn from: {} on: {}", from, params.member().getId());
            return SubmitResult.newBuilder().setResult(Result.NO_COMMITTEE).build();
        }
        return c.submit(request);
    }

    public Initial sync(Synchronize request, Digest from) {
        return recoveryCoordinator.buildSyncState(request, from);
    }

    private void synchronize(SynchronizedState state) {
        recoveryCoordinator.applySyncState(state);
    }

    private void synchronizedProcess(CertifiedBlock certifiedBlock) {
        syncValidator.processSynchronizedBlock(certifiedBlock);
    }


    /**
     * Capture current CHOAM state snapshot for validation.
     * Delegates to StateSnapshotCapture.
     *
     * @return Immutable snapshot of current state
     */
    private com.hellblazer.delos.choam.support.CHOAMStateSnapshot captureStateSnapshot() {
        return stateSnapshotCapture.captureStateSnapshot();
    }

}
