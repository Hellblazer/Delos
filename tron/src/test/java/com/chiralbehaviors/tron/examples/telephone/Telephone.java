/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.chiralbehaviors.tron.examples.telephone;

/**
 * 
 * @author hhildebrand
 * 
 */
public interface Telephone {
    public enum CallType {
        EMERGENCY, LOCAL, LONG_DISTANCE;
    }

    String LONG_DISTANCE = null;

    void addDisplay(String string);

    void clearDisplay();

    String getAreaCode();

    String getExchange();

    String getLocal();

    int getType();

    void loop(String string);

    void playDepositMoney();

    void playEmergency();

    void playInvalidNumber();

    void playNYCTemp();

    void playTime();

    void playTT(int d);

    void resetTimer(String string);

    void routeCall(int callType, String areaCode, String exchange, String local);

    void saveAreaCode(int d);

    void saveExchange(int d);

    void saveLocal(int d);

    void setReceiver(String string, String string2);

    void setType(CallType longDistance);

    void startClockTimer();

    void startTimer(String string, int i);

    void stopLoop(String string);

    void stopPlayback();

    void stopTimer(String timer);

    void updateClock();
}
