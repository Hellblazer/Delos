/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.chiralbehaviors.tron.examples.simpleProtocol;

import com.chiralbehaviors.tron.FsmExecutor;

/**
 * 
 * @author hhildebrand
 * 
 */
public interface SimpleFsm extends FsmExecutor<SimpleProtocol, SimpleFsm> {
    default SimpleFsm accepted(BufferHandler buffer) {
        return protocolError();
    }

    default SimpleFsm closing() {
        return protocolError();
    }

    default SimpleFsm connected(BufferHandler buffer) {
        return protocolError();
    }

    default SimpleFsm protocolError() {
        throw fsm().invalidTransitionOn();
    }

    default SimpleFsm readError() {
        return protocolError();
    }

    default SimpleFsm readReady() {
        return protocolError();
    }

    default SimpleFsm sendGoodbye() {
        return protocolError();
    }

    default SimpleFsm transmitMessage(String message) {
        return protocolError();
    }

    default SimpleFsm writeError() {
        return protocolError();
    }

    default SimpleFsm writeReady() {
        return protocolError();
    }
}
