/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 */
public class AniTest extends AbstractDhtTest {

    @Test
    public void smokin() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 7, 7, 7 });

        routers.values().forEach(lr -> lr.start());
        dhts.values().forEach(e -> e.start(Duration.ofSeconds(1)));

        var dht = dhts.firstEntry().getValue();

        Map<SigningMember, Ani> anis = dhts.entrySet()
                                           .stream()
                                           .collect(Collectors.toMap(e -> e.getKey(), e -> new Ani(e.getKey().getId(),
                                                                                                   dhts.get(e.getKey())
                                                                                                       .asKERL())));
        var ani = anis.values().stream().findFirst().get();

        // inception
        var specification = IdentifierSpecification.newBuilder();
        var initialKeyPair = specification.getSignatureAlgorithm().generateKeyPair(entropy);
        var nextKeyPair = specification.getSignatureAlgorithm().generateKeyPair(entropy);
        var inception = inception(specification, initialKeyPair, factory, nextKeyPair);

        dht.append(Collections.singletonList(inception.toKeyEvent_()));
        assertTrue(ani.eventValidation(Duration.ofSeconds(10)).validate(inception));
    }

}
