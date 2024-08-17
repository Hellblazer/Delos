/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.services.grpc.validation;

import com.hellblazer.delos.archipelago.Link;
import com.hellblazer.delos.stereotomy.services.proto.ProtoEventValidation;

/**
 * @author hal.hildebrand
 *
 */
public interface EventValidationService extends ProtoEventValidation, Link {

}
