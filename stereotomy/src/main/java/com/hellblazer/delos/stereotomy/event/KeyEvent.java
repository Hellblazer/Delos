/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.event;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.event.proto.KeyEventWithAttachments.Builder;
import com.hellblazer.delos.stereotomy.event.proto.KeyEvent_;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.joou.ULong;

/**
 * @author hal.hildebrand
 */
public interface KeyEvent {

    String DELEGATED_INCEPTION_TYPE = "dip";
    String DELEGATED_ROTATION_TYPE  = "drt";
    String INCEPTION_TYPE           = "icp";
    String INTERACTION_TYPE         = "ixn";
    String NONE                     = "nan";
    String ROTATION_TYPE            = "rot";

    JohnHancock getAuthentication();

    byte[] getBytes();

    default EventCoordinates getCoordinates() {
        return new EventCoordinates(getIdentifier(), getSequenceNumber(), getPriorEventDigest(), getIlk());
    }

    Identifier getIdentifier();

    String getIlk();

    EventCoordinates getPrevious();

    Digest getPriorEventDigest();

    ULong getSequenceNumber();

    Version getVersion();

    Digest hash(DigestAlgorithm digest);

    void setEventOf(Builder builder);

    KeyEvent_ toKeyEvent_();
}
