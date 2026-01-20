/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.chiralbehaviors.tron.examples.simpleProtocol.stateMaps;

import com.chiralbehaviors.tron.examples.simpleProtocol.BufferHandler;
import com.chiralbehaviors.tron.examples.simpleProtocol.SimpleFsm;

/**
 * 
 * @author hhildebrand
 * 
 */
public enum Simple implements SimpleFsm {
    CLOSED, CONNECTED() {
        @Override
        public SimpleFsm closing() {
            return CLOSED;
        }

        @Override
        public SimpleFsm readError() {
            return CLOSED;
        }

        @Override
        public SimpleFsm writeError() {
            return CLOSED;
        }
    },
    INITIAL() {
        @Override
        public SimpleFsm accepted(BufferHandler handler) {
            context().setHandler(handler);
            fsm().push(SimpleServer.ACCEPTED);
            return CONNECTED;
        }

        @Override
        public SimpleFsm connected(BufferHandler handler) {
            context().setHandler(handler);
            fsm().push(SimpleClient.CONNECTED);
            return CONNECTED;
        }
    },
    PROTOCOL_ERROR() {

    };

    @Override
    public SimpleFsm accepted(BufferHandler buffer) {
        return PROTOCOL_ERROR;
    }

    @Override
    public SimpleFsm closing() {
        return CLOSED;
    }

    @Override
    public SimpleFsm connected(BufferHandler buffer) {
        return PROTOCOL_ERROR;
    }

    @Override
    public SimpleFsm protocolError() {
        return PROTOCOL_ERROR;
    }

    @Override
    public SimpleFsm readError() {
        return CLOSED;
    }

    @Override
    public SimpleFsm readReady() {
        return CLOSED;
    }

    @Override
    public SimpleFsm sendGoodbye() {
        return PROTOCOL_ERROR;
    }

    @Override
    public SimpleFsm transmitMessage(String message) {
        return PROTOCOL_ERROR;
    }

    @Override
    public SimpleFsm writeError() {
        return CLOSED;
    }

    @Override
    public SimpleFsm writeReady() {
        return PROTOCOL_ERROR;
    }
}
