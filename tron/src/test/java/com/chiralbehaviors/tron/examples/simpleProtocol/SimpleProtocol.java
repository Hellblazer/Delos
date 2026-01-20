/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.chiralbehaviors.tron.examples.simpleProtocol;

/**
 * 
 * @author hhildebrand
 * 
 */
public interface SimpleProtocol {
    void ackReceived();

    void awaitAck();

    void enableSend();

    void establishClientSession();

    void sendGoodbye();

    void setHandler(BufferHandler handler);

    void transmitMessage(String message);
}
