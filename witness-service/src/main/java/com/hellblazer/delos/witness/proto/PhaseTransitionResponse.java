/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.delos.witness.proto;

/**
 * Phase transition response indicating success/failure of manual phase advancement.
 * <p>
 * <strong>NOTE:</strong> This is a temporary stub for Phase 1B-2-C Task 4.
 * Should be replaced with generated proto message in Phase 1B-3 when
 * full gRPC service definitions are added.
 *
 * @author hal.hildebrand
 */
public final class PhaseTransitionResponse {
    private final boolean success;
    private final String oldPhase;
    private final String newPhase;
    private final String errorMessage;

    private PhaseTransitionResponse(Builder builder) {
        this.success = builder.success;
        this.oldPhase = builder.oldPhase;
        this.newPhase = builder.newPhase;
        this.errorMessage = builder.errorMessage;
    }

    public boolean getSuccess() {
        return success;
    }

    public String getOldPhase() {
        return oldPhase;
    }

    public String getNewPhase() {
        return newPhase;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean success;
        private String oldPhase;
        private String newPhase;
        private String errorMessage;

        private Builder() {
        }

        public Builder setSuccess(boolean success) {
            this.success = success;
            return this;
        }

        public Builder setOldPhase(String oldPhase) {
            this.oldPhase = oldPhase;
            return this;
        }

        public Builder setNewPhase(String newPhase) {
            this.newPhase = newPhase;
            return this;
        }

        public Builder setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public PhaseTransitionResponse build() {
            return new PhaseTransitionResponse(this);
        }
    }
}
