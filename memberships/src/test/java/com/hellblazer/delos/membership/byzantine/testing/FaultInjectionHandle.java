/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine.testing;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BiConsumer;

/**
 * Handle for an injected fault with restoration capability.
 * <p>
 * Implements AutoCloseable for try-with-resources:
 * <pre>{@code
 * try (var fault = injector.injectEquivocation(memberId)) {
 *     // Fault is active
 *     testDetection();
 * } // Auto-restore on close
 * }</pre>
 *
 * @author hal.hildebrand
 * @see ByzantineFaultInjector
 */
public class FaultInjectionHandle implements AutoCloseable {

    private final Identifier memberId;
    private final ByzantineFaultInjector.InjectedFault fault;
    private final BiConsumer<Identifier, ByzantineFaultInjector.InjectedFault> restorer;
    private final Clock clock;
    private volatile boolean restored;

    FaultInjectionHandle(Identifier memberId,
                         ByzantineFaultInjector.InjectedFault fault,
                         BiConsumer<Identifier, ByzantineFaultInjector.InjectedFault> restorer,
                         Clock clock) {
        this.memberId = memberId;
        this.fault = fault;
        this.restorer = restorer;
        this.clock = clock;
        this.restored = false;
    }

    /**
     * Restores the system by removing the injected fault.
     * Idempotent - safe to call multiple times.
     */
    public void restore() {
        if (!restored) {
            restored = true;
            restorer.accept(memberId, fault);
        }
    }

    /**
     * Checks if this fault has been restored.
     *
     * @return true if restored
     */
    public boolean isRestored() {
        return restored;
    }

    /**
     * Gets the fault type.
     *
     * @return the injected fault type
     */
    public FaultType getType() {
        return fault.type;
    }

    /**
     * Gets the member ID this fault was injected for.
     *
     * @return the member identifier
     */
    public Identifier getMemberId() {
        return memberId;
    }

    /**
     * Gets the time the fault was injected.
     *
     * @return injection timestamp
     */
    public Instant getInjectedAt() {
        return fault.injectedAt;
    }

    /**
     * Gets the duration this fault was configured for.
     *
     * @return fault duration
     */
    public Duration getDuration() {
        return fault.duration;
    }

    /**
     * Calculates elapsed time since fault injection.
     * <p>
     * Uses the injected clock for deterministic testing support.
     * </p>
     *
     * @return elapsed duration
     */
    public Duration elapsed() {
        return Duration.between(fault.injectedAt, clock.instant());
    }

    /**
     * Checks if the fault has naturally expired (duration exceeded).
     *
     * @return true if expired
     */
    public boolean isExpired() {
        return elapsed().compareTo(fault.duration) > 0;
    }

    @Override
    public void close() {
        restore();
    }

    @Override
    public String toString() {
        return String.format("FaultInjectionHandle{member=%s, type=%s, restored=%s}",
                           memberId, fault.type, restored);
    }
}
