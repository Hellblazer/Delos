/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ValidationResult record.
 *
 * @author hal.hildebrand
 */
public class ValidationResultTest {

    @Test
    void testValidResult() {
        var result = ValidationResult.valid("test-value");

        assertThat(result.valid()).isTrue();
        assertThat(result.value()).isEqualTo("test-value");
        assertThat(result.failureReason()).isNull();
        assertThat(result.suspects()).isEmpty();
        assertThat(result.operation()).isEmpty();
    }

    @Test
    void testValidResultWithOperation() {
        var result = ValidationResult.valid("test-value", "read");

        assertThat(result.valid()).isTrue();
        assertThat(result.value()).isEqualTo("test-value");
        assertThat(result.operation()).isEqualTo("read");
    }

    @Test
    void testInvalidResult() {
        var member = createMember("byzantine-member");
        var suspects = Set.of(member);
        var result = ValidationResult.invalid("bad-value", suspects, "Invalid signature");

        assertThat(result.valid()).isFalse();
        assertThat(result.value()).isEqualTo("bad-value");
        assertThat(result.failureReason()).isEqualTo("Invalid signature");
        assertThat(result.suspects()).containsExactly(member);
        assertThat(result.operation()).isEmpty();
    }

    @Test
    void testInvalidResultWithOperation() {
        var member = createMember("byzantine-member");
        var suspects = Set.of(member);
        var result = ValidationResult.invalid("bad-value", suspects, "Invalid signature", "keyState");

        assertThat(result.valid()).isFalse();
        assertThat(result.value()).isEqualTo("bad-value");
        assertThat(result.failureReason()).isEqualTo("Invalid signature");
        assertThat(result.suspects()).containsExactly(member);
        assertThat(result.operation()).isEqualTo("keyState");
    }

    @Test
    void testMultipleSuspects() {
        var member1 = createMember("byzantine-1");
        var member2 = createMember("byzantine-2");
        var suspects = Set.of(member1, member2);
        var result = ValidationResult.invalid("forged-value", suspects, "Forged event");

        assertThat(result.suspects()).hasSize(2);
        assertThat(result.suspects()).containsExactlyInAnyOrder(member1, member2);
    }

    private Member createMember(String name) {
        var digest = DigestAlgorithm.DEFAULT.digest(name.getBytes());
        return new Member() {
            @Override
            public com.hellblazer.delos.cryptography.Digest getId() {
                return digest;
            }

            @Override
            public int compareTo(Member o) {
                return digest.compareTo(o.getId());
            }

            @Override
            public boolean verify(com.hellblazer.delos.cryptography.JohnHancock signature,
                                  java.io.InputStream message) {
                return true;
            }

            @Override
            public boolean verify(com.hellblazer.delos.cryptography.SigningThreshold threshold,
                                  com.hellblazer.delos.cryptography.JohnHancock signature,
                                  java.io.InputStream message) {
                return true;
            }
        };
    }
}
