/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.committee;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.cryptography.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Implementation of CHOAM transition recorder.
 * Submits transition metadata to CHOAM log asynchronously.
 * <p>
 * <b>Session Interface</b>:
 * Uses a generic session interface that accepts String metadata.
 * In production, this would use proper protobuf messages via CHOAM Session.
 *
 * @author hal.hildebrand
 */
public class CHOAMTransitionRecorderImpl implements CHOAMTransitionRecorder {

    private static final Logger log = LoggerFactory.getLogger(CHOAMTransitionRecorderImpl.class);

    /**
     * Generic session interface for CHOAM submission.
     * Abstracts away the actual CHOAM Session implementation for testing.
     */
    public interface Session {
        CompletableFuture<Digest> submit(String metadata);
    }

    private final Session session;
    private final Counter recordsSubmitted;
    private final Counter recordsFailed;

    /**
     * Create recorder with metrics tracking.
     *
     * @param session Session for CHOAM submission
     * @param metricRegistry Metrics registry
     */
    public CHOAMTransitionRecorderImpl(Session session, MetricRegistry metricRegistry) {
        this.session = Objects.requireNonNull(session, "session cannot be null");
        Objects.requireNonNull(metricRegistry, "metricRegistry cannot be null");

        this.recordsSubmitted = metricRegistry.counter("choam.transition.records_submitted");
        this.recordsFailed = metricRegistry.counter("choam.transition.records_failed");

        log.debug("Created CHOAMTransitionRecorderImpl with metrics tracking");
    }

    /**
     * Create recorder without metrics (for testing).
     *
     * @param session Session for CHOAM submission
     */
    public CHOAMTransitionRecorderImpl(Session session) {
        this.session = Objects.requireNonNull(session, "session cannot be null");
        this.recordsSubmitted = null;
        this.recordsFailed = null;

        log.debug("Created CHOAMTransitionRecorderImpl without metrics");
    }

    @Override
    public CompletableFuture<Digest> recordTransition(GenesisTransition transition) {
        // Marshal transition to string format
        var metadata = String.format(
            "TRANSITION{from=%s,to=%s,ts=%s,keys=%d,quorum=%s,members=%s}",
            transition.fromPhase(),
            transition.toPhase(),
            transition.timestamp(),
            transition.registeredKeyCount(),
            transition.quorumMet(),
            transition.registeredMembers().stream()
                .map(id -> id.toString())
                .collect(Collectors.toList())
        );

        // Submit to CHOAM asynchronously
        var future = session.submit(metadata);

        // Track metrics and log completion
        future.whenComplete((digest, throwable) -> {
            if (throwable == null) {
                if (recordsSubmitted != null) {
                    recordsSubmitted.inc();
                }
                log.info("Recorded genesis transition: {} -> {} (block: {})",
                    transition.fromPhase(), transition.toPhase(), digest);
            } else {
                if (recordsFailed != null) {
                    recordsFailed.inc();
                }
                log.error("Failed to record genesis transition: {} -> {}",
                    transition.fromPhase(), transition.toPhase(), throwable);
            }
        });

        return future;
    }
}
