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
 * safe (uses ConcurrentHashMap internally). The {@link #evaluateResponses()} method
 * may see profile state mid-update during a layer poll - this is acceptable for
 * Byzantine detection as we're computing approximate risk, not exact values.
 * </p>
 * <p>
 * <b>Async Response Handling (Fix 1)</b><br>
 * Uses CompletableFuture for response actions. Pending responses are tracked to
 * prevent duplicate actions for the same member. The response cooldown further
 * prevents re-triggering within a configurable window.
 * </p>
 * <pre>
 * pendingResponses.add(memberId);
 * responseHandler.handleCritical(memberId, profile)
 *     .thenAccept(success -> {
 *         pendingResponses.remove(memberId);
 *         if (success) recentResponses.put(memberId, clock.instant());
 *     })
 *     .exceptionally(e -> {
 *         pendingResponses.remove(memberId);
 *         log.error("Failed to handle critical response");
 *         return null;
 *     });
 * </pre>
 *
 * @author hal.hildebrand
 * @see ByzantineStateProvider
 * @see MemberRiskProfile
 * @see ResponseHandler
 */
public class ByzantineIntelligenceCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ByzantineIntelligenceCoordinator.class);

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

    // Pending responses to avoid duplicates
    private final Set<Identifier> pendingResponses;

    // Recent responses for cooldown tracking
    private final ConcurrentHashMap<Identifier, Instant> recentResponses;

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
        this.scheduler = Executors.newScheduledThreadPool(
            4, Thread.ofVirtual().name("byzantine-intel-", 0).factory());

        this.memberProfiles = new ConcurrentHashMap<>();
        this.pendingResponses = ConcurrentHashMap.newKeySet();
        this.recentResponses = new ConcurrentHashMap<>();

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

        // Schedule per-layer pollers (Amendment 6)
        for (var provider : providers) {
            scheduleLayerPoller(provider);
        }

        // Schedule response evaluation (runs at fastest poll interval)
        var evaluationInterval = getMinPollInterval();
        scheduler.scheduleAtFixedRate(
            this::evaluateResponses,
            evaluationInterval.toMillis(),
            evaluationInterval.toMillis(),
            TimeUnit.MILLISECONDS
        );

        log.info("Coordinator started, evaluation interval: {}ms", evaluationInterval.toMillis());
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
     * Clears all member profiles, pending responses, and recent responses.
     * Also resets all registered providers.
     * </p>
     */
    public void reset() {
        log.info("Resetting coordinator state");
        memberProfiles.clear();
        pendingResponses.clear();
        recentResponses.clear();
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
            log.error("Error polling layer {}: {}", provider.getLayerName(), e.getMessage(), e);
        } finally {
            timer.stop();
        }
    }

    /**
     * Evaluate all member profiles and trigger responses.
     * <p>
     * Thread-safe: runs on scheduler thread, may see mid-update profile state.
     * </p>
     */
    private void evaluateResponses() {
        if (closed.get()) {
            return;
        }

        var timer = metrics.evaluationCycleDuration().time();
        try {
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

        } catch (Exception e) {
            log.error("Error during response evaluation: {}", e.getMessage(), e);
        } finally {
            timer.stop();
        }
    }

    private void evaluateMember(Identifier memberId, MemberRiskProfile profile) {
        // Record score distribution
        metrics.aggregatedScoreDistribution().update((long) (profile.getAggregatedScore() * 1000));

        // Skip if already pending
        if (pendingResponses.contains(memberId)) {
            return;
        }

        // Check cooldown
        var lastResponse = recentResponses.get(memberId);
        if (lastResponse != null) {
            var elapsed = Duration.between(lastResponse, clock.instant());
            if (elapsed.compareTo(config.responseCooldown()) < 0) {
                metrics.cooldownSkips().inc();
                return;
            }
        }

        // Evaluate thresholds
        if (profile.isCritical()) {
            triggerCriticalResponse(memberId, profile);
        } else if (profile.isWarning()) {
            triggerWarningResponse(memberId, profile);
        }
    }

    private void triggerCriticalResponse(Identifier memberId, MemberRiskProfile profile) {
        log.warn("CRITICAL Byzantine detection for {}: score={}, sources={}",
            memberId, String.format("%.3f", profile.getAggregatedScore()), profile.getActiveSignalSources());

        metrics.criticalDetections().inc();
        metrics.responsesTriggered().mark();
        metrics.pendingResponses().inc();

        pendingResponses.add(memberId);

        responseHandler.handleCritical(memberId, profile)
            .thenAccept(success -> {
                pendingResponses.remove(memberId);
                metrics.pendingResponses().dec();
                if (success) {
                    recentResponses.put(memberId, clock.instant());
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

    private void triggerWarningResponse(Identifier memberId, MemberRiskProfile profile) {
        log.info("WARNING Byzantine detection for {}: score={}, sources={}",
            memberId, String.format("%.3f", profile.getAggregatedScore()), profile.getActiveSignalSources());

        metrics.warningDetections().inc();
        metrics.responsesTriggered().mark();
        metrics.pendingResponses().inc();

        pendingResponses.add(memberId);

        responseHandler.handleWarning(memberId, profile)
            .thenRun(() -> {
                pendingResponses.remove(memberId);
                metrics.pendingResponses().dec();
                recentResponses.put(memberId, clock.instant());
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

    private Duration getMinPollInterval() {
        var min = config.defaultPollInterval();
        for (var interval : config.layerPollIntervals().values()) {
            if (interval.compareTo(min) < 0) {
                min = interval;
            }
        }
        return min;
    }
}
