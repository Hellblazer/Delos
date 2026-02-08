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
    private final Config.Builder etherealConfigBase;
    private final Digest contextId;
    private final SigningMember member;
    private final Context<Member> membership;
    private final Router communications;
    private final ScheduledExecutorService scheduler;
    private final Signer signer;
    private final int maxBatchByteSize;

    public EtherealConsensusOracleFactory(Config.Builder etherealConfigBase, Digest contextId, SigningMember member,
                                          Context<Member> membership, Router communications,
                                          ScheduledExecutorService scheduler, Signer signer, int maxBatchByteSize) {
        this.etherealConfigBase = etherealConfigBase;
        this.contextId = contextId;
        this.member = member;
        this.membership = membership;
        this.communications = communications;
        this.scheduler = scheduler;
        this.signer = signer;
        this.maxBatchByteSize = maxBatchByteSize;
    }

    @Override
    public ConsensusOracle create(DataSource dataSource, BiConsumer<List<ByteString>, Boolean> serialCallback,
                                  Consumer<Integer> newEpochCallback, String label, Verifier[] verifiers) {

        // Configure Ethereal
        var etherealConfig = etherealConfigBase.clone().setLabel(label).setSigner(signer);

        // Create Ethereal with callbacks
        var ethereal = new Ethereal(etherealConfig.build(), maxBatchByteSize + (8 * 1024), dataSource,
                                    serialCallback, newEpochCallback, label, verifiers);

        // Create ChRbcGossip using Ethereal's processor (resolves circular dependency)
        var gossip = new ChRbcGossip(contextId, member, membership.allMembers().toList(),
                                     (Processor) ethereal.processor(), communications, null, scheduler);

        return new EtherealConsensusOracle(ethereal, gossip);
    }
}
