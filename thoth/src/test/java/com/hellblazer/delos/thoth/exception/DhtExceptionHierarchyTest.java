/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.exception;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the DhtException sealed hierarchy.
 */
class DhtExceptionHierarchyTest {

    @Test
    void quorumExceptionCarriesContext() {
        var ex = new DhtQuorumException(3, 1, "getKeyState");

        assertThat(ex).isInstanceOf(DhtException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.required()).isEqualTo(3);
        assertThat(ex.achieved()).isEqualTo(1);
        assertThat(ex.operation()).isEqualTo("getKeyState");
        assertThat(ex.getMessage()).contains("getKeyState")
                                   .contains("3")
                                   .contains("1");
    }

    @Test
    void validationExceptionCarriesSuspects() {
        var ex = new DhtSignatureValidationException("append", "Invalid BLS signature", Set.of());

        assertThat(ex).isInstanceOf(DhtValidationException.class);
        assertThat(ex).isInstanceOf(DhtException.class);
        assertThat(ex.operation()).isEqualTo("append");
        assertThat(ex.validationDetail()).isEqualTo("Invalid BLS signature");
        assertThat(ex.suspectedMembers()).isEmpty();
    }

    @Test
    void stateConsistencyExceptionIsValidationSubtype() {
        var ex = new DhtStateConsistencyException("getKerl", "Event chain broken", Set.of());

        assertThat(ex).isInstanceOf(DhtValidationException.class);
        assertThat(ex).isInstanceOf(DhtException.class);
        assertThat(ex.validationDetail()).isEqualTo("Event chain broken");
    }

    @Test
    void timeoutExceptionCarriesDuration() {
        var elapsed = Duration.ofSeconds(30);
        var ex = new DhtTimeoutException(elapsed, "reconcile");

        assertThat(ex).isInstanceOf(DhtException.class);
        assertThat(ex.elapsed()).isEqualTo(elapsed);
        assertThat(ex.operation()).isEqualTo("reconcile");
        assertThat(ex.getMessage()).contains("reconcile").contains("30");
    }

    @Test
    void resourceExceptionWrapsIOException() {
        var cause = new IOException("Connection reset");
        var ex = new DhtResourceException("Pool exhausted", cause);

        assertThat(ex).isInstanceOf(DhtException.class);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getMessage()).isEqualTo("Pool exhausted");
    }

    @Test
    void sealedHierarchyPatternMatching() {
        // Verify pattern matching works with the sealed hierarchy
        DhtException ex = new DhtQuorumException(3, 1, "test");

        var result = classifyException(ex);
        assertThat(result).isEqualTo("quorum:3");

        result = classifyException(new DhtTimeoutException(Duration.ofSeconds(5), "op"));
        assertThat(result).startsWith("timeout:");

        result = classifyException(new DhtResourceException("pool error"));
        assertThat(result).startsWith("resource:");

        result = classifyException(new DhtSignatureValidationException("op", "bad sig", Set.of()));
        assertThat(result).startsWith("validation:");
    }

    private String classifyException(DhtException ex) {
        if (ex instanceof DhtQuorumException q) {
            return "quorum:" + q.required();
        } else if (ex instanceof DhtTimeoutException t) {
            return "timeout:" + t.elapsed();
        } else if (ex instanceof DhtResourceException r) {
            return "resource:" + r.getMessage();
        } else if (ex instanceof DhtValidationException v) {
            return "validation:" + v.operation();
        }
        throw new IllegalStateException("Unexpected DhtException subtype: " + ex.getClass());
    }

    @Test
    void validationSubtypePatternMatching() {
        DhtValidationException ex = new DhtSignatureValidationException("op", "sig fail", Set.of());

        var result = switch (ex) {
            case DhtSignatureValidationException s -> "signature:" + s.validationDetail();
            case DhtStateConsistencyException c -> "consistency:" + c.validationDetail();
            default -> "validation:" + ex.validationDetail();
        };

        assertThat(result).isEqualTo("signature:sig fail");
    }

    @Test
    void suspectedMembersAreImmutableCopy() {
        var mutableSet = new java.util.HashSet<com.hellblazer.delos.membership.Member>();
        var mockMember = org.mockito.Mockito.mock(com.hellblazer.delos.membership.Member.class);

        var ex = new DhtSignatureValidationException("op", "detail", mutableSet);

        // Verify defensive copy: modifying original set should not affect exception
        assertThat(ex.suspectedMembers()).isEmpty();
        mutableSet.add(mockMember);
        assertThat(ex.suspectedMembers()).isEmpty();  // Still empty despite mutation

        // Verify returned set is immutable
        assertThat(ex.suspectedMembers()).isInstanceOf(java.util.Set.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ex.suspectedMembers().add(mockMember))
                                       .isInstanceOf(UnsupportedOperationException.class);
    }
}
