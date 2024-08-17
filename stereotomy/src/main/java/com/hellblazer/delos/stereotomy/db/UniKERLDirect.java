/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.db;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.event.AttachmentEvent;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import org.jooq.impl.DSL;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author hal.hildebrand
 */
public class UniKERLDirect extends UniKERL {

    public UniKERLDirect(Connection connection, DigestAlgorithm digestAlgorithm) {
        super(connection, digestAlgorithm);
    }

    @Override
    public KeyState append(KeyEvent event) {
        KeyState newState = processor.process(event);
        dsl.transaction(ctx -> append(DSL.using(ctx), event, newState, digestAlgorithm));
        return newState;
    }

    @Override
    public Void append(List<AttachmentEvent> events) {
        dsl.transaction(ctx -> events.forEach(event -> append(DSL.using(ctx), event)));
        return null;
    }

    @Override
    public List<KeyState> append(List<KeyEvent> events, List<AttachmentEvent> attachments) {
        List<KeyState> states = new ArrayList<>();
        dsl.transaction(ctx -> {
            var context = DSL.using(ctx);
            events.forEach(event -> {
                KeyState newState = processor.process(event);
                append(context, event, newState, digestAlgorithm);
                states.add(newState);
            });
            attachments.forEach(attach -> append(context, attach));
        });
        return states;
    }

    @Override
    public Void appendValidations(EventCoordinates coordinates, Map<EventCoordinates, JohnHancock> validations) {
        dsl.transaction(ctx -> appendValidations(DSL.using(ctx), coordinates, validations));
        return null;
    }
}
