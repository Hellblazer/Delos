/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.fsm;

import com.chiralbehaviors.tron.Entry;
import com.chiralbehaviors.tron.Exit;
import com.chiralbehaviors.tron.FsmExecutor;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import org.slf4j.LoggerFactory;

/**
 * @author hal.hildebrand
 */
public interface Combine {
    String AWAIT_REGEN = "AWAIT_REGEN";
    String AWAIT_SYNC  = "AWAIT_SYNC";

    void anchor();

    void awaitRegeneration();

    void awaitSynchronization();

    void cancelTimer(String timer);

    void combine();

    void fail();

    void recover(HashedCertifiedBlock anchor);

    void regenerate();

    void rotateViewKeys();

    enum Mercantile implements Transitions {
        /**
         * Waiting for view regeneration trigger.
         * System started but may not have full committee or view.
         * <p>
         * <b>Invariant:</b> System must be started.
         * </p>
         */
        AWAITING_REGENERATION {
            @Exit
            public void cancelTimer() {
                context().cancelTimer(Combine.AWAIT_REGEN);
            }

            @Override
            public Transitions combine() {
                context().combine();
                return null;
            }

            @Override
            public Transitions synchronizationFailed() {
                context().awaitRegeneration();
                return null;
            }

            @Entry
            public void synchronizeContext() {
                context().awaitRegeneration();
            }
        },
        /**
         * Genesis formation committee active.
         * Bootstrapping from anchor block to establish initial view.
         * <p>
         * <b>Invariant:</b> Must have GenesisFormation committee.
         * </p>
         */
        BOOTSTRAPPING {
            @Override
            public Transitions combine() {
                return null; // Just queue up any blocks
            }

            @Override
            public Transitions synchronizing() {
                return SYNCHRONIZING;
            }

            @Override
            public Transitions bootstrap(HashedCertifiedBlock anchor) {
                return null;
            }
        },
        /**
         * Creating checkpoint while operational.
         * Inherits OPERATIONAL invariants (hierarchical FSM push pattern).
         * <p>
         * <b>Invariant:</b> Must have operational state with head block (height > 0).
         * </p>
         */
        CHECKPOINTING {
            @Override
            public Transitions combine() {
                return null; // Just queue up any blocks
            }

            @Override
            public Transitions finishCheckpoint() {
                fsm().pop().combine();
                return null;
            }
        },
        /**
         * Entry point for CHOAM lifecycle.
         * System not yet started, no committee, no genesis.
         * <p>
         * <b>Invariant:</b> System must not be started.
         * </p>
         */
        INITIAL {
            @Override
            public Transitions start() {
                return RECOVERING;
            }
        },
        /**
         * Fully functional state, processing transactions.
         * Requires genesis, committee, and view to be established.
         * <p>
         * <b>Invariant:</b> Must have genesis, committee, and view.
         * </p>
         */
        OPERATIONAL {
            @Override
            public Transitions beginCheckpoint() {
                fsm().push(CHECKPOINTING);
                return null;
            }

            @Override
            public Transitions combine() {
                context().combine();
                return null;
            }

            @Override
            public Transitions rotateViewKeys() {
                context().rotateViewKeys();
                return null;
            }

        },
        /**
         * Absorbing failure state.
         * System has failed and transitions are disabled.
         * <p>
         * <b>Invariant:</b> Accepts any state configuration (no invariants).
         * </p>
         */
        PROTOCOL_FAILURE {
            @Entry
            public void failIt() {
                context().fail();
            }

            @Override
            public Transitions beginCheckpoint() {
                return null;
            }

            @Override
            public Transitions bootstrap(HashedCertifiedBlock anchor) {
                return null;
            }

            @Override
            public Transitions combine() {
                return null;
            }

            @Override
            public Transitions fail() {
                return null;
            }

            @Override
            public Transitions finishCheckpoint() {
                return null;
            }

            @Override
            public Transitions nextView() {
                return null;
            }

            @Override
            public Transitions regenerate() {
                return null;
            }

            @Override
            public Transitions regenerated() {
                return null;
            }

            @Override
            public Transitions rotateViewKeys() {
                return null;
            }

            @Override
            public Transitions start() {
                return null;
            }

            @Override
            public Transitions synchd() {
                return null;
            }

            @Override
            public Transitions synchronizationFailed() {
                return null;
            }

            @Override
            public Transitions synchronizing() {
                return null;
            }
        },
        /**
         * System started but no committee yet.
         * Waiting for bootstrap anchor or view regeneration trigger.
         * May have genesis if recovering from persistent state.
         * <p>
         * <b>Invariant:</b> System must be started without committee.
         * </p>
         */
        RECOVERING {
            @Override
            public Transitions bootstrap(HashedCertifiedBlock anchor) {
                try {
                    context().recover(anchor);
                } catch (Throwable e) {
                    LoggerFactory.getLogger(Mercantile.class).info("Unable to recover anchor: {}", anchor, e);
                    return null;
                }
                return BOOTSTRAPPING;
            }

            @Exit
            public void cancelTimer() {
                context().cancelTimer(Combine.AWAIT_SYNC);
            }

            @Override
            public Transitions combine() {
                context().anchor();
                return null;
            }

            @Override
            public Transitions regenerate() {
                return REGENERATING;
            }

            @Override
            public Transitions synchronizationFailed() {
                return AWAITING_REGENERATION;
            }

            @Entry
            public void synchronizeContext() {
                context().awaitSynchronization();
            }
        },
        /**
         * View regeneration in progress.
         * Committee required to coordinate view change.
         * <p>
         * <b>Invariant:</b> Must have committee for view regeneration.
         * </p>
         */
        REGENERATING {
            @Override
            public Transitions combine() {
                context().combine();
                return null;
            }

            @Override
            public Transitions nextView() {
                return RECOVERING;
            }

            @Override
            public Transitions regenerated() {
                return OPERATIONAL;
            }

            @Override
            public Transitions rotateViewKeys() {
                context().rotateViewKeys();
                return OPERATIONAL;
            }

            @Entry
            public void regenerateView() {
                context().regenerate();
            }
        },
        /**
         * Synchronization scheduled, committee formed.
         * Catching up to current view before transitioning to OPERATIONAL.
         * <p>
         * <b>Invariant:</b> Synchronization must be scheduled with committee.
         * </p>
         */
        SYNCHRONIZING {
            @Override
            public Transitions combine() {
                return null; // Just queue up any blocks
            }

            @Override
            public Transitions synchd() {
                return OPERATIONAL;
            }
        }
    }

    interface Transitions extends FsmExecutor<Combine, Combine.Transitions> {
        default Transitions beginCheckpoint() {
            throw fsm().invalidTransitionOn();
        }

        default Transitions bootstrap(HashedCertifiedBlock anchor) {
            throw fsm().invalidTransitionOn();
        }

        default Transitions combine() {
            throw fsm().invalidTransitionOn();
        }

        default Transitions fail() {
            return Mercantile.PROTOCOL_FAILURE;
        }

        default Transitions finishCheckpoint() {
            throw fsm().invalidTransitionOn();
        }

        default Transitions nextView() {
            return null;
        }

        default Transitions regenerate() {
            throw fsm().invalidTransitionOn();
        }

        default Transitions regenerated() {
            throw fsm().invalidTransitionOn();
        }

        default Transitions rotateViewKeys() {
            return null;
        }

        default Transitions start() {
            throw fsm().invalidTransitionOn();
        }

        default Transitions synchd() {
            throw fsm().invalidTransitionOn();
        }

        default Transitions synchronizationFailed() {
            throw fsm().invalidTransitionOn();
        }

        default Transitions synchronizing() {
            throw fsm().invalidTransitionOn();
        }
    }
}
