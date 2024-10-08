/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author hal.hildebrand
 */
public class TxDataSourceTest {

    @Test
    public void func() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);
        TxDataSource ds = new TxDataSource(new ControlledIdentifierMember(stereotomy.newIdentifier()), 100, null, 1024,
                                           Duration.ofMillis(100), 100);
        Transaction tx = Transaction.newBuilder()
                                    .setContent(ByteString.copyFromUtf8("Give me food or give me slack or kill me"))
                                    .build();
        int count = 0;
        while (ds.offer(tx)) {
            count++;
        }
        assertEquals(2400, count);
        assertEquals(2400, ds.getRemainingTransactions());

        var data = ds.getData();
        assertNotNull(data);
        assertEquals(1056, data.size());

        assertFalse(ds.offer(tx));

        data = ds.getData();
        assertNotNull(data);
        assertEquals(1056, data.size());

        data = ds.getData();
        assertNotNull(data);
        assertEquals(1056, data.size());

        data = ds.getData();
        assertNotNull(data);
        assertEquals(1056, data.size());

        data = ds.getData();
        assertNotNull(data);
        assertEquals(1056, data.size());

        data = ds.getData();
        assertNotNull(data);
        assertEquals(1056, data.size());

        for (int i = 0; i < 94; i++) {
            data = ds.getData();
            assertNotNull(data);
            assertEquals(1056, data.size());
        }

        assertEquals(0, ds.getRemainingTransactions());

        data = ds.getData();
        assertNotNull(data);
        assertEquals(0, data.size());
    }
}
