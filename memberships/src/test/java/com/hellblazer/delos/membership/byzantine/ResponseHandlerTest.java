/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ResponseHandler interface.
 */
class ResponseHandlerTest {

    @Test
    void noOpHandlerShouldReturnFalseForCritical() {
        var handler = ResponseHandler.noOp();
        var profile = createTestProfile();

        var result = handler.handleCritical(Identifier.NONE, profile).join();

        assertThat(result).isFalse();
    }

    @Test
    void noOpHandlerShouldCompleteWarning() {
        var handler = ResponseHandler.noOp();
        var profile = createTestProfile();

        assertThatCode(() -> handler.handleWarning(Identifier.NONE, profile).join())
            .doesNotThrowAnyException();
    }

    @Test
    void customHandlerShouldBeInvoked() {
        var criticalCalled = new AtomicBoolean(false);
        var warningCalled = new AtomicBoolean(false);

        var handler = new ResponseHandler() {
            @Override
            public CompletableFuture<Boolean> handleCritical(Identifier memberId, MemberRiskProfile profile) {
                criticalCalled.set(true);
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<Void> handleWarning(Identifier memberId, MemberRiskProfile profile) {
                warningCalled.set(true);
                return CompletableFuture.completedFuture(null);
            }
        };

        var profile = createTestProfile();
        handler.handleCritical(Identifier.NONE, profile).join();
        handler.handleWarning(Identifier.NONE, profile).join();

        assertThat(criticalCalled.get()).isTrue();
        assertThat(warningCalled.get()).isTrue();
    }

    @Test
    void asyncHandlerShouldCompleteEventually() {
        var callCount = new AtomicInteger(0);

        var handler = new ResponseHandler() {
            @Override
            public CompletableFuture<Boolean> handleCritical(Identifier memberId, MemberRiskProfile profile) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(10); // Simulate async work
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    callCount.incrementAndGet();
                    return true;
                });
            }

            @Override
            public CompletableFuture<Void> handleWarning(Identifier memberId, MemberRiskProfile profile) {
                return CompletableFuture.runAsync(() -> callCount.incrementAndGet());
            }
        };

        var profile = createTestProfile();
        var future1 = handler.handleCritical(Identifier.NONE, profile);
        var future2 = handler.handleWarning(Identifier.NONE, profile);

        // Should complete eventually
        CompletableFuture.allOf(future1, future2).join();

        assertThat(callCount.get()).isEqualTo(2);
        assertThat(future1.join()).isTrue();
    }

    @Test
    void handlerCanReportFailure() {
        var handler = new ResponseHandler() {
            @Override
            public CompletableFuture<Boolean> handleCritical(Identifier memberId, MemberRiskProfile profile) {
                return CompletableFuture.failedFuture(new RuntimeException("Shunning failed"));
            }

            @Override
            public CompletableFuture<Void> handleWarning(Identifier memberId, MemberRiskProfile profile) {
                return CompletableFuture.completedFuture(null);
            }
        };

        var profile = createTestProfile();
        var future = handler.handleCritical(Identifier.NONE, profile);

        assertThat(future.isCompletedExceptionally()).isTrue();
        assertThatThrownBy(future::join)
            .hasCauseInstanceOf(RuntimeException.class)
            .hasMessageContaining("Shunning failed");
    }

    private MemberRiskProfile createTestProfile() {
        var config = IntelligenceConfig.defaults();
        var profile = new MemberRiskProfile(Identifier.NONE, config);
        profile.updateLayerState(new LayerAnomalyState(
            "TEST", 0.9, Instant.now(), List.of("CRITICAL"), "test"
        ));
        return profile;
    }
}
