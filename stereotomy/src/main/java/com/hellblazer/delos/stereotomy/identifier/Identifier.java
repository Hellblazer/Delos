/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.identifier;

import static com.hellblazer.delos.cryptography.QualifiedBase64.digest;
import static com.hellblazer.delos.cryptography.QualifiedBase64.signature;

import java.nio.ByteBuffer;
import java.security.PublicKey;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.stereotomy.event.proto.Ident;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification;

/**
 * @author hal.hildebrand
 */
public interface Identifier {
    final ByteString EMPTY = ByteString.copyFrom(new byte[] { 0 });
    public static final Ident NONE_IDENT = Ident.newBuilder().setNONE(true).build();
    Identifier NONE = new Identifier() {

        @Override
        public byte identifierCode() {
            return 0;
        }

        @Override
        public boolean isNone() {
            return true;
        }

        @Override
        public boolean isTransferable() {
            return false;
        }

        @Override
        public Ident toIdent() {
            return NONE_IDENT;
        }

        @Override
        public String toString() {
            return "<NONE>";
        }
    };

    public static Identifier from(Ident identifier) {
        if (identifier.hasBasic()) {
            return new BasicIdentifier(identifier.getBasic());
        }
        if (identifier.hasSelfAddressing()) {
            return new SelfAddressingIdentifier(digest(identifier.getSelfAddressing()));
        }
        if (identifier.hasSelfSigning()) {
            return new SelfSigningIdentifier(signature(identifier.getSelfSigning()));
        }
        return Identifier.NONE;
    }

    @SuppressWarnings("unchecked")
    public static <D extends Identifier> D identifier(IdentifierSpecification<D> spec, ByteBuffer inceptionStatement) {
        var derivation = spec.getDerivation();
        if (derivation.isAssignableFrom(BasicIdentifier.class)) {
            return (D) basic(spec.getKeys().get(0));
        } else if (derivation.isAssignableFrom(SelfAddressingIdentifier.class)) {
            return (D) selfAddressing(inceptionStatement, spec.getSelfAddressingDigestAlgorithm());
        } else if (derivation.isAssignableFrom(SelfSigningIdentifier.class)) {
            return (D) selfSigning(inceptionStatement, spec.getSigner());
        } else {
            throw new IllegalArgumentException("unknown prefix type: " + derivation.getCanonicalName());
        }
    }

    static BasicIdentifier basic(PublicKey key) {
        return new BasicIdentifier(key);
    }

    static SelfAddressingIdentifier selfAddressing(ByteBuffer inceptionStatement, DigestAlgorithm digestAlgorithm) {
        var digest = digestAlgorithm.digest(inceptionStatement);
        return new SelfAddressingIdentifier(digest);
    }

    static SelfSigningIdentifier selfSigning(ByteBuffer inceptionStatement, Signer signer) {
        var signature = signer.sign(inceptionStatement);
        return new SelfSigningIdentifier(signature);
    }

    @Override
    boolean equals(Object obj);

    default Digest getDigest(DigestAlgorithm algo) {
        return algo.digest(toIdent().toByteString());
    }

    @Override
    int hashCode();

    byte identifierCode();

    default boolean isNone() {
        return false;
    }

    boolean isTransferable();

    Ident toIdent();
}
