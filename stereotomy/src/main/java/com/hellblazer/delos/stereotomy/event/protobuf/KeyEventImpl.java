/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.event.protobuf;

import static com.hellblazer.delos.cryptography.QualifiedBase64.digest;
import static com.hellblazer.delos.cryptography.QualifiedBase64.signature;
import static com.hellblazer.delos.stereotomy.identifier.QualifiedBase64Identifier.identifier;

import org.joou.ULong;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.stereotomy.event.proto.EventCommon;
import com.hellblazer.delos.stereotomy.event.proto.Header;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.event.Version;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

/**
 * Grpc implemention of abstract KeyEvent
 *
 * @author hal.hildebrand
 */
abstract public class KeyEventImpl implements KeyEvent {

    private final EventCommon common;
    private final Header      header;

    public KeyEventImpl(Header header, EventCommon common) {
        this.header = header;
        this.common = common;
    }

    @Override
    public JohnHancock getAuthentication() {
        return signature(common.getAuthentication());
    }

    @Override
    public final byte[] getBytes() {
        return toByteString().toByteArray();
    }

    @Override
    public Identifier getIdentifier() {
        return identifier(header.getIdentifier());
    }

    @Override
    public String getIlk() {
        return header.getIlk();
    }

    @Override
    public EventCoordinates getPrevious() {
        return EventCoordinates.from(common.getPrevious());
    }

    @Override
    public Digest getPriorEventDigest() {
        return digest(header.getPriorEventDigest());
    }

    @Override
    public ULong getSequenceNumber() {
        return ULong.valueOf(header.getSequenceNumber());
    }

    @Override
    public Version getVersion() {
        return new Version() {

            @Override
            public int getMajor() {
                return header.getVersion().getMajor();
            }

            @Override
            public int getMinor() {
                return header.getVersion().getMinor();
            }
        };
    }

    @Override
    public Digest hash(DigestAlgorithm digest) {
        return new Digest(digest, digest.hashOf(toByteString()));
    }

    @Override
    public String toString() {
        return String.format("%s[%s, %s]", getIlk(), getIdentifier(), getSequenceNumber());
    }

    protected abstract ByteString toByteString();
}
