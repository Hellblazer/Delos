/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.google.protobuf.Empty;
import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.comm.Concierge;
import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.cryptography.Digest;
import org.slf4j.Logger;

/**
 * GRPC Concierge service implementation.
 * Delegates all operations to CHOAM instance.
 * Extracted from CHOAM inner class: Trampoline.
 *
 * @author hal.hildebrand
 */
public class ConciergeService implements Concierge {
    private final CHOAM choam;
    private final Logger log;

    public ConciergeService(CHOAM choam, Logger log) {
        this.choam = choam;
        this.log = log;
    }

    @Override
    public CheckpointSegments fetch(CheckpointReplication request, Digest from) {
        return choam.fetch(request);
    }

    @Override
    public Blocks fetchBlocks(BlockReplication request, Digest from) {
        return choam.fetchBlocks(request);
    }

    @Override
    public Blocks fetchViewChain(BlockReplication request, Digest from) {
        return choam.fetchViewChain(request);
    }

    @Override
    public Empty join(SignedViewMember nextView, Digest from) {
        log.trace("Member: {} joining on: {}", from, choam.params().member().getId());
        choam.join(nextView, from);
        return Empty.getDefaultInstance();
    }

    @Override
    public Initial sync(Synchronize request, Digest from) {
        return choam.sync(request, from);
    }
}
