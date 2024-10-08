/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.comm;

import com.google.protobuf.Empty;
import com.hellblazer.delos.choam.proto.*;
import com.hellblazer.delos.cryptography.Digest;

/**
 * @author hal.hildebrand
 */
public interface Concierge {

    CheckpointSegments fetch(CheckpointReplication request, Digest from);

    Blocks fetchBlocks(BlockReplication request, Digest from);

    Blocks fetchViewChain(BlockReplication request, Digest from);

    Empty join(SignedViewMember nextView, Digest from);

    Initial sync(Synchronize request, Digest from);

}
