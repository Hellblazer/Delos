# Ed25519↔X25519 Key Conversion: Security Analysis

**Document Version:** 1.0
**Date:** 2025-12-31
**Bead Reference:** Delos-868.6
**Status:** Final

## Executive Summary

This document provides a comprehensive security analysis of the Ed25519→X25519 key conversion mechanism used in Delos. The conversion enables using a single Ed25519 identity key pair for both digital signatures (authentication) and X25519-based encryption (DHKEM for confidentiality).

**Key Findings:**
- The conversion is **cryptographically sound** when implemented correctly (fixed in commit e624559)
- **Formal security proofs exist** for joint Ed25519/X25519 key usage (Thormarker 2021)
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

1. **Ed25519 Private Key (Seed)** - 32-byte secret, source of truth for identity
2. **X25519 Private Key** - Derived scalar, used for ECDH
3. **Session Keys** - Ephemeral keys derived via DHKEM
4. **Long-term Identity Binding** - KERI identifier tied to Ed25519 public key

### 1.3 Security Goals

| Goal | Description | Mechanism |
|------|-------------|-----------|
| **Confidentiality** | Encrypted messages readable only by intended recipient | X25519 ECDH + symmetric encryption |
| **Authenticity** | Messages verifiably from claimed sender | Ed25519 signatures |
| **Forward Secrecy** | Past sessions secure if long-term key compromised | Ephemeral X25519 keys per session |
| **Key Binding** | Encryption/signing keys provably belong to same identity | Deterministic conversion from Ed25519 |

---

## 2. Conversion Algorithm Analysis

### 2.1 Private Key Conversion

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

**Previous Bug (Fixed):** The implementation incorrectly used SHA-256 instead of SHA-512, producing non-standard and potentially weak scalars. Fixed in commit e624559.

### 2.2 Public Key Conversion

**Algorithm:** Ed25519 point (x, y) → X25519 u-coordinate

```
// Birational equivalence between twisted Edwards and Montgomery curves
u = (1 + y) * (1 - y)^(-1) mod p
where p = 2^255 - 19
```

**Security Properties:**

| Property | Status | Rationale |
|----------|--------|-----------|
| **Lossy (sign bit)** | ⚠ By design | X25519 only uses u-coordinate; x sign discarded |
| **Deterministic** | ✓ Secure | Same Ed25519 public key → same X25519 public key |
| **Modular arithmetic** | ✓ Secure | Correct field operations (fixed in e624559) |

**Previous Bug (Fixed):** Integer division was used instead of modular inversion, producing invalid X25519 points that triggered "Point has small order" errors.

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

**Research Basis:**
- Thormarker, E. (2021). "[On using the same key pair for Ed25519 and an X25519 based KEM](https://eprint.iacr.org/2021/509)" proves:
  - Ed25519 remains EUF-CMA secure with X25519 decapsulation oracle
  - X25519 KEM remains IND-CCA secure with Ed25519 signing oracle
  - No domain separation assumptions required

### 3.2 Small Subgroup Attacks

**Definition:** Exploiting composite group order to leak private key bits through ECDH with low-order points.

**Curve25519 Cofactor:** h = 8 (group order = 8 × prime)

**Mitigation in Delos:**

| Defense | Implementation | Status |
|---------|---------------|--------|
| **Scalar Clamping** | Clear low 3 bits of private scalar | ✓ Applied |
| **Cofactor Multiplication** | Equivalent to clearing low 3 bits | ✓ Applied |
| **Point Validation** | DHKEM validates points internally | ✓ JDK handles |

**Result:** Small subgroup attacks are mitigated by design. An attacker learns only `8a mod 8 = 0`, revealing no private key information.

### 3.3 Invalid Curve Attacks

**Definition:** Attacker sends point on different curve (twist) to leak private key.

**X25519 Mitigation:**
- Montgomery ladder implementation is twist-secure
- Invalid points produce valid (but useless) shared secrets
- No point validation required for security

**Status:** ✓ Not vulnerable (X25519 design property)

### 3.4 Side-Channel Attacks

**Definition:** Extracting secrets through timing, power, or electromagnetic measurements.

| Attack Type | Concern | Mitigation Status |
|-------------|---------|-------------------|
| **Timing Attacks** | SHA-512, field operations must be constant-time | ⚠ JDK-dependent |
| **Cache Timing** | Table lookups can leak key bits | ⚠ JDK-dependent |
| **Power Analysis** | Scalar multiplication leakage | ⚠ Hardware-dependent |
| **EM Emissions** | Similar to power analysis | ⚠ Hardware-dependent |

**Recommendations:**
1. Use [BouncyCastle's constant-time implementations](https://www.bouncycastle.org/) for high-security deployments
2. Consider hardware security modules (HSMs) for long-term keys
3. Monitor [ed25519-unsafe-libs](https://github.com/MystenLabs/ed25519-unsafe-libs) for known vulnerable implementations

### 3.5 Double Public Key Attack (Ed25519-Specific)

**Definition:** Attacker provides mismatched public key to signing function, enabling private key recovery.

**Vulnerability Conditions:**
- Signing function accepts separate public and private key inputs
- No validation that public key corresponds to private key
- Same `R` value computed for different public keys

**Delos Status:** ✓ Not vulnerable
- `SignatureAlgorithm.ED_25519` uses coupled key pairs
- Public key derived from private key internally
- No API exposes decoupled key signing

---

## 4. Delos-Specific Threat Model

### 4.1 Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     KERI Identity                            │
│  ┌─────────────────┐                                         │
│  │ Ed25519 Keypair │ ← Source of Truth (KERL)                │
│  │   (Signing)     │                                         │
│  └────────┬────────┘                                         │
│           │ On-demand conversion                             │
│           ▼                                                  │
│  ┌─────────────────┐                                         │
│  │ X25519 Keypair  │ ← Derived for encryption                │
│  │  (Encryption)   │                                         │
│  └─────────────────┘                                         │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 Use Cases in Delos

| Component | Ed25519 Usage | X25519 Usage |
|-----------|---------------|--------------|
| **KERI (stereotomy)** | Identity signatures, event signing | Not used directly |
| **Fireflies** | Note signing, accusations | Member-to-member encryption (potential) |
| **CHOAM** | Transaction signing | Sealed proposals (potential) |
| **Gorgoneion** | Attestation signatures | Admission encryption (potential) |

### 4.3 Key Lifecycle

| Phase | Ed25519 | X25519 |
|-------|---------|--------|
| **Generation** | Generated once at identity creation | Derived on-demand |
| **Storage** | Stored in KERL (persistent) | Not stored (recomputed) |
| **Rotation** | Via KERI pre-rotation | Rotates with Ed25519 |
| **Revocation** | KERI event | Implicit (no separate revocation) |

### 4.4 Delos Security Properties

| Property | Analysis |
|----------|----------|
| **Key Binding** | ✓ X25519 cryptographically bound to Ed25519 identity |
| **No Key Sprawl** | ✓ Single key pair serves both purposes |
| **Rotation Consistency** | ✓ X25519 automatically updates with Ed25519 rotation |
| **Forward Secrecy** | ⚠ Requires ephemeral X25519 for each session |

---

## 5. Recommendations

### 5.1 Current Implementation (Post-Fix)

The current implementation is **secure for production use** with the following caveats:

| Recommendation | Priority | Status |
|----------------|----------|--------|
| Use SHA-512 for private key conversion | CRITICAL | ✓ Fixed |
| Use modular arithmetic for public key conversion | CRITICAL | ✓ Fixed |
| Apply proper bit clamping | CRITICAL | ✓ Fixed |
| Validate test vectors against libsodium | HIGH | ✓ Done |

### 5.2 Operational Recommendations

| Recommendation | Priority | Rationale |
|----------------|----------|-----------|
| **Use ephemeral keys for encryption sessions** | HIGH | Provides forward secrecy |
| **Store Ed25519 keys securely** | HIGH | Single point of compromise |
| **Prefer separate keys for high-security** | MEDIUM | Defense in depth (per libsodium) |
| **Monitor for side-channel vulnerabilities** | MEDIUM | JDK implementations may vary |

### 5.3 Future Considerations

| Enhancement | Priority | Notes |
|-------------|----------|-------|
| Post-quantum migration path | LOW | Ed25519/X25519 not quantum-resistant |
| Hardware key storage | MEDIUM | HSM support for long-term keys |
| Alternative: ECDH over Edwards | LOW | Avoids conversion (libsodium option) |

---

## 6. Test Coverage

The following tests validate security properties:

| Test | Property Validated |
|------|-------------------|
| `edDSAOperationsMatchesTestVectors` | Correct SHA-512 and clamping |
| `conversionIsDeterministic` | No randomness leakage |
| `signatureAlgorithmMatchesLazySodium` | Cross-implementation agreement |
| `keyCorrespondenceAfterConversion` | Public/private key relationship |
| `encryptDecryptWithConvertedKeys` | End-to-end encryption correctness |
| `x25519ToEd25519PublicRecoveryIsAmbiguous` | Documents sign-bit loss |
| `x25519ToEd25519PrivateRecoveryIsImpossible` | Documents one-way property |

---

## 7. References

### Academic Papers
- Thormarker, E. (2021). [On using the same key pair for Ed25519 and an X25519 based KEM](https://eprint.iacr.org/2021/509). IACR ePrint 2021/509.
- Brendel et al. (2020). [The Provable Security of Ed25519: Theory and Practice](https://eprint.iacr.org/2020/823). IACR ePrint 2020/823.
- Valenta et al. (2016). [Measuring small subgroup attacks against Diffie-Hellman](https://eprint.iacr.org/2016/995). IACR ePrint 2016/995.

### Standards
- RFC 8032: Edwards-Curve Digital Signature Algorithm (EdDSA)
- RFC 7748: Elliptic Curves for Security (X25519, X448)
- RFC 8410: Algorithm Identifiers for Ed25519, Ed448, X25519, X448

### Implementation References
- [Libsodium: Ed25519 to Curve25519](https://libsodium.gitbook.io/doc/advanced/ed25519-curve25519)
- [Curve25519 Clamping & Cofactor Clearing](https://risencrypto.github.io/CofactorClearing/)
- [MystenLabs: ed25519-unsafe-libs](https://github.com/MystenLabs/ed25519-unsafe-libs)

---

## Appendix A: Mathematical Background

### A.1 Curve Relationship

Ed25519 uses the twisted Edwards curve:
```
-x² + y² = 1 + dx²y²
where d = -121665/121666
```

X25519 uses the Montgomery curve:
```
v² = u³ + 486662u² + u
```

These curves are **birationally equivalent**:
```
(x, y) ↔ (u, v)
u = (1 + y) / (1 - y)
v = √(-486664) * u / x
```

### A.2 Why Sign Bit is Lost

Ed25519 public key encoding: 32 bytes = y-coordinate (255 bits) + sign(x) (1 bit)
X25519 public key encoding: 32 bytes = u-coordinate (255 bits)

The conversion `u = (1+y)/(1-y)` only uses the y-coordinate. The sign of x (stored in MSB of Ed25519) is discarded because X25519 doesn't need it for ECDH.

### A.3 Why Private Key Recovery is Impossible

Ed25519 private key: 32-byte seed
X25519 private key: SHA-512(seed)[0:32] with clamping

SHA-512 is a cryptographic hash function with:
- Preimage resistance: Given H(m), cannot find m
- Second preimage resistance: Given m, cannot find m' ≠ m with H(m) = H(m')

Therefore, given only the X25519 scalar, the original Ed25519 seed cannot be recovered.

---

## Appendix B: Changelog

| Date | Version | Changes |
|------|---------|---------|
| 2025-12-31 | 1.0 | Initial security analysis |

