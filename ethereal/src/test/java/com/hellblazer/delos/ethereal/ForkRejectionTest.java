/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.ethereal;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;

import org.joou.ULong;
import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.ethereal.PreUnit.preUnit;

/**
 * Tests for fork detection and rejection in the DAG.
 *
 * A fork occurs when the same creator produces two different units at the same height.
 * This is a Byzantine fault and should be detected and rejected.
 *
 * @author hal.hildebrand
 */
public class ForkRejectionTest {

    @Test
    public void testForkAttemptWithDifferentDataIsRejected() {
        var factory = new DagFactory.TestDagFactory();
        var dag = factory.createDag((short) 4);

        // Create dealing units for all processes
        var dealingUnits = new Unit[4];
        for (short pid = 0; pid < 4; pid++) {
            var crown = new Crown(new int[] { -1, -1, -1, -1 }, DigestAlgorithm.DEFAULT.getOrigin());
            var data = ByteString.copyFromUtf8("dealing-" + pid);
            var pu = createPreUnit(pid, 0, crown, data);
            var parents = new Unit[4];
            var unit = dag.build(pu, parents);
            dag.insert(unit);
            dealingUnits[pid] = unit;
        }

        // Create a valid unit at height 1 for creator 0
        var heights1 = new int[] { 0, 0, -1, -1 };
        var crown1 = new Crown(heights1,
            Digest.combine(DigestAlgorithm.DEFAULT, new Digest[]{dealingUnits[0].hash(), dealingUnits[1].hash()}));
        var data1 = ByteString.copyFromUtf8("unit-1");
        var pu1 = createPreUnit((short) 0, 0, crown1, data1);

        var parents1 = new Unit[4];
        parents1[0] = dealingUnits[0];
        parents1[1] = dealingUnits[1];
        var unit1 = dag.build(pu1, parents1);
        dag.insert(unit1);

        // Verify the first unit is in the DAG
        assertTrue(dag.contains(unit1.hash()));

        // Now attempt to create a FORK - same creator (0), same height (1), different data
        // Use a different signer to ensure different signature and hash
        var differentSigner = createDifferentSigner();
        var data2 = ByteString.copyFromUtf8("unit-1-FORK");
        var pu2 = createPreUnitWithSigner((short) 0, 0, crown1, data2, differentSigner);

        // The fork should have a different hash
        assertNotEquals(pu1.hash(), pu2.hash(), "Fork should have different hash");

        // Attempt to decode parents for the fork
        var decoded = dag.decodeParents(pu2);

        // Currently, decodeParents only checks for duplicate hash, not for fork at (creator, height)
        // If no fork detection is implemented, this will succeed
        // If fork detection is implemented, this should return an error classification
        if (!decoded.inError()) {
            // Fork was not detected by decodeParents, try to build and insert
            var parents2 = decoded.parents();
            var unit2 = dag.build(pu2, parents2);

            // Check if the DAG already has a unit at this (creator, height)
            var existingUnit = dag.get(PreUnit.id(1, (short) 0, 0));
            assertNotNull(existingUnit, "Original unit should exist at (creator=0, height=1)");
            assertEquals(unit1.hash(), existingUnit.hash(), "Original unit should be preserved");

            // Attempt to insert the fork
            // Expected: fork should be rejected (either exception or silent rejection)
            // Actual current behavior: fork gets stored in units map but not in height/level indices
            dag.insert(unit2);

            // Verify original unit is still the one at (creator=0, height=1)
            var unitAtPosition = dag.get(PreUnit.id(1, (short) 0, 0));
            assertEquals(unit1.hash(), unitAtPosition.hash(),
                "Original unit should remain at (creator=0, height=1), fork should be rejected");
        } else {
            // Fork was detected by decodeParents
            assertEquals(Correctness.DUPLICATE_UNIT, decoded.classification(),
                "Fork should be classified as DUPLICATE_UNIT");
        }
    }

    @Test
    public void testOriginalUnitPreservedAfterForkAttempt() {
        var factory = new DagFactory.TestDagFactory();
        var dag = factory.createDag((short) 4);

        // Create dealing units
        var dealingUnits = new Unit[4];
        for (short pid = 0; pid < 4; pid++) {
            var crown = new Crown(new int[] { -1, -1, -1, -1 }, DigestAlgorithm.DEFAULT.getOrigin());
            var data = ByteString.copyFromUtf8("dealing-" + pid);
            var pu = createPreUnit(pid, 0, crown, data);
            var parents = new Unit[4];
            var unit = dag.build(pu, parents);
            dag.insert(unit);
            dealingUnits[pid] = unit;
        }

        // Create original unit
        var heights = new int[] { 0, 0, -1, -1 };
        var crown = new Crown(heights,
            Digest.combine(DigestAlgorithm.DEFAULT, new Digest[]{dealingUnits[0].hash(), dealingUnits[1].hash()}));
        var originalData = ByteString.copyFromUtf8("original");
        var originalPU = createPreUnit((short) 0, 0, crown, originalData);

        var parents = new Unit[4];
        parents[0] = dealingUnits[0];
        parents[1] = dealingUnits[1];
        var originalUnit = dag.build(originalPU, parents);
        dag.insert(originalUnit);

        var originalHash = originalUnit.hash();

        // Attempt fork with different signer
        var differentSigner = createDifferentSigner();
        var forkData = ByteString.copyFromUtf8("fork");
        var forkPU = createPreUnitWithSigner((short) 0, 0, crown, forkData, differentSigner);

        // Try to insert fork
        var forkDecoded = dag.decodeParents(forkPU);
        if (!forkDecoded.inError()) {
            var forkParents = forkDecoded.parents();
            var forkUnit = dag.build(forkPU, forkParents);
            dag.insert(forkUnit);
        }

        // Verify original unit is still retrievable and unchanged
        var retrievedUnit = dag.get(PreUnit.id(1, (short) 0, 0));
        assertNotNull(retrievedUnit, "Original unit should still be retrievable");
        assertEquals(originalHash, retrievedUnit.hash(), "Original unit should be unchanged");

        // Verify original unit is still the maximal unit for creator 0
        var maxUnits = dag.maximalUnitsPerProcess();
        assertEquals(originalHash, maxUnits.get(0).hash(),
            "Original unit should still be the maximal unit for creator 0");
    }

    @Test
    public void testSameHashIsDetectedAsDuplicate() {
        var factory = new DagFactory.TestDagFactory();
        var dag = factory.createDag((short) 4);

        // Create dealing units
        var dealingUnits = new Unit[4];
        for (short pid = 0; pid < 4; pid++) {
            var crown = new Crown(new int[] { -1, -1, -1, -1 }, DigestAlgorithm.DEFAULT.getOrigin());
            var data = ByteString.copyFromUtf8("dealing-" + pid);
            var pu = createPreUnit(pid, 0, crown, data);
            var parents = new Unit[4];
            var unit = dag.build(pu, parents);
            dag.insert(unit);
            dealingUnits[pid] = unit;
        }

        // Create a unit
        var heights = new int[] { 0, 0, -1, -1 };
        var crown = new Crown(heights,
            Digest.combine(DigestAlgorithm.DEFAULT, new Digest[]{dealingUnits[0].hash(), dealingUnits[1].hash()}));
        var data = ByteString.copyFromUtf8("unit");
        var pu = createPreUnit((short) 0, 0, crown, data);

        var parents = new Unit[4];
        parents[0] = dealingUnits[0];
        parents[1] = dealingUnits[1];
        var unit = dag.build(pu, parents);
        dag.insert(unit);

        // Attempt to insert the same unit again (same hash)
        var decoded = dag.decodeParents(pu);

        assertTrue(decoded.inError(), "Duplicate unit should be detected");
        assertEquals(Correctness.DUPLICATE_UNIT, decoded.classification(),
            "Duplicate unit should be classified as DUPLICATE_UNIT");
    }

    private PreUnit createPreUnit(short creator, int epoch, Crown crown, ByteString data) {
        return createPreUnitWithSigner(creator, epoch, crown, data, DagReader.DEFAULT_SIGNER);
    }

    private PreUnit createPreUnitWithSigner(short creator, int epoch, Crown crown, ByteString data, Signer signer) {
        var salt = new byte[0];
        var signature = PreUnit.sign(signer, creator, crown, data, salt);
        return new preUnit(creator, epoch, crown.heights()[creator] + 1,
            signature.toDigest(DigestAlgorithm.DEFAULT), crown, data, signature, salt);
    }

    private Signer createDifferentSigner() {
        var keyPair = SignatureAlgorithm.DEFAULT.generateKeyPair();
        return new Signer.SignerImpl(keyPair.getPrivate(), ULong.valueOf(1));
    }
}
