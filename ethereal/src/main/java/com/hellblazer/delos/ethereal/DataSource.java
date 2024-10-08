/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.ethereal;

import com.google.protobuf.ByteString;

/**
 * @author hal.hildebrand
 */
@FunctionalInterface
public interface DataSource {

    ByteString getData();
}
