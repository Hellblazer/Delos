/*
 * Copyright (c) 2013 ChiralBehaviors LLC, all rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
