/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.hellblazer.delos.thoth.grpc.reconciliation;

import com.google.protobuf.Empty;
import com.hellblazer.delos.thoth.proto.Intervals;
import com.hellblazer.delos.thoth.proto.Update;
import com.hellblazer.delos.thoth.proto.Updating;
import com.hellblazer.delos.archipelago.Link;

/**
 * @author hal.hildebrand
 */
public interface ReconciliationService extends Link {

    Update reconcile(Intervals intervals);

    Empty update(Updating update);

}
