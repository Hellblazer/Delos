/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.validation;

import com.hellblazer.delos.choam.support.ByzantineDetectionMapper;
import com.hellblazer.delos.choam.support.ByzantineViolation;
import com.hellblazer.delos.choam.support.ByzantineViolationType;
import com.hellblazer.delos.choam.support.ValidationResult;

import java.util.List;
import java.util.Objects;

/**
 * Default implementation of BFTValidator wrapping ByzantineDetectionMapper.
 * Provides simple delegation to the underlying mapper for violation classification
 * and tracking.
 * <p>
 * This adapter uses the single-method delegation pattern throughout - no additional
 * logic or state beyond the wrapped mapper.
 * <p>
 * Thread Safety: This class is thread-safe. All operations delegate to
 * ByzantineDetectionMapper which provides concurrent-safe violation tracking.
 *
 * @author hal.hildebrand
 */
public class DefaultBFTValidator implements BFTValidator {
    private final ByzantineDetectionMapper mapper;

    /**
     * Constructs a DefaultBFTValidator wrapping the given mapper.
     *
     * @param mapper the Byzantine detection mapper (must not be null)
     * @throws NullPointerException if mapper is null
     */
    public DefaultBFTValidator(ByzantineDetectionMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper cannot be null");
    }

    @Override
    public ByzantineViolation mapViolation(ValidationResult result) {
        return mapper.mapViolation(result);
    }

    @Override
    public long getViolationCount(ByzantineViolationType type) {
        return mapper.getViolationCount(type);
    }

    @Override
    public long getTotalViolationCount() {
        return mapper.getTotalViolationCount();
    }

    @Override
    public List<ByzantineViolation> getRecentViolations() {
        return mapper.getRecentViolations();
    }

    @Override
    public List<ByzantineViolation> getRecentViolations(ByzantineViolationType type) {
        return mapper.getRecentViolations(type);
    }

    @Override
    public void reset() {
        mapper.reset();
    }
}
