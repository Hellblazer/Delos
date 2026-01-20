/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
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
