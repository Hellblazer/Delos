/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.services;

import java.util.Optional;
import java.util.concurrent.TimeoutException;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.services.Binder.Binding;

/**
 * @author hal.hildebrand
 *
 */
public interface Resolver {
    /**
     * Answer the binding associated with the non transferable identifier
     */
    Optional<Binding> lookup(Identifier prefix) throws TimeoutException;
}
