/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.byzantine.IntelligenceConfig;
import com.hellblazer.delos.membership.byzantine.MemberRiskProfile;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FirefliesResponseHandler.
 */
class FirefliesResponseHandlerTest {

    @Mock
    private View view;

    @Mock
    private FireflyMetrics metrics;

    private FirefliesResponseHandler handler;
    private IntelligenceConfig config;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        config = IntelligenceConfig.defaults();
        handler = new FirefliesResponseHandler(view, metrics);
    }

    @Test
    void shouldShunMemberOnCriticalDetection() throws Exception {
        var digest = DigestAlgorithm.DEFAULT.digest("byzantine-member");
        var identifier = new SelfAddressingIdentifier(digest);
        var profile = new MemberRiskProfile(identifier, config);

        when(view.shun(digest)).thenReturn(CompletableFuture.completedFuture(true));

        var result = handler.handleCritical(identifier, profile).get(5, TimeUnit.SECONDS);

        assertThat(result).isTrue();
        verify(view).shun(digest);
        verify(metrics).recordShunnedGossip();
    }

    @Test
    void shouldReturnFalseWhenAlreadyShunned() throws Exception {
        var digest = DigestAlgorithm.DEFAULT.digest("already-shunned");
        var identifier = new SelfAddressingIdentifier(digest);
        var profile = new MemberRiskProfile(identifier, config);

        when(view.shun(digest)).thenReturn(CompletableFuture.completedFuture(false));

        var result = handler.handleCritical(identifier, profile).get(5, TimeUnit.SECONDS);

        assertThat(result).isFalse();
        verify(view).shun(digest);
        verify(metrics, never()).recordShunnedGossip();
    }

    @Test
    void shouldHandleShunFailure() throws Exception {
        var digest = DigestAlgorithm.DEFAULT.digest("fail-to-shun");
        var identifier = new SelfAddressingIdentifier(digest);
        var profile = new MemberRiskProfile(identifier, config);

        var failingFuture = new CompletableFuture<Boolean>();
        failingFuture.completeExceptionally(new RuntimeException("Shunning failed"));
        when(view.shun(digest)).thenReturn(failingFuture);

        var result = handler.handleCritical(identifier, profile).get(5, TimeUnit.SECONDS);

        assertThat(result).isFalse();
        verify(metrics, never()).recordShunnedGossip();
    }

    @Test
    void shouldReturnFalseForNonSelfAddressingIdentifier() throws Exception {
        // Use Identifier.NONE which is not a SelfAddressingIdentifier
        var identifier = Identifier.NONE;
        var profile = new MemberRiskProfile(identifier, config);

        var result = handler.handleCritical(identifier, profile).get(5, TimeUnit.SECONDS);

        assertThat(result).isFalse();
        verify(view, never()).shun(any());
    }

    @Test
    void shouldHandleWarningWithoutShunning() throws Exception {
        var digest = DigestAlgorithm.DEFAULT.digest("warning-member");
        var identifier = new SelfAddressingIdentifier(digest);
        var profile = new MemberRiskProfile(identifier, config);

        var result = handler.handleWarning(identifier, profile).get(5, TimeUnit.SECONDS);

        // Warning should complete without action
        assertThat(result).isNull();
        verify(view, never()).shun(any());
    }

    @Test
    void shouldWorkWithoutMetrics() throws Exception {
        var handlerNoMetrics = new FirefliesResponseHandler(view);
        var digest = DigestAlgorithm.DEFAULT.digest("no-metrics-member");
        var identifier = new SelfAddressingIdentifier(digest);
        var profile = new MemberRiskProfile(identifier, config);

        when(view.shun(digest)).thenReturn(CompletableFuture.completedFuture(true));

        var result = handlerNoMetrics.handleCritical(identifier, profile).get(5, TimeUnit.SECONDS);

        assertThat(result).isTrue();
        verify(view).shun(digest);
        // No NPE when metrics is null
    }

    @Test
    void shouldRejectNullView() {
        assertThatThrownBy(() -> new FirefliesResponseHandler(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("view");
    }

    @Test
    void shouldBeIdempotent() throws Exception {
        var digest = DigestAlgorithm.DEFAULT.digest("idempotent-test");
        var identifier = new SelfAddressingIdentifier(digest);
        var profile = new MemberRiskProfile(identifier, config);

        // First call succeeds
        when(view.shun(digest))
            .thenReturn(CompletableFuture.completedFuture(true))
            .thenReturn(CompletableFuture.completedFuture(false));

        var result1 = handler.handleCritical(identifier, profile).get(5, TimeUnit.SECONDS);
        var result2 = handler.handleCritical(identifier, profile).get(5, TimeUnit.SECONDS);

        assertThat(result1).isTrue();
        assertThat(result2).isFalse();
        verify(view, times(2)).shun(digest);
        // Metrics only recorded once (when shunning succeeded)
        verify(metrics, times(1)).recordShunnedGossip();
    }
}
