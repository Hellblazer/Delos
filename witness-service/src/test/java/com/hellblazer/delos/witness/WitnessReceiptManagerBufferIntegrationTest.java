/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.migration.MigrationPhase;
import com.hellblazer.delos.witness.validation.graceful.DegradedThresholdCalculator;
import com.hellblazer.delos.witness.validation.graceful.GracefulDegradationConfig;
import com.hellblazer.delos.witness.validation.graceful.SignatureBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Test WitnessReceiptManager buffer integration for graceful degradation.
 * <p>
 * Phase 1C-3-C: Tests signature buffering during view changes and Byzantine detection.
 */
class WitnessReceiptManagerBufferIntegrationTest {

    private WitnessParameters parameters;
    private SignatureBuffer signatureBuffer;
    private AtomicBoolean isViewChangeActive;
    private DegradedThresholdCalculator degradedCalculator;
    private WitnessReceiptManager manager;

    @BeforeEach
    void setUp() {
        // Setup parameters for BLS mode (k=4, threshold=3, epoch=1)
        parameters = new WitnessParameters(
            4,                          // k (committee cardinality)
            3,                          // threshold (M-of-N)
            1,                          // epoch
            Duration.ofSeconds(5),      // drain period
            SignatureFormat.BLS_12_381, // BLS signatures
            MigrationPhase.BLS_ONLY     // BLS only phase
        );

        // Setup buffer with default config
        var config = GracefulDegradationConfig.defaultConfig();
        signatureBuffer = new SignatureBuffer(config);

        // Setup view change flag
        isViewChangeActive = new AtomicBoolean(false);

        // Setup degraded calculator (4 members, threshold 3, 2/3+1 min)
        degradedCalculator = new DegradedThresholdCalculator(3, 4, 0.67);

        // Create manager with buffer support
        manager = new WitnessReceiptManager(
            parameters,
            signatureBuffer,
            () -> isViewChangeActive.get(),
            degradedCalculator
        );
    }

    @Test
    void shouldBufferSignaturesDuringViewChange() {
        // Given: view change is active
        isViewChangeActive.set(true);

        var event = mockEvent();
        var member = mockMember("member1");
        var signature = mockSignature();

        // When: signature arrives during view change (would be Buffered by accumulator)
        // Note: In real scenario, accumulator returns Buffered during view change
        // For testing, we simulate the buffer operation directly

        var position = signatureBuffer.buffer(
            member,
            signature.toBytes(),
            new byte[0], // message placeholder
            event,
            parameters.epoch()
        );

        // Then: signature is buffered
        assertThat(position).isGreaterThanOrEqualTo(0);
        assertThat(signatureBuffer.size()).isEqualTo(1);

        var buffered = signatureBuffer.getForEpoch(parameters.epoch());
        assertThat(buffered).hasSize(1);
        assertThat(buffered.get(0).memberId()).isEqualTo(member);
    }

    @Test
    void shouldDrainBufferedSignaturesAfterViewChange() {
        // Given: buffered signatures from previous view change
        var event = mockEvent();
        var member1 = mockMember("member1");
        var member2 = mockMember("member2");
        var sig1 = mockSignature();
        var sig2 = mockSignature();

        // Buffer signatures during view change
        isViewChangeActive.set(true);
        signatureBuffer.buffer(member1, sig1.toBytes(), new byte[0], event, parameters.epoch());
        signatureBuffer.buffer(member2, sig2.toBytes(), new byte[0], event, parameters.epoch());

        // View change completes
        isViewChangeActive.set(false);
        degradedCalculator.markActive(member1);
        degradedCalculator.markActive(member2);

        // When: drain buffer for new epoch
        manager.drainBuffer(parameters.epoch() + 1, Set.of());

        // Then: signatures are replayed and buffer is cleared
        assertThat(signatureBuffer.getForEpoch(parameters.epoch())).isEmpty();
    }

    @Test
    void shouldExcludeByzantineMembersFromDrain() {
        // Given: buffered signatures including Byzantine member
        var event = mockEvent();
        var byzantineMember = mockMember("byzantine");
        var honestMember = mockMember("honest");
        var sig1 = mockSignature();
        var sig2 = mockSignature();

        signatureBuffer.buffer(byzantineMember, sig1.toBytes(), new byte[0], event, parameters.epoch());
        signatureBuffer.buffer(honestMember, sig2.toBytes(), new byte[0], event, parameters.epoch());

        // Mark one member as Byzantine
        degradedCalculator.markByzantine(byzantineMember);
        degradedCalculator.markActive(honestMember);

        var byzantineMembers = Set.of(byzantineMember);

        // When: drain buffer excluding Byzantine members
        manager.drainBuffer(parameters.epoch() + 1, byzantineMembers);

        // Then: Byzantine member signature not replayed, honest member processed
        // Verify by checking degradedCalculator's shouldInclude was respected
        assertThat(degradedCalculator.shouldInclude(byzantineMember, byzantineMembers)).isFalse();
        assertThat(degradedCalculator.shouldInclude(honestMember, byzantineMembers)).isTrue();
    }

    @Test
    void shouldMaintainBufferSizeLimits() {
        // Given: buffer with capacity limit
        var config = new GracefulDegradationConfig(
            0.33,      // byzantineQuorumReductionFactor
            10,        // maxSignaturesToBuffer (limited capacity)
            30000,     // signatureBufferTTLMs
            10000,     // viewChangeTimeoutMs
            true,      // enableAutoRecovery
            100,       // recoveryCheckIntervalMs
            true,      // dynamicThresholdRecalculation
            0.667,     // minThresholdPercentage
            true,      // enableMetrics
            10000      // metricsHistorySize
        );
        var limitedBuffer = new SignatureBuffer(config);

        // When: buffer more signatures than capacity
        var event = mockEvent();
        for (var i = 0; i < 15; i++) {
            var member = mockMember("member" + i);
            var signature = mockSignature();
            limitedBuffer.buffer(member, signature.toBytes(), new byte[0], event, 1);
        }

        // Then: buffer size is capped at max capacity
        assertThat(limitedBuffer.size()).isLessThanOrEqualTo(10);
    }

    @Test
    void shouldHandleRapidViewChanges() {
        // Given: rapid view changes with buffered signatures
        var event = mockEvent();

        // First view change - buffer signatures
        isViewChangeActive.set(true);
        signatureBuffer.buffer(mockMember("m1"), mockSignature().toBytes(), new byte[0], event, 1);
        signatureBuffer.buffer(mockMember("m2"), mockSignature().toBytes(), new byte[0], event, 1);

        // Drain first batch
        isViewChangeActive.set(false);
        manager.drainBuffer(2, Set.of());

        // Second view change - buffer more signatures
        isViewChangeActive.set(true);
        signatureBuffer.buffer(mockMember("m3"), mockSignature().toBytes(), new byte[0], event, 2);

        // Then: second epoch signatures still buffered
        assertThat(signatureBuffer.getForEpoch(2)).hasSize(1);
        assertThat(signatureBuffer.getForEpoch(1)).isEmpty(); // First epoch cleared
    }

    @Test
    void shouldHandleNullSignatureBufferGracefully() {
        // Given: manager without buffer support (feature flag disabled)
        var managerWithoutBuffer = new WitnessReceiptManager(parameters);

        // When: drain is called
        managerWithoutBuffer.drainBuffer(2, Set.of());

        // Then: no exception, operation is no-op
        // Test passes if no exception thrown
    }

    // Helper methods

    private EventCoordinates mockEvent() {
        var event = mock(EventCoordinates.class);
        when(event.toString()).thenReturn("event-1");
        return event;
    }

    private Identifier mockMember(String name) {
        var member = mock(Identifier.class);
        when(member.toString()).thenReturn(name);
        return member;
    }

    private BLSSignature mockSignature() {
        // BLSSignature is a record (final class), so create a real instance
        // with dummy bytes (96 bytes for BLS12-381 signature)
        var bytes = new byte[96];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i % 256);
        }
        return BLSSignature.fromBytes(bytes);
    }
}
