/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.kairos;

import java.time.Instant;
import java.time.InstantSource;

/**
 * Controls Kairos (simulation) time
 * 
 * @author hal.hildebrand
 *
 */
public class KairosInstanceSource implements InstantSource {
    private volatile Instant instant;

    public KairosInstanceSource(Instant instant) {
        this.instant = instant;
    }

    public void advance(Instant instant) {
        this.instant = instant;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
