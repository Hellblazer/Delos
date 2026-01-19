/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */
package com.hellblazer.delos.witness.proto;

/**
 * Phase transition request for manual migration phase advancement.
 * <p>
 * <strong>NOTE:</strong> This is a temporary stub for Phase 1B-2-C Task 4.
 * Should be replaced with generated proto message in Phase 1B-3 when
 * full gRPC service definitions are added.
 *
 * @author hal.hildebrand
 */
public final class PhaseTransitionRequest {
    private final String newPhase;

    private PhaseTransitionRequest(Builder builder) {
        this.newPhase = builder.newPhase;
    }

    public String getNewPhase() {
        return newPhase;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private String newPhase;

        private Builder() {
        }

        public Builder setNewPhase(String newPhase) {
            this.newPhase = newPhase;
            return this;
        }

        public PhaseTransitionRequest build() {
            return new PhaseTransitionRequest(this);
        }
    }
}
