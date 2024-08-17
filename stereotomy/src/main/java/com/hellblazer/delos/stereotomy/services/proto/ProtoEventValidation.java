/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.services.proto;

import java.util.concurrent.CompletableFuture;

import com.hellblazer.delos.stereotomy.event.proto.KeyEvent_;

/**
 * @author hal.hildebrand
 */
public interface ProtoEventValidation {
    CompletableFuture<Boolean> validate(KeyEvent_ event);
}
