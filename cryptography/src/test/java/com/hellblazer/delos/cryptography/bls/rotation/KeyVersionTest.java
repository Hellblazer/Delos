/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

import com.hellblazer.delos.cryptography.bls.BLSKeyPair;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.ProofOfPossession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for KeyVersion record.
 * Validates key version metadata, lifecycle checks, and expiration logic.
 *
 * @author hal.hildebrand
 */
class KeyVersionTest {

    private static final BLSProvider PROVIDER = BLSProvider.getDefault();
    private static final Random RANDOM = new Random(42);

    @Test
    void shouldCreateValidKeyVersion() {
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var now = Instant.now();
        var expires = now.plus(Duration.ofDays(30));

        var keyVersion = new KeyVersion(
            1,
            now,
            expires,
            KeyStatus.ACTIVE,
            "rot-123",
            keyPair.publicKey().proofOfPossession()
        );

        assertThat(keyVersion.versionNumber()).isEqualTo(1);
        assertThat(keyVersion.createdAt()).isEqualTo(now);
        assertThat(keyVersion.expiresAt()).isEqualTo(expires);
        assertThat(keyVersion.status()).isEqualTo(KeyStatus.ACTIVE);
        assertThat(keyVersion.rotationId()).isEqualTo("rot-123");
        assertThat(keyVersion.popProof()).isNotNull();
    }

    @Test
    void shouldRejectNullCreatedAt() {
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);

        assertThatThrownBy(() -> new KeyVersion(
            1,
            null,
            Instant.now(),
            KeyStatus.ACTIVE,
            "rot-123",
            keyPair.publicKey().proofOfPossession()
        )).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("createdAt");
    }

    @Test
    void shouldRejectNullStatus() {
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);

        assertThatThrownBy(() -> new KeyVersion(
            1,
            Instant.now(),
            null,
            null,
            "rot-123",
            keyPair.publicKey().proofOfPossession()
        )).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("status");
    }

    @Test
    void shouldRejectNullRotationId() {
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);

        assertThatThrownBy(() -> new KeyVersion(
            1,
            Instant.now(),
            null,
            KeyStatus.ACTIVE,
            null,
            keyPair.publicKey().proofOfPossession()
        )).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("rotationId");
    }

    @Test
    void shouldRejectNullPopProof() {
        assertThatThrownBy(() -> new KeyVersion(
            1,
            Instant.now(),
            null,
            KeyStatus.ACTIVE,
            "rot-123",
            null
        )).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("popProof");
    }

    @Test
    void shouldRejectExpirationBeforeCreation() {
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var now = Instant.now();
        var past = now.minus(Duration.ofDays(1));

        assertThatThrownBy(() -> new KeyVersion(
            1,
            now,
            past,
            KeyStatus.ACTIVE,
            "rot-123",
            keyPair.publicKey().proofOfPossession()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("expiresAt must be after createdAt");
    }

    @Test
    void shouldAllowNullExpiration() {
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var now = Instant.now();

        var keyVersion = new KeyVersion(
            1,
            now,
            null,
            KeyStatus.ACTIVE,
            "rot-123",
            keyPair.publicKey().proofOfPossession()
        );

        assertThat(keyVersion.expiresAt()).isNull();
        assertThat(keyVersion.isExpired(now.plus(Duration.ofDays(1000)))).isFalse();
    }

    @Test
    void shouldDetectExpiredKey() {
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var now = Instant.now();
        var expires = now.plus(Duration.ofHours(1));

        var keyVersion = new KeyVersion(
            1,
            now,
            expires,
            KeyStatus.ACTIVE,
            "rot-123",
            keyPair.publicKey().proofOfPossession()
        );

        // Not expired yet
        assertThat(keyVersion.isExpired(now)).isFalse();
        assertThat(keyVersion.isExpired(now.plus(Duration.ofMinutes(30)))).isFalse();

        // Expired
        assertThat(keyVersion.isExpired(expires)).isFalse(); // Exactly at expiration
        assertThat(keyVersion.isExpired(expires.plusMillis(1))).isTrue();
        assertThat(keyVersion.isExpired(now.plus(Duration.ofHours(2)))).isTrue();
    }

    @Test
    void shouldCheckActiveStatus() {
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var now = Instant.now();
        var expires = now.plus(Duration.ofHours(1));

        // Active and not expired
        var activeKey = new KeyVersion(
            1,
            now,
            expires,
            KeyStatus.ACTIVE,
            "rot-123",
            keyPair.publicKey().proofOfPossession()
        );
        assertThat(activeKey.isActive(now)).isTrue();
        assertThat(activeKey.isActive(now.plus(Duration.ofMinutes(30)))).isTrue();

        // Active but expired
        assertThat(activeKey.isActive(expires.plusMillis(1))).isFalse();

        // Not active status
        var deprecatedKey = new KeyVersion(
            1,
            now,
            expires,
            KeyStatus.DEPRECATED,
            "rot-123",
            keyPair.publicKey().proofOfPossession()
        );
        assertThat(deprecatedKey.isActive(now)).isFalse();
    }

    @Test
    void shouldSupportVersionNumbers() {
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var now = Instant.now();

        var v1 = new KeyVersion(1, now, null, KeyStatus.ACTIVE, "rot-1", keyPair.publicKey().proofOfPossession());
        var v2 = new KeyVersion(2, now, null, KeyStatus.ACTIVE, "rot-2", keyPair.publicKey().proofOfPossession());
        var v3 = new KeyVersion(100, now, null, KeyStatus.ACTIVE, "rot-100", keyPair.publicKey().proofOfPossession());

        assertThat(v1.versionNumber()).isEqualTo(1);
        assertThat(v2.versionNumber()).isEqualTo(2);
        assertThat(v3.versionNumber()).isEqualTo(100);
    }

    @Test
    void shouldSupportAllKeyStatuses() {
        var keyPair = BLSKeyPair.generate(RANDOM, PROVIDER);
        var now = Instant.now();

        for (var status : KeyStatus.values()) {
            var keyVersion = new KeyVersion(
                1,
                now,
                null,
                status,
                "rot-123",
                keyPair.publicKey().proofOfPossession()
            );
            assertThat(keyVersion.status()).isEqualTo(status);
        }
    }
}
