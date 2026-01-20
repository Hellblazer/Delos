/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.chiralbehaviors.tron;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a method as an exit action executed when transitioning OUT OF a state.
 *
 * Exit actions are executed automatically before the FSM transitions to a new state,
 * allowing cleanup and finalization of the current state.
 *
 * <h2>Constraints</h2>
 * <ul>
 *   <li><strong>Exactly ONE</strong> method per state must be annotated with @Exit. Defining multiple
 *       @Exit methods on the same state is an error, though the implementation will silently execute
 *       only the first method discovered by reflection (order is non-deterministic and JVM-dependent).
 *       Applications should enforce this constraint during development and testing.
 *   <li>Exit actions must NOT call push() or pop() - this causes pending transitions to elide (be skipped)
 *   <li>Exit action exceptions propagate to the transition caller
 * </ul>
 *
 * <h2>Method Signature</h2>
 * Exit actions can optionally receive the FSM context:
 * <pre>
 *   // Without context parameter
 *   @Exit
 *   void onExit() {
 *       System.out.println("Exiting state");
 *   }
 *
 *   // With context parameter
 *   @Exit
 *   void onExit(MyContext ctx) {
 *       ctx.stopTimer();
 *   }
 * </pre>
 *
 * <h2>Execution Order</h2>
 * For a transition from StateA to StateB:
 * <ol>
 *   <li>StateA's exit action executes (if defined)
 *   <li>Current state changes to StateB
 *   <li>StateB's entry action executes (if defined)
 *   <li>Control returns to transition caller
 * </ol>
 *
 * @author hhildebrand
 */
@Retention(value = RUNTIME)
@Target(value = { METHOD })
public @interface Exit {
}
