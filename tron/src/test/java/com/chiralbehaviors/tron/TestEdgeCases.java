/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.chiralbehaviors.tron;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for FSM edge cases including stack depth limits, push/pop with context changes,
 * and pending transition eliding.
 *
 * @author hhildebrand
 */
public class TestEdgeCases {

    /**
     * Test that MAX_STACK_DEPTH (16) is enforced when pushing states.
     * The 17th push should throw IllegalStateException.
     */
    @Test
    public void testMaxStackDepthEnforcement() {
        var protocol = new EdgeCaseProtocol();
        var fsm = Fsm.construct(protocol, EdgeCaseFsm.class, EdgeCaseStates.INITIAL, false);
        fsm.enterStartState();

        // Push 16 states successfully (MAX_STACK_DEPTH = 16)
        for (var i = 0; i < 16; i++) {
            var currentDepth = i;
            assertDoesNotThrow(() -> {
                fsm.getTransitions().pushNested();
            }, "Should allow pushing up to MAX_STACK_DEPTH (16), currently at depth " + currentDepth);
            assertEquals(EdgeCaseStates.NESTED, fsm.getCurrentState());
        }

        // 17th push should fail with IllegalStateException
        var exception = assertThrows(IllegalStateException.class, () -> {
            fsm.getTransitions().pushNested();
        }, "17th push should exceed MAX_STACK_DEPTH and throw");

        assertTrue(exception.getMessage().contains("Stack overflow"),
                   "Exception message should indicate stack overflow");
        assertTrue(exception.getMessage().contains("16"),
                   "Exception message should mention MAX_STACK_DEPTH value");
    }

    /**
     * Test that push operations handle context changes correctly.
     * Push with a new context and verify context is changed and restored on pop.
     */
    @Test
    public void testPushWithContextChanges() {
        var protocol = new EdgeCaseProtocol();
        var fsm = Fsm.construct(protocol, EdgeCaseFsm.class, EdgeCaseStates.INITIAL, false);
        fsm.enterStartState();

        var originalContext = fsm.getContext();
        assertSame(protocol, originalContext);

        // Push with a new context
        var context2 = new EdgeCaseProtocol();
        protocol.setPushContext(context2);
        fsm.getTransitions().pushWithContext();

        // Should now be in NESTED with context2
        assertEquals(EdgeCaseStates.NESTED, fsm.getCurrentState());
        assertSame(context2, fsm.getContext());
        assertNotSame(originalContext, fsm.getContext());

        // Pop back via a transition that pops - should restore INITIAL and original context
        fsm.getTransitions().popBack();
        assertEquals(EdgeCaseStates.INITIAL, fsm.getCurrentState());
        assertSame(originalContext, fsm.getContext());
    }

    /**
     * Test that pop operations handle context restoration correctly with multiple pushes.
     */
    @Test
    public void testPopWithContextChanges() {
        var protocol = new EdgeCaseProtocol();
        var fsm = Fsm.construct(protocol, EdgeCaseFsm.class, EdgeCaseStates.INITIAL, false);
        fsm.enterStartState();

        var context1 = protocol;
        var context2 = new EdgeCaseProtocol();
        var context3 = new EdgeCaseProtocol();

        // Push twice with different contexts
        context1.setPushContext(context2);
        fsm.getTransitions().pushWithContext();
        assertEquals(EdgeCaseStates.NESTED, fsm.getCurrentState());
        assertSame(context2, fsm.getContext());

        context2.setPushContext(context3);
        fsm.getTransitions().pushNested();
        assertEquals(EdgeCaseStates.NESTED, fsm.getCurrentState());
        assertSame(context3, fsm.getContext());

        // Pop once - should restore context2 and NESTED
        fsm.getTransitions().popBack();
        assertEquals(EdgeCaseStates.NESTED, fsm.getCurrentState());
        assertSame(context2, fsm.getContext());

        // Pop again - should restore context1 and INITIAL
        fsm.getTransitions().popBack();
        assertEquals(EdgeCaseStates.INITIAL, fsm.getCurrentState());
        assertSame(context1, fsm.getContext());
    }

    /**
     * Test that entry actions execute when transitioning to a state.
     * This is a basic sanity check before testing more complex eliding behavior.
     */
    @Test
    public void testEntryActionExecutes() {
        var protocol = new EdgeCaseProtocol();
        var fsm = Fsm.construct(protocol, EdgeCaseFsm.class, EdgeCaseStates.INITIAL, false);
        fsm.enterStartState();

        assertFalse(protocol.isEntryActionExecuted(), "Entry action should not have executed yet");

        // Transition to a state with an entry action
        fsm.getTransitions().transitionToStateWithEntry();

        assertTrue(protocol.isEntryActionExecuted(), "Entry action should have been executed");
        assertEquals(EdgeCaseStates.STATE_WITH_ENTRY, fsm.getCurrentState());
    }


    // ===== Test Protocol and States =====

    /**
     * Simple protocol for testing edge cases.
     */
    static class EdgeCaseProtocol implements EdgeCaseFsm {
        private boolean entryActionExecuted = false;
        private EdgeCaseProtocol pushContext = null;

        public boolean isEntryActionExecuted() {
            return entryActionExecuted;
        }

        public void markEntryActionExecuted() {
            this.entryActionExecuted = true;
        }

        public void setPushContext(EdgeCaseProtocol ctx) {
            this.pushContext = ctx;
        }

        public EdgeCaseProtocol getPushContext() {
            return pushContext;
        }
    }

    /**
     * FSM transitions interface for edge case testing.
     */
    interface EdgeCaseFsm extends FsmExecutor<EdgeCaseProtocol, EdgeCaseFsm> {
        default EdgeCaseFsm transition1() {
            return protocolError();
        }

        default EdgeCaseFsm noop() {
            return protocolError();
        }

        default EdgeCaseFsm transitionToStateWithEntry() {
            return protocolError();
        }

        default EdgeCaseFsm pushNested() {
            return protocolError();
        }

        default EdgeCaseFsm pushWithContext() {
            return protocolError();
        }

        default EdgeCaseFsm popBack() {
            return protocolError();
        }

        default EdgeCaseFsm protocolError() {
            throw fsm().invalidTransitionOn();
        }
    }

    /**
     * States for edge case testing.
     */
    enum EdgeCaseStates implements EdgeCaseFsm {
        INITIAL {
            @Override
            public EdgeCaseFsm noop() {
                // No-op transition, stays in INITIAL
                return INITIAL;
            }

            @Override
            public EdgeCaseFsm transitionToStateWithEntry() {
                // Transition to a simple state with entry action (no push)
                return STATE_WITH_ENTRY;
            }

            @Override
            public EdgeCaseFsm pushNested() {
                // Push to NESTED and stay in INITIAL
                fsm().push(NESTED);
                return INITIAL;
            }

            @Override
            public EdgeCaseFsm pushWithContext() {
                // Push to NESTED with a new context
                var newContext = context().getPushContext();
                if (newContext != null) {
                    fsm().push(NESTED, newContext);
                } else {
                    fsm().push(NESTED);
                }
                return INITIAL;
            }
        },
        NESTED {
            @Override
            public EdgeCaseFsm noop() {
                // No-op transition, stays in NESTED
                return NESTED;
            }

            @Override
            public EdgeCaseFsm transition1() {
                return STATE1;
            }

            @Override
            public EdgeCaseFsm pushNested() {
                // Push another nested level
                var newContext = context().getPushContext();
                if (newContext != null) {
                    fsm().push(NESTED, newContext);
                } else {
                    fsm().push(NESTED);
                }
                return NESTED;
            }

            @Override
            public EdgeCaseFsm popBack() {
                // Pop to restore previous state
                fsm().pop().noop();
                return null;
            }
        },
        STATE1 {
            @Override
            public EdgeCaseFsm noop() {
                return STATE1;
            }
        },
        STATE_WITH_ENTRY {
            @Entry
            public void onEntry() {
                context().markEntryActionExecuted();
            }
        }
    }
}
