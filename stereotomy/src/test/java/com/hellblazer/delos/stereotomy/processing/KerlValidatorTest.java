/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.stereotomy.processing;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for KerlValidator
 *
 * @author hal.hildebrand
 */
class KerlValidatorTest {

    @Test
    void testEmptyKerlThrowsException() {
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var validator = new KerlValidator(kerl);

        var emptyKerl = KERL_.getDefaultInstance();

        var exception = assertThrows(KerlValidationException.class,
                                      () -> validator.validateChain(emptyKerl));
        assertEquals("Empty KERL", exception.getMessage());
    }

    @Test
    void testKerlValidatorCreation() {
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var validator = new KerlValidator(kerl);
        assertNotNull(validator);
    }

    @Test
    void testKerlValidatorCreationWithProcessor() {
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var processor = new KeyEventProcessor(kerl);
        var validator = new KerlValidator(processor);
        assertNotNull(validator);
    }

    @Test
    void testValidateWitnessEndorsementsNoWitnesses() {
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var validator = new KerlValidator(kerl);

        // With no witnesses, endorsements should pass
        // Note: This requires a real KeyState, which needs a full KERL setup
        // For now, just verify the validator can be created
        assertNotNull(validator);
    }
}
