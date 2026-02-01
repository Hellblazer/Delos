/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.impl.SigningMemberImpl;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.joou.ULong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for FirefliesWitnessAdapter - KERI threshold to Fireflies bias mapping.
 */
class FirefliesWitnessAdapterTest {

    private FirefliesWitnessAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new FirefliesWitnessAdapter();
    }

    // ===== Bias Computation Tests =====

    @Test
    void computeBias_standard2f1_returnsCorrectBias() {
        // 3 of 5 = standard 2f+1 BFT
        int bias = adapter.computeBias(5, 3);
        assertThat(bias).isEqualTo(2);
    }

    @Test
    void computeBias_higherThreshold_returnsHigherBias() {
        // 4 of 5 = higher security (3f+1)
        int bias = adapter.computeBias(5, 4);
        assertThat(bias).isEqualTo(4);
    }

    @Test
    void computeBias_simpleMajority_returnsBias1() {
        // 3 of 5 with simple majority needs lower bias
        // Actually, 3 of 5 with bias=2 gives majority=3
        // For 2 of 5, we need bias=1
        int bias = adapter.computeBias(5, 2);
        assertThat(bias).isEqualTo(1);
    }

    @Test
    void computeBias_unanimous_returnsWitnessCount() {
        // 5 of 5 = unanimous
        int bias = adapter.computeBias(5, 5);
        assertThat(bias).isEqualTo(5);
    }

    @ParameterizedTest
    @CsvSource({
        // witnessCount, threshold, expectedBias
        "4, 3, 3",   // 3 of 4
        "5, 3, 2",   // 3 of 5 (standard BFT)
        "5, 4, 4",   // 4 of 5 (high security)
        "7, 5, 3",   // 5 of 7
        "7, 4, 2",   // 4 of 7
        "10, 7, 3",  // 7 of 10
        "10, 6, 2",  // 6 of 10
    })
    void computeBias_variousConfigurations(int witnesses, int threshold, int expectedBias) {
        int bias = adapter.computeBias(witnesses, threshold);
        assertThat(bias).isEqualTo(expectedBias);
    }

    // ===== Validation Tests =====

    @Test
    void computeBias_invalidWitnessCount_throws() {
        assertThatThrownBy(() -> adapter.computeBias(0, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("witnessCount must be positive");
    }

    @Test
    void computeBias_invalidThreshold_throws() {
        assertThatThrownBy(() -> adapter.computeBias(5, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threshold must be positive");
    }

    @Test
    void computeBias_thresholdExceedsWitnesses_throws() {
        assertThatThrownBy(() -> adapter.computeBias(5, 6))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threshold cannot exceed witnessCount");
    }

    // ===== Configuration Verification Tests =====

    @Test
    void verifyConfiguration_correctConfig_returnsTrue() {
        // With 5 rings and bias=2: tolerance = (5-1)/2 = 2, majority = 5-2 = 3
        assertThat(adapter.verifyConfiguration(5, 2, 3)).isTrue();
    }

    @Test
    void verifyConfiguration_incorrectConfig_returnsFalse() {
        // With 5 rings and bias=2: majority = 3, not 4
        assertThat(adapter.verifyConfiguration(5, 2, 4)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        // rings, bias, expectedMajority
        "4, 3, 3",
        "5, 2, 3",
        "5, 4, 4",
        "7, 2, 4",
        "7, 3, 5",
        "10, 2, 6",
        "10, 3, 7",
    })
    void verifyConfiguration_variousConfigs(int rings, int bias, int expectedMajority) {
        assertThat(adapter.verifyConfiguration(rings, bias, expectedMajority)).isTrue();
    }

    // ===== End-to-End Mapping Tests =====

    @ParameterizedTest
    @CsvSource({
        "5, 3",   // Standard BFT
        "5, 4",   // High security
        "7, 5",   // 5 of 7
        "7, 4",   // 4 of 7
        "10, 7",  // 7 of 10
    })
    void computeBias_producesMajorityMatchingThreshold(int witnesses, int threshold) {
        int bias = adapter.computeBias(witnesses, threshold);

        // Verify using Fireflies formula
        int tolerance = (witnesses - 1) / bias;
        int actualMajority = witnesses - tolerance;

        assertThat(actualMajority)
            .as("For %d witnesses with threshold %d, bias %d should produce majority %d",
                witnesses, threshold, bias, threshold)
            .isEqualTo(threshold);
    }

    // ===== Context Creation Tests =====
    // Note: DynamicContextImpl calculates ring count probabilistically via minMajority(),
    // so the actual majority may differ from the expected threshold. These tests verify
    // that the bias is correctly computed. Full threshold mapping requires either:
    // 1. A StaticContext with explicit ring count, or
    // 2. A custom DynamicContext subclass that supports explicit ring count

    @Test
    void createContext_setsCorrectBias() {
        var contextId = DigestAlgorithm.DEFAULT.getOrigin();

        var context = adapter.createContext(contextId, 5, 3, 0.1);

        // Verify bias is correctly set (core adapter logic works)
        assertThat(context.getBias()).isEqualTo(2);
    }

    @Test
    void createContext_highSecurityBias() {
        var contextId = DigestAlgorithm.DEFAULT.getOrigin();

        var context = adapter.createContext(contextId, 5, 4, 0.1);

        // Bias for 4-of-5 threshold
        assertThat(context.getBias()).isEqualTo(4);
    }

    @Test
    void createContext_sevenWitnessesBias() {
        var contextId = DigestAlgorithm.DEFAULT.getOrigin();

        var context = adapter.createContext(contextId, 7, 5, 0.1);

        // Bias for 5-of-7 threshold
        assertThat(context.getBias()).isEqualTo(3);
    }

    // ===== Event Hashing Tests =====

    @Test
    void hashEventCoordinates_deterministicAcrossCalls() {
        var coords = createMockEventCoordinates();

        var hash1 = adapter.hashEventCoordinates(coords);
        var hash2 = adapter.hashEventCoordinates(coords);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void hashEventCoordinates_differentEventsProduceDifferentHashes() {
        var coords1 = createMockEventCoordinates("id1", 1);
        var coords2 = createMockEventCoordinates("id2", 2);

        var hash1 = adapter.hashEventCoordinates(coords1);
        var hash2 = adapter.hashEventCoordinates(coords2);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    // ===== Witness Selection Tests =====

    @Test
    void selectWitnesses_usesBftSubset() {
        var contextId = DigestAlgorithm.DEFAULT.getOrigin();
        var context = adapter.<Member>createContext(contextId, 5, 3, 0.1);

        // Add some members
        for (int i = 0; i < 5; i++) {
            var member = createMockMember(i);
            context.add(member);
            context.activate(member);
        }

        var coords = createMockEventCoordinates();
        var witnesses = adapter.selectWitnesses(context, coords);

        // bftSubset returns up to ringCount unique successors
        assertThat(witnesses).isNotEmpty();
    }

    @Test
    void selectWitnesses_deterministicSelection() {
        var contextId = DigestAlgorithm.DEFAULT.getOrigin();
        var context = adapter.<Member>createContext(contextId, 5, 3, 0.1);

        for (int i = 0; i < 5; i++) {
            var member = createMockMember(i);
            context.add(member);
            context.activate(member);
        }

        var coords = createMockEventCoordinates();

        var witnesses1 = adapter.selectWitnesses(context, coords);
        var witnesses2 = adapter.selectWitnesses(context, coords);

        assertThat(witnesses1).isEqualTo(witnesses2);
    }

    // ===== Witness Identifier Conversion Tests =====

    @Test
    void toWitnessIdentifiers_convertsDigestsToIdentifiers() {
        var witnesses = new java.util.LinkedHashSet<Member>();
        witnesses.add(createMockMember(0));
        witnesses.add(createMockMember(1));
        witnesses.add(createMockMember(2));

        var identifiers = adapter.toWitnessIdentifiers(witnesses);

        assertThat(identifiers)
            .hasSize(3)
            .allMatch(id -> id instanceof SelfAddressingIdentifier);
    }

    @Test
    void toWitnessIdentifiers_emptySet_returnsEmptyList() {
        var witnesses = new java.util.LinkedHashSet<Member>();

        var identifiers = adapter.toWitnessIdentifiers(witnesses);

        assertThat(identifiers).isEmpty();
    }

    @Test
    void toWitnessIdentifiers_nullInput_throws() {
        assertThatThrownBy(() -> adapter.toWitnessIdentifiers(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("witnesses cannot be null");
    }

    // ===== Null Validation Tests =====

    @Test
    void selectWitnesses_nullContext_throws() {
        var coords = createMockEventCoordinates();

        assertThatThrownBy(() -> adapter.selectWitnesses(null, coords))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("context cannot be null");
    }

    @Test
    void selectWitnesses_nullEventCoordinates_throws() {
        var contextId = DigestAlgorithm.DEFAULT.getOrigin();
        var context = adapter.createContext(contextId, 5, 3, 0.1);

        assertThatThrownBy(() -> adapter.selectWitnesses(context, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("eventCoordinates cannot be null");
    }

    @Test
    void hashEventCoordinates_nullInput_throws() {
        assertThatThrownBy(() -> adapter.hashEventCoordinates(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("eventCoordinates cannot be null");
    }

    // ===== Mapping Table Generation =====

    @Test
    void computeThresholdMappingTable_generatesTable() {
        String table = adapter.computeThresholdMappingTable(7);

        assertThat(table).contains("Witnesses");
        assertThat(table).contains("Threshold");
        assertThat(table).contains("Bias");
        assertThat(table).contains("Majority");
    }

    // ===== Helper Methods =====

    private EventCoordinates createMockEventCoordinates() {
        return createMockEventCoordinates("test-id", 1);
    }

    private EventCoordinates createMockEventCoordinates(String id, long seq) {
        var coords = mock(EventCoordinates.class);
        var identifier = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest(id));

        when(coords.getIdentifier()).thenReturn(identifier);
        when(coords.getSequenceNumber()).thenReturn(ULong.valueOf(seq));
        when(coords.getDigest()).thenReturn(DigestAlgorithm.DEFAULT.digest("digest-" + seq));
        when(coords.getIlk()).thenReturn("icp");

        return coords;
    }

    private Member createMockMember(int index) {
        var member = mock(Member.class);
        var id = DigestAlgorithm.DEFAULT.digest("member-" + index);
        when(member.getId()).thenReturn(id);
        return member;
    }
}
