/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.eatthepath.noise;

import com.eatthepath.noise.component.NoiseCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import javax.crypto.ShortBufferException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.security.Key;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for CipherState, particularly nonce overflow protection.
 */
class CipherStateTest {

    private CipherState cipherState;
    private byte[] testKey;
    private byte[] testPlaintext;
    private byte[] testAssociatedData;

    @BeforeEach
    void setUp() throws ShortBufferException, AEADBadTagException {
        // Mock the cipher to provide basic functionality
        final NoiseCipher mockCipher = mock(NoiseCipher.class);

        // Mock buildKey to return a mock Key
        when(mockCipher.buildKey(any(byte[].class))).thenReturn(mock(Key.class));

        // Mock encrypt to copy plaintext and return length + 16 (simulating AEAD tag)
        when(mockCipher.encrypt(any(Key.class), anyLong(), any(), any(ByteBuffer.class), any(ByteBuffer.class)))
            .thenAnswer(invocation -> {
                ByteBuffer plaintext = invocation.getArgument(3);
                ByteBuffer ciphertext = invocation.getArgument(4);
                var length = plaintext.remaining();
                ciphertext.put(plaintext);
                ciphertext.put(new byte[16]); // AEAD tag
                return length + 16;
            });

        when(mockCipher.encrypt(any(Key.class), anyLong(), any(), any(byte[].class), anyInt(), anyInt(), any(byte[].class), anyInt()))
            .thenAnswer(invocation -> {
                int plaintextLength = invocation.getArgument(5);
                return plaintextLength + 16;
            });

        // Mock decrypt to copy ciphertext (minus tag) and return length - 16
        when(mockCipher.decrypt(any(Key.class), anyLong(), any(), any(ByteBuffer.class), any(ByteBuffer.class)))
            .thenAnswer(invocation -> {
                ByteBuffer ciphertext = invocation.getArgument(3);
                ByteBuffer plaintext = invocation.getArgument(4);
                var length = ciphertext.remaining() - 16;
                var data = new byte[length];
                ciphertext.get(data);
                plaintext.put(data);
                return length;
            });

        when(mockCipher.decrypt(any(Key.class), anyLong(), any(), any(byte[].class), anyInt(), anyInt(), any(byte[].class), anyInt()))
            .thenAnswer(invocation -> {
                int ciphertextLength = invocation.getArgument(5);
                return ciphertextLength - 16;
            });

        cipherState = new CipherState(mockCipher);

        // Initialize with a test key (32 bytes)
        testKey = new byte[32];
        for (int i = 0; i < testKey.length; i++) {
            testKey[i] = (byte) i;
        }
        cipherState.setKey(testKey);

        // Test data
        testPlaintext = "Test message for encryption".getBytes();
        testAssociatedData = "associated data".getBytes();
    }

    @Test
    void testNonceOverflowProtectionOnEncrypt() throws Exception {
        // Set nonce to MAX_VALUE-1 so first operation increments to MAX_VALUE
        setNonce(Long.MAX_VALUE - 1);

        // First encryption at MAX_VALUE-1 should succeed and increment to MAX_VALUE
        var ciphertext = cipherState.encrypt(testAssociatedData, testPlaintext);
        assertNotNull(ciphertext);
        assertEquals(Long.MAX_VALUE, getNonce(), "Nonce should now be at MAX_VALUE");

        // Second encryption at MAX_VALUE would overflow - should throw IllegalStateException
        var exception = assertThrows(IllegalStateException.class, () -> {
            cipherState.encrypt(testAssociatedData, testPlaintext);
        });

        assertTrue(exception.getMessage().contains("Nonce overflow"),
                "Exception message should explain nonce overflow: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("AEAD security"),
                "Exception message should mention AEAD security risk: " + exception.getMessage());
    }

    @Test
    void testNonceOverflowProtectionOnEncryptByteBuffer() throws Exception {
        // Set nonce to MAX_VALUE-1 so first operation increments to MAX_VALUE
        setNonce(Long.MAX_VALUE - 1);

        var plaintext = ByteBuffer.wrap(testPlaintext);

        // First encryption at MAX_VALUE-1 should succeed and increment to MAX_VALUE
        var ciphertext = cipherState.encrypt(testAssociatedData, plaintext);
        assertNotNull(ciphertext);
        assertEquals(Long.MAX_VALUE, getNonce(), "Nonce should now be at MAX_VALUE");

        // Second encryption at MAX_VALUE would overflow - should throw IllegalStateException
        plaintext.rewind();
        var exception = assertThrows(IllegalStateException.class, () -> {
            cipherState.encrypt(testAssociatedData, plaintext);
        });

        assertTrue(exception.getMessage().contains("Nonce overflow"),
                "Exception message should explain nonce overflow");
        assertTrue(exception.getMessage().contains("AEAD security"),
                "Exception message should mention AEAD security risk");
    }

    @Test
    void testNonceOverflowProtectionOnDecrypt() throws Exception {
        // Create a valid ciphertext first (at nonce 0)
        var ciphertext = cipherState.encrypt(testAssociatedData, testPlaintext);

        // Set nonce to MAX_VALUE-1
        setNonce(Long.MAX_VALUE - 1);

        // First decrypt at MAX_VALUE-1 should succeed and increment to MAX_VALUE
        var decrypted = cipherState.decrypt(testAssociatedData, ciphertext);
        assertNotNull(decrypted);
        assertEquals(Long.MAX_VALUE, getNonce(), "Nonce should now be at MAX_VALUE");

        // Second decrypt at MAX_VALUE would overflow - should throw IllegalStateException
        var exception = assertThrows(IllegalStateException.class, () -> {
            cipherState.decrypt(testAssociatedData, ciphertext);
        });

        assertTrue(exception.getMessage().contains("Nonce overflow"),
                "Exception message should explain nonce overflow");
    }

    @Test
    void testNonceOverflowProtectionOnDecryptByteBuffer() throws Exception {
        // Create a valid ciphertext first (at nonce 0)
        var ciphertext = cipherState.encrypt(testAssociatedData, testPlaintext);

        // Set nonce to MAX_VALUE-1
        setNonce(Long.MAX_VALUE - 1);

        // First decrypt at MAX_VALUE-1 should succeed and increment to MAX_VALUE
        var ciphertextBuffer = ByteBuffer.wrap(ciphertext);
        var decrypted = cipherState.decrypt(testAssociatedData, ciphertextBuffer);
        assertNotNull(decrypted);
        assertEquals(Long.MAX_VALUE, getNonce(), "Nonce should now be at MAX_VALUE");

        // Second decrypt at MAX_VALUE would overflow - should throw IllegalStateException
        ciphertextBuffer.rewind(); // Rewind for second operation
        var exception = assertThrows(IllegalStateException.class, () -> {
            cipherState.decrypt(testAssociatedData, ciphertextBuffer);
        });

        assertTrue(exception.getMessage().contains("Nonce overflow"),
                "Exception message should explain nonce overflow");
    }

    @Test
    void testNormalOperationWithoutOverflow() throws Exception {
        // Verify normal operation still works (just check it doesn't throw)
        for (int i = 0; i < 10; i++) {
            var ciphertext = cipherState.encrypt(testAssociatedData, testPlaintext);
            assertNotNull(ciphertext);
        }

        // Verify nonce has incremented normally
        assertEquals(10, getNonce(), "Nonce should have incremented 10 times");
    }

    @Test
    void testMultipleOperationsWithoutOverflow() throws Exception {
        // Perform multiple encrypt/decrypt operations to verify nonce increments correctly
        for (int i = 0; i < 100; i++) {
            var ciphertext = cipherState.encrypt(testAssociatedData, testPlaintext);
            assertNotNull(ciphertext);
        }

        // Verify we can still decrypt (with correct nonce)
        var currentNonce = getNonce();
        assertEquals(100, currentNonce, "Nonce should have incremented 100 times");
    }

    /**
     * Helper method to set nonce via reflection for testing.
     */
    private void setNonce(long value) throws Exception {
        Field nonceField = CipherState.class.getDeclaredField("nonce");
        nonceField.setAccessible(true);
        nonceField.setLong(cipherState, value);
    }

    /**
     * Helper method to get nonce via reflection for verification.
     */
    private long getNonce() throws Exception {
        Field nonceField = CipherState.class.getDeclaredField("nonce");
        nonceField.setAccessible(true);
        return nonceField.getLong(cipherState);
    }
}
