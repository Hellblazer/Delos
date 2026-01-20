/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.chiralbehaviors.tron;

/**
 * The exception indicating that the transition is invalid for the current state
 * of the Fsm
 * 
 * @author hhildebrand
 * 
 */
public class InvalidTransition extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidTransition(String msg) {
        super(msg);
    }

    public InvalidTransition(String string, Throwable e) {
        super(string, e);
    }

    @SuppressWarnings("unused")
    private InvalidTransition() {
    }
}
