/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.chiralbehaviors.tron;

/**
 * @author hal.hildebrand
 *
 */
public interface FsmExecutor<Context, T> {
    default Context context() {
        return Fsm.thisContext();
    }

    default Fsm<Context, T> fsm() {
        return Fsm.thisFsm();
    }
}
