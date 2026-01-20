/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.leyden;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.h2.mvstore.WriteBuffer;
import org.h2.mvstore.type.BasicDataType;

import java.nio.ByteBuffer;

/**
 * @author hal.hildebrand
 */
public final class DigestDatatype extends BasicDataType<Digest> {
    private final DigestAlgorithm algorithm;

    public DigestDatatype(DigestAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    @Override
    public int compare(Digest a, Digest b) {
        return a.compareTo(b);
    }

    @Override
    public Digest[] createStorage(int size) {
        return new Digest[size];
    }

    @Override
    public int getMemory(Digest data) {
        return algorithm.longLength() * 8;
    }

    @Override
    public Digest read(ByteBuffer buff) {
        byte[] data = new byte[algorithm.longLength() * 8];
        buff.get(data);
        return new Digest(algorithm, data);
    }

    @Override
    public void write(WriteBuffer buff, Digest data) {
        buff.put(data.getBytes());
    }
}
