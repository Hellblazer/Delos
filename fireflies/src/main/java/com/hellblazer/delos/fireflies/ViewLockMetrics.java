/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Timer;

/**
 * Metrics for View lock performance monitoring
 *
 * @author hal.hildebrand
 */
public interface ViewLockMetrics {

    Timer readLockAcquireTime();

    Histogram readLockHoldTime();

    Timer writeLockAcquireTime();

    Histogram writeLockHoldTime();

    Histogram threadsBlockedOnLock();
}
