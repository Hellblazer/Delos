/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.context.Context;
import org.joou.ULong;

import java.time.Duration;

/**
 * Event emitted when the block consumer detects a stall condition.
 * <p>
 * A stall is detected when the consumer thread has polled the pending queue
 * MAX_EMPTY_POLLS times consecutively without receiving any blocks. This
 * typically indicates network partition, consensus slowdown, or Byzantine
 * behavior preventing new blocks from being produced.
 * <p>
 * This event uses the observer pattern to decouple stall detection (in
 * BlockProcessorImpl) from recovery strategy (in CHOAM). The CHOAM instance
 * can register a listener and decide on appropriate recovery action based
 * on diagnosis of the root cause.
 *
 * @param lastProcessedHeight The height of the last successfully processed block
 * @param stallDuration       The duration of the stall (emptyPollCount * poll interval)
 * @param emptyPollCount      The number of consecutive empty polls that triggered the event
 * @param context             The context in which the stall occurred
 * @author hal.hildebrand
 */
public record StallDetectedEvent(ULong lastProcessedHeight, Duration stallDuration, int emptyPollCount,
                                 Context<?> context) {
}
