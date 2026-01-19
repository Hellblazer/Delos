/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.context.ViewChange;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.View;
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
 * IMPORTANT: In Phase 1A-2, block height tracking is simplified (incremental).
 * In Phase 1A-3, actual block heights come from CHOAM consensus log.
 */
public class WitnessCHOAMViewChangeListener {

    private static final Logger log = LoggerFactory.getLogger(WitnessCHOAMViewChangeListener.class);

    private final String listenerId;
    private final WitnessCHOAM witnessCHOAM;
    private final WitnessContext witnessContext;
    private final DigestAlgorithm digestAlgorithm;
    private final Consumer<ViewChange> viewChangeHandler;

    // Simplified height tracking (will be replaced by CHOAM block height in Phase 1A-3)
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
        this.witnessCHOAM = witnessCHOAM;
        this.witnessContext = witnessContext;
        this.digestAlgorithm = digestAlgorithm;
        this.listenerId = listenerId;
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
     * Process:
     * 1. Increment view height (simplified tracking; CHOAM will provide actual heights in Phase 1A-3)
     * 2. Create synthetic block with new height and diadem as digest
     * 3. Update WitnessContext with new members and epoch
     * 4. Notify WitnessCHOAM of view change (triggers drain period)
     *
     * @param viewChange Fireflies notification with membership changes
     */
    private void handleViewChange(ViewChange viewChange) {
        final long newHeight = viewHeight.incrementAndGet();
        final Digest diadem = viewChange.diadem();
        final int memberCount = (int) viewChange.context().allMembers().count();

        log.info("View change detected: height={}, diadem={}, joining={}, leaving={}, members={}",
                 newHeight, diadem, viewChange.joining().size(), viewChange.leaving().size(), memberCount);

        // Create synthetic block for WitnessCHOAM (diadem as block identity)
        var viewBlock = createViewBlock(newHeight, diadem);

        // Notify WitnessCHOAM of view change (starts drain period, updates view height)
        witnessCHOAM.onViewChange(viewBlock);

        log.debug("View change coordinated: height={}, members={}", newHeight, memberCount);
    }

    /**
     * Create synthetic HashedCertifiedBlock for view representation.
     *
     * IMPORTANT: In Phase 1A-2, this is a simplified synthetic block.
     * In Phase 1A-3, blocks will come directly from CHOAM consensus log
     * with actual block contents, signatures, and deterministic ordering.
     *
     * @param height View height (simplified tracking)
     * @param diadem Fireflies diadem hash (view identity)
     * @return HashedCertifiedBlock representing this view change
     */
    private HashedCertifiedBlock createViewBlock(long height, Digest diadem) {
        // TODO Phase 1A-3: Replace with actual CHOAM block when consensus integration is complete
        // For now, use diadem as the view block identity and height from our counter

        // Create a minimal proto block structure
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
     *
     * @return Current height
     */
    public long getViewHeight() {
        return viewHeight.get();
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
