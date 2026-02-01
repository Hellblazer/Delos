/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinator for cross-layer Byzantine intelligence.
 * <p>
 * Polls multiple {@link ByzantineStateProvider} implementations at per-layer
 * intervals (Amendment 6), aggregates signals into {@link MemberRiskProfile},
 * and triggers async {@link ResponseHandler} actions when thresholds are exceeded.
 * </p>
 * <p>
 * <b>Concurrency Model (Fix 3)</b><br>
 * Multiple layer pollers run concurrently, each with its own scheduled task and
 * independent poll interval. Concurrent updates to {@link MemberRiskProfile} are
 * safe (uses synchronized methods internally). The {@link #evaluateResponses()} method
 * may see profile state mid-update during a layer poll - this is acceptable for
 * Byzantine detection as we're computing approximate risk, not exact values.
 * </p>
 * <p>
 * <b>Async Response Handling (Fix 1)</b><br>
 * Uses CompletableFuture for response actions. Pending responses are tracked atomically
 * to prevent duplicate actions for the same member. The response cooldown further
 * prevents re-triggering within a configurable window using atomic compare-and-set.
 * </p>
 * <p>
 * <b>Decay Behavior</b><br>
 * Scores decay each evaluation cycle. Members whose scores decay below 0.01 are
 * removed from tracking. If a layer temporarily stops reporting a member (e.g.,
 * network partition), the member will be "forgotten" and may be re-detected when
 * the partition heals. This is intentional - stale data should not persist.
 * </p>
 *
 * @author hal.hildebrand
 * @see ByzantineStateProvider
 * @see MemberRiskProfile
 * @see ResponseHandler
 */
public class ByzantineIntelligenceCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ByzantineIntelligenceCoordinator.class);

    /**
     * Minimum thread pool size for scheduler.
     */
    private static final int MIN_THREAD_POOL_SIZE = 4;

    private final IntelligenceConfig config;
    private final ResponseHandler responseHandler;
    private final ByzantineIntelligenceMetrics metrics;
    private final Clock clock;

    // Layer providers and their scheduled pollers
    private final List<ByzantineStateProvider> providers;
    private final Map<String, ScheduledFuture<?>> layerPollers;
    private final ScheduledExecutorService scheduler;

    // Member risk profiles (thread-safe)
    private final ConcurrentHashMap<Identifier, MemberRiskProfile> memberProfiles;

    // Pending responses to avoid duplicates (atomic add via Set.add() return value)
    private final Set<Identifier> pendingResponses;

    // Recent responses for cooldown tracking (atomic via putIfAbsent)
    private final ConcurrentHashMap<Identifier, Instant> recentResponses;

    // Rate limiting: responses triggered this interval (Phase 5)
    private final java.util.concurrent.atomic.AtomicInteger responsesThisInterval;

    // Signal deduplication: track seen signal fingerprints (Phase 5)
    private final ConcurrentHashMap<String, Instant> seenSignals;

    // Cached minimum poll interval (computed once at start)
    private volatile Duration minPollInterval;

    // Lifecycle
    private final AtomicBoolean started;
    private final AtomicBoolean closed;

    /**
     * Create a coordinator with the given configuration.
     *
     * @param config          Configuration for thresholds, intervals, weights
     * @param responseHandler Handler for warning and critical responses
     * @param metrics         Metrics for observability
     * @param clock           Clock for time operations (injectable for testing)
     */
    public ByzantineIntelligenceCoordinator(
        IntelligenceConfig config,
        ResponseHandler responseHandler,
        ByzantineIntelligenceMetrics metrics,
        Clock clock
    ) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.responseHandler = Objects.requireNonNull(responseHandler, "responseHandler cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");

        this.providers = new CopyOnWriteArrayList<>();
        this.layerPollers = new ConcurrentHashMap<>();
        // Thread pool scales with expected provider count, minimum 4
        this.scheduler = Executors.newScheduledThreadPool(
            MIN_THREAD_POOL_SIZE, Thread.ofVirtual().name("byzantine-intel-", 0).factory());

        this.memberProfiles = new ConcurrentHashMap<>();
        this.pendingResponses = ConcurrentHashMap.newKeySet();
        this.recentResponses = new ConcurrentHashMap<>();

        // Phase 5: Anti-feedback mechanism
        this.responsesThisInterval = new java.util.concurrent.atomic.AtomicInteger(0);
        this.seenSignals = new ConcurrentHashMap<>();

        this.started = new AtomicBoolean(false);
        this.closed = new AtomicBoolean(false);
    }

    /**
     * Create a coordinator with system clock.
     */
    public ByzantineIntelligenceCoordinator(
        IntelligenceConfig config,
        ResponseHandler responseHandler,
        ByzantineIntelligenceMetrics metrics
    ) {
        this(config, responseHandler, metrics, Clock.systemUTC());
    }

    /**
     * Register a layer provider.
     * <p>
     * Must be called before {@link #start()}.
     * </p>
     *
     * @param provider Provider to register
     * @throws IllegalStateException if already started
     */
    public void registerProvider(ByzantineStateProvider provider) {
        if (started.get()) {
            throw new IllegalStateException("Cannot register providers after start");
        }
        Objects.requireNonNull(provider, "provider cannot be null");
        providers.add(provider);
        log.info("Registered Byzantine state provider: {}", provider.getLayerName());
    }

    /**
     * Start the coordinator.
     * <p>
     * Begins polling all registered providers at their configured intervals
     * and schedules periodic response evaluation.
     * </p>
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            log.warn("Coordinator already started");
            return;
        }
        if (closed.get()) {
            throw new IllegalStateException("Coordinator has been closed");
        }

        log.info("Starting Byzantine Intelligence Coordinator with {} providers", providers.size());

        // Cache minimum poll interval
        minPollInterval = computeMinPollInterval();

        // Schedule per-layer pollers (Amendment 6)
        for (var provider : providers) {
            scheduleLayerPoller(provider);
        }

        // Schedule response evaluation (runs at fastest poll interval)
        scheduler.scheduleAtFixedRate(
            this::evaluateResponses,
            minPollInterval.toMillis(),
            minPollInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );

        log.info("Coordinator started, evaluation interval: {}ms", minPollInterval.toMillis());
    }

    /**
     * Stop the coordinator.
     * <p>
     * Cancels all scheduled tasks and shuts down the executor.
     * </p>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        log.info("Closing Byzantine Intelligence Coordinator");

        // Cancel all pollers
        for (var future : layerPollers.values()) {
            future.cancel(false);
        }
        layerPollers.clear();

        // Shutdown scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("Coordinator closed");
    }

    /**
     * Get the risk profile for a member.
     *
     * @param memberId Member to query
     * @return Profile if tracked, empty otherwise
     */
    public Optional<MemberRiskProfile> getMemberProfile(Identifier memberId) {
        return Optional.ofNullable(memberProfiles.get(memberId));
    }

    /**
     * Get all tracked member profiles.
     *
     * @return Unmodifiable map of profiles
     */
    public Map<Identifier, MemberRiskProfile> getAllProfiles() {
        return Collections.unmodifiableMap(new HashMap<>(memberProfiles));
    }

    /**
     * Get the number of members currently tracked.
     *
     * @return Member count
     */
    public int getTrackedMemberCount() {
        return memberProfiles.size();
    }

    /**
     * Check if the coordinator is running.
     *
     * @return true if started and not closed
     */
    public boolean isRunning() {
        return started.get() && !closed.get();
    }

    /**
     * Get number of pending responses.
     *
     * @return Count of in-flight response actions
     */
    public int getPendingResponseCount() {
        return pendingResponses.size();
    }

    /**
     * Reset all tracked state.
     * <p>
     * Clears all member profiles, pending responses, recent responses,
     * and anti-feedback tracking. Also resets all registered providers.
     * </p>
     */
    public void reset() {
        log.info("Resetting coordinator state");
        memberProfiles.clear();
        pendingResponses.clear();
        recentResponses.clear();
        // Phase 5: Reset anti-feedback state
        responsesThisInterval.set(0);
        seenSignals.clear();
        for (var provider : providers) {
            provider.reset();
        }
    }

    // ==================== Internal Methods ====================

    private void scheduleLayerPoller(ByzantineStateProvider provider) {
        var layerName = provider.getLayerName();
        var interval = config.getPollIntervalFor(layerName);

        var future = scheduler.scheduleAtFixedRate(
            () -> pollLayer(provider),
            interval.toMillis(),
            interval.toMillis(),
            TimeUnit.MILLISECONDS
        );

        layerPollers.put(layerName, future);
        log.debug("Scheduled poller for {} at {}ms interval", layerName, interval.toMillis());
    }

    /**
     * Poll a single layer and update member profiles.
     * <p>
     * Thread-safe: may run concurrently with other layer pollers.
     * </p>
     */
    private void pollLayer(ByzantineStateProvider provider) {
        if (closed.get()) {
            return;
        }

        var timer = metrics.layerPollDuration().time();
        try {
            var layerStates = provider.getMemberAnomalyStates();
            for (var entry : layerStates.entrySet()) {
                var memberId = entry.getKey();
                var state = entry.getValue();

                var profile = memberProfiles.computeIfAbsent(
                    memberId,
                    id -> new MemberRiskProfile(id, config)
                );
                profile.updateLayerState(state);
            }

            log.trace("Polled {} layer: {} members with anomalies",
                provider.getLayerName(), layerStates.size());

        } catch (Exception e) {
            metrics.providerErrors().inc();
            log.error("Error polling layer {}: {}", provider.getLayerName(), e.getMessage(), e);
        } finally {
            timer.stop();
        }
    }

    /**
     * Evaluate all member profiles and trigger responses.
     * <p>
     * Thread-safe: runs on scheduler thread, may see mid-update profile state.
     * Phase 5: Includes rate limiting and signal deduplication.
     * </p>
     */
    private void evaluateResponses() {
        if (closed.get()) {
            return;
        }

        var timer = metrics.evaluationCycleDuration().time();
        try {
            // Phase 5: Reset rate limit counter and record previous interval
            var previousResponses = responsesThisInterval.getAndSet(0);
            metrics.responsesPerInterval().update(previousResponses);

            metrics.trackedMemberCount().update(memberProfiles.size());

            for (var entry : memberProfiles.entrySet()) {
                var memberId = entry.getKey();
                var profile = entry.getValue();

                evaluateMember(memberId, profile);
            }

            // Apply decay to all profiles
            for (var profile : memberProfiles.values()) {
                profile.applyDecay();
            }

            // Cleanup negligible profiles
            memberProfiles.entrySet().removeIf(e -> e.getValue().isNegligible(0.01));

            // Phase 5: Cleanup expired signal fingerprints
            cleanupExpiredSignals();

        } catch (Exception e) {
            log.error("Error during response evaluation: {}", e.getMessage(), e);
        } finally {
            timer.stop();
        }
    }

    private void evaluateMember(Identifier memberId, MemberRiskProfile profile) {
        // Record score distribution
        metrics.aggregatedScoreDistribution().update((long) (profile.getAggregatedScore() * 1000));

        // Phase 5: Check rate limit before processing
        if (responsesThisInterval.get() >= config.maxResponsesPerInterval()) {
            metrics.rateLimitedSkips().inc();
            return; // Rate limit reached
        }

        // Atomically claim pending slot - if add() returns false, already pending
        // This fixes HIGH #4: race condition on pending check
        if (!pendingResponses.add(memberId)) {
            return; // Already pending
        }

        // Now we have exclusive claim on this member - check cooldown
        var now = clock.instant();
        var lastResponse = recentResponses.get(memberId);
        if (lastResponse != null) {
            var elapsed = Duration.between(lastResponse, now);
            if (elapsed.compareTo(config.responseCooldown()) < 0) {
                // Within cooldown - release the pending slot and skip
                pendingResponses.remove(memberId);
                metrics.cooldownSkips().inc();
                return;
            }
        }

        // Phase 5: Check signal deduplication
        if (isSignalDuplicate(memberId, profile)) {
            pendingResponses.remove(memberId);
            metrics.deduplicatedSignals().inc();
            return;
        }

        // Evaluate thresholds - still holding pending slot
        if (profile.isCritical()) {
            triggerCriticalResponse(memberId, profile, now);
        } else if (profile.isWarning()) {
            triggerWarningResponse(memberId, profile, now);
        } else {
            // Below thresholds - release pending slot
            pendingResponses.remove(memberId);
        }
    }

    private void triggerCriticalResponse(Identifier memberId, MemberRiskProfile profile, Instant now) {
        log.warn("CRITICAL Byzantine detection for {}: score={}, sources={}",
            memberId, String.format("%.3f", profile.getAggregatedScore()), profile.getActiveSignalSources());

        // Phase 5: Increment rate limit counter
        responsesThisInterval.incrementAndGet();

        metrics.criticalDetections().inc();
        metrics.responsesTriggered().mark();
        metrics.pendingResponses().inc();

        // Atomically claim cooldown slot to prevent duplicate responses
        // This fixes HIGH #3: race condition on cooldown check
        var previousResponse = recentResponses.putIfAbsent(memberId, now);
        if (previousResponse != null) {
            var elapsed = Duration.between(previousResponse, now);
            if (elapsed.compareTo(config.responseCooldown()) < 0) {
                // Another thread just triggered response - back off
                pendingResponses.remove(memberId);
                metrics.pendingResponses().dec();
                metrics.cooldownSkips().inc();
                return;
            }
            // Cooldown expired, update the timestamp
            recentResponses.put(memberId, now);
        }

        responseHandler.handleCritical(memberId, profile)
            .thenAccept(success -> {
                pendingResponses.remove(memberId);
                metrics.pendingResponses().dec();
                if (success) {
                    log.info("Critical response completed for {}", memberId);
                } else {
                    log.debug("Critical response skipped for {} (already handled)", memberId);
                }
            })
            .exceptionally(e -> {
                pendingResponses.remove(memberId);
                metrics.pendingResponses().dec();
                metrics.failedResponses().inc();
                log.error("Failed to handle critical response for {}: {}", memberId, e.getMessage());
                return null;
            });
    }

    private void triggerWarningResponse(Identifier memberId, MemberRiskProfile profile, Instant now) {
        log.info("WARNING Byzantine detection for {}: score={}, sources={}",
            memberId, String.format("%.3f", profile.getAggregatedScore()), profile.getActiveSignalSources());

        // Phase 5: Increment rate limit counter
        responsesThisInterval.incrementAndGet();

        metrics.warningDetections().inc();
        metrics.responsesTriggered().mark();
        metrics.pendingResponses().inc();

        // Atomically claim cooldown slot
        var previousResponse = recentResponses.putIfAbsent(memberId, now);
        if (previousResponse != null) {
            var elapsed = Duration.between(previousResponse, now);
            if (elapsed.compareTo(config.responseCooldown()) < 0) {
                pendingResponses.remove(memberId);
                metrics.pendingResponses().dec();
                metrics.cooldownSkips().inc();
                return;
            }
            recentResponses.put(memberId, now);
        }

        responseHandler.handleWarning(memberId, profile)
            .thenRun(() -> {
                pendingResponses.remove(memberId);
                metrics.pendingResponses().dec();
                log.debug("Warning response completed for {}", memberId);
            })
            .exceptionally(e -> {
                pendingResponses.remove(memberId);
                metrics.pendingResponses().dec();
                metrics.failedResponses().inc();
                log.error("Failed to handle warning response for {}: {}", memberId, e.getMessage());
                return null;
            });
    }

    private Duration computeMinPollInterval() {
        var min = config.defaultPollInterval();
        for (var interval : config.layerPollIntervals().values()) {
            if (interval.compareTo(min) < 0) {
                min = interval;
            }
        }
        return min;
    }

    // ==================== Phase 5: Anti-Feedback Helpers ====================

    /**
     * Check if a signal is a duplicate of a recently seen signal.
     * <p>
     * Creates a fingerprint from the member ID and active signal sources
     * to detect when multiple layers report the same underlying event.
     * </p>
     *
     * @param memberId Member being evaluated
     * @param profile  Risk profile with signal sources
     * @return true if this signal was recently seen (duplicate)
     */
    private boolean isSignalDuplicate(Identifier memberId, MemberRiskProfile profile) {
        var fingerprint = createSignalFingerprint(memberId, profile);
        var now = clock.instant();

        var previous = seenSignals.putIfAbsent(fingerprint, now);
        if (previous == null) {
            return false; // First time seeing this signal
        }

        // Check if within deduplication window
        var elapsed = Duration.between(previous, now);
        if (elapsed.compareTo(config.signalDeduplicationWindow()) < 0) {
            return true; // Duplicate within window
        }

        // Window expired, update timestamp
        seenSignals.put(fingerprint, now);
        return false;
    }

    /**
     * Create a fingerprint for signal deduplication.
     * <p>
     * Combines member ID with sorted signal sources to create a unique
     * identifier for this signal combination.
     * </p>
     */
    private String createSignalFingerprint(Identifier memberId, MemberRiskProfile profile) {
        var sources = profile.getActiveSignalSources().stream()
                              .sorted()
                              .toList();
        return memberId.toString() + ":" + sources.toString();
    }

    /**
     * Cleanup expired signal fingerprints.
     * <p>
     * Removes fingerprints older than the deduplication window to prevent
     * unbounded memory growth.
     * </p>
     */
    private void cleanupExpiredSignals() {
        var now = clock.instant();
        var window = config.signalDeduplicationWindow();
        seenSignals.entrySet().removeIf(e ->
            Duration.between(e.getValue(), now).compareTo(window) > 0);
    }
}
