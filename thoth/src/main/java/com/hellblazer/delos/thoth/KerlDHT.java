/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.google.common.collect.Multiset.Entry;
import com.google.protobuf.Empty;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.archipelago.server.FernetServerInterceptor;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.DelegatedContext;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.context.ViewChange;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.byzantine.ByzantineIntelligenceCoordinator;
import com.hellblazer.delos.membership.byzantine.ByzantineStateProvider;
import com.hellblazer.delos.membership.byzantine.IntelligenceConfig;
import com.hellblazer.delos.ring.SliceIterator;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.caching.CachingKERL;
import com.hellblazer.delos.stereotomy.db.UniKERLDirectPooled;
import com.hellblazer.delos.stereotomy.db.UniKERLDirectPooled.ClosableKERL;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.event.proto.*;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.services.grpc.StereotomyMetrics;
import com.hellblazer.delos.stereotomy.services.grpc.kerl.KERLAdapter;
import com.hellblazer.delos.stereotomy.services.grpc.proto.KeyStates;
import com.hellblazer.delos.stereotomy.services.proto.ProtoKERLAdapter;
import com.hellblazer.delos.stereotomy.services.proto.ProtoKERLService;
import com.hellblazer.delos.thoth.LoggingOutputStream.LogLevel;
import com.hellblazer.delos.thoth.grpc.dht.DhtClient;
import com.hellblazer.delos.thoth.grpc.dht.DhtServer;
import com.hellblazer.delos.thoth.grpc.dht.DhtService;
import com.hellblazer.delos.thoth.grpc.reconciliation.Reconciliation;
import com.hellblazer.delos.thoth.grpc.reconciliation.ReconciliationClient;
import com.hellblazer.delos.thoth.grpc.reconciliation.ReconciliationServer;
import com.hellblazer.delos.thoth.grpc.reconciliation.ReconciliationService;
import com.hellblazer.delos.thoth.exception.DhtQuorumException;
import com.hellblazer.delos.thoth.exception.DhtResourceException;
import com.hellblazer.delos.thoth.metrics.KerlDhtMetrics;
import com.hellblazer.delos.thoth.proto.Intervals;
import com.hellblazer.delos.thoth.proto.Update;
import com.hellblazer.delos.thoth.proto.Updating;
import com.hellblazer.delos.utils.Entropy;
import com.hellblazer.delos.utils.Utils;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.Scope.Attr;
import liquibase.database.core.H2Database;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.ui.ConsoleUIService;
import liquibase.ui.UIService;
import org.h2.jdbcx.JdbcConnectionPool;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory.digestOf;
import static com.hellblazer.delos.stereotomy.schema.tables.Identifier.IDENTIFIER;
import static com.hellblazer.delos.thoth.schema.Tables.IDENTIFIER_LOCATION_HASH;
import static com.hellblazer.delos.utils.Utils.b64;

/**
 * KerlDHT provides the replicated state store for KERLs
 *
 * @author hal.hildebrand
 */
public class KerlDHT implements ProtoKERLService {
    private final static Logger log          = LoggerFactory.getLogger(KerlDHT.class);
    private final static Logger reconcileLog = LoggerFactory.getLogger(KerlSpace.class);

    private final Ani                                                         ani;
    private final ThothByzantineStateProvider                                 byzantineProvider;
    private final DhtValidationPipeline                                       validationPipeline;
    private final CachingKERL                                                 cache;
    private final JdbcConnectionPool                                          connectionPool;
    private final DelegatedContext<Member>                                    context;
    private final CommonCommunications<DhtService, ProtoKERLService>          dhtComms;
    private final KerlDhtMetrics                                              dhtMetrics;
    private final double                                                      fpr;
    private final Duration                                                    operationsFrequency;
    private final CachingKERL                                                 kerl;
    private final UniKERLDirectPooled                                         kerlPool;
    private final KerlSpace                                                   kerlSpace;
    private final SigningMember                                               member;
    private final CommonCommunications<ReconciliationService, Reconciliation> reconcileComms;
    private final Reconcile                                                   reconciliation = new Reconcile();
    private final ScheduledExecutorService                                    scheduler;
    private final Service                                                     service        = new Service();
    private final AtomicBoolean                                               started        = new AtomicBoolean();
    private final Duration                                                    operationTimeout;
    private final Set<CompletableFuture<Void>>                                inFlightValidations = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public KerlDHT(Duration operationsFrequency, Context<? extends Member> context, SigningMember member,
                   BiFunction<KerlDHT, KERL.AppendKERL, KERL.AppendKERL> wrap, JdbcConnectionPool connectionPool,
                   DigestAlgorithm digestAlgorithm, Router communications, Duration operationTimeout,
                   double falsePositiveRate, StereotomyMetrics metrics, KerlDhtMetrics dhtMetrics) {
        assert member != null;
        this.context = new DelegatedContext<>((Context<Member>) new StaticContext<>(context));
        this.member = member;
        this.operationTimeout = operationTimeout;
        this.fpr = falsePositiveRate;
        this.operationsFrequency = operationsFrequency;
        this.dhtMetrics = dhtMetrics != null ? dhtMetrics : KerlDhtMetrics.noOp();
        this.byzantineProvider = new ThothByzantineStateProvider();
        this.scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        var kerlAdapter = new KERLAdapter(this, digestAlgorithm);
        this.cache = new CachingKERL(f -> {
            try {
                return f.apply(kerlAdapter);
            } catch (Exception e) {
                log.error("Cache operation failed on: {}", member.getId(), e);
                throw new DhtResourceException("Cache operation failed", e);
            }
        });
        dhtComms = communications.create(member, context.getId(), service, service.getClass().getCanonicalName(),
                                         r -> new DhtServer(r, metrics), DhtClient.getCreate(metrics),
                                         DhtClient.getLocalLoopback(service, member));
        reconcileComms = communications.create(member, context.getId(), reconciliation,
                                               reconciliation.getClass().getCanonicalName(),
                                               r -> new ReconciliationServer(r,
                                                                             communications.getClientIdentityProvider(),
                                                                             metrics),
                                               ReconciliationClient.getCreate(context.getId(), metrics),
                                               ReconciliationClient.getLocalLoopback(reconciliation, member));
        this.connectionPool = connectionPool;
        kerlPool = new UniKERLDirectPooled(connectionPool, digestAlgorithm);
        this.kerlSpace = new KerlSpace(connectionPool, member.getId(), digestAlgorithm);

        initializeSchema();
        kerl = new CachingKERL(f -> {
            try (var k = kerlPool.create()) {
                return f.apply(wrap.apply(this, wrap(k)));
            } catch (Exception e) {
                log.error("KERL operation failed on: {}", member.getId(), e);
                throw new DhtResourceException("KERL operation failed", e);
            }
        });
        this.ani = new Ani(member.getId(), asKERL());
        this.validationPipeline = new DhtValidationPipeline(ani, asKERL(), operationTimeout, byzantineProvider,
                                                             dhtMetrics, scheduler);
    }

    /**
     * Backward-compatible constructor without KerlDhtMetrics (uses no-op metrics).
     */
    public KerlDHT(Duration operationsFrequency, Context<? extends Member> context, SigningMember member,
                   BiFunction<KerlDHT, KERL.AppendKERL, KERL.AppendKERL> wrap, JdbcConnectionPool connectionPool,
                   DigestAlgorithm digestAlgorithm, Router communications, Duration operationTimeout,
                   double falsePositiveRate, StereotomyMetrics metrics) {
        this(operationsFrequency, context, member, wrap, connectionPool, digestAlgorithm, communications,
             operationTimeout, falsePositiveRate, metrics, null);
    }

    public KerlDHT(Duration operationsFrequency, Context<? extends Member> context, SigningMember member,
                   JdbcConnectionPool connectionPool, DigestAlgorithm digestAlgorithm, Router communications,
                   Duration operationTimeout, double falsePositiveRate, StereotomyMetrics metrics) {
        this(operationsFrequency, context, member, (t, k) -> k, connectionPool, digestAlgorithm, communications,
             operationTimeout, falsePositiveRate, metrics, null);
    }

    /**
     * @return the Byzantine state provider for coordinator registration
     */
    public ThothByzantineStateProvider getByzantineProvider() {
        return byzantineProvider;
    }

    /**
     * @return the DHT metrics
     */
    public KerlDhtMetrics getDhtMetrics() {
        return dhtMetrics;
    }

    public static void updateLocationHash(Identifier identifier, DigestAlgorithm digestAlgorithm, DSLContext dsl) {
        dsl.transaction(config -> {
            var context = DSL.using(config);
            var identBytes = b64(identifier.toIdent());
            // Braindead, but correct
            var id = context.select(IDENTIFIER.ID).from(IDENTIFIER).where(IDENTIFIER.PREFIX.eq(identBytes)).fetchOne();
            if (id == null) {
                throw new IllegalStateException("Identifier: %s not found".formatted(identifier));
            }

            var hashed = digestAlgorithm.digest(identBytes);
            context.insertInto(IDENTIFIER_LOCATION_HASH, IDENTIFIER_LOCATION_HASH.IDENTIFIER,
                               IDENTIFIER_LOCATION_HASH.DIGEST)
                   .values(id.value1(), b64(hashed.getBytes()))
                   .onDuplicateKeyIgnore()
                   .execute();
        });
    }

    public KeyState_ append(AttachmentEvent event) {
        if (event == null) {
            return null;
        }
        log.info("Append event: {} on: {}", EventCoordinates.from(event.getCoordinates()), member.getId());
        Digest identifier = digestOf(event, digestAlgorithm());
        if (identifier == null) {
            return null;
        }
        Instant timedOut = Instant.now().plus(operationTimeout);
        Supplier<Boolean> isTimedOut = () -> Instant.now().isAfter(timedOut);
        var result = new CompletableFuture<KeyStates>();
        QuorumResponseTracker<KeyStates> gathered = new QuorumResponseTracker<>();
        var slice = context.bftSubset(identifier);
        var iterator = new SliceIterator<>(context.getId().toString(), member, slice, dhtComms, scheduler);
        try {
            iterator.iterate((link) -> link.append(Collections.emptyList(), Collections.singletonList(event)),
                             (futureSailor, tally, link, respondingMember) -> mutate(gathered, futureSailor, respondingMember,
                                                                     identifier, isTimedOut,
                                                                      tally, link, "append events"),
                             () -> completeIt(result, gathered), operationsFrequency);
            List<KeyState_> s = result.get().getKeyStatesList();
            return s.isEmpty() ? null : s.getFirst();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof CompletionException ce) {
                log.info("error appending event: {} on: {}", ce.getMessage(), member.getId());
                return KeyState_.getDefaultInstance();
            }
            throw new DhtResourceException("Append event failed", e.getCause());
        }
    }

    @Override
    public List<KeyState_> append(KERL_ kerl) {
        var startNanos = System.nanoTime();
        if (kerl.getEventsList().isEmpty()) {
            return Collections.emptyList();
        }
        final var event = kerl.getEventsList().getFirst();
        Digest identifier = digestOf(event, digestAlgorithm());
        if (identifier == null) {
            return Collections.emptyList();
        }
        Instant timedOut = Instant.now().plus(operationTimeout);
        Supplier<Boolean> isTimedOut = () -> Instant.now().isAfter(timedOut);
        var result = new CompletableFuture<KeyStates>();
        QuorumResponseTracker<KeyStates> gathered = new QuorumResponseTracker<>();
        var slice = context.bftSubset(identifier);
        var iterator = new SliceIterator<>(context.getId().toString(), member, slice, dhtComms, scheduler);
        try {
            iterator.iterate((link) -> link.append(kerl),
                             (futureSailor, tally, link, respondingMember) -> mutate(gathered, futureSailor, respondingMember,
                                                                     identifier, isTimedOut,
                                                                      tally, link, "append kerl"),
                             () -> completeIt(result, gathered), operationsFrequency);
            var keyStates = result.get().getKeyStatesList();
            dhtMetrics.recordWriteLatency("appendKERL", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumSuccess("appendKERL");
            return keyStates;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dhtMetrics.recordWriteLatency("appendKERL", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumFailure("appendKERL");
            return null;
        } catch (ExecutionException e) {
            dhtMetrics.recordWriteLatency("appendKERL", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error appending KERL: {} on: {}", ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("appendKERL");
                return Collections.emptyList();
            }
            dhtMetrics.incrementQuorumFailure("appendKERL");
            throw new DhtResourceException("Append KERL failed", e.getCause());
        }
    }

    public KeyState_ append(KeyEvent_ event) {
        var startNanos = System.nanoTime();
        Digest identifier = digestOf(event, digestAlgorithm());
        if (identifier == null) {
            return null;
        }

        // Note: KERI validation happens post-quorum via DhtValidationPipeline (lines 1000-1012)
        // to avoid deadlock from blocking DHT reads during write operations

        Instant timedOut = Instant.now().plus(operationTimeout);
        Supplier<Boolean> isTimedOut = () -> Instant.now().isAfter(timedOut);
        var result = new CompletableFuture<KeyStates>();
        QuorumResponseTracker<KeyStates> gathered = new QuorumResponseTracker<>();
        var slice = context.bftSubset(identifier);
        var iterator = new SliceIterator<>(context.getId().toString(), member, slice, dhtComms, scheduler);
        try {
            iterator.iterate((link) -> link.append(Collections.singletonList(event)),
                             (futureSailor, tally, link, respondingMember) -> mutate(gathered, futureSailor, respondingMember,
                                                                     identifier, isTimedOut,
                                                                      tally, link, "append kerl"),
                             () -> completeIt(result, gathered), operationsFrequency);
            var ks = result.get();
            dhtMetrics.recordWriteLatency("appendEvent", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumSuccess("appendEvent");
            return ks.getKeyStatesCount() == 0 ? KeyState_.getDefaultInstance() : ks.getKeyStatesList().getFirst();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dhtMetrics.recordWriteLatency("appendEvent", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumFailure("appendEvent");
            return null;
        } catch (ExecutionException e) {
            dhtMetrics.recordWriteLatency("appendEvent", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error appending Key Event: {} on: {}", ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("appendEvent");
                return KeyState_.getDefaultInstance();
            }
            dhtMetrics.incrementQuorumFailure("appendEvent");
            throw new DhtResourceException("Append key event failed", e.getCause());
        }
    }

    @Override
    public List<KeyState_> append(List<KeyEvent_> events) {
        if (events.isEmpty()) {
            return Collections.emptyList();
        }
        List<KeyState_> states = new ArrayList<>();
        events.stream().map(this::append).forEach(states::add);
        return states;
    }

    @Override
    public List<KeyState_> append(List<KeyEvent_> events, List<AttachmentEvent> attachments) {
        if (events.isEmpty()) {
            return Collections.emptyList();
        }
        List<KeyState_> states = new ArrayList<>();
        events.stream().map(this::append).forEach(states::add);

        attachments.forEach(this::append);
        return states;
    }

    @Override
    public Empty appendAttachments(List<AttachmentEvent> events) {
        if (events.isEmpty()) {
            return Empty.getDefaultInstance();
        }
        final var event = events.getFirst();
        Digest identifier = digestAlgorithm().digest(event.getCoordinates().getIdentifier().toByteString());
        if (identifier == null) {
            return Empty.getDefaultInstance();
        }
        Instant timedOut = Instant.now().plus(operationTimeout);
        Supplier<Boolean> isTimedOut = () -> Instant.now().isAfter(timedOut);
        var result = new CompletableFuture<Empty>();
        QuorumResponseTracker<Empty> gathered = new QuorumResponseTracker<>();
        var slice = context.bftSubset(identifier);
        var iterator = new SliceIterator<>(context.getId().toString(), member, slice, dhtComms, scheduler);
        try {
            iterator.iterate((link) -> link.appendAttachments(events),
                             (futureSailor, tally, link, respondingMember) -> mutate(gathered, futureSailor, respondingMember,
                                                                     identifier, isTimedOut,
                                                                      tally, link, "append kerl"),
                             () -> completeIt(result, gathered), operationsFrequency);
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error appending attachments: {} on: {}", ce.getMessage(), member.getId());
                return Empty.getDefaultInstance();
            }
            throw new DhtResourceException("Append attachments failed", e.getCause());
        }
    }

    @Override
    public Empty appendValidations(Validations validations) {
        var startNanos = System.nanoTime();
        if (validations.getValidationsCount() == 0) {
            return null;
        }
        Digest identifier = digestAlgorithm().digest(validations.getCoordinates().getIdentifier().toByteString());
        if (identifier == null) {
            return null;
        }
        Instant timedOut = Instant.now().plus(operationTimeout);
        Supplier<Boolean> isTimedOut = () -> Instant.now().isAfter(timedOut);
        var result = new CompletableFuture<Empty>();
        QuorumResponseTracker<Empty> gathered = new QuorumResponseTracker<>();
        var slice = context.bftSubset(identifier);
        var iterator = new SliceIterator<>(context.getId().toString(), member, slice, dhtComms, scheduler);
        try {
            iterator.iterate((link) -> link.appendValidations(validations),
                             (futureSailor, tally, link, respondingMember) -> mutate(gathered, futureSailor, respondingMember,
                                                                     identifier, isTimedOut,
                                                                      tally, link, "append kerl"),
                             () -> completeIt(result, gathered), operationsFrequency);
            var empty = result.get();
            dhtMetrics.recordWriteLatency("appendValidations", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumSuccess("appendValidations");
            return empty;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dhtMetrics.recordWriteLatency("appendValidations", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumFailure("appendValidations");
            return null;
        } catch (ExecutionException e) {
            dhtMetrics.recordWriteLatency("appendValidations", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error appending validations: {} on: {}", ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("appendValidations");
                return Empty.getDefaultInstance();
            }
            dhtMetrics.incrementQuorumFailure("appendValidations");
            throw new DhtResourceException("Append validations failed", e.getCause());
        }
    }

    public KERL.AppendKERL asKERL() {
        return cache;
    }

    /**
     * Clear the caches of the receiver
     */
    public void clearCache() {
        cache.clear();
    }

    public DigestAlgorithm digestAlgorithm() {
        return kerlPool.getDigestAlgorithm();
    }

    public Ani getAni() {
        return ani;
    }

    @Override
    public Attachment getAttachment(EventCoords coordinates) {
        var startNanos = System.nanoTime();
        if (coordinates == null) {
            return Attachment.getDefaultInstance();
        }
        Digest identifier = digestAlgorithm().digest(coordinates.getIdentifier().toByteString());
        if (identifier == null) {
            return Attachment.getDefaultInstance();
        }
        Instant timedOut = Instant.now().plus(operationTimeout);
        Supplier<Boolean> isTimedOut = () -> Instant.now().isAfter(timedOut);
        var result = new CompletableFuture<Attachment>();
        QuorumResponseTracker<Attachment> gathered = new QuorumResponseTracker<>();
        var operation = "getAttachment(%s)".formatted(EventCoordinates.from(coordinates));
        var slice = context.bftSubset(identifier);
        var iter = new SliceIterator<>(context.getId().toString(), member, slice, dhtComms, scheduler);
        iter.iterate(link -> link.getAttachment(coordinates),
                     (futureSailor, tally, destination, respondingMember) -> read(result, gathered, respondingMember,
                                                                        tally, futureSailor, identifier,
                                                                   isTimedOut, destination, operation),
                     () -> failedMajority(result, maxCount(gathered), operation), operationsFrequency);
        try {
            var attachment = result.get();
            dhtMetrics.recordReadLatency("getAttachment", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumSuccess("getAttachment");
            return attachment;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dhtMetrics.recordReadLatency("getAttachment", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumFailure("getAttachment");
            return null;
        } catch (ExecutionException e) {
            dhtMetrics.recordReadLatency("getAttachment", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error {} : {} on: {}", operation, ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("getAttachment");
                return null;
            }
            dhtMetrics.incrementQuorumFailure("getAttachment");
            throw new DhtResourceException("Get attachment failed", e.getCause());
        }
    }

    @Override
    public KERL_ getKERL(Ident identifier) {
        var startNanos = System.nanoTime();
        if (identifier == null) {
            return KERL_.getDefaultInstance();
        }
        Digest digest = digestAlgorithm().digest(identifier.toByteString());
        if (digest == null) {
            return KERL_.getDefaultInstance();
        }
        Instant timedOut = Instant.now().plus(operationTimeout);
        Supplier<Boolean> isTimedOut = () -> Instant.now().isAfter(timedOut);
        var result = new CompletableFuture<KERL_>();
        QuorumResponseTracker<KERL_> gathered = new QuorumResponseTracker<>();
        var operation = "getKerl(%s)".formatted(Identifier.from(identifier));
        var slice = context.bftSubset(digest);
        var iter = new SliceIterator<>(context.getId().toString(), member, slice, dhtComms, scheduler);
        iter.iterate(link -> link.getKERL(identifier),
                     (futureSailor, tally, destination, respondingMember) -> read(result, gathered, respondingMember,
                                                                        tally, futureSailor, digest,
                                                                   isTimedOut, destination, operation),
                     () -> failedMajority(result, maxCount(gathered), operation), operationsFrequency);
        try {
            var kerl = result.get();
            dhtMetrics.recordReadLatency("getKerl", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumSuccess("getKerl");
            return kerl;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dhtMetrics.recordReadLatency("getKerl", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumFailure("getKerl");
            return null;
        } catch (ExecutionException e) {
            dhtMetrics.recordReadLatency("getKerl", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error {} : {} on: {}", operation, ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("getKerl");
                return KERL_.getDefaultInstance();
            }
            dhtMetrics.incrementQuorumFailure("getKerl");
            throw new DhtResourceException("Get KERL failed", e.getCause());
        }
    }

    @Override
    public KeyEvent_ getKeyEvent(EventCoords coordinates) {
        var startNanos = System.nanoTime();
        if (!coordinates.isInitialized()) {
            return KeyEvent_.getDefaultInstance();
        }
        var operation = "getKeyEvent(%s)".formatted(EventCoordinates.from(coordinates));
        if (coordinates == null) {
            return KeyEvent_.getDefaultInstance();
        }
        Digest digest = digestAlgorithm().digest(coordinates.getIdentifier().toByteString());
        if (digest == null) {
            return KeyEvent_.getDefaultInstance();
        }
        Instant timedOut = Instant.now().plus(operationTimeout);
        Supplier<Boolean> isTimedOut = () -> Instant.now().isAfter(timedOut);
        var result = new CompletableFuture<KeyEvent_>();
        QuorumResponseTracker<KeyEvent_> gathered = new QuorumResponseTracker<>();
        var slice = context.bftSubset(digest);
        var iter = new SliceIterator<>(context.getId().toString(), member, slice, dhtComms, scheduler);
        result = iter.voteAsync(link -> link.getKeyEvent(coordinates), context.majority(), operationsFrequency,
                                operationTimeout);
        try {
            var keyEvent = result.get();
            dhtMetrics.recordReadLatency("getKeyEvent", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumSuccess("getKeyEvent");
            return keyEvent;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dhtMetrics.recordReadLatency("getKeyEvent", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumFailure("getKeyEvent");
            return null;
        } catch (ExecutionException e) {
            dhtMetrics.recordReadLatency("getKeyEvent", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.info("error {} : {} on: {}", operation, ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("getKeyEvent");
                return KeyEvent_.getDefaultInstance();
            }
            dhtMetrics.incrementQuorumFailure("getKeyEvent");
            throw new DhtResourceException("Get key event failed", e.getCause());
        }
    }

    @Override
    public KeyState_ getKeyState(EventCoords coordinates) {
        var startNanos = System.nanoTime();
        var operation = "getKeyState(%s)".formatted(EventCoordinates.from(coordinates));
        log.info("{} on: {}", operation, member.getId());
        if (coordinates == null) {
            return KeyState_.getDefaultInstance();
        }
        Digest digest = digestAlgorithm().digest(coordinates.getIdentifier().toByteString());
        if (digest == null) {
            return KeyState_.getDefaultInstance();
        }
        Instant timedOut = Instant.now().plus(operationTimeout);
        Supplier<Boolean> isTimedOut = () -> Instant.now().isAfter(timedOut);
        var result = new CompletableFuture<KeyState_>();
        QuorumResponseTracker<KeyState_> gathered = new QuorumResponseTracker<>();
        var slice = context.bftSubset(digest);
        var iter = new SliceIterator<>(context.getId().toString(), member, slice, dhtComms, scheduler);
        iter.iterate(link -> link.getKeyState(coordinates),
                     (futureSailor, tally, destination, respondingMember) -> read(result, gathered, respondingMember,
                                                                        tally, futureSailor, digest,
                                                                   isTimedOut, destination, operation),
                     () -> failedMajority(result, maxCount(gathered), operation), operationsFrequency);
        try {
            var keyState = result.get();
            dhtMetrics.recordReadLatency("getKeyStateCoords", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumSuccess("getKeyStateCoords");
            return keyState;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dhtMetrics.recordReadLatency("getKeyStateCoords", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumFailure("getKeyStateCoords");
            return null;
        } catch (ExecutionException e) {
            dhtMetrics.recordReadLatency("getKeyStateCoords", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error {} : {} on: {}", operation, ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("getKeyStateCoords");
                return KeyState_.getDefaultInstance();
            }
            dhtMetrics.incrementQuorumFailure("getKeyStateCoords");
            throw new DhtResourceException("Get key state failed", e.getCause());
        }
    }

    @Override
    public KeyState_ getKeyState(Ident identifier, ULong sequenceNumber) {
        var startNanos = System.nanoTime();
        if (identifier == null) {
            return KeyState_.getDefaultInstance();
        }
        var operation = "getKeyState(%s, %s)".formatted(Identifier.from(identifier), sequenceNumber);
        log.warn("{} on: {}", operation, member.getId());
        Digest digest = digestAlgorithm().digest(identifier.toByteString());
        if (digest == null) {
            return KeyState_.getDefaultInstance();
        }
        var identAndSeq = IdentAndSeq.newBuilder()
                                     .setIdentifier(identifier)
                                     .setSequenceNumber(sequenceNumber.longValue())
                                     .build();
        Instant timedOut = Instant.now().plus(operationTimeout);
        Supplier<Boolean> isTimedOut = () -> Instant.now().isAfter(timedOut);
        var result = new CompletableFuture<KeyState_>();
        QuorumResponseTracker<KeyState_> gathered = new QuorumResponseTracker<>();
        var slice = context.bftSubset(digest);
        var iter = new SliceIterator<>(context.getId().toString(), member, slice, dhtComms, scheduler);
        iter.iterate(link -> link.getKeyState(identAndSeq),
                     (futureSailor, tally, destination, respondingMember) -> read(result, gathered, respondingMember,
                                                                        tally, futureSailor, digest,
                                                                   isTimedOut, destination, operation),
                     () -> failedMajority(result, maxCount(gathered), operation), operationsFrequency);
        try {
            var keyState = result.get();
            dhtMetrics.recordReadLatency("getKeyStateSeq", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumSuccess("getKeyStateSeq");
            return keyState;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dhtMetrics.recordReadLatency("getKeyStateSeq", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumFailure("getKeyStateSeq");
            return null;
        } catch (ExecutionException e) {
            dhtMetrics.recordReadLatency("getKeyStateSeq", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error {} : {} on: {}", operation, ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("getKeyStateSeq");
                return KeyState_.getDefaultInstance();
            }
            dhtMetrics.incrementQuorumFailure("getKeyStateSeq");
            throw new DhtResourceException("Get key state by sequence failed", e.getCause());
        }
    }

    @Override
    public KeyState_ getKeyState(Ident identifier) {
        var startNanos = System.nanoTime();
        if (identifier == null) {
            return KeyState_.getDefaultInstance();
        }
        var operation = "getKeyState(%s)".formatted(Identifier.from(identifier));
        log.info("{} on: {}", operation, member.getId());
        Digest digest = digestAlgorithm().digest(identifier.toByteString());
        if (digest == null) {
            return KeyState_.getDefaultInstance();
        }
        Instant timedOut = Instant.now().plus(operationTimeout);
        Supplier<Boolean> isTimedOut = () -> Instant.now().isAfter(timedOut);
        var result = new CompletableFuture<KeyState_>();
        QuorumResponseTracker<KeyState_> gathered = new QuorumResponseTracker<>();
        var slice = context.bftSubset(digest);
        var iter = new SliceIterator<>(context.getId().toString(), member, slice, dhtComms, scheduler);
        iter.iterate(link -> link.getKeyState(identifier),
                     (futureSailor, tally, destination, respondingMember) -> read(result, gathered, respondingMember,
                                                                        tally, futureSailor, digest,
                                                                   isTimedOut, destination, operation),
                     () -> failedMajority(result, maxCount(gathered), operation), operationsFrequency);
        try {
            var keyState = result.get();
            dhtMetrics.recordReadLatency("getKeyState", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumSuccess("getKeyState");
            return keyState;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dhtMetrics.recordReadLatency("getKeyState", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumFailure("getKeyState");
            return null;
        } catch (ExecutionException e) {
            dhtMetrics.recordReadLatency("getKeyState", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error {} : {} on: {}", operation, ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("getKeyState");
                return KeyState_.getDefaultInstance();
            }
            dhtMetrics.incrementQuorumFailure("getKeyState");
            throw new DhtResourceException("Get key state by identifier failed", e.getCause());
        }
    }

    @Override
    public KeyState_ getKeyStateSeqNum(IdentAndSeq request) {
        var startNanos = System.nanoTime();
        var identifier = request.getIdentifier();
        var sequenceNumber = request.getSequenceNumber();
        var operation = "getKeyState(%s, %s)".formatted(Identifier.from(identifier), ULong.valueOf(sequenceNumber));
        log.warn("{} on: {}", operation, member.getId());
        if (identifier == null) {
            return KeyState_.getDefaultInstance();
        }
        Digest digest = digestAlgorithm().digest(identifier.toByteString());
        if (digest == null) {
            return KeyState_.getDefaultInstance();
        }
        var identAndSeq = IdentAndSeq.newBuilder().setIdentifier(identifier).setSequenceNumber(sequenceNumber).build();
        Instant timedOut = Instant.now().plus(operationTimeout);
        Supplier<Boolean> isTimedOut = () -> Instant.now().isAfter(timedOut);
        var result = new CompletableFuture<KeyState_>();
        QuorumResponseTracker<KeyState_> gathered = new QuorumResponseTracker<>();
        var slice = context.bftSubset(digest);
        var iter = new SliceIterator<>(context.getId().toString(), member, slice, dhtComms, scheduler);
        iter.iterate(link -> link.getKeyState(identAndSeq),
                     (futureSailor, tally, destination, respondingMember) -> read(result, gathered, respondingMember,
                                                                        tally, futureSailor, digest,
                                                                   isTimedOut, destination, operation),
                     () -> failedMajority(result, maxCount(gathered), operation), operationsFrequency);
        try {
            var keyState = result.get();
            dhtMetrics.recordReadLatency("getKeyStateSeqNum", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumSuccess("getKeyStateSeqNum");
            return keyState;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dhtMetrics.recordReadLatency("getKeyStateSeqNum", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumFailure("getKeyStateSeqNum");
            return null;
        } catch (ExecutionException e) {
            dhtMetrics.recordReadLatency("getKeyStateSeqNum", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error {} : {} on: {}", operation, ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("getKeyStateSeqNum");
                return KeyState_.getDefaultInstance();
            }
            dhtMetrics.incrementQuorumFailure("getKeyStateSeqNum");
            throw new DhtResourceException("Get key state by sequence number failed", e.getCause());
        }
    }

    @Override
    public KeyStateWithAttachments_ getKeyStateWithAttachments(EventCoords coordinates) {
        var startNanos = System.nanoTime();
        var operation = "getKeyStateWithAttachments(%s)".formatted(EventCoordinates.from(coordinates));
        log.info("{} on: {}", operation, member.getId());
        if (coordinates == null) {
            return KeyStateWithAttachments_.getDefaultInstance();
        }
        Digest digest = digestAlgorithm().digest(coordinates.getIdentifier().toByteString());
        if (digest == null) {
            return KeyStateWithAttachments_.getDefaultInstance();
        }
        Instant timedOut = Instant.now().plus(operationTimeout);
        Supplier<Boolean> isTimedOut = () -> Instant.now().isAfter(timedOut);
        var result = new CompletableFuture<KeyStateWithAttachments_>();
        QuorumResponseTracker<KeyStateWithAttachments_> gathered = new QuorumResponseTracker<>();
        var slice = context.bftSubset(digest);
        var iter = new SliceIterator<>(context.getId().toString(), member, slice, dhtComms, scheduler);
        iter.iterate(link -> link.getKeyStateWithAttachments(coordinates),
                     (futureSailor, tally, destination, respondingMember) -> read(result, gathered, respondingMember,
                                                                        tally, futureSailor, digest,
                                                                   isTimedOut, destination, operation),
                     () -> failedMajority(result, maxCount(gathered), operation), operationsFrequency);
        try {
            var keyStateWithAttachments = result.get();
            dhtMetrics.recordReadLatency("getKeyStateWithAttachments", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumSuccess("getKeyStateWithAttachments");
            return keyStateWithAttachments;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dhtMetrics.recordReadLatency("getKeyStateWithAttachments", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumFailure("getKeyStateWithAttachments");
            return null;
        } catch (ExecutionException e) {
            dhtMetrics.recordReadLatency("getKeyStateWithAttachments", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error {} on: {}", operation, member.getId(), ce);
                dhtMetrics.incrementQuorumFailure("getKeyStateWithAttachments");
                return null;
            }
            dhtMetrics.incrementQuorumFailure("getKeyStateWithAttachments");
            throw new DhtResourceException("Get key state with attachments failed", e.getCause());
        }
    }

    @Override
    public KeyStateWithEndorsementsAndValidations_ getKeyStateWithEndorsementsAndValidations(EventCoords coordinates) {
        var startNanos = System.nanoTime();
        var operation = "getKeyStateWithEndorsementsAndValidations(%s)".formatted(EventCoordinates.from(coordinates));
        log.info("{} on: {}", operation, member.getId());
        if (coordinates == null) {
            return KeyStateWithEndorsementsAndValidations_.getDefaultInstance();
        }
        Digest digest = digestAlgorithm().digest(coordinates.getIdentifier().toByteString());
        if (digest == null) {
            return KeyStateWithEndorsementsAndValidations_.getDefaultInstance();
        }
        Instant timedOut = Instant.now().plus(operationTimeout);
        Supplier<Boolean> isTimedOut = () -> Instant.now().isAfter(timedOut);
        var result = new CompletableFuture<KeyStateWithEndorsementsAndValidations_>();
        QuorumResponseTracker<KeyStateWithEndorsementsAndValidations_> gathered = new QuorumResponseTracker<>();
        var slice = context.bftSubset(digest);
        var iter = new SliceIterator<>(context.getId().toString(), member, slice, dhtComms, scheduler);
        iter.iterate(link -> link.getKeyStateWithEndorsementsAndValidations(coordinates),
                     (futureSailor, tally, destination, respondingMember) -> read(result, gathered, respondingMember,
                                                                        tally, futureSailor, digest,
                                                                   isTimedOut, destination, operation),
                     () -> failedMajority(result, maxCount(gathered), operation), operationsFrequency);
        try {
            var keyStateWithEndorsementsAndValidations = result.get();
            dhtMetrics.recordReadLatency("getKeyStateWithEndorsementsAndValidations", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumSuccess("getKeyStateWithEndorsementsAndValidations");
            return keyStateWithEndorsementsAndValidations;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dhtMetrics.recordReadLatency("getKeyStateWithEndorsementsAndValidations", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumFailure("getKeyStateWithEndorsementsAndValidations");
            return null;
        } catch (ExecutionException e) {
            dhtMetrics.recordReadLatency("getKeyStateWithEndorsementsAndValidations", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error {} : {} on: {}", operation, ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("getKeyStateWithEndorsementsAndValidations");
                return null;
            }
            dhtMetrics.incrementQuorumFailure("getKeyStateWithEndorsementsAndValidations");
            throw new DhtResourceException("Get key state with endorsements and validations failed", e.getCause());
        }
    }

    @Override
    public Validations getValidations(EventCoords coordinates) {
        var startNanos = System.nanoTime();
        var operation = "getValidations(%s)".formatted(EventCoordinates.from(coordinates));
        log.info("{} on: {}", operation, member.getId());
        if (coordinates == null) {
            return Validations.getDefaultInstance();
        }
        Digest identifier = digestAlgorithm().digest(coordinates.getIdentifier().toByteString());
        if (identifier == null) {
            return Validations.getDefaultInstance();
        }
        Instant timedOut = Instant.now().plus(operationTimeout);
        Supplier<Boolean> isTimedOut = () -> Instant.now().isAfter(timedOut);
        var result = new CompletableFuture<Validations>();
        QuorumResponseTracker<Validations> gathered = new QuorumResponseTracker<>();
        var slice = context.bftSubset(identifier);
        var iter = new SliceIterator<>(context.getId().toString(), member, slice, dhtComms, scheduler);
        iter.iterate(link -> link.getValidations(coordinates),
                     (futureSailor, tally, destination, respondingMember) -> read(result, gathered, respondingMember,
                                                                        tally, futureSailor, identifier,
                                                                   isTimedOut, destination, operation),
                     () -> failedMajority(result, maxCount(gathered), operation), operationsFrequency);
        try {
            var validations = result.get();
            dhtMetrics.recordReadLatency("getValidations", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumSuccess("getValidations");
            return validations;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dhtMetrics.recordReadLatency("getValidations", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumFailure("getValidations");
            return null;
        } catch (ExecutionException e) {
            dhtMetrics.recordReadLatency("getValidations", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error {} : {} on: {}", operation, ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("getValidations");
                return null;
            }
            dhtMetrics.incrementQuorumFailure("getValidations");
            throw new DhtResourceException("Get validations failed", e.getCause());
        }
    }

    /**
     * Get the Byzantine state provider for coordinator registration.
     * <p>
     * The returned provider:
     * - Reports layer name as {@link IntelligenceConfig#LAYER_THOTH}
     * - Tracks validation failures, quorum failures, and timeouts
     * - Can be registered with {@link ByzantineIntelligenceCoordinator#registerProvider(ByzantineStateProvider)}
     * </p>
     *
     * @return The Byzantine state provider for this DHT instance
     */
    public ThothByzantineStateProvider getByzantineStateProvider() {
        return byzantineProvider;
    }

    public Verifiers getVerifiers() {
        return new Verifiers() {
            @Override
            public Optional<Verifier> verifierFor(EventCoordinates coordinates) {
                return verifierFor(coordinates.getIdentifier());
            }

            @Override
            public Optional<Verifier> verifierFor(Identifier identifier) {
                return Optional.of(new KerlVerifier<>(identifier, asKERL()));
            }
        };
    }

    public void nextView(ViewChange viewChange) {
        log.info("Next view: {} context: {} on: {}", viewChange.diadem(), viewChange.context().getId(), member.getId());
        context.setContext(viewChange.context());
    }

    public void start(Duration duration) {
        start(duration, null);
    }

    public void start(Duration duration, Predicate<FernetServerInterceptor.HashedToken> validator) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        dhtComms.register(context.getId(), service, validator);
        reconcileComms.register(context.getId(), reconciliation, validator);
        schedule(duration);
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping KerlDHT on: {}", member.getId());

        // 1. Deregister communications (prevents new inbound)
        dhtComms.deregister(context.getId());
        reconcileComms.deregister(context.getId());

        // 2. Wait for in-flight validations
        if (!inFlightValidations.isEmpty()) {
            log.debug("Waiting for {} in-flight validations", inFlightValidations.size());
            try {
                CompletableFuture.allOf(inFlightValidations.toArray(new CompletableFuture[0]))
                    .orTimeout(5, TimeUnit.SECONDS)
                    .exceptionally(ex -> null)
                    .join();
            } catch (Exception e) {
                log.warn("Timeout waiting for in-flight validations during shutdown", e);
            }
        }

        // 3. Graceful scheduler shutdown, then force if needed
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Scheduler did not terminate gracefully, forcing on: {}", member.getId());
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 4. Dispose connection pool
        connectionPool.dispose();

        // 5. Reset Byzantine provider
        byzantineProvider.reset();

        log.info("KerlDHT stopped on: {}", member.getId());
    }

    private <T> T complete(Function<ProtoKERLAdapter, T> func) {
        try {
            return func.apply(new ProtoKERLAdapter(kerl));
        } catch (Exception e) {
            log.error("Error completing on: {}", member.getId(), e);
            throw new DhtResourceException("Completion operation failed", e);
        }
    }

    private <T> void completeIt(CompletableFuture<T> result, QuorumResponseTracker<T> gathered) {
        var max = gathered.maxEntry();
        var majority = context.size() == 1 ? 1 : context.majority();
        if (max != null) {
            if (max.getCount() >= majority) {
                var element = max.getElement();
                // Complete result immediately to reduce tail latency
                try {
                    result.complete(element);
                } catch (Exception e) {
                    log.error("Unable to complete it on {}", member.getId(), e);
                }

                // Phase 4: Post-quorum validation for KeyStates responses (async, advisory only)
                if (element instanceof KeyStates keyStates) {
                    @SuppressWarnings("unchecked")
                    var providers = ((QuorumResponseTracker<KeyStates>) gathered).providersOf(keyStates);
                    // Run validation asynchronously - don't block result completion
                    var validationFuture = CompletableFuture.runAsync(() -> {
                        try {
                            var validationResult = validationPipeline.validateKeyStates(keyStates, providers);
                            if (!validationResult.valid()) {
                                validationPipeline.reportFailure(validationResult);
                                log.warn("Advisory: KeyStates validation failed but accepted response");
                            }
                        } catch (Exception e) {
                            log.error("Validation failed with exception", e);
                        }
                    }, scheduler);
                    inFlightValidations.add(validationFuture);
                    validationFuture.whenComplete((v, ex) -> inFlightValidations.remove(validationFuture));
                }
                return;
            }
        } else {
            log.warn("Unable to achieve majority, max agree: 0 required: {} on: {}", majority, member.getId());
        }
        result.completeExceptionally(new DhtQuorumException(
        majority, max == null ? 0 : max.getCount(), "mutate operation"));
    }

    private boolean failedMajority(CompletableFuture<?> result, int maxAgree, String operation) {
        var majority = context.size() == 1 ? 1 : context.majority();
        log.debug("Unable to achieve majority read: {}, max agree: {} required: {} on: {}", operation, maxAgree,
                  majority, member.getId());
        return result.completeExceptionally(new DhtQuorumException(
        majority, maxAgree, operation));
    }

    private void initializeSchema() {
        ConsoleUIService service = (ConsoleUIService) Scope.getCurrentScope().get(Attr.ui, UIService.class);
        service.setOutputStream(
        new PrintStream(new LoggingOutputStream(LoggerFactory.getLogger("liquibase"), LogLevel.INFO)));
        try (var connection = connectionPool.getConnection()) {
            var database = new H2Database();
            database.setConnection(new liquibase.database.jvm.JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase("/initialize-thoth.xml", new ClassLoaderResourceAccessor(),
                                                     database)) {
                liquibase.update((String) null);
            } catch (LiquibaseException e) {
                log.error("Unable to initialize schema on: {}", member.getId(), e);
                throw new IllegalStateException(e);
            }
            // Note: Don't close database object - try-with-resources handles connection cleanup
        } catch (SQLException e) {
            log.error("Unable to initialize schema on: {}", member.getId(), e);
            throw new IllegalStateException(e);
        }
    }

    private CombinedIntervals keyIntervals() {
        List<KeyInterval> intervals = new ArrayList<>();
        for (int i = 0; i < context.getRingCount(); i++) {
            Member predecessor = context.predecessor(i, member);
            if (predecessor == null) {
                continue;
            }

            Digest begin = context.hashFor(predecessor, i);
            Digest end = context.hashFor(member, i);

            if (begin.compareTo(end) > 0) { // wrap around the origin of the ring
                intervals.add(new KeyInterval(end, digestAlgorithm().getLast()));
                intervals.add(new KeyInterval(digestAlgorithm().getOrigin(), begin));
            } else {
                intervals.add(new KeyInterval(begin, end));
            }
        }
        return new CombinedIntervals(intervals);
    }

    private <T> Entry<T> max(QuorumResponseTracker<T> gathered) {
        return gathered.maxEntry();
    }

    private int maxCount(QuorumResponseTracker<?> gathered) {
        return gathered.maxCount();
    }

    private <T> boolean mutate(QuorumResponseTracker<T> gathered, Optional<T> futureSailor, Member respondingMember,
                               Digest identifier, Supplier<Boolean> isTimedOut, AtomicInteger tally,
                               DhtService destination, String action) {
        if (futureSailor.isEmpty()) {
            log.debug("Failed {}: {} tally: {} from: {}  on: {}", action, identifier, tally.get(),
                      destination.getMember() == null ? "<null>" : destination.getMember().getId(), member.getId());
            // Record Byzantine provider signals for failure tracking
            if (destination.getMember() != null) {
                if (isTimedOut.get()) {
                    byzantineProvider.recordTimeout(destination.getMember().getId());
                } else {
                    byzantineProvider.recordQuorumFailure(destination.getMember().getId());
                }
            }
            return !isTimedOut.get();
        }
        T content = futureSailor.get();
        gathered.add(content, respondingMember);
        var max = gathered.maxEntry();
        if (max != null) {
            tally.set(max.getCount());
        }
        log.warn("{}: {} tally: {} from: {} on: {}", action, identifier, tally.get(), destination.getMember().getId(),
                 member.getId());
        return !isTimedOut.get();
    }

    private <T> boolean read(CompletableFuture<T> result, QuorumResponseTracker<T> gathered, Member respondingMember,
                             AtomicInteger tally, Optional<T> futureSailor, Digest identifier,
                             Supplier<Boolean> isTimedOut, DhtService destination, String action) {
        if (futureSailor.isEmpty()) {
            log.debug("Failed {}: {} tally: {} from: {}  on: {}", action, identifier, tally,
                      destination.getMember() == null ? "<null>" : destination.getMember().getId(), member.getId());
            // Record Byzantine provider signals for failure tracking
            if (destination.getMember() != null) {
                if (isTimedOut.get()) {
                    byzantineProvider.recordTimeout(destination.getMember().getId());
                } else {
                    byzantineProvider.recordQuorumFailure(destination.getMember().getId());
                }
            }
            return !isTimedOut.get();
        }
        T content = futureSailor.get();
        log.trace("{}: {} tally: {} from: {}  on: {}", action, identifier, tally.get(), destination.getMember().getId(),
                  member.getId());
        gathered.add(content, respondingMember);
        var max = max(gathered);
        if (max != null) {
            tally.set(max.getCount());
            var ctxMajority = context.size() == 1 ? 1 : context.majority();
            final var majority = tally.get() >= ctxMajority;
            if (majority) {
                var element = max.getElement();
                // Complete result immediately to reduce tail latency
                result.complete(element);

                // Phase 3: Post-quorum validation for KeyState_ responses (async, advisory only)
                if (element instanceof KeyState_ keyState) {
                    @SuppressWarnings("unchecked")
                    var providers = ((QuorumResponseTracker<KeyState_>) gathered).providersOf(keyState);
                    // Run validation asynchronously - don't block result completion
                    var validationFuture = CompletableFuture.runAsync(() -> {
                        try {
                            var validationResult = validationPipeline.validateKeyState(keyState, providers);
                            if (!validationResult.valid()) {
                                validationPipeline.reportFailure(validationResult);
                                log.warn("Advisory: KeyState validation failed but accepted response: {}", action);
                            }
                        } catch (Exception e) {
                            log.error("Validation failed with exception", e);
                        }
                    }, scheduler);
                    inFlightValidations.add(validationFuture);
                    validationFuture.whenComplete((v, ex) -> inFlightValidations.remove(validationFuture));
                }
                log.debug("Majority: {} achieved: {}: {} tally: {} on: {}", max.getCount(), action, identifier,
                          tally.get(), member.getId());
                return false;
            }
        }
        return !isTimedOut.get();
    }

    private void reconcile(Update update, ReconciliationService link) {
        if (!started.get()) {
            return;
        }
        try {
            if (update.getEventsCount() > 0) {
                reconcileLog.trace("Received: {} events in interval reconciliation from: {} on: {}",
                                   update.getEventsCount(), link.getMember().getId(), member.getId());
                kerlSpace.update(update.getEventsList(), kerl);
            }
        } catch (NoSuchElementException e) {
            reconcileLog.debug("null interval reconciliation with {} : {} on: {}", link.getMember().getId(),
                               e.getMessage(), member.getId());
        }
    }

    private Update reconcile(ReconciliationService link, Integer ring) {
        if (member.equals(link.getMember())) {
            return null;
        }
        CombinedIntervals keyIntervals = keyIntervals();
        reconcileLog.trace("Interval reconciliation on ring: {} with: {} intervals: {} on: {} ", ring,
                           link.getMember().getId(), keyIntervals, member.getId());
        return link.reconcile(Intervals.newBuilder()
                                       .setRing(ring)
                                       .addAllIntervals(keyIntervals.toIntervals())
                                       .setHave(kerlSpace.populate(Entropy.nextBitsStreamLong(), keyIntervals, fpr))
                                       .build());
    }

    private void reconcile(Duration duration) {
        if (!started.get()) {
            return;
        }
        var successors = context.successors(member.getId(), m -> true, member);
        Collections.shuffle(successors);
        successors.forEach(i -> {
            try (var link = reconcileComms.connect(i.m())) {
                if (link != null) {
                    reconcile(reconcile(link, i.ring()), link);
                }
                try {
                    Thread.sleep(duration.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } catch (IOException e) {
                log.debug("Error reconciling with: {} on: {}", i.m(), member.getId(), e);
            } finally {
                schedule(duration);
            }
        });
    }

    private void schedule(Duration duration) {
        Thread.ofVirtual().start(() -> Utils.wrapped(() -> reconcile(duration), log));
    }

    private void updateLocationHash(Identifier identifier) {
        try (var connection = connectionPool.getConnection()) {
            var dsl = DSL.using(connection, SQLDialect.H2);
            updateLocationHash(identifier, kerl.getDigestAlgorithm(), dsl);
        } catch (SQLException e) {
            log.error("Cannot update location hash for: {} on: {}", identifier, member.getId());
            throw new IllegalStateException(
            "Cannot update location hash S for: %s on: %s".formatted(identifier, member.getId()));
        } catch (DataAccessException e) {
            log.trace("Duplicate location hash for: {} on: {}", identifier, member.getId());
        }
    }

    private boolean valid(Digest from, int ring) {
        if (ring >= context.getRingCount() || ring < 0) {
            log.warn("Invalid ring: {} (valid range: 0-{}) from: {} on: {}",
                    ring, context.getRingCount() - 1, from, member.getId());
            return false;
        }
        Member fromMember = context.getMember(from);
        if (fromMember == null) {
            log.warn("Unknown member: {} for ring: {} on: {}", from, ring, member.getId());
            return false;
        }
        Member successor = context.successor(ring, fromMember);
        if (successor == null) {
            log.warn("No successor found for member: {} on ring: {} on: {}", from, ring, member.getId());
            return false;
        }
        return successor.equals(member);
    }

    private DelegatedKERL wrap(ClosableKERL k) {
        return new DelegatedKERL(k) {

            @Override
            public KeyState append(KeyEvent event) {
                KeyState ks = super.append(event);
                if (ks != null) {
                    updateLocationHash(ks.getCoordinates().getIdentifier());
                }
                return ks;
            }

            @Override
            public List<KeyState> append(KeyEvent... events) {
                List<KeyState> lks = super.append(events);
                if (!lks.isEmpty()) {
                    updateLocationHash(lks.getFirst().getCoordinates().getIdentifier());
                }
                return lks;
            }

            @Override
            public List<KeyState> append(List<KeyEvent> events,
                                         List<com.hellblazer.delos.stereotomy.event.AttachmentEvent> attachments) {
                List<KeyState> lks = super.append(events, attachments);
                if (!lks.isEmpty()) {
                    updateLocationHash(lks.getFirst().getCoordinates().getIdentifier());
                }
                return lks;
            }
        };
    }

    public static class CompletionException extends Exception {

        private static final long serialVersionUID = 1L;

        public CompletionException(String message) {
            super(message);
        }
    }

    private class Reconcile implements Reconciliation {

        @Override
        public Update reconcile(Intervals intervals, Digest from) {
            var ring = intervals.getRing();
            if (!valid(from, ring)) {
                reconcileLog.trace("Invalid reconcile from: {} ring: {} on: {}", from, ring, member.getId());
                return Update.getDefaultInstance();
            }
            reconcileLog.trace("Reconcile from: {} ring: {} on: {}", from, ring, member.getId());
            try (var k = kerlPool.create()) {
                final var builder = KerlDHT.this.kerlSpace.reconcile(intervals, k);
                CombinedIntervals keyIntervals = keyIntervals();
                builder.addAllIntervals(keyIntervals.toIntervals())
                       .setHave(kerlSpace.populate(Entropy.nextBitsStreamLong(), keyIntervals, fpr));
                if (builder.getEventsCount() > 0) {
                    reconcileLog.trace("Reconcile for: {} ring: {} count: {} on: {}", from, ring,
                                       builder.getEventsCount(), member.getId());
                }
                return builder.build();
            } catch (IOException | SQLException e) {
                reconcileLog.error("Cannot acquire KERL for reconciliation on: {}", member.getId(), e);
                throw new IllegalStateException("Cannot acquire KERL", e);
            } catch (Exception e) {
                reconcileLog.error("Error during reconciliation on: {}", member.getId(), e);
                return Update.getDefaultInstance();
            }
        }

        @Override
        public void update(Updating update, Digest from) {
            var ring = update.getRing();
            if (!valid(from, ring)) {
                return;
            }
            KerlDHT.this.kerlSpace.update(update.getEventsList(), kerl);
        }
    }

    private class Service implements ProtoKERLService {

        @Override
        public List<KeyState_> append(KERL_ kerl_) {
            log.debug("appending kerl on: {}", member.getId());
            return complete(k -> k.append(kerl_));
        }

        @Override
        public List<KeyState_> append(List<KeyEvent_> events) {
            log.debug("appending events on: {}", member.getId());
            return complete(k -> k.append(events));
        }

        @Override
        public List<KeyState_> append(List<KeyEvent_> events, List<AttachmentEvent> attachments) {
            log.debug("appending events and attachments on: {}", member.getId());
            return complete(k -> k.append(events, attachments));
        }

        @Override
        public Empty appendAttachments(List<AttachmentEvent> attachments) {
            log.debug("append attachments on: {}", member.getId());
            return complete(k -> k.appendAttachments(attachments));
        }

        @Override
        public Empty appendValidations(Validations validations) {
            log.debug("append validations on: {}", member.getId());
            return complete(k -> k.appendValidations(validations));
        }

        @Override
        public Attachment getAttachment(EventCoords coordinates) {
            log.trace("get attachments for coordinates on: {}", member.getId());
            return complete(k -> k.getAttachment(coordinates));
        }

        @Override
        public KERL_ getKERL(Ident identifier) {
            log.trace("get kerl for identifier on: {}", member.getId());
            return complete(k -> k.getKERL(identifier));
        }

        @Override
        public KeyEvent_ getKeyEvent(EventCoords coordinates) {
            return complete(k -> k.getKeyEvent(coordinates));
        }

        @Override
        public KeyState_ getKeyState(EventCoords coordinates) {
            log.trace("get key state for coordinates on: {}", member.getId());
            return complete(k -> k.getKeyState(coordinates));
        }

        @Override
        public KeyState_ getKeyState(Ident identifier, ULong sequenceNumber) {
            if (log.isTraceEnabled()) {
                log.trace("get key state for {}:{} on: {}", Identifier.from(identifier), sequenceNumber,
                          member.getId());
            }
            return complete(k -> k.getKeyState(identifier, sequenceNumber));
        }

        @Override
        public KeyState_ getKeyState(Ident identifier) {
            log.trace("get key state for identifier on: {}", member.getId());
            return complete(k -> k.getKeyState(identifier));
        }

        @Override
        public KeyState_ getKeyStateSeqNum(IdentAndSeq request) {
            return getKeyState(request.getIdentifier(), ULong.valueOf(request.getSequenceNumber()));
        }

        @Override
        public KeyStateWithAttachments_ getKeyStateWithAttachments(EventCoords coords) {
            log.trace("get key state with attachments for coordinates on: {}", member.getId());
            return complete(k -> k.getKeyStateWithAttachments(coords));
        }

        @Override
        public KeyStateWithEndorsementsAndValidations_ getKeyStateWithEndorsementsAndValidations(
        EventCoords coordinates) {
            log.trace("get key state with endorsements and attachments for coordinates on: {}", member.getId());
            return complete(k -> {
                final var fs = new CompletableFuture<KeyStateWithEndorsementsAndValidations_>();
                KeyStateWithAttachments_ ksa = k.getKeyStateWithAttachments(coordinates);
                var validations = complete(ks -> ks.getValidations(coordinates));

                return ksa == null ? KeyStateWithEndorsementsAndValidations_.getDefaultInstance()
                                   : KeyStateWithEndorsementsAndValidations_.newBuilder()
                                                                            .setState(ksa.getState())
                                                                            .putAllEndorsements(
                                                                            ksa.getAttachment().getEndorsementsMap())
                                                                            .addAllValidations(
                                                                            validations.getValidationsList())
                                                                            .build();
            });
        }

        @Override
        public Validations getValidations(EventCoords coordinates) {
            log.trace("get validations for coordinates on: {}", member.getId());
            return complete(k -> k.getValidations(coordinates));
        }
    }
}
