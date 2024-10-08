/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.stereotomy.event.AttachmentEvent;
import com.hellblazer.delos.stereotomy.event.AttachmentEvent.Attachment;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.event.KeyStateWithEndorsementsAndValidations;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.joou.ULong;

import java.util.List;
import java.util.Map;

/**
 * @author hal.hildebrand
 */
public class DelegatedKERL implements KERL.AppendKERL {
    protected final AppendKERL delegate;

    public DelegatedKERL(AppendKERL delegate) {
        this.delegate = delegate;
    }

    @Override
    public KeyState append(KeyEvent event) {
        return delegate.append(event);
    }

    @Override
    public List<KeyState> append(KeyEvent... events) {
        return delegate.append(events);
    }

    @Override
    public Void append(List<AttachmentEvent> events) {
        return delegate.append(events);
    }

    @Override
    public List<KeyState> append(List<KeyEvent> events, List<AttachmentEvent> attachments) {
        return delegate.append(events, attachments);
    }

    @Override
    public Void appendValidations(EventCoordinates coordinates, Map<EventCoordinates, JohnHancock> validations) {
        return delegate.appendValidations(coordinates, validations);
    }

    @Override
    public Attachment getAttachment(EventCoordinates coordinates) {
        return delegate.getAttachment(coordinates);
    }

    @Override
    public DigestAlgorithm getDigestAlgorithm() {
        return delegate.getDigestAlgorithm();
    }

    @Override
    public KeyEvent getKeyEvent(EventCoordinates coordinates) {
        return delegate.getKeyEvent(coordinates);
    }

    @Override
    public KeyState getKeyState(EventCoordinates coordinates) {
        return delegate.getKeyState(coordinates);
    }

    @Override
    public KeyState getKeyState(Identifier identifier) {
        return delegate.getKeyState(identifier);
    }

    @Override
    public KeyState getKeyState(Identifier identifier, ULong sequenceNumber) {
        return delegate.getKeyState(identifier, sequenceNumber);
    }

    @Override
    public KeyStateWithAttachments getKeyStateWithAttachments(EventCoordinates coordinates) {
        return delegate.getKeyStateWithAttachments(coordinates);
    }

    @Override
    public KeyStateWithEndorsementsAndValidations getKeyStateWithEndorsementsAndValidations(
    EventCoordinates coordinates) {
        return delegate.getKeyStateWithEndorsementsAndValidations(coordinates);
    }

    @Override
    public Map<EventCoordinates, JohnHancock> getValidations(EventCoordinates coordinates) {
        return delegate.getValidations(coordinates);
    }

    @Override
    public Verifier.DefaultVerifier getVerifier(KeyCoordinates coordinates) {
        return delegate.getVerifier(coordinates);
    }

    @Override
    public List<EventWithAttachments> kerl(Identifier identifier) {
        return delegate.kerl(identifier);
    }
}
