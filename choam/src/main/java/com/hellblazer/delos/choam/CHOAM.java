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
    private final    TransSubmission                                       txnSubmission         = new TransSubmission();
    private final    ScheduledExecutorService                              scheduler;
    private final    com.chiralbehaviors.tron.Fsm<Combine, Combine.Transitions> fsm;
    private final    ByzantineDetectionMapper                             byzantineMapper;
    private final    StallDiagnostics                                     stallDiagnostics;
    private final    BlockConsumer                                        blockConsumer;
    private final    SynchronizedBlockValidator                          syncValidator;
    private final    BlockProducerImpl                                   blockProducer;
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
                this::captureStateSnapshot
            );
            log.info("State machine validation ENABLED on: {}", params.member().getId());
        } else {
            this.transitions = rawTransitions;
        }
        this.blockConsumer = new BlockConsumer(blockChainState, committeeState, params.runtime(), transitions, log);
        this.syncValidator = new SynchronizedBlockValidator(controlState, blockChainState, committeeState, params.runtime(), transitions, log, params.digestAlgorithm());
        this.blockProducer = new BlockProducerImpl(params, blockChainState, checkpointManager, combine, transitions, log, this::checkpoint);
        roundScheduler = new RoundScheduler("CHOAM" + params.member().getId() + params.context().getId(),
                                            params.context().timeToLive());
        combine.register(_ -> roundScheduler.tick());
        session = new Session(params, service(), scheduler);
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

    public nextView getNextView() {
        return (nextView) viewStateHolder.getNext();
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
        transitions.beginCheckpoint();
        HashedBlock lb = blockChainState.getHead();
        File state = params.checkpointer().apply(lb.height());
        if (state == null) {
            log.error("Cannot create checkpoint on: {}", params.member().getId());
            transitions.fail();
            return null;
        }
        final HashedBlock c = checkpointManager.currentCheckpoint();
        final ULong newHeight = lb.height().add(1);

        // Use consolidated checkpoint manager for creation and caching
        Checkpoint cp = checkpointManager.createCheckpointAndGet(newHeight, state);
        if (cp == null) {
            transitions.fail();
            return null;
        }

        final HashedCertifiedBlock v = blockChainState.getView();
        final Block block = Block.newBuilder()
                                 .setHeader(
                                 buildHeader(params.digestAlgorithm(), cp, lb.hash, newHeight, c.height(),
                                             c.hash, v.height(), v.hash))
                                 .setCheckpoint(cp)
                                 .build();

        HashedBlock hb = new HashedBlock(params.digestAlgorithm(), block);
        log.info("Created checkpoint: {} height: {} on: {}", hb.hash, hb.height(), params.member().getId());
        transitions.finishCheckpoint();
        return block;
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
        final var c = committeeState.getCommittee();
        final HashedCertifiedBlock h = blockChainState.getHead();
        log.info("Begin block: {} hash: {} height: {} committee: {} on: {}", h.block.getBodyCase(), h.hash, h.height(),
                 c.getClass().getSimpleName(), params.member().getId());
        switch (h.block.getBodyCase()) {
        case RECONFIGURE: {
            params.processor().beginBlock(h.height(), h.hash);
            reconfigure(h.hash, h.block.getReconfigure());
            break;
        }
        case GENESIS: {
            cancelSynchronization();
            cancelBootstrap();
            genesisInitialization(h, h.block.getGenesis().getInitializeList());
            reconfigure(h.hash, h.block.getGenesis().getInitialView());
            break;
        }
        case ASSEMBLE: {
            params.processor().beginBlock(h.height(), h.hash);
            c.assemble(h.block.getAssemble());
            break;
        }
        case EXECUTIONS: {
            params.processor().beginBlock(h.height(), h.hash);
            execute(h.block.getExecutions().getExecutionsList());
            break;
        }
        case CHECKPOINT: {
            params.processor().beginBlock(h.height(), h.hash);
            var lastCheckpoint = checkpointManager.currentCheckpoint().height();
            ((CheckpointManagerImpl) checkpointManager).updateCheckpoint(h);
            store.gcFrom(h.height(), lastCheckpoint.add(1));
        }
        default:
            break;
        }
        params.processor().endBlock(h.height(), h.hash);
        log.info("End block: {} hash: {} height: {} on: {}", h.block.getBodyCase(), h.hash, h.height(),
                 params.member().getId());
    }

    public boolean validate(HashedCertifiedBlock hb, Map<Member, Verifier> validators) {
        if (hb == null || validators == null) {
            return false;
        }
        var certifications = hb.certifiedBlock.getCertificationsList();
        var required = validators.size() / 2 + 1; // Simple majority
        var valid = certifications.stream()
                                   .filter(cert -> {
                                       var validator = validators.get(params.context().getMember(Digest.from(cert.getId())));
                                       return validator != null && validator.verify(JohnHancock.from(cert.getSignature()), hb.block.toByteString());
                                   })
                                   .limit(required)
                                   .count();
        return valid >= required;
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
    private List<Runnable> collectReconfigureCallbacks(Digest hash, Reconfigure reconfigure) {
        List<Runnable> callbacks = new ArrayList<>();

        // Capture old committee reference for later cleanup
        // NOTE: oldCommittee captured here - remains valid even if current is modified
        final Committee oldCommittee = committeeState.getCommittee();

        // Determine which committee type to create and the associated setup
        var validators = validatorsOf(reconfigure, params.context(), params.member().getId(), log);
        final HashedCertifiedBlock h = blockChainState.getHead();
        final var currentView = viewStateHolder.getNext();

        // Callback 1: Rotate view keys
        callbacks.add(() -> {
            log.trace("Rotating view keys on: {}", params.member().getId());
            transitions.rotateViewKeys();
        });

        // Callback 2: Update view state and session
        callbacks.add(() -> {
            log.trace("Updating view state on: {}", params.member().getId());
            blockChainState.setView(h);
            session.setView(h);
        });

        // Callback 3: Atomic committee transition (create new, swap, stop old)
        // CRITICAL: This callback must be atomic to avoid race conditions:
        // 1. Create new committee (starts producer)
        // 2. Atomically swap current reference
        // 3. Immediately stop old committee
        // This ensures no window where current points to a stopped producer (NO_COMMITTEE),
        // while minimizing the window where both producers are running.
        callbacks.add(() -> {
            log.trace("Transitioning committee on: {}", params.member().getId());
            Committee newCommittee = null;

            // Step 1: Create new committee (may throw, old committee remains active if this fails)
            try {
                if (validators.containsKey(params.member())) {
                    if (Dag.validate(validators.size())) {
                        newCommittee = new Associate(h, validators, (nextView) currentView);
                    } else {
                        log.warn("Reconfiguration to associate failed: {} committee: {} in view: {} on:{}",
                                 validators.size(), hash, committeeState.getCommittee().getClass().getSimpleName(),
                                 params.member().getId());
                        transitions.fail();
                        return; // Keep old committee active
                    }
                } else {
                    newCommittee = new Client(validators, getViewId());
                }
            } catch (Throwable e) {
                log.error("Failed to create new committee on: {}", params.member().getId(), e);
                transitions.fail();
                return; // Keep old committee active
            }

            // Step 2: Atomic swap - new committee now handles all transactions
            committeeState.setCommittee(newCommittee);

            // Step 3: Stop old committee immediately after swap
            // Note: oldCommittee can be null during recovery/startup
            if (oldCommittee != null) {
                try {
                    oldCommittee.complete();
                } catch (Throwable e) {
                    log.error("Failed to complete old committee on: {}", params.member().getId(), e);
                    // Continue - new committee is already active and handling transactions
                }
            } else {
                log.debug("No old committee to complete (recovery scenario) on: {}", params.member().getId());
            }
        });

        // Callback 5: Log completion
        callbacks.add(() -> {
            if (controlState.endJoinWithCAS()) {
                log.trace("Halting ongoing join on: {}", params.member().getId());
            }
            log.info("Reconfigured to view: {} committee: {} validators: {} on: {}",
                     hash, committeeState.getCommittee().getClass().getSimpleName(),
                     validators.entrySet().stream()
                                .map(e -> String.format("id: %s key: %s",
                                                        e.getKey().getId(),
                                                        params.digestAlgorithm()
                                                              .digest(e.toString())))
                                .toList(),
                     params.member().getId());
        });

        return callbacks;
    }

    /**
     * Check if memory pressure is too high for safe reconfiguration.
     * Uses MemoryMXBean to check heap usage against configured threshold.
     *
     * @return true if heap usage exceeds (1.0 - minFreeMemoryRatio), false otherwise
     */
    public boolean isMemoryPressureHigh() {
        var heapUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var usedRatio = (double) heapUsage.getUsed() / heapUsage.getMax();
        var threshold = 1.0 - params.minFreeMemoryRatio();

        if (usedRatio > threshold) {
            log.warn("Memory pressure detected: {}% heap used (threshold: {}%) on: {}",
                     String.format("%.1f", usedRatio * 100), String.format("%.1f", threshold * 100),
                     params.member().getId());
            return true;
        }
        return false;
    }

    private void reconfigure(Digest hash, Reconfigure reconfigure) {
        // Check memory pressure before proceeding with reconfiguration
        if (isMemoryPressureHigh()) {
            log.error("Rejecting reconfiguration due to high memory pressure on: {}", params.member().getId());
            throw new IllegalStateException("Memory pressure too high for reconfiguration - heap usage exceeds threshold");
        }

        // Phase 1 (locked): Collect callbacks for deterministic computation
        List<Runnable> callbacks;
        viewStateLock.lock();
        try {
            log.info("Setting next view id: {} on: {}", hash, params.member().getId());
            viewStateHolder.setNextViewId(hash);

            // Update pending views
            var advanced = viewStateHolder.getPendingViews().advance();
            viewStateHolder.setPendingViews(advanced);
            var pv = advanced.last();
            if (pv != null) {
                params.context().setContext(pv.context());
            }

            // Collect callbacks for execution outside lock
            callbacks = collectReconfigureCallbacks(hash, reconfigure);
        } finally {
            viewStateLock.unlock();
        }

        // Phase 2 (unlocked): Execute collected callbacks
        // This allows callbacks to acquire other locks without reentrancy risks
        log.debug("Executing reconfigure callbacks on: {}", params.member().getId());
        for (int i = 0; i < callbacks.size(); i++) {
            try {
                callbacks.get(i).run();
            } catch (Throwable t) {
                if (t instanceof Error) {
                    log.error("Fatal error in callback {} during reconfigure on: {}: {}", i, params.member().getId(),
                              t.getMessage(), t);
                    throw t; // Fail-fast on Error types (OOM, StackOverflow, etc.)
                }
                log.error("Callback {} execution failed during reconfigure on: {}", i, params.member().getId(), t);
                // Continue with remaining callbacks for non-fatal exceptions
            }
        }
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
        HashedCertifiedBlock lastBlock = store.getLastBlock();
        if (lastBlock == null) {
            log.info("No state to restore from on: {}", params.member().getId());
            return;
        }
        HashedCertifiedBlock geni = new HashedCertifiedBlock(params.digestAlgorithm(),
                                                             store.getCertifiedBlock(ULong.valueOf(0)));
        blockChainState.setGenesis(geni);
        blockChainState.setHead(geni);
        ((CheckpointManagerImpl) checkpointManager).updateCheckpoint(geni);
        CertifiedBlock lastCheckpoint = store.getCertifiedBlock(
        ULong.valueOf(lastBlock.block.getHeader().getLastCheckpoint()));
        if (lastCheckpoint != null) {
            HashedCertifiedBlock ckpt = new HashedCertifiedBlock(params.digestAlgorithm(), lastCheckpoint);
            ((CheckpointManagerImpl) checkpointManager).updateCheckpoint(ckpt);
            blockChainState.setHead(ckpt);
            HashedCertifiedBlock lastView = new HashedCertifiedBlock(params.digestAlgorithm(), store.getCertifiedBlock(
            ULong.valueOf(ckpt.block.getHeader().getLastReconfig())));
            Reconfigure reconfigure = lastView.block.hasGenesis() ? lastView.block.getGenesis().getInitialView()
                                                                  : lastView.block.getReconfigure();
            blockChainState.setView(lastView);
            var validators = validatorsOf(reconfigure, params.context(), params.member().getId(), log);
            committeeState.setCommittee(new CommitteeSynchronizer(this, validators, log));
            log.info("Reconfigured to checkpoint view: {} committee: {} on: {}", new Digest(reconfigure.getId()),
                     committeeState.getCommittee().getClass().getSimpleName(), params.member().getId());
        }

        log.info("Restored to: {} lastView: {} lastCheckpoint: {} lastBlock: {} on: {}", geni.hash, blockChainState.getView().hash,
                 checkpointManager.currentCheckpoint().hash, lastBlock.hash, params.member().getId());
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
        viewStateHolder.setNext(new nextView(ViewMember.newBuilder()
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
    private SubmitResult submit(Transaction request, Digest from) {
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
        final HashedCertifiedBlock g = blockChainState.getGenesis();
        if (g != null) {
            Initial.Builder initial = Initial.newBuilder();
            initial.setGenesis(g.certifiedBlock);
            HashedCertifiedBlock cp = checkpointManager.currentCheckpoint();
            if (cp != null) {
                ULong height = ULong.valueOf(request.getHeight());

                while (cp.height().compareTo(height) > 0) {
                    cp = new HashedCertifiedBlock(params.digestAlgorithm(), store.getCertifiedBlock(
                    ULong.valueOf(cp.block.getHeader().getLastCheckpoint())));
                }
                final ULong lastReconfig = ULong.valueOf(cp.block.getHeader().getLastReconfig());
                HashedCertifiedBlock lastView = null;

                var stored = store.getCertifiedBlock(lastReconfig);
                if (stored != null) {
                    lastView = new HashedCertifiedBlock(params.digestAlgorithm(), stored);
                }
                if (lastView == null) {
                    lastView = g;
                }
                initial.setCheckpoint(cp.certifiedBlock).setCheckpointView(lastView.certifiedBlock);

                log.debug("Returning sync: {} view: {} chkpt: {} to: {} on: {}", g.hash, lastView.hash, cp.hash, from,
                          params.member().getId());
            } else {
                log.debug("Returning sync: {} to: {} on: {}", g.hash, from, params.member().getId());
            }
            return initial.build();
        } else {
            log.debug("Genesis undefined, returning null sync to: {} on: {}", from, params.member().getId());
            return Initial.getDefaultInstance();
        }
    }

    private void synchronize(SynchronizedState state) {
        transitions.synchronizing();
        CertifiedBlock current1;
        if (state.lastCheckpoint() == null) {
            log.info("Synchronizing from genesis: {} on: {}", state.genesis().hash, params.member().getId());
            current1 = state.genesis().certifiedBlock;
        } else {
            log.info("Synchronizing from checkpoint: {} on: {}", state.lastCheckpoint().hash, params.member().getId());
            assert state.checkpoint() != null : "checkpoint is null";
            restoreFrom(state.lastCheckpoint(), state.checkpoint());
            current1 = store.getCertifiedBlock(state.lastCheckpoint().height().add(1));
        }
        while (current1 != null) {
            synchronizedProcess(current1);
            current1 = store.getCertifiedBlock(height(current1.getBlock()).add(1));
        }
        log.info("Synchronized, resuming view: {} deferred blocks: {} on: {}",
                 state.lastCheckpoint() != null ? state.lastCheckpoint().hash : state.genesis().hash, blockChainState.getPendingSize(),
                 params.member().getId());
        Thread.ofVirtual().start(Utils.wrapped(() -> {
            if (!controlState.isStarted()) {
                return;
            }
            transitions.synchd();
            transitions.combine();
        }, log));
    }

    private void synchronizedProcess(CertifiedBlock certifiedBlock) {
        syncValidator.processSynchronizedBlock(certifiedBlock);
    }

    public interface BlockProducer {
        Block checkpoint();

        Block genesis(Map<Digest, Join> joining, Digest nextViewId, HashedBlock previous);

        void onFailure();

        Block produce(ULong height, Digest prev, Assemble assemble, HashedBlock checkpoint);

        Block produce(ULong height, Digest prev, Executions executions, HashedBlock checkpoint);

        void publish(Digest hash, CertifiedBlock cb, boolean beacon);

        Block reconfigure(Map<Digest, Join> joining, Digest nextViewId, HashedBlock previous, HashedBlock checkpoint);
    }

    @FunctionalInterface
    public interface TransactionExecutor {
        default void beginBlock(ULong height, Digest hash) {
        }

        default void endBlock(ULong height, Digest hash) {
        }

        @SuppressWarnings("rawtypes")
        void execute(int index, Digest hash, Transaction tx, CompletableFuture onComplete);

        default void genesis(Digest hash, List<Transaction> initialization) {
        }
    }

    /**
     * Lightweight wrapper for ImmutablePendingViews that maintains API compatibility.
     *
     * KEY INSIGHT: This class has NO internal locks. It's just a read-only view of
     * an ImmutablePendingViews instance. Thread safety is provided by immutability.
     *
     * This wrapper exists solely to maintain the existing API for ViewContext and other
     * consumers that expect CHOAM.PendingViews type.
     */
    public static class PendingViews {
        private final ImmutablePendingViews delegate;

        public PendingViews(ImmutablePendingViews delegate) {
            this.delegate = delegate;
        }

        public PendingView get(Digest diadem) {
            var immutablePv = delegate.get(diadem);
            return immutablePv == null ? null : new PendingView(immutablePv.diadem(), immutablePv.context());
        }

        public Views.Builder getViews(Digest hash) {
            return delegate.getViews(hash);
        }

        public PendingView last() {
            var immutablePv = delegate.last();
            return immutablePv == null ? null : new PendingView(immutablePv.diadem(), immutablePv.context());
        }
    }

    public record PendingView(Digest diadem, Context<Member> context) {
        /**
         * Answer the view created by finding the successors of the supplied hash on this Context
         *
         * @param hash - the "cut" across the rings of the context, determining the successors and thus the committee
         *             members of the view
         * @return the Vue determined by this Context and the supplied hash value
         */
        public View getView(Digest hash) {
            var builder = View.newBuilder().setDiadem(diadem.toDigeste()).setMajority(context.majority());
            ((Context<? super Member>) context).bftSubset(hash)
                                               .forEach(d -> builder.addCommittee(d.getId().toDigeste()));
            return builder.build();
        }
    }

    public record nextView(ViewMember member, KeyPair consensusKeyPair) {
    }

    /** abstract class to maintain the common state */
    private abstract class Administration implements Committee {
        protected final Digest                viewId;
        private final   GroupIterator         servers;
        private final   Map<Member, Verifier> validators;

        public Administration(Map<Member, Verifier> validators, Digest viewId) {
            this.validators = validators;
            this.viewId = viewId;
            servers = new GroupIterator(validators.keySet());
        }

        @Override
        public void accept(HashedCertifiedBlock hb) {
            process();
        }

        @Override
        public void assemble(Assemble assemble) {
            var mid = params.member().getId();
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
            return validators.containsKey(params.member());
        }

        @Override
        public Logger log() {
            return log;
        }

        @Override
        public void nextView(Digest diadem, Context<Member> pendingView) {
            viewStateHolder.setPendingViews(viewStateHolder.getPendingViews().add(diadem, pendingView));
            log.info("Pending context for view: {} size: {} on: {}",
                     viewStateHolder.getNextViewId() == null ? "<null>" : viewStateHolder.getNextViewId(), pendingView.size(),
                     params.member().getId());
        }

        @Override
        public Parameters params() {
            return params;
        }

        @Override
        public SubmitResult submitTxn(Transaction transaction) {
            if (!controlState.isStarted()) {
                log.trace("Failed submitting txn: {} no servers available in: {} on: {}",
                          hashOf(transaction, params.digestAlgorithm()), viewId, params.member().getId());
                return SubmitResult.newBuilder().setResult(Result.ERROR_SUBMITTING).setErrorMsg("Shutdown").build();
            }
            if (!servers.hasNext()) {
                log.trace("Failed submitting txn: {} no servers available in: {} on: {}",
                          hashOf(transaction, params.digestAlgorithm()), viewId, params.member().getId());
                return SubmitResult.newBuilder()
                                   .setResult(Result.ERROR_SUBMITTING)
                                   .setErrorMsg("no servers available")
                                   .build();
            }
            Member target = servers.next();
            try (var link = submissionComm.connect(target)) {
                if (link == null) {
                    log.debug("No link for: {} for submitting txn on: {}", target.getId(), params.member().getId());
                    return SubmitResult.newBuilder().setResult(Result.UNAVAILABLE).build();
                }
                log.trace("Submitting txn: {} to: {} in view: {} on: {}", hashOf(transaction, params.digestAlgorithm()),
                          link.getMember().getId(), viewId, params.member().getId());
                return link.submit(transaction);
            } catch (StatusRuntimeException e) {
                log.trace("Failed submitting txn: {} status:{} to: {} in: {} on: {}",
                          hashOf(transaction, params.digestAlgorithm()), e.getStatus(), target.getId(), viewId,
                          params.member().getId());
                return SubmitResult.newBuilder()
                                   .setResult(Result.ERROR_SUBMITTING)
                                   .setErrorMsg(e.getStatus().toString())
                                   .build();
            } catch (Throwable e) {
                log.debug("Failed submitting txn: {} to: {} in: {} on: {}",
                          hashOf(transaction, params.digestAlgorithm()), target.getId(), viewId,
                          params.member().getId(), e);
                return SubmitResult.newBuilder().setResult(Result.ERROR_SUBMITTING).setErrorMsg(e.toString()).build();
            }
        }

        @Override
        public boolean validate(HashedCertifiedBlock hb) {
            return validate(hb, validators);
        }

        private void join(View view) {
            if (!controlState.beginJoin()) {
                throw new IllegalStateException("Ongoing join should have been cancelled");
            }
            log.trace("Joining view: {} diadem: {} on: {}", viewStateHolder.getNextViewId(), Digest.from(view.getDiadem()),
                      params.member().getId());
            var servers = new ConcurrentSkipListSet<>(validators.keySet());
            var joined = new AtomicInteger();
            log.trace("Starting join of: {} diadem {} on: {}", viewStateHolder.getNextViewId(), Digest.from(view.getDiadem()),
                      params.member().getId());
            var scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
            AtomicReference<Runnable> action = new AtomicReference<>();
            var attempts = new AtomicInteger();
            action.set(() -> {
                log.trace("Join attempt: {} ongoing: {} joined: {} majority: {} on: {}", attempts.incrementAndGet(),
                          controlState.isJoinOngoing(), joined.get(), view.getMajority(), params.member().getId());
                if (controlState.isJoinOngoing() & joined.get() < view.getMajority()) {
                    join(view, servers, joined);
                    if (joined.get() >= view.getMajority()) {
                        controlState.endJoin();
                        log.trace("Finished join of: {} diadem: {} joins: {} on: {}", viewStateHolder.getNextViewId(),
                                  Digest.from(view.getDiadem()), joined.get(), params.member().getId());
                    } else if (controlState.isJoinOngoing()) {
                        log.trace("Rescheduling join of: {} diadem: {} joins: {} on: {}", viewStateHolder.getNextViewId(),
                                  Digest.from(view.getDiadem()), joined.get(), params.member().getId());
                        scheduler.schedule(action.get(), 50, TimeUnit.MILLISECONDS);
                    }
                }
            });
            scheduler.schedule(action.get(), 50, TimeUnit.MILLISECONDS);
        }

        private void join(View view, Collection<Member> members, AtomicInteger joined) {
            var sampled = new ArrayList<>(members);
            Collections.shuffle(sampled);
            log.trace("Joining view: {} diadem: {} servers: {} on: {}", viewId, Digest.from(view.getDiadem()),
                      sampled.stream().map(Member::getId).toList(), params.member().getId());
            final var c = (nextView) viewStateHolder.getNext();
            var inView = ViewMember.newBuilder(c.member)
                                   .setDiadem(view.getDiadem())
                                   .setView(viewStateHolder.getNextViewId().toDigeste())
                                   .build();
            var svm = SignedViewMember.newBuilder()
                                      .setVm(inView)
                                      .setSignature(params.member().sign(inView.toByteString()).toSig())
                                      .build();
            var countdown = new CountDownLatch(sampled.size());
            sampled.stream().map(m -> {
                var connection = comm.connect(m);
                log.trace("connect to: {} is: {} on: {}", m.getId(), connection, params.member().getId());
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
                                      params.member().getId());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (ExecutionException e) {
                            log.error("Failed to join with: {} view: {} diadem: {} on: {}", t.m.getId(), viewId,
                                      Digest.from(view.getDiadem()), params.member().getId(), e.getCause());
                        } catch (Throwable e) {
                            log.error("Failed to join with: {} view: {} diadem: {} on: {}", t.m.getId(), viewId,
                                      Digest.from(view.getDiadem()), params.member().getId(), e);
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
                          context().getId(), Digest.from(view.getDiadem()), params.member().getId());
                return new Attempt(t.getMember(), t.join(svm));
            } catch (StatusRuntimeException sre) {
                log.trace("Failed join attempt: {} with: {} view: {} diadem: {} on: {}", sre.getStatus(),
                          t.getMember().getId(), viewStateHolder.getNextViewId(), Digest.from(view.getDiadem()), params.member().getId(),
                          sre);
            } catch (Throwable throwable) {
                log.error("Failed join attempt with: {} view: {} diadem: {} on: {}", t.getMember().getId(), viewStateHolder.getNextViewId(),
                          Digest.from(view.getDiadem()), params.member().getId(), throwable);
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

    /**
     * Capture current CHOAM state snapshot for validation.
     * Called by ValidatingCombineTransitions to obtain pre/post snapshots.
     *
     * @return Immutable snapshot of current state
     */
    private com.hellblazer.delos.choam.support.CHOAMStateSnapshot captureStateSnapshot() {
        // Capture from StateHolders (lock-free atomic reads)
        var started = controlState.isStarted();
        var joinOngoing = controlState.isJoinOngoing();

        var committee = committeeState.getCommittee();
        var hasCommittee = committee != null;
        var committeeType = committee == null ? null :
            (committee instanceof com.hellblazer.delos.choam.support.GenesisFormation ? "GenesisFormation" : "Standard");

        var head = blockChainState.getHead();
        var hasGenesis = head != null && !(head instanceof com.hellblazer.delos.choam.support.HashedCertifiedBlock.NullBlock);
        var hasHead = hasGenesis;  // Same condition
        var headHeight = hasHead ? head.height().longValue() : -1L;

        var viewId = viewStateHolder.getNextViewId();
        var hasView = viewId != null;
        var viewHeight = hasView ? blockChainState.getView().height().longValue() : -1L;
        var pendingViewCount = viewStateHolder.getPendingViews().size();

        var syncAttempts = asyncOperationState.getSyncAttempts();
        var bootstrapActive = asyncOperationState.getBootstrapFuture() != null;
        var syncScheduled = asyncOperationState.getSyncFuture() != null;

        // Get FSM state name
        var currentState = fsm.getCurrentState();
        var fsmStateName = currentState.toString();  // Mercantile enum name

        return new com.hellblazer.delos.choam.support.CHOAMStateSnapshot(
            started,
            joinOngoing,
            hasCommittee,
            committeeType,
            hasGenesis,
            hasHead,
            headHeight,
            hasView,
            viewHeight,
            pendingViewCount,
            syncAttempts,
            bootstrapActive,
            syncScheduled,
            fsmStateName
        );
    }

    /** a member of the current committee */
    private class Associate extends Administration {

        private final Producer producer;

        Associate(HashedCertifiedBlock viewChange, Map<Member, Verifier> validators, nextView nextView) {
            super(validators, new Digest(
            viewChange.block.hasGenesis() ? viewChange.block.getGenesis().getInitialView().getId()
                                          : viewChange.block.getReconfigure().getId()));
            var context = new StaticContext<>(viewId, params.context().getProbabilityByzantine(), 3,
                                              validators.keySet(), params.context().getEpsilon(), validators.size());
            log.trace("Using consensus key: {} sig: {} for view: {} on: {}",
                      params.digestAlgorithm().digest(nextView.consensusKeyPair.getPublic().getEncoded()),
                      params.digestAlgorithm().digest(nextView.member.getSignature().toByteString()), viewId,
                      params.member().getId());
            Signer signer = new SignerImpl(nextView.consensusKeyPair.getPrivate(), ULong.MIN);
            var pv = pendingViews();
            producer = new Producer(viewStateHolder.getNextViewId(),
                                    new ViewContext(context, params, pv, signer, validators, constructBlock()),
                                    blockChainState.getHead(), checkpointManager.currentCheckpoint(), getLabel(), scheduler);
            producer.start();
        }

        @Override
        public void complete() {
            producer.stop();
        }

        @Override
        public void join(SignedViewMember nextView, Digest from) {
            if (!from.equals(Digest.from(nextView.getVm().getId()))) {
                log.trace("Join from: {} does not match {} from join: {} diadem: {} on: {}", from,
                          Digest.from(nextView.getVm().getId()), Digest.from(nextView.getVm().getView()),
                          Digest.from(nextView.getVm().getDiadem()), params.member().getId());
                throw new StatusRuntimeException(INVALID_ARGUMENT);
            }
            producer.join(nextView);
        }

        @Override
        public SubmitResult submit(Transaction request) {
            return producer.submit(request);
        }
    }

    /** a client of the current committee */
    private class Client extends Administration {

        public Client(Map<Member, Verifier> validators, Digest viewId) {
            super(validators, viewId);
        }
    }

    private class TransSubmission implements Submitter {
        @Override
        public SubmitResult submit(Transaction request, Digest from) {
            return CHOAM.this.submit(request, from);
        }
    }
}
