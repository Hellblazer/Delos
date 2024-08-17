package com.hellblazer.delos.membership;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SigningThreshold;

import java.io.InputStream;

/**
 * A stand in for comparison functions
 *
 * @author hal.hildebrand
 **/
public class MockMember implements Member {
    private final Digest id;

    public MockMember(Digest id) {
        this.id = id;
    }

    @Override
    public int compareTo(Member o) {
        return id.compareTo(o.getId());
    }

    @Override
    public Digest getId() {
        return id;
    }

    @Override
    public boolean verify(JohnHancock signature, InputStream message) {
        return false;
    }

    @Override
    public boolean verify(SigningThreshold threshold, JohnHancock signature, InputStream message) {
        return false;
    }
}
