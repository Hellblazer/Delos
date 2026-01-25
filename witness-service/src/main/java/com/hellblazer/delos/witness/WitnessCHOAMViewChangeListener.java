/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.context.ViewChange;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.View;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * WitnessCHOAMViewChangeListener: Integrates Fireflies view changes with WitnessCHOAM state machine.
 *
 * Subscribes to Fireflies View membership changes and coordinates:
 * - WitnessCHOAM view change handling (drain periods, sequence tracking)
 * - WitnessContext epoch synchronization
 * - Drain period coordination during membership changes
 *
 * Bridge pattern: Fireflies gossip overlay (View) → WitnessCHOAM consensus handler
 *
 * Phase 1A-3: Block heights are synchronized from CHOAM consensus log.
 * Architecture reference: /Users/hal.hildebrand/git/Delos/.pm/designs/phase1a3/PHASE_1A3_ARCHITECTURE.md
 */
public class WitnessCHOAMViewChangeListener {

    private static final Logger log = LoggerFactory.getLogger(WitnessCHOAMViewChangeListener.class);

    private final String listenerId;
    private final WitnessCHOAM witnessCHOAM;
    private final WitnessContext witnessContext;
    private final DigestAlgorithm digestAlgorithm;
    private final Consumer<ViewChange> viewChangeHandler;
    private final CHOAM choam;  // Phase 1A-3: Reference to CHOAM consensus for real block heights
    private final BLSMetrics blsMetrics;  // Phase 1C: Metrics for view change tracking (nullable)

    // Simplified height tracking (used when CHOAM is null - for Phase 1A-2 backwards compatibility)
    private final AtomicLong viewHeight = new AtomicLong(0L);

    /**
     * Create listener for Fireflies view change coordination.
     *
     * @param witnessCHOAM      CHOAM state machine to update on view changes
     * @param witnessContext    Epoch and member tracking
     * @param digestAlgorithm   Algorithm for digest operations
     * @param listenerId        Unique listener ID for Fireflies registration
     */
    public WitnessCHOAMViewChangeListener(WitnessCHOAM witnessCHOAM,
                                         WitnessContext witnessContext,
                                         DigestAlgorithm digestAlgorithm,
                                         String listenerId) {
        this(witnessCHOAM, witnessContext, digestAlgorithm, listenerId, null, null);
    }

    /**
     * Create listener with CHOAM integration for real block heights (Phase 1A-3).
     *
     * @param witnessCHOAM      CHOAM state machine to update on view changes
     * @param witnessContext    Epoch and member tracking
     * @param digestAlgorithm   Algorithm for digest operations
     * @param listenerId        Unique listener ID for Fireflies registration
     * @param choam             CHOAM consensus reference for real block heights (null for Phase 1A-2 mode)
     */
    public WitnessCHOAMViewChangeListener(WitnessCHOAM witnessCHOAM,
                                         WitnessContext witnessContext,
                                         DigestAlgorithm digestAlgorithm,
                                         String listenerId,
                                         CHOAM choam) {
        this(witnessCHOAM, witnessContext, digestAlgorithm, listenerId, choam, null);
    }

    /**
     * Create listener with CHOAM integration and metrics (Phase 1C).
     *
     * @param witnessCHOAM      CHOAM state machine to update on view changes
     * @param witnessContext    Epoch and member tracking
     * @param digestAlgorithm   Algorithm for digest operations
     * @param listenerId        Unique listener ID for Fireflies registration
     * @param choam             CHOAM consensus reference for real block heights (null for Phase 1A-2 mode)
     * @param blsMetrics        BLS metrics for view change tracking (nullable for Phase 1A-2 backwards compatibility)
     */
    public WitnessCHOAMViewChangeListener(WitnessCHOAM witnessCHOAM,
                                         WitnessContext witnessContext,
                                         DigestAlgorithm digestAlgorithm,
                                         String listenerId,
                                         CHOAM choam,
                                         BLSMetrics blsMetrics) {
        this.witnessCHOAM = witnessCHOAM;
        this.witnessContext = witnessContext;
        this.digestAlgorithm = digestAlgorithm;
        this.listenerId = listenerId;
        this.choam = choam;
        this.blsMetrics = blsMetrics;
        this.viewChangeHandler = createViewChangeHandler();
    }

    /**
     * Register this listener with Fireflies View.
     *
     * @param view Fireflies View to subscribe to
     */
    public void register(View view) {
        view.register(listenerId, viewChangeHandler);
        log.info("WitnessCHOAM view change listener registered: {}", listenerId);
    }

    /**
     * Deregister this listener from Fireflies View.
     *
     * @param view Fireflies View to unsubscribe from
     */
    public void deregister(View view) {
        view.deregister(viewChangeHandler);
        log.info("WitnessCHOAM view change listener deregistered: {}", listenerId);
    }

    /**
     * Create the view change handler that processes Fireflies notifications.
     *
     * @return Consumer processing ViewChange events
     */
    private Consumer<ViewChange> createViewChangeHandler() {
        return (viewChange) -> {
            try {
                handleViewChange(viewChange);
            } catch (Exception e) {
                log.error("Error handling view change: diadem={}", viewChange.diadem(), e);
            }
        };
    }

    /**
     * Handle Fireflies view change: coordinate membership update with WitnessCHOAM.
     *
     * Phase 1A-3 Process:
     * 1. Get actual block height from CHOAM consensus (if available)
     * 2. Verify height continuity and detect gaps
     * 3. Create block with real CHOAM height or synthetic height (backwards compatibility)
     * 4. Update WitnessContext with new members and epoch
     * 5. Notify WitnessCHOAM of view change (triggers drain period)
     *
     * @param viewChange Fireflies notification with membership changes
     */
    private void handleViewChange(ViewChange viewChange) {
        // Phase 1C: Record view change initiation metrics
        if (blsMetrics != null) {
            blsMetrics.incrementViewChangesInitiated();
        }

        long startNanos = System.nanoTime();

        // Phase 1A-3: Use CHOAM consensus height if available
        final long newHeight;
        if (choam != null) {
            ULong choamHeight = choam.currentHeight();
            newHeight = (choamHeight != null) ? choamHeight.longValue() : viewHeight.incrementAndGet();
            if (choamHeight != null) {
                log.debug("Using CHOAM consensus height: {}", newHeight);

                // Verify height continuity across view changes
                if (!verifyHeightContinuity(newHeight)) {
                    log.error("Height continuity violation detected, proceeding with caution");
                }

                // Detect and log height gaps during recovery
                long expectedHeight = viewHeight.get() + 1;
                long gap = detectHeightGap(expectedHeight, newHeight);
                if (gap > 0) {
                    log.warn("Detected {} missing blocks during recovery, height jumped from {} to {}",
                            gap, expectedHeight - 1, newHeight);
                }
            } else {
                log.warn("CHOAM height unavailable, using synthetic height: {}", newHeight);
            }
        } else {
            // Phase 1A-2 backwards compatibility: incremental counter
            newHeight = viewHeight.incrementAndGet();
        }

        final Digest diadem = viewChange.diadem();
        final int memberCount = (int) viewChange.context().allMembers().count();

        log.info("View change detected: height={}, diadem={}, joining={}, leaving={}, members={}",
                 newHeight, diadem, viewChange.joining().size(), viewChange.leaving().size(), memberCount);

        // Create block for WitnessCHOAM with real or synthetic height
        var viewBlock = createViewBlock(newHeight, diadem);

        // Notify WitnessCHOAM of view change (starts drain period, updates view height)
        witnessCHOAM.onViewChange(viewBlock);

        // Phase 1C: Record view change duration and active view
        if (blsMetrics != null) {
            long durationNanos = System.nanoTime() - startNanos;
            long durationMicros = durationNanos / 1000; // Convert nanoseconds to microseconds
            blsMetrics.recordViewChangeDuration(durationMicros);
            blsMetrics.setActiveView(newHeight);
        }

        log.debug("View change coordinated: height={}, members={}", newHeight, memberCount);
    }

    /**
     * Create HashedCertifiedBlock for view representation.
     *
     * Phase 1A-3: Block height comes from CHOAM consensus log when available.
     * Uses diadem as view block identity for deterministic ordering.
     *
     * @param height View height (from CHOAM or synthetic counter)
     * @param diadem Fireflies diadem hash (view identity)
     * @return HashedCertifiedBlock representing this view change
     */
    private HashedCertifiedBlock createViewBlock(long height, Digest diadem) {
        // Create a minimal proto block structure with real or synthetic height
        var header = com.hellblazer.delos.choam.proto.Header.newBuilder()
            .setHeight(height)
            .build();

        var block = com.hellblazer.delos.choam.proto.Block.newBuilder()
            .setHeader(header)
            .build();

        var certifiedBlock = com.hellblazer.delos.choam.proto.CertifiedBlock.newBuilder()
            .setBlock(block)
            .build();

        return new HashedCertifiedBlock(digestAlgorithm, certifiedBlock);
    }

    /**
     * Get current view height (for testing and monitoring).
     * Returns CHOAM consensus height if available, otherwise synthetic counter.
     *
     * @return Current height
     */
    public long getViewHeight() {
        if (choam != null) {
            var choamHeight = choam.currentHeight();
            if (choamHeight != null) {
                return choamHeight.longValue();
            }
        }
        return viewHeight.get();
    }

    /**
     * Detect height gaps during recovery.
     * Used to identify missing blocks or discontinuities.
     *
     * @param expectedHeight Expected next height
     * @param actualHeight   Actual height from CHOAM
     * @return Gap size (0 if no gap, positive if gap exists)
     */
    public long detectHeightGap(long expectedHeight, long actualHeight) {
        if (actualHeight > expectedHeight) {
            long gap = actualHeight - expectedHeight;
            log.warn("Height gap detected: expected={}, actual={}, gap={}", expectedHeight, actualHeight, gap);
            return gap;
        }
        return 0;
    }

    /**
     * Verify height continuity across view changes.
     * Ensures monotonic increase without backwards transitions.
     *
     * @param newHeight New height to validate
     * @return true if height is valid (monotonic increase), false if violation
     */
    public boolean verifyHeightContinuity(long newHeight) {
        long currentHeight = getViewHeight();
        if (newHeight < currentHeight) {
            log.error("Height continuity violation: current={}, new={}", currentHeight, newHeight);
            return false;
        }
        if (newHeight > currentHeight + 1) {
            log.warn("Height discontinuity: current={}, new={}, gap={}", currentHeight, newHeight, newHeight - currentHeight);
        }
        return true;
    }

    /**
     * Reset view height counter (for testing).
     *
     * @param height New height value
     */
    public void setViewHeight(long height) {
        viewHeight.set(height);
    }
}
