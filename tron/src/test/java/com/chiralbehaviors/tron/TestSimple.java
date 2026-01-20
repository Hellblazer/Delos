/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.chiralbehaviors.tron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import com.chiralbehaviors.tron.examples.simpleProtocol.BufferHandler;
import com.chiralbehaviors.tron.examples.simpleProtocol.SimpleFsm;
import com.chiralbehaviors.tron.examples.simpleProtocol.SimpleProtocol;
import com.chiralbehaviors.tron.examples.simpleProtocol.impl.SimpleProtocolImpl;
import com.chiralbehaviors.tron.examples.simpleProtocol.stateMaps.Simple;
import com.chiralbehaviors.tron.examples.simpleProtocol.stateMaps.SimpleClient;

/**
 * 
 * @author hhildebrand
 * 
 */
public class TestSimple {
    @Test
    public void testIt() {
        SimpleProtocol protocol = new SimpleProtocolImpl();
        Fsm<SimpleProtocol, SimpleFsm> fsm = Fsm.construct(protocol, SimpleFsm.class, Simple.INITIAL, true);
        verifyFsmStates(fsm, protocol);
    }

    @Test
    public void testItWithCustomClassLoader() {
        SimpleProtocol protocol = new SimpleProtocolImpl();
        Fsm<SimpleProtocol, SimpleFsm> fsm = Fsm.construct(protocol, SimpleFsm.class, SimpleFsm.class.getClassLoader(),
                                                           Simple.INITIAL, true);
        verifyFsmStates(fsm, protocol);
    }

    private void verifyFsmStates(Fsm<SimpleProtocol, SimpleFsm> fsm, SimpleProtocol protocol) {
        assertNotNull(fsm);
        BufferHandler handler = new BufferHandler();
        fsm.getTransitions().connected(handler);
        assertEquals(handler, ((SimpleProtocolImpl) protocol).getHandler());
        assertEquals(SimpleClient.CONNECTED, fsm.getCurrentState());
        fsm.getTransitions().writeReady();
        assertEquals(SimpleClient.ESTABLISH_SESSION, fsm.getCurrentState());
        fsm.getTransitions().readReady();
        assertEquals(SimpleClient.SEND_MESSAGE, fsm.getCurrentState());
        fsm.getTransitions().sendGoodbye();
        assertEquals(SimpleClient.SEND_GOODBYE, fsm.getCurrentState());
        fsm.getTransitions().readReady();
        assertEquals(Simple.CLOSED, fsm.getCurrentState());
    }
}
