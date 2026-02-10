package com.eatthepath.noise;

import com.eatthepath.noise.component.NoiseCipher;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import javax.crypto.AEADBadTagException;
import javax.crypto.ShortBufferException;
import java.nio.ByteBuffer;
import java.security.Key;

@NotThreadSafe
class CipherState {

  private Key key;
  private long nonce;

  private final NoiseCipher cipher;

  public CipherState(final NoiseCipher cipher) {
    this.cipher = cipher;
  }

  public void setKey(final byte[] keyBytes) {
    final byte[] sizedKeyBytes;

    if (keyBytes.length > 32) {
      sizedKeyBytes = new byte[32];
      System.arraycopy(keyBytes, 0, sizedKeyBytes, 0, 32);
    } else {
      sizedKeyBytes = keyBytes;
    }

    this.key = cipher.buildKey(sizedKeyBytes);
    this.nonce = 0;
  }

  public boolean hasKey() {
    return this.key != null;
  }

  public ByteBuffer decrypt(@Nullable final byte[] associatedData, final ByteBuffer ciphertext)
      throws AEADBadTagException {

    final ByteBuffer plaintext = ByteBuffer.allocate(getPlaintextLength(ciphertext.remaining()));

    try {
      decrypt(associatedData, ciphertext, plaintext);
    } catch (final ShortBufferException e) {
      // This should never happen for a buffer we control
      throw new AssertionError(e);
    }

    return plaintext.flip();
  }

  public int decrypt(@Nullable final byte[] associatedData, final ByteBuffer ciphertext, final ByteBuffer plaintext)
      throws AEADBadTagException, ShortBufferException {

    if (hasKey()) {
      final int plaintextLength = cipher.decrypt(key, nonce, associatedData, ciphertext, plaintext);
      checkAndIncrementNonce();

      return plaintextLength;
    } else {
      final int ciphertextLength = ciphertext.remaining();
      plaintext.put(ciphertext);

      return ciphertextLength;
    }
  }

  public byte[] decrypt(@Nullable final byte[] associatedData, final byte[] ciphertext) throws AEADBadTagException {
    final byte[] plaintext = new byte[getPlaintextLength(ciphertext.length)];

    try {
      decrypt(associatedData,
          ciphertext,
          0,
          ciphertext.length,
          plaintext,
          0);
    } catch (final ShortBufferException e) {
      // This should never happen for a buffer we control
      throw new AssertionError(e);
    }

    return plaintext;
  }

  public int decrypt(@Nullable final byte[] associatedData,
                     final byte[] ciphertext,
                     final int ciphertextOffset,
                     final int ciphertextLength,
                     final byte[] plaintext,
                     final int plaintextOffset) throws AEADBadTagException, ShortBufferException {

    if (hasKey()) {
      final int plaintextLength = cipher.decrypt(key,
          nonce,
          associatedData,
          ciphertext,
          ciphertextOffset,
          ciphertextLength,
          plaintext,plaintextOffset);

      checkAndIncrementNonce();

      return plaintextLength;
    } else {
      System.arraycopy(ciphertext, ciphertextOffset, plaintext, plaintextOffset, ciphertextLength);
      return ciphertextLength;
    }
  }

  public ByteBuffer encrypt(@Nullable final byte[] associatedData, final ByteBuffer plaintext) {
    final ByteBuffer ciphertext = ByteBuffer.allocate(getCiphertextLength(plaintext.remaining()));

    try {
      encrypt(associatedData, plaintext, ciphertext);
    } catch (final ShortBufferException e) {
      // This should never happen for a buffer we control
      throw new AssertionError(e);
    }

    return ciphertext.flip();
  }

  public int encrypt(@Nullable final byte[] associatedData, final ByteBuffer plaintext, final ByteBuffer ciphertext) throws ShortBufferException {
    if (hasKey()) {
      final int ciphertextLength = cipher.encrypt(key, nonce, associatedData, plaintext, ciphertext);
      checkAndIncrementNonce();

      return ciphertextLength;
    } else {
      final int plaintextLength = plaintext.remaining();
      ciphertext.put(plaintext);

      return plaintextLength;
    }
  }

  public byte[] encrypt(@Nullable final byte[] associatedData, final byte[] plaintext) {
    final byte[] ciphertext = new byte[getCiphertextLength(plaintext.length)];

    try {
      encrypt(associatedData,
          plaintext,
          0,
          plaintext.length,
          ciphertext,
          0);
    } catch (final ShortBufferException e) {
      // This should never happen for a buffer we control
      throw new AssertionError(e);
    }

    return ciphertext;
  }

  public int encrypt(@Nullable final byte[] associatedData,
                       final byte[] plaintext,
                       final int plaintextOffset,
                       final int plaintextLength,
                       final byte[] ciphertext,
                       final int ciphertextOffset) throws ShortBufferException {

    if (hasKey()) {
      final int ciphertextLength = cipher.encrypt(key,
          nonce,
          associatedData,
          plaintext,
          plaintextOffset,
          plaintextLength,
          ciphertext,
          ciphertextOffset);

      checkAndIncrementNonce();

      return ciphertextLength;
    } else {
      System.arraycopy(plaintext, plaintextOffset, ciphertext, ciphertextOffset, plaintextLength);
      return plaintextLength;
    }
  }

  public int getCiphertextLength(final int plaintextLength) {
    return hasKey() ? plaintextLength + 16 : plaintextLength;
  }

  public int getPlaintextLength(final int ciphertextLength) {
    if (hasKey()) {
      if (ciphertextLength < 16) {
        throw new IllegalArgumentException("Ciphertexts must be at least 16 bytes long");
      }

      return ciphertextLength - 16;
    } else {
      return ciphertextLength;
    }
  }

  public void rekey() {
    key = cipher.rekey(key);
  }

  NoiseCipher getCipher() {
    return cipher;
  }

  /**
   * Checks for nonce overflow and increments the nonce.
   * Per the Noise protocol specification, nonces must be monotonically increasing.
   * If nonce were to overflow from Long.MAX_VALUE to Long.MIN_VALUE (negative),
   * this would catastrophically break AEAD security guarantees by violating
   * nonce uniqueness requirements.
   *
   * @throws IllegalStateException if incrementing would cause overflow
   */
  private void checkAndIncrementNonce() {
    if (nonce == Long.MAX_VALUE) {
      throw new IllegalStateException(
          "Nonce overflow detected. Cannot increment nonce beyond Long.MAX_VALUE. " +
          "This would catastrophically break AEAD security by violating the Noise protocol's " +
          "requirement for monotonically increasing nonces. A new CipherState must be established.");
    }
    nonce += 1;
  }
}
