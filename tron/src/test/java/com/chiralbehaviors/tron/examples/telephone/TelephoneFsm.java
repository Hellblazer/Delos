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
public interface TelephoneFsm {
    TelephoneFsm clockTimer();

    TelephoneFsm depositMoney();

    TelephoneFsm dialingDone(int callType, String areaCode, String exchange, String local);

    TelephoneFsm digit(String digit);

    TelephoneFsm emergency();

    TelephoneFsm invalidDigit();

    TelephoneFsm invalidNumber();

    TelephoneFsm leftOfHook();

    TelephoneFsm lineBusy();

    TelephoneFsm loopTimer();

    TelephoneFsm nycTemp();

    TelephoneFsm offHook();

    TelephoneFsm offHookTimer();

    TelephoneFsm onHook();

    TelephoneFsm ringTimer();

    TelephoneFsm time();
}
