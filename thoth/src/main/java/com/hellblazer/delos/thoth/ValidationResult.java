/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.membership.Member;

import java.util.Set;

/**
 * Result of post-quorum validation for DHT responses.
 * <p>
 * Indicates whether a response value passed cryptographic validation and
 * identifies Byzantine members if validation failed.
 * </p>
 *
 * @param <T>           Response type (KeyState_, etc.)
 * @param value         The response value being validated
 * @param valid         True if validation passed
 * @param failureReason Reason for validation failure (null if valid)
 * @param suspects      Members suspected of Byzantine behavior (empty if valid)
 * @param operation     Operation that produced this result
 * @author hal.hildebrand
 */
public record ValidationResult<T>(
    T value,
    boolean valid,
    String failureReason,
    Set<Member> suspects,
    String operation
) {
    /**
     * Create a valid result.
     *
     * @param value The validated value
     * @param <T>   Response type
     * @return ValidationResult indicating success
     */
    public static <T> ValidationResult<T> valid(T value) {
        return new ValidationResult<>(value, true, null, Set.of(), "");
    }

    /**
     * Create a valid result for a specific operation.
     *
     * @param value     The validated value
     * @param operation Operation name
     * @param <T>       Response type
     * @return ValidationResult indicating success
     */
    public static <T> ValidationResult<T> valid(T value, String operation) {
        return new ValidationResult<>(value, true, null, Set.of(), operation);
    }

    /**
     * Create an invalid result with suspect members.
     *
     * @param value    The invalid value
     * @param suspects Members that provided this invalid value (null safe)
     * @param reason   Reason for validation failure
     * @param <T>      Response type
     * @return ValidationResult indicating failure
     */
    public static <T> ValidationResult<T> invalid(T value, Set<Member> suspects, String reason) {
        return new ValidationResult<>(value, false, reason, suspects == null ? Set.of() : suspects, "");
    }

    /**
     * Create an invalid result with suspect members and operation.
     *
     * @param value     The invalid value
     * @param suspects  Members that provided this invalid value (null safe)
     * @param reason    Reason for validation failure
     * @param operation Operation name
     * @param <T>       Response type
     * @return ValidationResult indicating failure
     */
    public static <T> ValidationResult<T> invalid(T value, Set<Member> suspects, String reason, String operation) {
        return new ValidationResult<>(value, false, reason, suspects == null ? Set.of() : suspects, operation);
    }
}
