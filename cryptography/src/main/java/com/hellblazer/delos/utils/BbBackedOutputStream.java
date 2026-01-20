/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.utils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * @author hal.hildebrand
 *
 */
public class BbBackedOutputStream extends OutputStream {
    private final ByteBuffer buf;

    public BbBackedOutputStream(ByteBuffer buf) {
        this.buf = buf;
    }

    @Override
    public void write(byte[] bytes, int off, int len) throws IOException {
        buf.put(bytes, off, len);
    }

    @Override
    public void write(int b) throws IOException {
        buf.put((byte) b);
    }

}
