/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.chiralbehaviors.tron.examples.simpleProtocol.stateMaps;

import com.chiralbehaviors.tron.Entry;
import com.chiralbehaviors.tron.examples.simpleProtocol.BufferHandler;
import com.chiralbehaviors.tron.examples.simpleProtocol.SimpleFsm;
import com.chiralbehaviors.tron.examples.simpleProtocol.SimpleProtocol;

/**
 * 
 * @author hhildebrand
 * 
 */
public enum SimpleClient implements SimpleFsm {
    ACK_MESSAGE() {
        @Entry
        public void entry() {
            context().ackReceived();
        }
    },
    AWAIT_ACK() {
        @Override
        public SimpleFsm readReady() {
            return ACK_MESSAGE;
        }
    },
    CONNECTED() {
        // Context injection into Entry action
        @Entry
        public void establishClientSession(SimpleProtocol context) {
            context.establishClientSession();
        }

        @Override
        public SimpleFsm writeReady() {
            return ESTABLISH_SESSION;
        }
    },
    ESTABLISH_SESSION() {
        @Entry
        public void entry() {
            context().awaitAck();
        }

        @Override
        public SimpleFsm readReady() {
            context().enableSend();
            return SEND_MESSAGE;
        }
    },
    MessageSent() {
        @Entry
        public void entry() {
            context().awaitAck();
        }

        @Override
        public SimpleFsm writeReady() {
            return AWAIT_ACK;
        }
    },
    SEND_GOODBYE {
        @Entry
        public void entry() {
            context().sendGoodbye();
        }

        @Override
        public SimpleFsm readReady() {
            SimpleFsm popTransition = fsm().pop();
            popTransition.closing();
            return null;
        }
    },
    SEND_MESSAGE() {
        @Override
        public SimpleFsm sendGoodbye() {
            return SEND_GOODBYE;
        }

        @Override
        public SimpleFsm transmitMessage(String message) {
            context().transmitMessage(message);
            return MessageSent;
        }
    };

    @Override
    public SimpleFsm accepted(BufferHandler buffer) {
        return null;
    }

    @Override
    public SimpleFsm closing() {
        SimpleFsm popTransition = fsm().pop();
        popTransition.closing();
        return null;
    }

    @Override
    public SimpleFsm protocolError() {
        SimpleFsm popTransition = fsm().pop();
        popTransition.protocolError();
        return null;
    }

    @Override
    public SimpleFsm sendGoodbye() {
        throw fsm().invalidTransitionOn();
    }

    @Override
    public SimpleFsm transmitMessage(String message) {
        throw fsm().invalidTransitionOn();
    }
}
