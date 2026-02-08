/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.fsm.Combine;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract tests for ByzantineTestFramework to verify fault injection and detection.
 *
 * @author hal.hildebrand
 */
@DisplayName("ByzantineTestFramework Contract Tests")
class ByzantineTestFrameworkContractTest {
    private ByzantineTestFramework framework;
    private MockBFTValidator       validator;
    private Member                 member1;
    private Member                 member2;

    @BeforeEach
    void setUp() {
        validator = new MockBFTValidator();
        framework = new ByzantineTestFramework(validator);

        // Create test members
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), new SecureRandom());
        member1 = new ControlledIdentifierMember(stereotomy.newIdentifier());
        member2 = new ControlledIdentifierMember(stereotomy.newIdentifier());
    }

    @Test
    @DisplayName("framework starts disabled by default")
    void framework_StartsDisabled() {
        var injected = framework.injectSignatureFault(member1, Combine.Mercantile.INITIAL);

        assertThat(injected).isFalse();
        assertThat(framework.getFaultCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("setEnabled enables fault injection")
    void setEnabled_EnablesInjection() {
        framework.setEnabled(true);
        framework.configureFaults(member1, ByzantineTestFramework.FaultConfig.signatureOnly(1.0));

        var injected = framework.injectSignatureFault(member1, Combine.Mercantile.INITIAL);

        assertThat(injected).isTrue();
        assertThat(framework.getFaultCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("configureFaults sets member fault rates")
    void configureFaults_SetsMemberRates() {
        framework.setEnabled(true);
        var config = ByzantineTestFramework.FaultConfig.allFaults(1.0);
        framework.configureFaults(member1, config);

        // All fault types should inject at 100% rate
        assertThat(framework.injectSignatureFault(member1, Combine.Mercantile.INITIAL)).isTrue();
        framework.clearHistory();
        assertThat(framework.injectStateCorruption(member1, Combine.Mercantile.OPERATIONAL)).isTrue();
        framework.clearHistory();
        assertThat(framework.injectTimingAnomaly(member1, Combine.Mercantile.RECOVERING)).isTrue();
    }

    @Test
    @DisplayName("injectSignatureFault records fault and notifies validator")
    void injectSignatureFault_RecordsAndNotifies() {
        framework.setEnabled(true);
        framework.configureFaults(member1, ByzantineTestFramework.FaultConfig.signatureOnly(1.0));

        framework.injectSignatureFault(member1, Combine.Mercantile.INITIAL);

        assertThat(framework.getFaultHistory()).hasSize(1);
        assertThat(framework.getFaultHistory().get(0).type)
            .isEqualTo(ByzantineTestFramework.FaultType.SIGNATURE_FORGERY);
        assertThat(framework.getFaultHistory().get(0).member).isEqualTo(member1);

        // Validator should have recorded violation
        assertThat(validator.getTotalViolationCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("injectStateCorruption records fault")
    void injectStateCorruption_RecordsFault() {
        framework.setEnabled(true);
        framework.configureFaults(member1, ByzantineTestFramework.FaultConfig.allFaults(1.0));

        framework.injectStateCorruption(member1, Combine.Mercantile.OPERATIONAL);

        assertThat(framework.getFaultsByType(ByzantineTestFramework.FaultType.STATE_CORRUPTION))
            .hasSize(1);
    }

    @Test
    @DisplayName("injectTimingAnomaly records fault")
    void injectTimingAnomaly_RecordsFault() {
        framework.setEnabled(true);
        framework.configureFaults(member1, ByzantineTestFramework.FaultConfig.allFaults(1.0));

        framework.injectTimingAnomaly(member1, Combine.Mercantile.RECOVERING);

        assertThat(framework.getFaultsByType(ByzantineTestFramework.FaultType.TIMING_ANOMALY))
            .hasSize(1);
    }

    @Test
    @DisplayName("injectEquivocation records conflicting states")
    void injectEquivocation_RecordsConflict() {
        framework.setEnabled(true);
        framework.configureFaults(member1, ByzantineTestFramework.FaultConfig.allFaults(1.0));

        framework.injectEquivocation(member1, Combine.Mercantile.OPERATIONAL, Combine.Mercantile.RECOVERING);

        var faults = framework.getFaultsByType(ByzantineTestFramework.FaultType.EQUIVOCATION);
        assertThat(faults).hasSize(1);
        assertThat(faults.get(0).details).contains("Conflicting states");
    }

    @Test
    @DisplayName("onFaultInjected notifies listeners")
    void onFaultInjected_NotifiesListeners() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var capturedType = new AtomicReference<ByzantineTestFramework.FaultType>();

        framework.onFaultInjected((member, type) -> {
            capturedType.set(type);
            latch.countDown();
        });

        framework.setEnabled(true);
        framework.configureFaults(member1, ByzantineTestFramework.FaultConfig.signatureOnly(1.0));
        framework.injectSignatureFault(member1, Combine.Mercantile.INITIAL);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(capturedType.get()).isEqualTo(ByzantineTestFramework.FaultType.SIGNATURE_FORGERY);
    }

    @Test
    @DisplayName("getFaultsByMember filters by member")
    void getFaultsByMember_FiltersByMember() {
        framework.setEnabled(true);
        framework.configureFaults(member1, ByzantineTestFramework.FaultConfig.allFaults(1.0));
        framework.configureFaults(member2, ByzantineTestFramework.FaultConfig.allFaults(1.0));

        framework.injectSignatureFault(member1, Combine.Mercantile.INITIAL);
        framework.injectSignatureFault(member2, Combine.Mercantile.INITIAL);
        framework.injectStateCorruption(member1, Combine.Mercantile.OPERATIONAL);

        assertThat(framework.getFaultsByMember(member1)).hasSize(2);
        assertThat(framework.getFaultsByMember(member2)).hasSize(1);
    }

    @Test
    @DisplayName("getFaultsByType filters by type")
    void getFaultsByType_FiltersByType() {
        framework.setEnabled(true);
        framework.configureFaults(member1, ByzantineTestFramework.FaultConfig.allFaults(1.0));

        framework.injectSignatureFault(member1, Combine.Mercantile.INITIAL);
        framework.injectSignatureFault(member1, Combine.Mercantile.OPERATIONAL);
        framework.injectStateCorruption(member1, Combine.Mercantile.OPERATIONAL);

        assertThat(framework.getFaultsByType(ByzantineTestFramework.FaultType.SIGNATURE_FORGERY)).hasSize(2);
        assertThat(framework.getFaultsByType(ByzantineTestFramework.FaultType.STATE_CORRUPTION)).hasSize(1);
    }

    @Test
    @DisplayName("fault rates control injection probability")
    void faultRates_ControlProbability() {
        framework.setEnabled(true);
        // 0% rate should never inject
        framework.configureFaults(member1, ByzantineTestFramework.FaultConfig.signatureOnly(0.0));

        // Try 100 times, should never inject
        var injected = false;
        for (int i = 0; i < 100; i++) {
            if (framework.injectSignatureFault(member1, Combine.Mercantile.INITIAL)) {
                injected = true;
                break;
            }
        }

        assertThat(injected).isFalse();
        assertThat(framework.getFaultCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("clearHistory clears fault records")
    void clearHistory_ClearsFaults() {
        framework.setEnabled(true);
        framework.configureFaults(member1, ByzantineTestFramework.FaultConfig.signatureOnly(1.0));
        framework.injectSignatureFault(member1, Combine.Mercantile.INITIAL);

        framework.clearHistory();

        assertThat(framework.getFaultHistory()).isEmpty();
        assertThat(framework.getFaultCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("reset clears all state")
    void reset_ClearsAllState() {
        framework.setEnabled(true);
        framework.configureFaults(member1, ByzantineTestFramework.FaultConfig.allFaults(1.0));
        framework.onFaultInjected((m, t) -> {});
        framework.injectSignatureFault(member1, Combine.Mercantile.INITIAL);

        framework.reset();

        assertThat(framework.getFaultHistory()).isEmpty();
        assertThat(framework.getFaultCount()).isEqualTo(0);
        // Framework should be disabled after reset
        assertThat(framework.injectSignatureFault(member1, Combine.Mercantile.INITIAL)).isFalse();
    }

    @Test
    @DisplayName("assertFaultDetected passes when fault detected")
    void assertFaultDetected_PassesWhenDetected() {
        framework.setEnabled(true);
        framework.configureFaults(member1, ByzantineTestFramework.FaultConfig.signatureOnly(1.0));
        framework.injectSignatureFault(member1, Combine.Mercantile.INITIAL);

        framework.assertFaultDetected(ByzantineTestFramework.FaultType.SIGNATURE_FORGERY);  // Should not throw
    }

    @Test
    @DisplayName("assertFaultDetected fails when fault not detected")
    void assertFaultDetected_FailsWhenNotDetected() {
        assertThatThrownBy(() -> framework.assertFaultDetected(ByzantineTestFramework.FaultType.EQUIVOCATION))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Expected");
    }

    @Test
    @DisplayName("assertFaultDetected requires validator")
    void assertFaultDetected_RequiresValidator() {
        var noValidatorFramework = new ByzantineTestFramework();  // No validator

        assertThatThrownBy(() -> noValidatorFramework.assertFaultDetected(
            ByzantineTestFramework.FaultType.SIGNATURE_FORGERY))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No validator configured");
    }

    @Test
    @DisplayName("sequence numbers are monotonic")
    void sequenceNumbers_AreMonotonic() {
        framework.setEnabled(true);
        framework.configureFaults(member1, ByzantineTestFramework.FaultConfig.allFaults(1.0));

        framework.injectSignatureFault(member1, Combine.Mercantile.INITIAL);
        framework.injectStateCorruption(member1, Combine.Mercantile.OPERATIONAL);
        framework.injectTimingAnomaly(member1, Combine.Mercantile.RECOVERING);

        var faults = framework.getFaultHistory();
        assertThat(faults).hasSize(3);
        assertThat(faults.get(0).sequenceNumber).isEqualTo(1);
        assertThat(faults.get(1).sequenceNumber).isEqualTo(2);
        assertThat(faults.get(2).sequenceNumber).isEqualTo(3);
    }

    @Test
    @DisplayName("fault timestamps are monotonic")
    void faultTimestamps_AreMonotonic() throws InterruptedException {
        framework.setEnabled(true);
        framework.configureFaults(member1, ByzantineTestFramework.FaultConfig.signatureOnly(1.0));

        framework.injectSignatureFault(member1, Combine.Mercantile.INITIAL);
        Thread.sleep(10);
        framework.injectSignatureFault(member1, Combine.Mercantile.OPERATIONAL);

        var faults = framework.getFaultHistory();
        assertThat(faults.get(1).timestamp).isGreaterThanOrEqualTo(faults.get(0).timestamp);
    }
}
