/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.validation;

import com.hellblazer.delos.choam.support.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DefaultBFTValidator adapter.
 * Validates delegation to ByzantineDetectionMapper.
 *
 * @author hal.hildebrand
 */
class DefaultBFTValidatorTest {

    @Mock
    private ByzantineDetectionMapper mapper;

    private ValidationResult validationResult;
    private ByzantineViolation violation;
    private DefaultBFTValidator validator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        validator = new DefaultBFTValidator(mapper);
        // Create real instances (records/final classes can't be mocked)
        // Use direct constructor for ByzantineViolation to avoid ValidationResult state issues
        validationResult = null; // Not used in most tests
        violation = new ByzantineViolation(ByzantineViolationType.EQUIVOCATION, ByzantineViolation.Severity.HIGH,
                                          "test state", "test transition", List.of("test violation"),
                                          java.time.Instant.now(), "remediation", "test context");
    }

    @Test
    void testMapViolation() {
        when(mapper.mapViolation(validationResult)).thenReturn(violation);

        var result = validator.mapViolation(validationResult);

        assertThat(result).isSameAs(violation);
        verify(mapper).mapViolation(validationResult);
    }

    @Test
    void testMapViolationNull() {
        when(mapper.mapViolation(validationResult)).thenReturn(null);

        var result = validator.mapViolation(validationResult);

        assertThat(result).isNull();
        verify(mapper).mapViolation(validationResult);
    }

    @Test
    void testGetViolationCount() {
        var type = ByzantineViolationType.EQUIVOCATION;
        when(mapper.getViolationCount(type)).thenReturn(5L);

        var count = validator.getViolationCount(type);

        assertThat(count).isEqualTo(5L);
        verify(mapper).getViolationCount(type);
    }

    @Test
    void testGetViolationCountZero() {
        var type = ByzantineViolationType.PRECONDITION_VIOLATION;
        when(mapper.getViolationCount(type)).thenReturn(0L);

        var count = validator.getViolationCount(type);

        assertThat(count).isZero();
        verify(mapper).getViolationCount(type);
    }

    @Test
    void testGetTotalViolationCount() {
        when(mapper.getTotalViolationCount()).thenReturn(42L);

        var total = validator.getTotalViolationCount();

        assertThat(total).isEqualTo(42L);
        verify(mapper).getTotalViolationCount();
    }

    @Test
    void testGetRecentViolations() {
        var violations = List.of(violation);
        when(mapper.getRecentViolations()).thenReturn(violations);

        var result = validator.getRecentViolations();

        assertThat(result).isSameAs(violations);
        verify(mapper).getRecentViolations();
    }

    @Test
    void testGetRecentViolationsEmpty() {
        when(mapper.getRecentViolations()).thenReturn(List.of());

        var result = validator.getRecentViolations();

        assertThat(result).isEmpty();
        verify(mapper).getRecentViolations();
    }

    @Test
    void testGetRecentViolationsByType() {
        var type = ByzantineViolationType.TIMING_ANOMALY;
        var violations = List.of(violation);
        when(mapper.getRecentViolations(type)).thenReturn(violations);

        var result = validator.getRecentViolations(type);

        assertThat(result).isSameAs(violations);
        verify(mapper).getRecentViolations(type);
    }

    @Test
    void testGetRecentViolationsByTypeEmpty() {
        var type = ByzantineViolationType.STATE_INCONSISTENCY;
        when(mapper.getRecentViolations(type)).thenReturn(List.of());

        var result = validator.getRecentViolations(type);

        assertThat(result).isEmpty();
        verify(mapper).getRecentViolations(type);
    }

    @Test
    void testReset() {
        validator.reset();

        verify(mapper).reset();
    }

    @Test
    void testAllViolationTypes() {
        // Verify delegation works for all violation types
        for (var type : ByzantineViolationType.values()) {
            when(mapper.getViolationCount(type)).thenReturn(1L);

            var count = validator.getViolationCount(type);

            assertThat(count).isEqualTo(1L);
        }

        verify(mapper, times(ByzantineViolationType.values().length)).getViolationCount(any());
    }

    @Test
    void testNullMapper() {
        assertThatThrownBy(() -> new DefaultBFTValidator(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("mapper cannot be null");
    }
}
