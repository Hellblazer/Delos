/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.model.demesnes.comm;

import com.hellblazer.delos.demesne.proto.SubContext;
import com.hellblazer.delos.cryptography.proto.Digeste;

/**
 * @author hal.hildebrand
 */
public interface OuterContextService {
    void deregister(Digeste context);

    void register(SubContext context);
}
