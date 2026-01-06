# EdDSA↔XDH Key Conversion: Security Analysis

**Document Version:** 2.0
**Date:** 2025-12-31
**Bead Reference:** Delos-868.6
**Status:** Final

## Executive Summary

This document provides a comprehensive security analysis of the Ed25519→X25519 and Ed448→X448 key conversion mechanisms used in Delos. The conversions enable using a single EdDSA identity key pair for both digital signatures (authentication) and XDH-based encryption (DHKEM for confidentiality).

**Key Findings:**
- Both conversions are **cryptographically sound** when implemented correctly
- **Formal security proofs exist** for joint Ed25519/X25519 key usage (Thormarker 2021)
- Ed448/X448 follows analogous security properties with different mathematical constants
- **Key separation is recommended** for high-security applications but not strictly required
- **Side-channel resistance** depends on implementation details
- Delos's architecture (KERI identity + on-demand conversion) follows best practices

---

## 1. Threat Model

### 1.1 Adversary Capabilities

| Adversary Type | Capabilities | Relevant Attacks |
|----------------|--------------|------------------|
| **Network Attacker** | Passive eavesdropping, active MITM | Cross-protocol attacks, key recovery via protocol confusion |
| **Malicious Peer** | Participates in protocol, sends crafted messages | Small subgroup attacks, invalid curve attacks |
| **Side-Channel Attacker** | Timing measurements, power analysis, EM emissions | Key extraction via non-constant-time operations |
| **Cryptanalytic Attacker** | Unbounded computation (theoretical) | Mathematical weaknesses in curve or conversion |

### 1.2 Assets Under Protection

| Asset | Ed25519/X25519 | Ed448/X448 |
|-------|----------------|------------|
| **Private Key (Seed)** | 32-byte secret | 57-byte secret |
| **Derived Scalar** | 32 bytes (ECDH) | 56 bytes (ECDH) |
| **Session Keys** | Ephemeral via DHKEM | Ephemeral via DHKEM |
| **Identity Binding** | KERI identifier | KERI identifier |

### 1.3 Security Goals

| Goal | Description | Mechanism |
|------|-------------|-----------|
| **Confidentiality** | Encrypted messages readable only by intended recipient | X25519/X448 ECDH + symmetric encryption |
| **Authenticity** | Messages verifiably from claimed sender | Ed25519/Ed448 signatures |
| **Forward Secrecy** | Past sessions secure if long-term key compromised | Ephemeral XDH keys per session |
| **Key Binding** | Encryption/signing keys provably belong to same identity | Deterministic conversion from EdDSA |

---

## 2. Conversion Algorithm Analysis

### 2.1 Ed25519→X25519 Private Key Conversion

**Algorithm:** Ed25519 seed → X25519 scalar

```
x25519_scalar = SHA-512(ed25519_seed)[0:32]
x25519_scalar[0]  &= 0xF8   // Clear low 3 bits (cofactor clearing)
x25519_scalar[31] &= 0x7F   // Clear high bit
x25519_scalar[31] |= 0x40   // Set bit 254
```

**Security Properties:**

| Property | Status | Rationale |
|----------|--------|-----------|
| **One-way** | ✓ Secure | SHA-512 preimage resistance (2^256 security) |
| **Deterministic** | ✓ Secure | Same seed always produces same scalar |
| **Cofactor cleared** | ✓ Secure | Prevents small subgroup attacks |
| **Bit 254 set** | ✓ Secure | Enables Montgomery ladder optimization |

### 2.2 Ed448→X448 Private Key Conversion

**Algorithm:** Ed448 seed → X448 scalar

```
h = SHAKE256(ed448_seed, output_length=114)
h[0]  &= 0xFC   // Clear bits 0-1 (cofactor clearing for h=4)
h[55] |= 0x80   // Set bit 447
h[56]  = 0x00   // Clear bits 448-455
x448_scalar = h[0:56]
```

**Security Properties:**

| Property | Status | Rationale |
|----------|--------|-----------|
| **One-way** | ✓ Secure | SHAKE256 preimage resistance (2^256 security) |
| **Deterministic** | ✓ Secure | Same seed always produces same scalar |
| **Cofactor cleared** | ✓ Secure | Clear 2 bits for cofactor h=4 |
| **Bit 447 set** | ✓ Secure | Enables Montgomery ladder optimization |

**Key Differences from Ed25519:**

| Aspect | Ed25519 | Ed448 |
|--------|---------|-------|
| Hash function | SHA-512 | SHAKE256 (XOF) |
| Hash output | 64 bytes | 114 bytes |
| Cofactor | h=8 | h=4 |
| Bits cleared | 3 (low) | 2 (low) |
| High bit set | Bit 254 | Bit 447 |

### 2.3 Ed25519→X25519 Public Key Conversion

**Algorithm:** Ed25519 point (x, y) → X25519 u-coordinate

```
// Ed25519: TWISTED Edwards curve (a=-1)
// Birational equivalence to Montgomery:
u = (1 + y) * (1 - y)^(-1) mod p
where p = 2^255 - 19
```

**Security Properties:**

| Property | Status | Rationale |
|----------|--------|-----------|
| **Lossy (sign bit)** | ⚠ By design | X25519 only uses u-coordinate; x sign discarded |
| **Deterministic** | ✓ Secure | Same Ed25519 public key → same X25519 public key |
| **Modular arithmetic** | ✓ Secure | Correct field operations over GF(2^255-19) |

### 2.4 Ed448→X448 Public Key Conversion

**Algorithm:** Ed448 point (x, y) → X448 u-coordinate

```
// Ed448: UNTWISTED Edwards curve (a=1)
// Birational equivalence to Montgomery (DIFFERENT from Ed25519!):
u = (1 - y) * (1 + y)^(-1) mod p
where p = 2^448 - 2^224 - 1 (Goldilocks prime)
```

**CRITICAL:** The birational map for Ed448 is **inverted** compared to Ed25519 because:
- Ed25519 uses a **twisted** Edwards curve: -x² + y² = 1 + dx²y²
- Ed448 uses an **untwisted** Edwards curve: x² + y² = 1 + dx²y²

**Implementation Note:** In Delos, Ed448→X448 public key conversion requires the full KeyPair, not just the public key. This is because deriving the X448 public key from the X448 private key ensures consistency. Use `SignatureAlgorithm.ED_448.toEncryption(KeyPair)` instead of `toEncryption(PublicKey)`.

---

## 3. Attack Vector Analysis

### 3.1 Cross-Protocol Attacks

**Definition:** Exploiting mathematical relationships when the same key is used in multiple protocols.

**Ed25519 + X25519 Analysis:**

| Attack Vector | Risk | Mitigation |
|---------------|------|------------|
| **Signature/Encryption Oracle** | LOW | Thormarker (2021) proves joint security in ROM |
| **Protocol Confusion** | LOW | Ed25519 and X25519 use different encodings, domains |
| **Chosen-Message Attacks** | LOW | Ed25519 uses deterministic nonces (no nonce reuse) |

**Ed448 + X448 Analysis:**

| Attack Vector | Risk | Mitigation |
|---------------|------|------------|
| **Signature/Encryption Oracle** | LOW | Analogous to Ed25519/X25519 (no known attacks) |
| **Protocol Confusion** | LOW | Ed448 and X448 use different encodings, domains |
| **Chosen-Message Attacks** | LOW | Ed448 uses deterministic nonces (SHAKE256-based) |

**Research Basis:**
- Thormarker, E. (2021). "[On using the same key pair for Ed25519 and an X25519 based KEM](https://eprint.iacr.org/2021/509)" proves:
  - Ed25519 remains EUF-CMA secure with X25519 decapsulation oracle
  - X25519 KEM remains IND-CCA secure with Ed25519 signing oracle
  - No domain separation assumptions required
  - Ed448/X448 expected to follow analogous security properties

### 3.2 Small Subgroup Attacks

**Definition:** Exploiting composite group order to leak private key bits through ECDH with low-order points.

| Curve | Cofactor | Bits Cleared | Protection |
|-------|----------|--------------|------------|
| Curve25519 | h=8 | 3 low bits | Attacker learns only `8a mod 8 = 0` |
| Curve448 | h=4 | 2 low bits | Attacker learns only `4a mod 4 = 0` |

**Result:** Small subgroup attacks are mitigated by design for both curves.

### 3.3 Invalid Curve Attacks

**Definition:** Attacker sends point on different curve (twist) to leak private key.

**Mitigation (Both Curves):**
- Montgomery ladder implementation is twist-secure
- Invalid points produce valid (but useless) shared secrets
- No point validation required for security

**Status:** ✓ Not vulnerable (Montgomery ladder design property)

### 3.4 Side-Channel Attacks

**Definition:** Extracting secrets through timing, power, or electromagnetic measurements.

| Attack Type | Concern | Mitigation Status |
|-------------|---------|-------------------|
| **Timing Attacks** | Hash/field operations must be constant-time | ⚠ JDK-dependent |
| **Cache Timing** | Table lookups can leak key bits | ⚠ JDK-dependent |
| **Power Analysis** | Scalar multiplication leakage | ⚠ Hardware-dependent |
| **EM Emissions** | Similar to power analysis | ⚠ Hardware-dependent |

**Recommendations:**
1. Use [BouncyCastle's constant-time implementations](https://www.bouncycastle.org/) for high-security deployments
2. Consider hardware security modules (HSMs) for long-term keys
3. Monitor [ed25519-unsafe-libs](https://github.com/MystenLabs/ed25519-unsafe-libs) for known vulnerable implementations

### 3.5 Double Public Key Attack (EdDSA-Specific)

**Definition:** Attacker provides mismatched public key to signing function, enabling private key recovery.

**Delos Status:** ✓ Not vulnerable (both Ed25519 and Ed448)
- `SignatureAlgorithm` uses coupled key pairs
- Public key derived from private key internally
- No API exposes decoupled key signing

---

## 4. Delos-Specific Threat Model

### 4.1 Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     KERI Identity                            │
│                                                              │
│  ┌─────────────────┐         ┌─────────────────┐            │
│  │ Ed25519 Keypair │         │  Ed448 Keypair  │            │
│  │   (Signing)     │         │    (Signing)    │            │
│  └────────┬────────┘         └────────┬────────┘            │
│           │ On-demand                 │ On-demand            │
│           │ conversion                │ conversion           │
│           ▼                           ▼                      │
│  ┌─────────────────┐         ┌─────────────────┐            │
│  │ X25519 Keypair  │         │  X448 Keypair   │            │
│  │  (Encryption)   │         │  (Encryption)   │            │
│  └─────────────────┘         └─────────────────┘            │
│                                                              │
│  Source of Truth: EdDSA keys in KERL                        │
│  XDH keys: Derived on-demand, never persisted               │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 Use Cases in Delos

| Component | EdDSA Usage | XDH Usage |
|-----------|-------------|-----------|
| **KERI (stereotomy)** | Identity signatures, event signing | Not used directly |
| **Fireflies** | Note signing, accusations | Member-to-member encryption (potential) |
| **CHOAM** | Transaction signing | Sealed proposals (potential) |
| **Gorgoneion** | Attestation signatures | Admission encryption (potential) |

### 4.3 Key Lifecycle

| Phase | EdDSA Keys | XDH Keys |
|-------|------------|----------|
| **Generation** | Generated once at identity creation | Derived on-demand |
| **Storage** | Stored in KERL (persistent) | Not stored (recomputed) |
| **Rotation** | Via KERI pre-rotation | Rotates with EdDSA |
| **Revocation** | KERI event | Implicit (no separate revocation) |

### 4.4 Delos Security Properties

| Property | Analysis |
|----------|----------|
| **Key Binding** | ✓ XDH keys cryptographically bound to EdDSA identity |
| **No Key Sprawl** | ✓ Single key pair serves both purposes |
| **Rotation Consistency** | ✓ XDH keys automatically update with EdDSA rotation |
| **Forward Secrecy** | ⚠ Requires ephemeral XDH keys for each session |

---

## 5. Recommendations

### 5.1 Current Implementation

The current implementation is **secure for production use** with the following status:

| Recommendation | Priority | Ed25519/X25519 | Ed448/X448 |
|----------------|----------|----------------|------------|
| Use correct hash (SHA-512/SHAKE256) | CRITICAL | ✓ Fixed | ✓ Implemented |
| Use modular arithmetic for conversion | CRITICAL | ✓ Fixed | ✓ Implemented |
| Apply proper bit clamping | CRITICAL | ✓ Fixed | ✓ Implemented |
| Validate via end-to-end tests | HIGH | ✓ Done | ✓ Done |

### 5.2 Operational Recommendations

| Recommendation | Priority | Rationale |
|----------------|----------|-----------|
| **Use ephemeral keys for encryption sessions** | HIGH | Provides forward secrecy |
| **Store EdDSA keys securely** | HIGH | Single point of compromise |
| **Prefer separate keys for high-security** | MEDIUM | Defense in depth (per libsodium) |
| **Monitor for side-channel vulnerabilities** | MEDIUM | JDK implementations may vary |

### 5.3 API Usage

**Ed25519/X25519:**
```java
// All three methods work for Ed25519
SignatureAlgorithm.ED_25519.toEncryption(keyPair);
SignatureAlgorithm.ED_25519.toEncryption(publicKey);
SignatureAlgorithm.ED_25519.toEncryption(privateKey);
```

**Ed448/X448:**
```java
// For Ed448, use the KeyPair method
KeyPair x448 = SignatureAlgorithm.ED_448.toEncryption(ed448KeyPair);

// toEncryption(PublicKey) throws UnsupportedOperationException
// because Ed448→X448 public conversion requires the private key
```

### 5.4 Future Considerations

| Enhancement | Priority | Notes |
|-------------|----------|-------|
| Post-quantum migration path | LOW | EdDSA/XDH not quantum-resistant |
| Hardware key storage | MEDIUM | HSM support for long-term keys |
| Alternative: ECDH over Edwards | LOW | Avoids conversion (libsodium option) |

---

## 6. Test Coverage

### Ed25519/X25519 Tests

| Test | Property Validated |
|------|-------------------|
| `edDSAOperationsMatchesTestVectors` | Correct SHA-512 and clamping |
| `conversionIsDeterministic` | No randomness leakage |
| `signatureAlgorithmMatchesLazySodium` | Cross-implementation agreement |
| `keyCorrespondenceAfterConversion` | Public/private key relationship |
| `encryptDecryptWithConvertedKeys` | End-to-end encryption correctness |
| `x25519ToEd25519PublicRecoveryIsAmbiguous` | Documents sign-bit loss |
| `x25519ToEd25519PrivateRecoveryIsImpossible` | Documents one-way property |

### Ed448/X448 Tests

| Test | Property Validated |
|------|-------------------|
| `conversionIsDeterministic` | Same input always produces same output |
| `differentKeysProduceDifferentConversions` | No collision in conversion |
| `keyCorrespondenceAfterConversion` | ECDH self-agreement validates key pairing |
| `encryptDecryptWithConvertedKeys` | End-to-end encryption with Alice/Bob |
| `ed448KeysHaveCorrectSize` | 57-byte public key, 114-byte signature |
| `x448ConvertedKeysHaveCorrectSize` | XDH algorithm type confirmed |
| `ed448SigningStillWorksAfterConversionImplemented` | No regression in signature functionality |
| `edDSAOperationsX448MethodsWork` | Low-level SHAKE256 and clamping correct |
| `seedBasedGenerationIsConsistent` | Deterministic generation |
| `encryptionAlgorithmX448WorksWithConvertedKeys` | DHKEM integration test |
| `x448ToEd448PrivateRecoveryIsImpossible` | Documents one-way SHAKE256 property |
| `x448ToEd448PublicRecoveryIsAmbiguous` | Documents sign-bit loss |

---

## 7. References

### Academic Papers
- Thormarker, E. (2021). [On using the same key pair for Ed25519 and an X25519 based KEM](https://eprint.iacr.org/2021/509). IACR ePrint 2021/509.
- Brendel et al. (2020). [The Provable Security of Ed25519: Theory and Practice](https://eprint.iacr.org/2020/823). IACR ePrint 2020/823.
- Valenta et al. (2016). [Measuring small subgroup attacks against Diffie-Hellman](https://eprint.iacr.org/2016/995). IACR ePrint 2016/995.
- Hamburg, M. (2015). [Ed448-Goldilocks, a new elliptic curve](https://eprint.iacr.org/2015/625). IACR ePrint 2015/625.

### Standards
- RFC 8032: Edwards-Curve Digital Signature Algorithm (EdDSA)
- RFC 7748: Elliptic Curves for Security (X25519, X448)
- RFC 8410: Algorithm Identifiers for Ed25519, Ed448, X25519, X448

### Implementation References
- [Libsodium: Ed25519 to Curve25519](https://libsodium.gitbook.io/doc/advanced/ed25519-curve25519)
- [Curve25519 Clamping & Cofactor Clearing](https://risencrypto.github.io/CofactorClearing/)
- [MystenLabs: ed25519-unsafe-libs](https://github.com/MystenLabs/ed25519-unsafe-libs)
- [BouncyCastle Ed448/X448 Implementation](https://www.bouncycastle.org/)

---

## Appendix A: Mathematical Background

### A.1 Curve Relationships

**Ed25519 (Twisted Edwards):**
```
-x² + y² = 1 + dx²y²
where d = -121665/121666
Prime: p = 2^255 - 19
```

**X25519 (Montgomery):**
```
v² = u³ + 486662u² + u
```

**Birational Equivalence (Ed25519→X25519):**
```
u = (1 + y) / (1 - y) mod p
```

---

**Ed448 (Untwisted Edwards):**
```
x² + y² = 1 - 39081x²y²
Prime: p = 2^448 - 2^224 - 1 (Goldilocks)
```

**X448 (Montgomery):**
```
v² = u³ + 156326u² + u
```

**Birational Equivalence (Ed448→X448):**
```
u = (1 - y) / (1 + y) mod p
```

**Note:** The formulas are **inverted** because Ed25519 is a **twisted** Edwards curve (a=-1) while Ed448 is **untwisted** (a=1).

### A.2 Why Sign Bit is Lost

| Curve | Public Key Encoding | XDH Encoding |
|-------|---------------------|--------------|
| Ed25519 | 32 bytes: y (255 bits) + sign(x) (1 bit) | 32 bytes: u (255 bits) |
| Ed448 | 57 bytes: y (455 bits) + sign(x) (1 bit) | 56 bytes: u (448 bits) |

The birational conversion only uses the y-coordinate. The x coordinate sign is discarded because XDH doesn't need it for ECDH.

### A.3 Why Private Key Recovery is Impossible

| Curve | Derivation | Security |
|-------|------------|----------|
| Ed25519→X25519 | SHA-512(seed)[0:32] | Preimage resistance of SHA-512 |
| Ed448→X448 | SHAKE256(seed, 114)[0:56] | Preimage resistance of SHAKE256 |

Both are one-way functions with ~2^256 security. Given only the XDH scalar, the original EdDSA seed cannot be recovered.

---

## Appendix B: Changelog

| Date | Version | Changes |
|------|---------|---------|
| 2025-12-31 | 1.0 | Initial security analysis (Ed25519/X25519) |
| 2025-12-31 | 2.0 | Extended to cover Ed448/X448 conversion |

