/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.consensus;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.ethereal.Config;
import com.hellblazer.delos.ethereal.DataSource;
import com.hellblazer.delos.ethereal.Ethereal;
import com.hellblazer.delos.ethereal.Processor;
import com.hellblazer.delos.ethereal.memberships.ChRbcGossip;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.archipelago.Router;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Factory for creating EtherealConsensusOracle instances with appropriate configuration.
 * Handles the complex initialization of Ethereal and ChRbcGossip, wiring callbacks and
 * resolving the circular dependency between oracle and gossip.
 * <p>
 * Thread Safety: This factory is thread-safe and can be used from multiple threads.
 *
 * @author hal.hildebrand
 */
public class EtherealConsensusOracleFactory implements ConsensusOracleFactory {
    /**
     * Overhead bytes added to maxBatchByteSize for Ethereal message framing.
     */
    private static final int ETHEREAL_OVERHEAD_BYTES = 8 * 1024;

    private final Config.Builder           etherealConfigBase;
    private final Digest                   contextId;
    private final SigningMember            member;
    private final Context<Member>          membership;
    private final Router                   communications;
    private final ScheduledExecutorService scheduler;
    private final Signer                   signer;
    private final int                      maxBatchByteSize;

    /**
     * Constructs an EtherealConsensusOracleFactory with required dependencies.
     *
     * @param etherealConfigBase base configuration for Ethereal (must not be null)
     * @param contextId          context identifier (must not be null)
     * @param member             signing member (must not be null)
     * @param membership         membership context (must not be null)
     * @param communications     router for communications (must not be null)
     * @param scheduler          scheduled executor service (must not be null)
     * @param signer             signer for cryptographic operations (must not be null)
     * @param maxBatchByteSize   maximum batch size in bytes
     * @throws NullPointerException if any parameter except maxBatchByteSize is null
     */
    public EtherealConsensusOracleFactory(Config.Builder etherealConfigBase, Digest contextId, SigningMember member,
                                          Context<Member> membership, Router communications,
                                          ScheduledExecutorService scheduler, Signer signer, int maxBatchByteSize) {
        this.etherealConfigBase = Objects.requireNonNull(etherealConfigBase, "etherealConfigBase cannot be null");
        this.contextId = Objects.requireNonNull(contextId, "contextId cannot be null");
        this.member = Objects.requireNonNull(member, "member cannot be null");
        this.membership = Objects.requireNonNull(membership, "membership cannot be null");
        this.communications = Objects.requireNonNull(communications, "communications cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
        this.signer = Objects.requireNonNull(signer, "signer cannot be null");
        this.maxBatchByteSize = maxBatchByteSize;
    }

    @Override
    public ConsensusOracle create(DataSource dataSource, BiConsumer<List<ByteString>, Boolean> serialCallback,
                                  Consumer<Integer> newEpochCallback, String label, Verifier[] verifiers) {
        // Validate parameters
        Objects.requireNonNull(dataSource, "dataSource cannot be null");
        Objects.requireNonNull(serialCallback, "serialCallback cannot be null");
        Objects.requireNonNull(newEpochCallback, "newEpochCallback cannot be null");
        Objects.requireNonNull(label, "label cannot be null");
        Objects.requireNonNull(verifiers, "verifiers cannot be null");

        // Clone config (deep copy) to isolate per-oracle configuration
        var etherealConfig = etherealConfigBase.clone().setLabel(label).setSigner(signer);

        // Create Ethereal with callbacks and overhead for framing
        var ethereal = new Ethereal(etherealConfig.build(), maxBatchByteSize + ETHEREAL_OVERHEAD_BYTES, dataSource,
                                    serialCallback, newEpochCallback, label, verifiers);

        // Verify processor type before casting (resolves circular dependency)
        Object processorObj = ethereal.processor();
        if (!(processorObj instanceof Processor)) {
            throw new IllegalStateException(
                "Expected Processor but got: " + processorObj.getClass().getName());
        }
        var processor = (Processor) processorObj;

        // Create ChRbcGossip using Ethereal's processor
        var gossip = new ChRbcGossip(contextId, member, membership.allMembers().toList(), processor, communications,
                                     null, scheduler);

        return new EtherealConsensusOracle(ethereal, gossip);
    }
}
