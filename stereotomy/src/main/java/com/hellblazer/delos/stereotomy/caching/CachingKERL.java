/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.hellblazer.delos.stereotomy.caching;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.event.AttachmentEvent;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * @author hal.hildebrand
 */
public class CachingKERL extends CachingKEL<KERL.AppendKERL> implements KERL.AppendKERL {
    private static final Logger log = LoggerFactory.getLogger(CachingKERL.class);

    public CachingKERL(Function<Function<AppendKERL, ?>, ?> kelSupplier) {
        super(kelSupplier);
    }

    public CachingKERL(Function<Function<AppendKERL, ?>, ?> kelSupplier, Caffeine<EventCoordinates, KeyState> builder,
                       Caffeine<EventCoordinates, KeyEvent> eventBuilder) {
        super(kelSupplier, builder, eventBuilder);
    }

    @Override
    public Void append(List<AttachmentEvent> event) {
        try {
            complete(kerl -> kerl.append(event));
        } catch (Throwable e) {
            log.error("Cannot complete append", e);
            return null;
        }
        return null;
    }

    @Override
    public Void appendValidations(EventCoordinates coordinates, Map<EventCoordinates, JohnHancock> validations) {
        try {
            return complete(kerl -> kerl.appendValidations(coordinates, validations));
        } catch (Throwable e) {
            log.error("Cannot complete append", e);
            return null;
        }
    }

    @Override
    public KeyState getKeyState(Identifier identifier, ULong sequenceNumber) {
        return complete(kerl -> kerl.getKeyState(identifier, sequenceNumber));
    }

    @Override
    public Map<EventCoordinates, JohnHancock> getValidations(EventCoordinates coordinates) {
        try {
            return complete(kerl -> kerl.getValidations(coordinates));
        } catch (Throwable e) {
            log.error("Cannot complete getValidations", e);
            return null;
        }
    }

    @Override
    public List<EventWithAttachments> kerl(Identifier identifier) {
        try {
            return complete(kerl -> kerl.kerl(identifier));
        } catch (Throwable e) {
            log.error("Cannot complete kerl", e);
            return null;
        }
    }
}
