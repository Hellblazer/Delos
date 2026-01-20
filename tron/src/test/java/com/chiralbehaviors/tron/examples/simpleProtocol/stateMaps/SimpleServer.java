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
public enum SimpleServer implements SimpleFsm {
    ACCEPTED, AWAIT_MESSAGE, PROCESS_MESSAGE, SESSION_ESTABLISHED,;

    @Override
    public SimpleFsm accepted(BufferHandler buffer) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SimpleFsm closing() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SimpleFsm connected(BufferHandler buffer) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SimpleFsm protocolError() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SimpleFsm readError() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SimpleFsm readReady() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SimpleFsm sendGoodbye() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SimpleFsm transmitMessage(String message) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SimpleFsm writeError() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SimpleFsm writeReady() {
        // TODO Auto-generated method stub
        return null;
    }
}
