/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine.testing;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.byzantine.IntelligenceConfig;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ByzantineFaultInjector.
 */
class ByzantineFaultInjectorTest {

    private ByzantineFaultInjector injector;
    private SecureRandom entropy;
    private Clock fixedClock;

    @BeforeEach
    void setUp() throws Exception {
        entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 1, 2, 3 });
        fixedClock = Clock.fixed(Instant.parse("2026-01-31T12:00:00Z"), ZoneId.of("UTC"));
        injector = new ByzantineFaultInjector(IntelligenceConfig.LAYER_FIREFLIES, entropy, fixedClock);
    }

    @Test
    void shouldReturnCorrectLayerName() {
        assertThat(injector.getLayerName()).isEqualTo(IntelligenceConfig.LAYER_FIREFLIES);
    }

    @Test
    void shouldInjectFault() {
        var member = createMember("test-member");

        var handle = injector.injectFault(member, FaultType.EQUIVOCATION, Duration.ofMinutes(5));

        assertThat(handle).isNotNull();
        assertThat(handle.getType()).isEqualTo(FaultType.EQUIVOCATION);
        assertThat(handle.getMemberId()).isEqualTo(member);
        assertThat(handle.isRestored()).isFalse();
    }

    @Test
    void shouldTrackActiveMembers() {
        var member1 = createMember("member-1");
        var member2 = createMember("member-2");

        assertThat(injector.getTrackedMemberCount()).isEqualTo(0);

        injector.injectFault(member1, FaultType.CRASH, Duration.ofMinutes(1));
        assertThat(injector.getTrackedMemberCount()).isEqualTo(1);

        injector.injectFault(member2, FaultType.DELAY, Duration.ofMinutes(1));
        assertThat(injector.getTrackedMemberCount()).isEqualTo(2);
    }

    @Test
    void shouldRestoreFault() {
        var member = createMember("restore-test");

        var handle = injector.injectFault(member, FaultType.EQUIVOCATION, Duration.ofMinutes(5));
        assertThat(injector.hasFault(member)).isTrue();

        handle.restore();
        assertThat(injector.hasFault(member)).isFalse();
        assertThat(handle.isRestored()).isTrue();
    }

    @Test
    void shouldBeIdempotentRestore() {
        var member = createMember("idempotent-test");
        var handle = injector.injectFault(member, FaultType.CRASH, Duration.ofMinutes(5));

        // Restore multiple times
        handle.restore();
        handle.restore();
        handle.restore();

        assertThat(handle.isRestored()).isTrue();
        assertThat(injector.hasFault(member)).isFalse();
    }

    @Test
    void shouldAutoCloseWithTryWithResources() {
        var member = createMember("auto-close-test");

        try (var handle = injector.injectCrash(member)) {
            assertThat(injector.hasFault(member)).isTrue();
        }

        assertThat(injector.hasFault(member)).isFalse();
    }

    @Test
    void shouldProvideMemberAnomalyStates() {
        var member1 = createMember("state-1");
        var member2 = createMember("state-2");

        injector.injectEquivocation(member1);
        injector.injectDelay(member2, Duration.ofSeconds(5));

        var states = injector.getMemberAnomalyStates();

        assertThat(states).hasSize(2);
        assertThat(states).containsKey(member1);
        assertThat(states).containsKey(member2);
    }

    @Test
    void shouldReturnCorrectAnomalyScore() {
        var member = createMember("score-test");

        // Equivocation has high weight (0.9)
        injector.injectEquivocation(member);

        var state = injector.getMemberState(member);
        assertThat(state).isPresent();
        assertThat(state.get().anomalyScore()).isEqualTo(0.9);
    }

    @Test
    void shouldTrackFaultHistory() {
        var member = createMember("history-test");

        injector.injectFault(member, FaultType.CRASH, Duration.ofMinutes(1));
        injector.injectFault(member, FaultType.DELAY, Duration.ofMinutes(1));

        var history = injector.getFaultHistory(member);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).type).isEqualTo(FaultType.CRASH);
        assertThat(history.get(1).type).isEqualTo(FaultType.DELAY);
    }

    @Test
    void shouldClearAllFaults() {
        var member1 = createMember("clear-1");
        var member2 = createMember("clear-2");

        injector.injectCrash(member1);
        injector.injectCrash(member2);
        assertThat(injector.getTrackedMemberCount()).isEqualTo(2);

        injector.clearAll();
        assertThat(injector.getTrackedMemberCount()).isEqualTo(0);
    }

    @Test
    void shouldResetIncludingHistory() {
        var member = createMember("reset-test");

        injector.injectCrash(member);
        assertThat(injector.getFaultHistory(member)).hasSize(1);

        injector.reset();

        assertThat(injector.getTrackedMemberCount()).isEqualTo(0);
        assertThat(injector.getFaultHistory(member)).isEmpty();
    }

    @Test
    void shouldConvenienceMethodsWork() {
        var member = createMember("convenience-test");

        // Test all convenience methods
        var crash = injector.injectCrash(member);
        assertThat(crash.getType()).isEqualTo(FaultType.CRASH);
        crash.restore();

        var delay = injector.injectDelay(member, Duration.ofSeconds(1));
        assertThat(delay.getType()).isEqualTo(FaultType.DELAY);
        delay.restore();

        var equivocation = injector.injectEquivocation(member);
        assertThat(equivocation.getType()).isEqualTo(FaultType.EQUIVOCATION);
        equivocation.restore();

        var forgery = injector.injectSignatureForgery(member);
        assertThat(forgery.getType()).isEqualTo(FaultType.SIGNATURE_FORGERY);
        forgery.restore();

        var timing = injector.injectTimingAttack(member);
        assertThat(timing.getType()).isEqualTo(FaultType.TIMING_ATTACK);
        timing.restore();
    }

    @Test
    void shouldAcceptDigestConvenience() {
        var digest = DigestAlgorithm.DEFAULT.digest("digest-test");

        var handle = injector.injectFault(digest, FaultType.CRASH, Duration.ofMinutes(1));

        assertThat(handle).isNotNull();
        assertThat(injector.getTrackedMemberCount()).isEqualTo(1);
    }

    @Test
    void shouldRejectNullLayerName() {
        assertThatThrownBy(() -> new ByzantineFaultInjector(null, entropy))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("layerName");
    }

    @Test
    void shouldRejectNullEntropy() {
        assertThatThrownBy(() -> new ByzantineFaultInjector("test", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("entropy");
    }

    @Test
    void shouldGetActiveFaultType() {
        var member = createMember("type-test");

        assertThat(injector.getActiveFaultType(member)).isEmpty();

        injector.injectEquivocation(member);
        assertThat(injector.getActiveFaultType(member)).contains(FaultType.EQUIVOCATION);
    }

    @Test
    void shouldCreateDeterministicMemberId() {
        var id1 = injector.createMemberId("same-seed");
        var id2 = injector.createMemberId("same-seed");
        var id3 = injector.createMemberId("different-seed");

        assertThat(id1).isEqualTo(id2);
        assertThat(id1).isNotEqualTo(id3);
    }

    private SelfAddressingIdentifier createMember(String seed) {
        return new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest(seed));
    }
}
