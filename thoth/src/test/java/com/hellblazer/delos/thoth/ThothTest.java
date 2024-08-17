/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.ControlledIdentifier;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.Stereotomy;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.event.Seal;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification;
import com.hellblazer.delos.stereotomy.identifier.spec.InteractionSpecification;
import com.hellblazer.delos.stereotomy.identifier.spec.RotationSpecification;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author hal.hildebrand
 */
public class ThothTest {
    private SecureRandom secureRandom;

    @BeforeEach
    public void before() throws Exception {
        secureRandom = SecureRandom.getInstance("SHA1PRNG");
        secureRandom.setSeed(new byte[] { 0 });
    }

    @Test
    public void smokin() throws Exception {
        var ks = new MemKeyStore();
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        Stereotomy stereotomy = new StereotomyImpl(ks, kerl, secureRandom);

        var thoth = new Thoth(stereotomy);

        ControlledIdentifier<SelfAddressingIdentifier> controller = stereotomy.newIdentifier();

        // delegated inception
        var incp = thoth.inception(controller.getIdentifier(),
                                   IdentifierSpecification.<SelfAddressingIdentifier>newBuilder());
        assertNotNull(incp);

        var seal = Seal.EventSeal.construct(incp.getIdentifier(), incp.hash(stereotomy.digestAlgorithm()),
                                            incp.getSequenceNumber().longValue());

        var builder = InteractionSpecification.newBuilder().addAllSeals(Collections.singletonList(seal));

        // Commit
        EventCoordinates coords = controller.seal(builder);
        thoth.commit(coords);
        assertNotNull(thoth.identifier());

        // Delegated rotation
        var rot = thoth.rotate(RotationSpecification.newBuilder());

        assertNotNull(rot);

        seal = Seal.EventSeal.construct(rot.getIdentifier(), rot.hash(stereotomy.digestAlgorithm()),
                                        rot.getSequenceNumber().longValue());

        builder = InteractionSpecification.newBuilder().addAllSeals(Collections.singletonList(seal));

        // Commit
        coords = controller.seal(builder);
        thoth.commit(coords);
    }
}
