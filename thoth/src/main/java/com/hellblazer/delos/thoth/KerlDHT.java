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
import com.hellblazer.delos.ring.QuorumException;
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
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory.digestOf;
import static com.hellblazer.delos.stereotomy.schema.tables.Identifier.IDENTIFIER;
import static com.hellblazer.delos.thoth.schema.Tables.IDENTIFIER_LOCATION_HASH;
import static com.hellblazer.delos.utils.Utils.b64;

/**
 * KerlDHT provides the replicated state store for KERLs.
 * <p>
 * Implements AutoCloseable for proper resource lifecycle management. Use try-with-resources to ensure
 * graceful shutdown of scheduler, validation executor, and connection pool.
 * </p>
 *
 * @author hal.hildebrand
 */
public class KerlDHT implements ProtoKERLService, AutoCloseable {
    private final static Logger log          = LoggerFactory.getLogger(KerlDHT.class);
    private final static Logger reconcileLog = LoggerFactory.getLogger(KerlSpace.class);

    private final Ani                                                         ani;
    private final ThothByzantineStateProvider                                 byzantineProvider;
    private final ByzantineIntelligenceCoordinator                            byzantineCoordinator;
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
    private final ExecutorService                                             validationExecutor;
    private final Service                                                     service        = new Service();
    private final AtomicBoolean                                               started        = new AtomicBoolean();
    private final Duration                                                    operationTimeout;
    private final Duration                                                    shutdownTimeout;
    private final Set<CompletableFuture<Void>>                                inFlightValidations = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, RequestContext>                                 activeRequests      = new ConcurrentHashMap<>();
    private final NonceVerifier                                               nonceVerifier;
    private volatile ScheduledFuture<?>                                       poolMonitoringTask;

    /**
     * Request context for freshness validation with cryptographic nonce (Phase 3).
     * Tracks operation, identifier, timestamp, and a unique nonce to prevent replay attacks.
     * The nonce is generated per-request and verified by {@link NonceVerifier} on response.
     *
     * @param operation  Operation name (e.g., "getKeyState")
     * @param identifier Identifier being queried
     * @param timestamp  Request creation timestamp (milliseconds)
     * @param nonce      Unique single-use value for replay protection
     */
    private record RequestContext(String operation, Digest identifier, long timestamp, String nonce) {
    }

    /**
     * Backward-compatible constructor without shutdownTimeout (uses default 10s).
     */
    public KerlDHT(Duration operationsFrequency, Context<? extends Member> context, SigningMember member,
                   BiFunction<KerlDHT, KERL.AppendKERL, KERL.AppendKERL> wrap, JdbcConnectionPool connectionPool,
                   DigestAlgorithm digestAlgorithm, Router communications, Duration operationTimeout,
                   double falsePositiveRate, StereotomyMetrics metrics, KerlDhtMetrics dhtMetrics,
                   ByzantineIntelligenceCoordinator byzantineCoordinator) {
        this(operationsFrequency, context, member, wrap, connectionPool, digestAlgorithm, communications,
             operationTimeout, falsePositiveRate, metrics, dhtMetrics, byzantineCoordinator, Duration.ofSeconds(10));
    }

    /**
     * Main constructor with configurable shutdown timeout.
     */
    public KerlDHT(Duration operationsFrequency, Context<? extends Member> context, SigningMember member,
                   BiFunction<KerlDHT, KERL.AppendKERL, KERL.AppendKERL> wrap, JdbcConnectionPool connectionPool,
                   DigestAlgorithm digestAlgorithm, Router communications, Duration operationTimeout,
                   double falsePositiveRate, StereotomyMetrics metrics, KerlDhtMetrics dhtMetrics,
                   ByzantineIntelligenceCoordinator byzantineCoordinator, Duration shutdownTimeout) {
        assert member != null;
        this.context = new DelegatedContext<>((Context<Member>) new StaticContext<>(context));
        this.member = member;
        this.operationTimeout = operationTimeout;
        this.shutdownTimeout = shutdownTimeout != null ? shutdownTimeout : Duration.ofSeconds(10);
        this.fpr = falsePositiveRate;
        this.operationsFrequency = operationsFrequency;
        this.dhtMetrics = dhtMetrics != null ? dhtMetrics : KerlDhtMetrics.noOp();
        this.byzantineProvider = new ThothByzantineStateProvider();
        this.byzantineCoordinator = byzantineCoordinator;
        this.scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual()
            .name("thoth-dht-", 0)
            .factory());
        this.validationExecutor = Executors.newVirtualThreadPerTaskExecutor();
        // Phase 3: Create default InMemoryNonceVerifier using the shared scheduler.
        // TTL matches operationTimeout so nonces expire when their request would have timed out.
        this.nonceVerifier = new InMemoryNonceVerifier(operationTimeout, 100_000, this.scheduler);
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
                                         r -> new DhtServer(r, metrics, member), DhtClient.getCreate(metrics),
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
                                                             this.dhtMetrics, scheduler);
    }

    /**
     * Backward-compatible constructor without KerlDhtMetrics (uses no-op metrics) and ByzantineCoordinator.
     */
    public KerlDHT(Duration operationsFrequency, Context<? extends Member> context, SigningMember member,
                   BiFunction<KerlDHT, KERL.AppendKERL, KERL.AppendKERL> wrap, JdbcConnectionPool connectionPool,
                   DigestAlgorithm digestAlgorithm, Router communications, Duration operationTimeout,
                   double falsePositiveRate, StereotomyMetrics metrics, KerlDhtMetrics dhtMetrics) {
        this(operationsFrequency, context, member, wrap, connectionPool, digestAlgorithm, communications,
             operationTimeout, falsePositiveRate, metrics, dhtMetrics, null, Duration.ofSeconds(10));
    }

    /**
     * Backward-compatible constructor without KerlDhtMetrics (uses no-op metrics).
     */
    public KerlDHT(Duration operationsFrequency, Context<? extends Member> context, SigningMember member,
                   BiFunction<KerlDHT, KERL.AppendKERL, KERL.AppendKERL> wrap, JdbcConnectionPool connectionPool,
                   DigestAlgorithm digestAlgorithm, Router communications, Duration operationTimeout,
                   double falsePositiveRate, StereotomyMetrics metrics) {
        this(operationsFrequency, context, member, wrap, connectionPool, digestAlgorithm, communications,
             operationTimeout, falsePositiveRate, metrics, null, null, Duration.ofSeconds(10));
    }

    public KerlDHT(Duration operationsFrequency, Context<? extends Member> context, SigningMember member,
                   JdbcConnectionPool connectionPool, DigestAlgorithm digestAlgorithm, Router communications,
                   Duration operationTimeout, double falsePositiveRate, StereotomyMetrics metrics) {
        this(operationsFrequency, context, member, (t, k) -> k, connectionPool, digestAlgorithm, communications,
             operationTimeout, falsePositiveRate, metrics, null, null, Duration.ofSeconds(10));
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

    /**
     * Check if DHT is healthy based on current metrics.
     * <p>
     * Health criteria:
     * - Validation success rate ≥ 95%
     * - Connection pool utilization < 90%
     * - Circuit breaker closed
     * </p>
     *
     * @return true if DHT is operating within SLA thresholds
     */
    public boolean isHealthy() {
        return dhtMetrics.getSnapshot().isHealthy();
    }

    /**
     * Get current health snapshot for monitoring dashboards.
     *
     * @return Current health snapshot
     */
    public KerlDhtMetrics.Snapshot getHealthSnapshot() {
        return dhtMetrics.getSnapshot();
    }

    /**
     * Get current time in milliseconds. Protected for testing.
     *
     * @return current time in milliseconds since epoch
     */
    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * Creates a request context for freshness validation with cryptographic nonce (Phase 3).
     * Each request gets a unique nonce generated by the NonceVerifier, providing
     * replay protection beyond simple timestamp comparison.
     *
     * @param operation  Operation name for tracking
     * @param identifier Identifier being queried
     * @return RequestContext with current timestamp and unique nonce
     */
    private RequestContext createRequestContext(String operation, Digest identifier) {
        return new RequestContext(operation, identifier, currentTimeMillis(), nonceVerifier.generateNonce());
    }

    /**
     * Generates a unique request ID for tracking active requests.
     *
     * @return UUID-based request ID
     */
    private String generateRequestId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Validates response freshness using timestamp comparison and cryptographic nonce verification.
     *
     * <h3>Phase 2: Timestamp freshness</h3>
     * <p>Responses older than {@code operationTimeout} are rejected as stale. This mitigates
     * many replay scenarios but is still vulnerable to clock skew attacks on Byzantine nodes.</p>
     *
     * <h3>Phase 3: Nonce uniqueness (implemented)</h3>
     * <p>Each request carries a unique cryptographic nonce generated by {@link NonceVerifier}.
     * On response, the nonce is checked against the used-nonce store. A duplicate nonce
     * indicates a replay attack, regardless of timestamp, and is rejected. The Byzantine
     * provider records a validation failure signal for the offending member.</p>
     *
     * <p>Mitigation summary:
     * <ul>
     *   <li>Response signature verification prevents cross-identifier replay attacks</li>
     *   <li>Quorum consensus requires majority agreement, limiting single-node impact</li>
     *   <li>Cryptographic nonces close the window-of-opportunity replay attack</li>
     * </ul>
     * </p>
     *
     * @param content          Response content (unused, kept for API consistency)
     * @param context          Original request context with timestamp and nonce
     * @param respondingMember Member who sent the response
     * @param requestId        Request ID for tracking
     * @param <T>              Response content type
     * @return true if response is fresh and nonce is unique, false if stale or replay detected
     */
    private <T> boolean validateResponseFreshness(T content, RequestContext context, Member respondingMember,
                                                   String requestId) {
        if (context == null) {
            log.warn("Response freshness check failed: no request context for requestId={} from member={}", requestId,
                     respondingMember.getId());
            return false;
        }

        var currentTime = currentTimeMillis();
        var responseAge = currentTime - context.timestamp;
        var timeoutMillis = operationTimeout.toMillis();

        if (responseAge > timeoutMillis) {
            log.warn("Stale response detected: operation={}, identifier={}, age={}ms, timeout={}ms, member={}",
                     context.operation, context.identifier, responseAge, timeoutMillis, respondingMember.getId());

            // Record Byzantine signal for staleness (advisory only)
            if (byzantineCoordinator != null) {
                byzantineProvider.recordValidationFailure(respondingMember.getId(),
                                                          "Stale response: age=%dms > timeout=%dms".formatted(
                                                          responseAge, timeoutMillis));
            }

            dhtMetrics.incrementValidationFailure(context.operation, "stale_response");
            return false;
        }

        // Phase 3: Cryptographic nonce verification — replay attack prevention
        if (context.nonce() != null && !nonceVerifier.recordAndVerify(context.nonce(), respondingMember.getId())) {
            log.warn("Replay attack detected: duplicate nonce for requestId={} from member={}", requestId,
                     respondingMember.getId());
            byzantineProvider.recordValidationFailure(respondingMember.getId(),
                                                      "Replay attack: duplicate nonce for request=%s".formatted(
                                                      requestId));
            dhtMetrics.incrementValidationFailure(context.operation, "replay_attack");
            return false;
        }

        return true;
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
            throw new IllegalArgumentException("append(AttachmentEvent) requires non-null event");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "append(AttachmentEvent)", member.getId());
            return null;
        }
        log.info("Append event: {} on: {}", EventCoordinates.from(event.getCoordinates()), member.getId());
        Digest identifier = digestOf(event, digestAlgorithm());
        if (identifier == null) {
            throw new IllegalArgumentException("append(AttachmentEvent) requires event with valid identifier");
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
                             () -> completeIt(result, gathered, "appendEvent"), operationsFrequency);
            List<KeyState_> s = result.get().getKeyStatesList();
            return s.isEmpty() ? KeyState_.getDefaultInstance() : s.getFirst();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DhtResourceException("append(AttachmentEvent) interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof CompletionException ce) {
                log.info("error appending event: {} on: {}", ce.getMessage(), member.getId());
                var majority = context.size() == 1 ? 1 : context.majority();
                var achieved = gathered.maxCount();
                throw new DhtQuorumException(majority, achieved, "append(AttachmentEvent)");
            }
            throw new DhtResourceException("Append event failed", e.getCause());
        }
    }

    @Override
    public List<KeyState_> append(KERL_ kerl) {
        if (kerl == null) {
            throw new IllegalArgumentException("append(KERL_) requires non-null kerl");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "append(KERL_)", member.getId());
            return Collections.emptyList();
        }
        var startNanos = System.nanoTime();
        if (kerl.getEventsList().isEmpty()) {
            return Collections.emptyList();
        }
        final var event = kerl.getEventsList().getFirst();
        Digest identifier = digestOf(event, digestAlgorithm());
        if (identifier == null) {
            throw new IllegalArgumentException("append(KERL_) requires kerl with valid identifier in first event");
        }

        // Phase 2: Lightweight pre-quorum structural validation for all events in KERL
        for (var evt : kerl.getEventsList()) {
            if (!validateEventWithAttachmentsStructure(evt)) {
                var evtIdentifier = digestOf(evt, digestAlgorithm());
                var reason = "STRUCTURAL_VALIDATION_FAILURE: KERL event has invalid structure";
                log.warn("Pre-quorum structural validation failed: {} for event: {}", reason, evtIdentifier);
                byzantineProvider.recordValidationFailure(evtIdentifier != null ? evtIdentifier : identifier, reason);
                dhtMetrics.incrementValidationFailure("appendKERL", "structural");
                // Advisory-only: Continue with append despite structural failure
            }
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
                             () -> completeIt(result, gathered, "appendKERL"), operationsFrequency);
            var keyStates = result.get().getKeyStatesList();
            dhtMetrics.recordWriteLatency("appendKERL", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumSuccess("appendKERL");
            return keyStates;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dhtMetrics.recordWriteLatency("appendKERL", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumFailure("appendKERL");
            throw new DhtResourceException("append(KERL_) interrupted", e);
        } catch (ExecutionException e) {
            dhtMetrics.recordWriteLatency("appendKERL", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error appending KERL: {} on: {}", ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("appendKERL");
                var majority = context.size() == 1 ? 1 : context.majority();
                var achieved = gathered.maxCount();
                throw new DhtQuorumException(majority, achieved, "append(KERL_)");
            }
            dhtMetrics.incrementQuorumFailure("appendKERL");
            throw new DhtResourceException("Append KERL failed", e.getCause());
        }
    }

    public KeyState_ append(KeyEvent_ event) {
        if (event == null) {
            throw new IllegalArgumentException("append(KeyEvent_) requires non-null event");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "append(KeyEvent_)", member.getId());
            return null;
        }
        var startNanos = System.nanoTime();
        Digest identifier = digestOf(event, digestAlgorithm());
        if (identifier == null) {
            throw new IllegalArgumentException("append(KeyEvent_) requires event with valid identifier");
        }

        // Phase 2: Lightweight pre-quorum structural validation (Hybrid Validation)
        // Check basic event structure without cryptographic validation
        if (!validateEventStructure(event)) {
            var reason = "STRUCTURAL_VALIDATION_FAILURE: Event has invalid structure (missing identifier, signatures, or type)";
            log.warn("Pre-quorum structural validation failed: {} for identifier: {}", reason, identifier);
            byzantineProvider.recordValidationFailure(identifier, reason);
            dhtMetrics.incrementValidationFailure("appendEvent", "structural");
            // Advisory-only: Continue with append despite structural failure
        }

        // Note: Full KERI cryptographic validation happens post-quorum via DhtValidationPipeline
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
                             () -> completeIt(result, gathered, "appendEvent"), operationsFrequency);
            var ks = result.get();
            dhtMetrics.recordWriteLatency("appendEvent", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumSuccess("appendEvent");
            return ks.getKeyStatesCount() == 0 ? KeyState_.getDefaultInstance() : ks.getKeyStatesList().getFirst();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dhtMetrics.recordWriteLatency("appendEvent", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumFailure("appendEvent");
            throw new DhtResourceException("append(KeyEvent_) interrupted", e);
        } catch (ExecutionException e) {
            dhtMetrics.recordWriteLatency("appendEvent", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error appending Key Event: {} on: {}", ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("appendEvent");
                var majority = context.size() == 1 ? 1 : context.majority();
                var achieved = gathered.maxCount();
                throw new DhtQuorumException(majority, achieved, "append(KeyEvent_)");
            }
            dhtMetrics.incrementQuorumFailure("appendEvent");
            throw new DhtResourceException("Append key event failed", e.getCause());
        }
    }

    @Override
    public List<KeyState_> append(List<KeyEvent_> events) {
        if (events == null) {
            throw new IllegalArgumentException("append(List<KeyEvent_>) requires non-null events list");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "append(List<KeyEvent_>)", member.getId());
            return Collections.emptyList();
        }
        if (events.isEmpty()) {
            return Collections.emptyList();
        }
        List<KeyState_> states = new ArrayList<>();
        events.stream().map(this::append).forEach(states::add);
        return states;
    }

    @Override
    public List<KeyState_> append(List<KeyEvent_> events, List<AttachmentEvent> attachments) {
        if (events == null) {
            throw new IllegalArgumentException("append(List<KeyEvent_>, List<AttachmentEvent>) requires non-null events list");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "append(List<KeyEvent_>, List<AttachmentEvent>)", member.getId());
            return Collections.emptyList();
        }
        if (events.isEmpty()) {
            return Collections.emptyList();
        }
        List<KeyState_> states = new ArrayList<>();
        events.stream().map(this::append).forEach(states::add);

        if (attachments != null) {
            attachments.forEach(this::append);
        }
        return states;
    }

    @Override
    public Empty appendAttachments(List<AttachmentEvent> events) {
        if (events == null) {
            throw new IllegalArgumentException("appendAttachments(List<AttachmentEvent>) requires non-null events list");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "appendAttachments", member.getId());
            return Empty.getDefaultInstance();
        }
        if (events.isEmpty()) {
            return Empty.getDefaultInstance();
        }
        final var event = events.getFirst();
        Digest identifier = digestAlgorithm().digest(event.getCoordinates().getIdentifier().toByteString());
        if (identifier == null) {
            throw new IllegalArgumentException("appendAttachments requires events with valid identifier");
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
                             () -> completeIt(result, gathered, "appendAttachments"), operationsFrequency);
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DhtResourceException("appendAttachments interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error appending attachments: {} on: {}", ce.getMessage(), member.getId());
                var majority = context.size() == 1 ? 1 : context.majority();
                var achieved = gathered.maxCount();
                throw new DhtQuorumException(majority, achieved, "appendAttachments");
            }
            throw new DhtResourceException("Append attachments failed", e.getCause());
        }
    }

    @Override
    public Empty appendValidations(Validations validations) {
        if (validations == null) {
            throw new IllegalArgumentException("appendValidations requires non-null validations");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "appendValidations", member.getId());
            return Empty.getDefaultInstance();
        }
        var startNanos = System.nanoTime();
        if (validations.getValidationsCount() == 0) {
            return null;  // Empty validations is allowed - return null per existing behavior
        }
        Digest identifier = digestAlgorithm().digest(validations.getCoordinates().getIdentifier().toByteString());
        if (identifier == null) {
            throw new IllegalArgumentException("appendValidations requires validations with valid identifier");
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
                             () -> completeIt(result, gathered, "appendValidations"), operationsFrequency);
            var empty = result.get();
            dhtMetrics.recordWriteLatency("appendValidations", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumSuccess("appendValidations");
            return empty;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dhtMetrics.recordWriteLatency("appendValidations", System.nanoTime() - startNanos);
            dhtMetrics.incrementQuorumFailure("appendValidations");
            throw new DhtResourceException("appendValidations interrupted", e);
        } catch (ExecutionException e) {
            dhtMetrics.recordWriteLatency("appendValidations", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error appending validations: {} on: {}", ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("appendValidations");
                var majority = context.size() == 1 ? 1 : context.majority();
                var achieved = gathered.maxCount();
                throw new DhtQuorumException(majority, achieved, "appendValidations");
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
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "clearCache", member.getId());
            return;
        }
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
        if (coordinates == null) {
            throw new IllegalArgumentException("getAttachment requires non-null coordinates");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "getAttachment", member.getId());
            return null;
        }
        var startNanos = System.nanoTime();
        Digest identifier = digestAlgorithm().digest(coordinates.getIdentifier().toByteString());
        if (identifier == null) {
            throw new IllegalArgumentException("getAttachment requires coordinates with valid identifier");
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
            throw new DhtResourceException("getAttachment interrupted", e);
        } catch (ExecutionException e) {
            dhtMetrics.recordReadLatency("getAttachment", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error {} : {} on: {}", operation, ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("getAttachment");
                var majority = context.size() == 1 ? 1 : context.majority();
                var achieved = gathered.maxCount();
                throw new DhtQuorumException(majority, achieved, operation);
            }
            dhtMetrics.incrementQuorumFailure("getAttachment");
            throw new DhtResourceException("Get attachment failed", e.getCause());
        }
    }

    @Override
    public KERL_ getKERL(Ident identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("getKERL requires non-null identifier");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "getKERL", member.getId());
            return null;
        }
        var startNanos = System.nanoTime();
        Digest digest = digestAlgorithm().digest(identifier.toByteString());
        if (digest == null) {
            throw new IllegalArgumentException("getKERL requires identifier with valid digest");
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
            throw new DhtResourceException("getKERL interrupted", e);
        } catch (ExecutionException e) {
            dhtMetrics.recordReadLatency("getKerl", System.nanoTime() - startNanos);
            if (e.getCause() instanceof CompletionException ce) {
                log.warn("error {} : {} on: {}", operation, ce.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("getKerl");
                var majority = context.size() == 1 ? 1 : context.majority();
                var achieved = gathered.maxCount();
                throw new DhtQuorumException(majority, achieved, operation);
            }
            dhtMetrics.incrementQuorumFailure("getKerl");
            throw new DhtResourceException("Get KERL failed", e.getCause());
        }
    }

    @Override
    public KeyEvent_ getKeyEvent(EventCoords coordinates) {
        if (coordinates == null) {
            throw new IllegalArgumentException("getKeyEvent requires non-null coordinates");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "getKeyEvent", member.getId());
            return null;
        }
        var startNanos = System.nanoTime();
        if (!coordinates.isInitialized()) {
            return KeyEvent_.getDefaultInstance();
        }
        var operation = "getKeyEvent(%s)".formatted(EventCoordinates.from(coordinates));
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
            if (e.getCause() instanceof QuorumException qe) {
                log.debug("Quorum not reached for {}: {} on: {}", operation, qe.getMessage(), member.getId());
                dhtMetrics.incrementQuorumFailure("getKeyEvent");
                return KeyEvent_.getDefaultInstance();
            }
            dhtMetrics.incrementQuorumFailure("getKeyEvent");
            throw new DhtResourceException("Get key event failed", e.getCause());
        }
    }

    @Override
    public KeyState_ getKeyState(EventCoords coordinates) {
        if (coordinates == null) {
            throw new IllegalArgumentException("getKeyState requires non-null coordinates");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "getKeyState(EventCoords)", member.getId());
            return null;
        }
        var startNanos = System.nanoTime();
        var operation = "getKeyState(%s)".formatted(EventCoordinates.from(coordinates));
        log.info("{} on: {}", operation, member.getId());
        Digest digest = digestAlgorithm().digest(coordinates.getIdentifier().toByteString());
        if (digest == null) {
            return KeyState_.getDefaultInstance();
        }

        // Phase 2: Create request context for freshness validation
        var requestId = generateRequestId();
        var requestContext = createRequestContext(operation, digest);
        activeRequests.put(requestId, requestContext);

        try {
            Instant timedOut = Instant.now().plus(operationTimeout);
            Supplier<Boolean> isTimedOut = () -> Instant.now().isAfter(timedOut);
            var result = new CompletableFuture<KeyState_>();
            QuorumResponseTracker<KeyState_> gathered = new QuorumResponseTracker<>();
            var slice = context.bftSubset(digest);
            var iter = new SliceIterator<>(context.getId().toString(), member, slice, dhtComms, scheduler);
            iter.iterate(link -> link.getKeyState(coordinates),
                         (futureSailor, tally, destination, respondingMember) -> read(result, gathered,
                                                                                      respondingMember, tally,
                                                                                      futureSailor, digest, isTimedOut,
                                                                                      destination, operation, requestId),
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
        } finally {
            // Clean up request context after operation completes
            activeRequests.remove(requestId);
        }
    }

    @Override
    public KeyState_ getKeyState(Ident identifier, ULong sequenceNumber) {
        if (identifier == null) {
            throw new IllegalArgumentException("getKeyState requires non-null identifier");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "getKeyState(Ident, ULong)", member.getId());
            return null;
        }
        var startNanos = System.nanoTime();
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
        if (identifier == null) {
            throw new IllegalArgumentException("getKeyState requires non-null identifier");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "getKeyState(Ident)", member.getId());
            return null;
        }
        var startNanos = System.nanoTime();
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
        if (request == null) {
            throw new IllegalArgumentException("getKeyStateSeqNum requires non-null request");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "getKeyStateSeqNum", member.getId());
            return null;
        }
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
        if (coordinates == null) {
            throw new IllegalArgumentException("getKeyStateWithAttachments requires non-null coordinates");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "getKeyStateWithAttachments", member.getId());
            return null;
        }
        var startNanos = System.nanoTime();
        var operation = "getKeyStateWithAttachments(%s)".formatted(EventCoordinates.from(coordinates));
        log.info("{} on: {}", operation, member.getId());
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
        if (coordinates == null) {
            throw new IllegalArgumentException("getKeyStateWithEndorsementsAndValidations requires non-null coordinates");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "getKeyStateWithEndorsementsAndValidations", member.getId());
            return null;
        }
        var startNanos = System.nanoTime();
        var operation = "getKeyStateWithEndorsementsAndValidations(%s)".formatted(EventCoordinates.from(coordinates));
        log.info("{} on: {}", operation, member.getId());
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
        if (coordinates == null) {
            throw new IllegalArgumentException("getValidations requires non-null coordinates");
        }
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "getValidations", member.getId());
            return null;
        }
        var startNanos = System.nanoTime();
        var operation = "getValidations(%s)".formatted(EventCoordinates.from(coordinates));
        log.info("{} on: {}", operation, member.getId());
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
        if (!started.get()) {
            log.debug("Operation rejected - DHT stopped: {} on: {}", "nextView", member.getId());
            return;
        }
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

        // Register Byzantine provider with coordinator (Phase 5)
        if (byzantineCoordinator != null) {
            byzantineCoordinator.registerProvider(byzantineProvider);
            log.info("Registered Thoth Byzantine provider with coordinator on: {}", member.getId());
        }

        // Start connection pool monitoring
        startPoolMonitoring();

        schedule(duration);
    }

    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping KerlDHT on: {}", member.getId());

        // 1. Stop connection pool monitoring
        stopPoolMonitoring();

        // 2. Deregister Byzantine provider from coordinator (Phase 5)
        // Note: Providers use CopyOnWriteArrayList, so removal is safe during coordinator polling
        // The coordinator will stop polling this provider after its current cycle completes
        if (byzantineCoordinator != null) {
            // Coordinator doesn't expose deregister - provider lifecycle tied to DHT lifecycle
            // Reset to clear any accumulated state before shutdown
            byzantineProvider.reset();
            log.info("Reset Thoth Byzantine provider state on: {}", member.getId());
        }

        // 3. Deregister communications (prevents new inbound)
        dhtComms.deregister(context.getId());
        reconcileComms.deregister(context.getId());

        // 3. Wait for in-flight validations (use half of shutdown timeout to allow time for executors)
        if (!inFlightValidations.isEmpty()) {
            log.debug("Waiting for {} in-flight validations", inFlightValidations.size());
            try {
                var validationTimeout = shutdownTimeout.dividedBy(2);
                CompletableFuture.allOf(inFlightValidations.toArray(new CompletableFuture[0]))
                    .orTimeout(validationTimeout.toSeconds(), TimeUnit.SECONDS)
                    .exceptionally(ex -> null)
                    .join();
            } catch (Exception e) {
                log.warn("Timeout waiting for in-flight validations during shutdown", e);
            }
        }

        // 3a. Close NonceVerifier to cancel its eviction task (uses the shared scheduler)
        nonceVerifier.close();

        // 3b. Graceful scheduler shutdown, then force if needed
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(shutdownTimeout.toSeconds(), TimeUnit.SECONDS)) {
                log.warn("Scheduler did not terminate gracefully, forcing on: {}", member.getId());
                var droppedTasks = scheduler.shutdownNow();
                if (!droppedTasks.isEmpty()) {
                    log.warn("Dropped {} scheduler tasks during forced shutdown on: {}", droppedTasks.size(),
                             member.getId());
                }
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 4. Shutdown validation executor (separate from scheduler to avoid deadlock)
        validationExecutor.shutdown();
        try {
            if (!validationExecutor.awaitTermination(shutdownTimeout.toSeconds(), TimeUnit.SECONDS)) {
                log.warn("Validation executor did not terminate gracefully, forcing on: {}", member.getId());
                var droppedTasks = validationExecutor.shutdownNow();
                if (!droppedTasks.isEmpty()) {
                    log.warn("Dropped {} validation tasks during forced shutdown on: {}", droppedTasks.size(),
                             member.getId());
                }
            }
        } catch (InterruptedException e) {
            validationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 5. Dispose connection pool
        connectionPool.dispose();

        log.info("KerlDHT stopped on: {}", member.getId());
    }

    /**
     * Implements AutoCloseable to support try-with-resources pattern.
     * Delegates to {@link #stop()} to ensure proper resource cleanup.
     * <p>
     * Safe to call multiple times - subsequent calls are no-ops due to started AtomicBoolean guard in stop().
     * </p>
     */
    @Override
    public void close() {
        stop();
    }

    private <T> T complete(Function<ProtoKERLAdapter, T> func) {
        try {
            return func.apply(new ProtoKERLAdapter(kerl));
        } catch (Exception e) {
            log.error("Error completing on: {}", member.getId(), e);
            throw new DhtResourceException("Completion operation failed", e);
        }
    }

    private <T> void completeIt(CompletableFuture<T> result, QuorumResponseTracker<T> gathered, String operation) {
        var max = gathered.maxEntry();
        var majority = context.size() == 1 ? 1 : context.majority();
        if (max != null) {
            if (max.getCount() >= majority) {
                var element = max.getElement();
                // Record quorum metrics
                dhtMetrics.recordQuorumRespondentCount(operation, gathered.size());

                // Complete result immediately to reduce tail latency
                try {
                    result.complete(element);
                } catch (Exception e) {
                    log.error("Unable to complete it on {}", member.getId(), e);
                }

                // Detect divergent responses (potential Byzantine behavior)
                var allResponses = gathered.allResponsesWithProviders();
                for (var entry : allResponses.entrySet()) {
                    if (!entry.getKey().equals(element)) {
                        // Minority response differs from majority
                        for (var provider : entry.getValue()) {
                            byzantineProvider.recordQuorumFailure(provider.getId());
                            log.debug("Member {} returned divergent response (minority)", provider.getId());
                            dhtMetrics.incrementByzantineDetection("divergent_response");
                        }
                    }
                }

                // Phase 4: Post-quorum validation for KeyStates responses (async, advisory only)
                if (element instanceof KeyStates keyStates) {
                    @SuppressWarnings("unchecked")
                    var providers = ((QuorumResponseTracker<KeyStates>) gathered).providersOf(keyStates);
                    // Run validation asynchronously - don't block result completion
                    // Use separate validationExecutor to avoid scheduler deadlock (see ADR-0013)
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
                    }, validationExecutor);
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
            var destinationId = destination != null && destination.getMember() != null ?
                                destination.getMember().getId().toString() : "<null>";
            if (destination == null) {
                log.warn("Failed {}: {} tally: {} from: <null destination>  on: {} - connection failure",
                         action, identifier, tally.get(), member.getId());
            } else {
                log.debug("Failed {}: {} tally: {} from: {}  on: {}", action, identifier, tally.get(), destinationId,
                          member.getId());
            }
            // Record Byzantine provider signals for failure tracking
            if (destination != null && destination.getMember() != null) {
                if (isTimedOut.get()) {
                    byzantineProvider.recordTimeout(destination.getMember().getId());
                } else {
                    byzantineProvider.recordQuorumFailure(destination.getMember().getId());
                }
            } else {
                // Connection failed before we could identify remote member
                // Record as environmental anomaly for local member's operations
                byzantineProvider.recordConnectionFailure(member.getId(),
                    "Connection failure during %s for %s".formatted(action, identifier));
            }
            return !isTimedOut.get();
        }
        T content = futureSailor.get();

        // Verify write acknowledgment authenticity before adding to quorum
        if (!verifyWriteAcknowledgment(content, respondingMember, destination)) {
            log.warn("Write acknowledgment verification failed for {} from: {} on: {}", action,
                     respondingMember.getId(), member.getId());
            byzantineProvider.recordSignatureFailure(respondingMember.getId(),
                                                     "Write acknowledgment verification failed");
            return !isTimedOut.get();  // Reject this response, continue with quorum
        }

        // Add response with equivocation detection
        gathered.add(content, respondingMember, byzantineProvider);
        var max = gathered.maxEntry();
        if (max != null) {
            tally.set(max.getCount());
        }
        var destinationId = destination != null && destination.getMember() != null ?
                            destination.getMember().getId().toString() : "<null>";
        if (destination == null) {
            log.warn("{}: {} tally: {} from: <null destination>  on: {} - connection failure", action,
                     identifier, tally.get(), member.getId());
        } else {
            log.trace("{}: {} tally: {} from: {} on: {}", action, identifier, tally.get(), destinationId,
                      member.getId());
        }
        return !isTimedOut.get();
    }

    private <T> boolean read(CompletableFuture<T> result, QuorumResponseTracker<T> gathered, Member respondingMember,
                             AtomicInteger tally, Optional<T> futureSailor, Digest identifier,
                             Supplier<Boolean> isTimedOut, DhtService destination, String action) {
        if (futureSailor.isEmpty()) {
            var destinationId = destination != null && destination.getMember() != null ?
                                destination.getMember().getId().toString() : "<null>";
            if (destination == null) {
                log.warn("Failed {}: {} tally: {} from: <null destination>  on: {} - this should not happen",
                         action, identifier, tally, member.getId());
            } else {
                log.debug("Failed {}: {} tally: {} from: {}  on: {}", action, identifier, tally, destinationId,
                          member.getId());
            }
            // Record Byzantine provider signals for failure tracking
            if (destination != null && destination.getMember() != null) {
                if (isTimedOut.get()) {
                    byzantineProvider.recordTimeout(destination.getMember().getId());
                } else {
                    byzantineProvider.recordQuorumFailure(destination.getMember().getId());
                }
            } else {
                // Connection failed before we could identify remote member
                // Record as environmental anomaly for local member's operations
                byzantineProvider.recordConnectionFailure(member.getId(),
                    "Connection failure during %s for %s".formatted(action, identifier));
            }
            return !isTimedOut.get();
        }
        T content = futureSailor.get();
        var destinationId = destination != null && destination.getMember() != null ?
                            destination.getMember().getId().toString() : "<null>";
        if (destination == null) {
            log.warn("{}: {} tally: {} from: <null destination>  on: {} - this should not happen", action,
                     identifier, tally.get(), member.getId());
        } else {
            log.trace("{}: {} tally: {} from: {}  on: {}", action, identifier, tally.get(), destinationId,
                      member.getId());
        }

        // Phase 2: Verify response signature before adding to quorum (advisory only)
        if (!verifyResponseSignature(content, respondingMember, destination)) {
            log.warn("Signature verification failed for {} from: {} on: {}", action, respondingMember.getId(),
                     member.getId());
            byzantineProvider.recordSignatureFailure(respondingMember.getId(),
                                                     "Response signature verification failed");
            return !isTimedOut.get();  // Reject this response, continue with quorum
        }

        // Add response with equivocation detection
        gathered.add(content, respondingMember, byzantineProvider);
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
                    // Use separate validationExecutor to avoid scheduler deadlock (see ADR-0013)
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
                    }, validationExecutor);
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

    /**
     * Read callback with freshness validation (Phase 2).
     * Overloaded version that validates response freshness before adding to quorum.
     *
     * @param result CompletableFuture to complete with result
     * @param gathered Quorum response tracker
     * @param respondingMember Member providing response
     * @param tally Current quorum tally
     * @param futureSailor Response content
     * @param identifier Identifier being queried
     * @param isTimedOut Timeout predicate
     * @param destination Destination service
     * @param action Operation name for logging
     * @param requestId Request ID for context lookup
     * @param <T> Response type
     * @return true to continue iteration, false to stop
     */
    private <T> boolean read(CompletableFuture<T> result, QuorumResponseTracker<T> gathered, Member respondingMember,
                             AtomicInteger tally, Optional<T> futureSailor, Digest identifier,
                             Supplier<Boolean> isTimedOut, DhtService destination, String action, String requestId) {
        if (futureSailor.isEmpty()) {
            var destinationId = destination != null && destination.getMember() != null ?
                                destination.getMember().getId().toString() : "<null>";
            if (destination == null) {
                log.warn("Failed {}: {} tally: {} from: <null destination>  on: {} - this should not happen",
                         action, identifier, tally, member.getId());
            } else {
                log.debug("Failed {}: {} tally: {} from: {}  on: {}", action, identifier, tally, destinationId,
                          member.getId());
            }
            // Record Byzantine provider signals for failure tracking
            if (destination != null && destination.getMember() != null) {
                if (isTimedOut.get()) {
                    byzantineProvider.recordTimeout(destination.getMember().getId());
                } else {
                    byzantineProvider.recordQuorumFailure(destination.getMember().getId());
                }
            } else {
                // Connection failed before we could identify remote member
                // Record as environmental anomaly for local member's operations
                byzantineProvider.recordConnectionFailure(member.getId(),
                    "Connection failure during %s for %s".formatted(action, identifier));
            }
            return !isTimedOut.get();
        }
        T content = futureSailor.get();
        var destinationId = destination != null && destination.getMember() != null ?
                            destination.getMember().getId().toString() : "<null>";
        if (destination == null) {
            log.warn("{}: {} tally: {} from: <null destination>  on: {} - this should not happen", action,
                     identifier, tally.get(), member.getId());
        } else {
            log.trace("{}: {} tally: {} from: {}  on: {}", action, identifier, tally.get(), destinationId,
                      member.getId());
        }

        // Phase 2: Verify response signature before adding to quorum (advisory only)
        if (!verifyResponseSignature(content, respondingMember, destination)) {
            log.warn("Signature verification failed for {} from: {} on: {}", action, respondingMember.getId(),
                     member.getId());
            byzantineProvider.recordSignatureFailure(respondingMember.getId(),
                                                     "Response signature verification failed");
            return !isTimedOut.get();  // Reject this response, continue with quorum
        }

        // Phase 2: Validate response freshness (timestamp-based)
        var requestContext = activeRequests.get(requestId);
        if (!validateResponseFreshness(content, requestContext, respondingMember, requestId)) {
            // Validation logged and Byzantine signal recorded by validateResponseFreshness()
            return !isTimedOut.get();  // Reject stale response, continue with quorum
        }

        // Add response with equivocation detection
        gathered.add(content, respondingMember, byzantineProvider);
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
                    // Use separate validationExecutor to avoid scheduler deadlock (see ADR-0013)
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
                    }, validationExecutor);
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

    /**
     * Verify read-response signature authenticity (Phase A implementation).
     *
     * <p>Delegates to {@link DhtClient#wasLastVerificationValid()} which stores the
     * result of the cryptographic verification performed inside the gRPC client at
     * the network boundary.  For the local loopback service there is no network hop
     * and no signature, so we accept unconditionally.</p>
     *
     * <p>Phase A semantics: absent signatures are accepted (backward compat with
     * mixed-version clusters).  Present-but-invalid signatures return {@code false}
     * so the caller records a Byzantine signal and rejects the response from quorum.</p>
     *
     * @param content          Response content to verify (already deserialized)
     * @param respondingMember Member that provided the response
     * @param destination      Service endpoint that returned the response
     * @return {@code true} if the signature was absent or verified;
     *         {@code false} if a signature was present but failed verification
     */
    private <T> boolean verifyResponseSignature(T content, Member respondingMember, DhtService destination) {
        if (destination instanceof DhtClient dhtClient) {
            return dhtClient.wasLastVerificationValid();
        }
        // Local loopback — no network, no signature needed
        return true;
    }

    private void reconcile(Update update, ReconciliationService link) {
        if (!started.get()) {
            return;
        }
        try {
            if (update.getEventsCount() > 0) {
                reconcileLog.trace("Received: {} events in interval reconciliation from: {} on: {}",
                                   update.getEventsCount(), link.getMember().getId(), member.getId());
                dhtMetrics.recordReconciliationEventsReceived(update.getEventsCount());
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
        var update = link.reconcile(Intervals.newBuilder()
                                             .setRing(ring)
                                             .addAllIntervals(keyIntervals.toIntervals())
                                             .setHave(kerlSpace.populate(Entropy.nextBitsStreamLong(), keyIntervals, fpr))
                                             .build());
        if (update != null && update.getEventsCount() > 0) {
            dhtMetrics.recordReconciliationEventsSent(update.getEventsCount());
        }
        return update;
    }

    private void reconcile(Duration duration) {
        if (!started.get()) {
            return;
        }
        var startNanos = System.nanoTime();
        try {
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
                }
            });
        } finally {
            dhtMetrics.recordReconciliationLatency(System.nanoTime() - startNanos);
            schedule(duration);
        }
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

    /**
     * Check if connection pool is exhausted (≥90% utilization).
     * Returns false if DHT is stopped or pool is disposed.
     *
     * @return true if pool utilization ≥ 90%
     */
    public boolean isPoolExhausted() {
        if (!started.get()) {
            return false; // DHT stopped, pool monitoring not active
        }
        try {
            var active = connectionPool.getActiveConnections();
            var max = connectionPool.getMaxConnections();
            return active >= (max * 0.9);
        } catch (Exception e) {
            // Pool may be disposed during shutdown
            log.trace("Unable to check pool exhaustion on: {}", member.getId(), e);
            return false;
        }
    }

    /**
     * Start periodic connection pool monitoring.
     * Scheduled task samples pool state and records metrics at operationsFrequency interval.
     */
    private void startPoolMonitoring() {
        poolMonitoringTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                var active = connectionPool.getActiveConnections();
                var max = connectionPool.getMaxConnections();
                var idle = max - active;

                dhtMetrics.recordConnectionPoolActive(active);
                dhtMetrics.recordConnectionPoolIdle(idle);

                log.trace("Pool state on {}: active={}, idle={}, max={}", member.getId(), active, idle, max);
            } catch (Exception e) {
                // Pool may be disposed during shutdown, log at trace to avoid noise
                log.trace("Error sampling connection pool on: {}", member.getId(), e);
            }
        }, 0, operationsFrequency.toMillis(), TimeUnit.MILLISECONDS);

        log.debug("Started connection pool monitoring on: {}", member.getId());
    }

    /**
     * Stop periodic connection pool monitoring.
     * Cancels the scheduled monitoring task.
     */
    private void stopPoolMonitoring() {
        if (poolMonitoringTask != null) {
            poolMonitoringTask.cancel(false); // Don't interrupt if running
            poolMonitoringTask = null;
            log.debug("Stopped connection pool monitoring on: {}", member.getId());
        }
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

            // TODO Phase 5: Add reconciliation validation once correct event coordinate extraction is implemented
            // Reconciliation events are KeyEventWithAttachments which require extracting coordinates from
            // the nested event type (InceptionEvent/RotationEvent/InteractionEvent via EventCommon)
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

    /**
     * Verify write-acknowledgment authenticity before adding to quorum.
     *
     * <p>Write responses (e.g. {@link KeyStates}) are not yet wrapped in
     * {@link com.hellblazer.delos.thoth.proto.SignedDhtResponse} — that is
     * planned for Phase B.  For now we perform structural validation only.</p>
     *
     * @param content          Response content from member
     * @param respondingMember Member that sent the response
     * @param destination      Destination service
     * @return {@code true} if structurally valid, {@code false} if malformed
     */
    private <T> boolean verifyWriteAcknowledgment(T content, Member respondingMember, DhtService destination) {
        if (content instanceof KeyStates keyStates) {
            return validateKeyStatesStructure(keyStates);
        }
        return true;
    }

    /**
     * Validate KeyStates structural integrity.
     *
     * @param keyStates KeyStates response to validate
     * @return true if structurally valid, false otherwise
     */
    private boolean validateKeyStatesStructure(KeyStates keyStates) {
        if (keyStates == null || !keyStates.isInitialized()) {
            return false;
        }
        // Empty KeyStates is a valid acknowledgment (e.g., new event appends, enrollment)
        // Only null or uninitialized responses are invalid
        return true;
    }

    /**
     * Lightweight pre-quorum structural validation for KeyEventWithAttachments (Phase 2 - Hybrid Validation).
     * <p>
     * Validates KeyEventWithAttachments has the same structural requirements as KeyEvent_.
     * </p>
     *
     * @param event Event with attachments to validate
     * @return true if structurally valid, false otherwise
     */
    private static boolean validateEventWithAttachmentsStructure(KeyEventWithAttachments event) {
        // Check event not null
        if (event == null) {
            return false;
        }

        // Check event has valid type (inception, rotation, or interaction)
        if (!event.hasInception() && !event.hasRotation() && !event.hasInteraction()) {
            return false;
        }

        // Extract identifier and common based on event type (same logic as KeyEvent_)
        Ident identifier;
        EventCommon common;

        if (event.hasInception()) {
            var inception = event.getInception();
            identifier = inception.getIdentifier();
            common = inception.getCommon();
        } else if (event.hasRotation()) {
            var rotation = event.getRotation();
            if (!rotation.hasSpecification() || !rotation.getSpecification().hasHeader()) {
                return false;
            }
            identifier = rotation.getSpecification().getHeader().getIdentifier();
            common = rotation.getCommon();
        } else {
            // Interaction event
            var interaction = event.getInteraction();
            if (!interaction.hasSpecification() || !interaction.getSpecification().hasHeader()) {
                return false;
            }
            identifier = interaction.getSpecification().getHeader().getIdentifier();
            common = interaction.getCommon();
        }

        // Check identifier is valid and has a type set (not default, not NONE)
        if (identifier == null || (!identifier.hasBasic() && !identifier.hasSelfAddressing()
                                   && !identifier.hasSelfSigning())) {
            return false;
        }

        // Validate identifier content is non-empty and meets minimum requirements
        if (identifier.hasBasic()) {
            var basic = identifier.getBasic();
            // PubKey.encoded must be non-empty and at least 32 bytes
            if (basic.getEncoded().isEmpty() || basic.getEncoded().size() < 32) {
                return false;
            }
        }
        if (identifier.hasSelfAddressing()) {
            var selfAddr = identifier.getSelfAddressing();
            // Digeste.hash must be non-empty (repeated uint64)
            if (selfAddr.getHashCount() == 0) {
                return false;
            }
        }
        if (identifier.hasSelfSigning()) {
            var selfSign = identifier.getSelfSigning();
            // Sig.signatures must be non-empty and each signature at least 32 bytes
            if (selfSign.getSignaturesCount() == 0) {
                return false;
            }
            for (var sig : selfSign.getSignaturesList()) {
                if (sig.isEmpty() || sig.size() < 32) {
                    return false;
                }
            }
        }

        // Check authentication signature has non-empty signatures list
        if (common == null || !common.hasAuthentication() || common.getAuthentication().getSignaturesCount() == 0) {
            return false;
        }

        return true;
    }

    /**
     * Lightweight pre-quorum structural validation (Phase 2 - Hybrid Validation).
     * <p>
     * Checks basic event structure without cryptographic validation.
     * Post-quorum crypto validation happens via DhtValidationPipeline.
     * </p>
     * <p>
     * Validates:
     * <ul>
     *   <li>Event has valid type (inception, rotation, or interaction)</li>
     *   <li>Event has valid identifier (not null, initialized)</li>
     *   <li>Event has authentication signature (not empty)</li>
     * </ul>
     * </p>
     * <p>
     * This method is package-private to enable unit testing.
     * </p>
     *
     * @param event Event to validate
     * @return true if structurally valid, false otherwise
     */
    static boolean validateEventStructure(KeyEvent_ event) {
        // Check event not null (caller should check, but defensive)
        if (event == null) {
            return false;
        }

        // Check event has valid type (inception, rotation, or interaction)
        if (!event.hasInception() && !event.hasRotation() && !event.hasInteraction()) {
            return false;
        }

        // Extract identifier and common based on event type
        Ident identifier;
        EventCommon common;

        if (event.hasInception()) {
            var inception = event.getInception();
            identifier = inception.getIdentifier();
            common = inception.getCommon();
        } else if (event.hasRotation()) {
            var rotation = event.getRotation();
            if (!rotation.hasSpecification() || !rotation.getSpecification().hasHeader()) {
                return false;
            }
            identifier = rotation.getSpecification().getHeader().getIdentifier();
            common = rotation.getCommon();
        } else {
            // Interaction event
            var interaction = event.getInteraction();
            if (!interaction.hasSpecification() || !interaction.getSpecification().hasHeader()) {
                return false;
            }
            identifier = interaction.getSpecification().getHeader().getIdentifier();
            common = interaction.getCommon();
        }

        // Check identifier is valid and has a type set (not default, not NONE)
        if (identifier == null || (!identifier.hasBasic() && !identifier.hasSelfAddressing()
                                   && !identifier.hasSelfSigning())) {
            return false;
        }

        // Validate identifier content is non-empty and meets minimum requirements
        if (identifier.hasBasic()) {
            var basic = identifier.getBasic();
            // PubKey.encoded must be non-empty and at least 32 bytes
            if (basic.getEncoded().isEmpty() || basic.getEncoded().size() < 32) {
                return false;
            }
        }
        if (identifier.hasSelfAddressing()) {
            var selfAddr = identifier.getSelfAddressing();
            // Digeste.hash must be non-empty (repeated uint64)
            if (selfAddr.getHashCount() == 0) {
                return false;
            }
        }
        if (identifier.hasSelfSigning()) {
            var selfSign = identifier.getSelfSigning();
            // Sig.signatures must be non-empty and each signature at least 32 bytes
            if (selfSign.getSignaturesCount() == 0) {
                return false;
            }
            for (var sig : selfSign.getSignaturesList()) {
                if (sig.isEmpty() || sig.size() < 32) {
                    return false;
                }
            }
        }

        // Check authentication signature has non-empty signatures list
        if (common == null || !common.hasAuthentication() || common.getAuthentication().getSignaturesCount() == 0) {
            return false;
        }

        return true;
    }
}
