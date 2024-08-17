/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion.comm.endorsement;

import com.hellblazer.delos.gorgoneion.proto.Credentials;
import com.hellblazer.delos.gorgoneion.proto.MemberSignature;
import com.hellblazer.delos.gorgoneion.proto.Nonce;
import com.hellblazer.delos.gorgoneion.proto.Notarization;
import com.hellblazer.delos.stereotomy.event.proto.Validation_;
import com.hellblazer.delos.cryptography.Digest;

/**
 * @author hal.hildebrand
 */
public interface EndorsementService {

    MemberSignature endorse(Nonce request, Digest from);

    void enroll(Notarization request, Digest from);

    Validation_ validate(Credentials credentials, Digest id);
}
