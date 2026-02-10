/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.chiralbehaviors.tron;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer implementation of FsmMetrics.
 * <p>
 * Provides comprehensive observability for FSM operations including:
 * <ul>
 *   <li>Transition counts (per state, per transition)</li>
 *   <li>Transition latency</li>
 *   <li>State duration</li>
 *   <li>Error rates (invalid transitions, failures)</li>
 *   <li>Stack depth for hierarchical FSMs</li>
 * </ul>
 *
 * @author hhildebrand
 */
public class MicrometerFsmMetrics implements FsmMetrics {

    private final String         fsmName;
    private final MeterRegistry  registry;
    private final AtomicInteger  stackDepth;

    public MicrometerFsmMetrics(String fsmName, MeterRegistry registry) {
        this.fsmName = fsmName;
        this.registry = registry;
        this.stackDepth = new AtomicInteger(0);

        // Register stack depth gauge
        registry.gauge("fsm.stack.depth", stackDepth, AtomicInteger::get);
    }

    @Override
    public void recordTransition(String fromState, String toState, String transition, long durationNanos) {
        // Record transition count
        Counter.builder("fsm.transitions")
               .description("Number of state transitions")
               .tag("fsm", fsmName)
               .tag("from", fromState)
               .tag("to", toState != null ? toState : "loopback")
               .tag("transition", transition)
               .register(registry)
               .increment();

        // Record transition latency
        Timer.builder("fsm.transition.latency")
             .description("State transition latency")
             .tag("fsm", fsmName)
             .tag("from", fromState)
             .tag("to", toState != null ? toState : "loopback")
             .tag("transition", transition)
             .register(registry)
             .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordStateDuration(String state, long durationNanos) {
        Timer.builder("fsm.state.duration")
             .description("Duration FSM remained in a state")
             .tag("fsm", fsmName)
             .tag("state", state)
             .register(registry)
             .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordInvalidTransition(String fromState, String transition) {
        Counter.builder("fsm.transitions.invalid")
               .description("Number of invalid transition attempts")
               .tag("fsm", fsmName)
               .tag("from", fromState)
               .tag("transition", transition)
               .register(registry)
               .increment();
    }

    @Override
    public void recordStackDepth(int depth) {
        stackDepth.set(depth);
    }

    @Override
    public void recordEntryAction(String state, long durationNanos) {
        Timer.builder("fsm.entry.action")
             .description("Entry action execution duration")
             .tag("fsm", fsmName)
             .tag("state", state)
             .register(registry)
             .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordExitAction(String state, long durationNanos) {
        Timer.builder("fsm.exit.action")
             .description("Exit action execution duration")
             .tag("fsm", fsmName)
             .tag("state", state)
             .register(registry)
             .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordTransitionError(String fromState, String transition, Throwable error) {
        Counter.builder("fsm.transitions.errors")
               .description("Number of transition errors")
               .tag("fsm", fsmName)
               .tag("from", fromState)
               .tag("transition", transition)
               .tag("error", error.getClass().getSimpleName())
               .register(registry)
               .increment();
    }
}
