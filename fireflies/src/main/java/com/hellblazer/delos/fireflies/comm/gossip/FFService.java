/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies.comm.gossip;

import com.hellblazer.delos.fireflies.proto.Gossip;
import com.hellblazer.delos.fireflies.proto.SayWhat;
import com.hellblazer.delos.fireflies.proto.State;
import com.hellblazer.delos.cryptography.Digest;

/**
 * @author hal.hildebrand
 */
public interface FFService {

    Gossip rumors(SayWhat request, Digest from);

    void update(State request, Digest from);

}
