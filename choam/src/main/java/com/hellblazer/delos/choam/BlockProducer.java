/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.proto.Assemble;
import com.hellblazer.delos.choam.proto.Block;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
import com.hellblazer.delos.choam.proto.Executions;
import com.hellblazer.delos.choam.proto.Join;
import com.hellblazer.delos.choam.support.HashedBlock;
import com.hellblazer.delos.cryptography.Digest;
import org.joou.ULong;

import java.util.Map;

/**
 * Producer interface for CHOAM block production. Implementations are responsible for creating
 * blocks of different types (genesis, checkpoint, executions, etc.) and publishing them to the
 * committee.
 *
 * @author hal.hildebrand
 */
public interface BlockProducer {
    Block checkpoint();

    Block genesis(Map<Digest, Join> joining, Digest nextViewId, HashedBlock previous);

    void onFailure();

    Block produce(ULong height, Digest prev, Assemble assemble, HashedBlock checkpoint);

    Block produce(ULong height, Digest prev, Executions executions, HashedBlock checkpoint);

    void publish(Digest hash, CertifiedBlock cb, boolean beacon);

    Block reconfigure(Map<Digest, Join> joining, Digest nextViewId, HashedBlock previous, HashedBlock checkpoint);
}
