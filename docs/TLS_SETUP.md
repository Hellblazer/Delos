# TLS Setup for Delos MTLS Communication

**Status:** Complete
**Last Updated:** 2026-01-06
**Audience:** Operators, DevOps Engineers

---

## Overview

Delos uses Mutual TLS (MTLS) for secure gRPC communication between nodes. Each node requires:
- A server certificate (authenticated by Certificate Authority)
- Client certificates for peer communication
- A trust store containing CA certificate and peer certificates

This guide covers setup, validation, and troubleshooting of MTLS infrastructure.

---

## Architecture

```
┌─────────────────────────────────────┐
│ Public PKI (Certificate Authority)  │
│  ├─ Root CA Certificate             │
│  ├─ Intermediate CA (optional)      │
│  └─ CRL/OCSP endpoints              │
└──────────────┬──────────────────────┘
               │ Issues & Signs
               ▼
┌─────────────────────────────────────┐
│ Node MTLS Configuration             │
├─────────────────────────────────────┤
│ For Each Node:                      │
│  ├─ Server Keystore                 │
│  │   ├─ Private Key                 │
│  │   └─ Server Certificate          │
│  ├─ Trust Store                     │
│  │   ├─ CA Certificate              │
│  │   └─ Peer Certificates (optional)│
│  └─ mTLS Config (server/client)     │
└─────────────────────────────────────┘
```

---

## Implementation Options

### Option 1: Self-Signed Certificates (Development/Testing Only)

⚠️ **SECURITY WARNING**: Self-signed certificates provide encryption but NO authentication. Do NOT use in production or any environment with untrusted networks.

```bash
# Create self-signed certificate for node1 (valid 365 days)
openssl req -x509 -newkey rsa:4096 -keyout node1-key.pem -out node1-cert.pem \
  -days 365 -nodes \
  -subj "/CN=node1.delos.local/O=Delos/C=US"

# Create PKCS12 keystore for Java (server certificate)
openssl pkcs12 -export -in node1-cert.pem -inkey node1-key.pem \
  -out node1-keystore.p12 \
  -name delos-node1 \
  -passout pass:KeystorePassword123!

# For testing: use certificate as trust anchor
cp node1-cert.pem node1-truststore.pem
```

**Verification:**
```bash
# List keystore contents
keytool -list -v -keystore node1-keystore.p12 -storetype PKCS12 -storepass KeystorePassword123!

# Verify certificate details
openssl x509 -in node1-cert.pem -text -noout
```

### Option 2: CA-Signed Certificates (Production Recommended)

**Prerequisites:**
- CA infrastructure (internal or public CA)
- CSR (Certificate Signing Request) generation capability
- CA signing process

**Process:**

1. **Generate Private Key and CSR:**
```bash
# Create private key
openssl genrsa -out node1-key.pem 4096

# Create Certificate Signing Request
openssl req -new -key node1-key.pem -out node1.csr \
  -subj "/CN=node1.delos.local/O=Delos/C=US"
```

2. **Submit CSR to CA** (your organization's process)

3. **Receive Signed Certificate** from CA

4. **Create Keystore:**
```bash
# Combine private key + signed certificate + CA chain
openssl pkcs12 -export -in node1-signed-cert.pem -inkey node1-key.pem \
  -certfile ca-chain.pem -out node1-keystore.p12 \
  -name delos-node1 \
  -passout pass:SECURE_PASSWORD_HERE
```

5. **Create Trust Store:**
```bash
# Convert CA certificate to Java trust store
keytool -import -alias ca-cert -file ca-certificate.pem \
  -keystore truststore.jks -storepass TRUSTSTORE_PASSWORD \
  -noprompt
```

---

## MTLS Configuration in delos.yaml

```yaml
# Server-side TLS configuration
mtls:
  # Keystore containing server certificate + private key
  serverKeystore:
    path: /opt/delos/keys/node1-keystore.p12
    type: PKCS12
    password: ${KEYSTORE_PASSWORD}  # Use environment variable!

  # Trust store for validating client certificates
  trustStore:
    path: /opt/delos/keys/truststore.jks
    type: JKS
    password: ${TRUSTSTORE_PASSWORD}

  # Require client certificate validation
  requireClientAuth: true

  # TLS protocol version (1.3 recommended)
  tlsVersion: TLSv1.3

  # Allowed cipher suites (strong ciphers only)
  cipherSuites:
    - TLS_AES_256_GCM_SHA384
    - TLS_CHACHA20_POLY1305_SHA256
    - TLS_AES_128_GCM_SHA256

# gRPC server binding
grpc:
  server:
    port: 50051
    bindAddress: 0.0.0.0
    maxConcurrentStreams: 100

# Client-side connection configuration
nodeConnections:
  maxRetries: 3
  retryDelayMs: 100
  timeoutMs: 10000
```

⚠️ **SECURITY WARNING**: Never hardcode passwords in configuration files.

**Recommended approach:**
```yaml
mtls:
  serverKeystore:
    path: /opt/delos/keys/node1-keystore.p12
    password: ${KEYSTORE_PASSWORD}    # Read from environment
  trustStore:
    path: /opt/delos/keys/truststore.jks
    password: ${TRUSTSTORE_PASSWORD}  # Read from environment
```

**Set environment variables before running:**
```bash
export KEYSTORE_PASSWORD="$(vault kv get -field=keystore_password secret/delos/node1)"
export TRUSTSTORE_PASSWORD="$(vault kv get -field=truststore_password secret/delos/node1)"
```

---

## Certificate Rotation

### Planning

- **Self-signed certificates**: Rotate every 90 days
- **CA-signed certificates**: Rotate per CA policy (typically 1 year before expiration)
- **Private keys**: Rotate immediately if compromised

### Procedure

1. **Generate new certificate** (self-signed or request from CA)
2. **Create new keystore** with new certificate
3. **Distribute to trust stores** on all peers
4. **Rolling update**: Update nodes one at a time
   - Stop node → Swap keystores → Verify health → Start node
   - Wait for node to rejoin cluster before updating next node
5. **Verify cluster health** after all nodes updated:
```bash
# Check if all nodes are communicating
./tools/verify-identity.sh

# Verify consensus is progressing
./tools/check-consensus.sh
```

### Monitoring

```bash
# Check certificate expiration
openssl x509 -in node1-cert.pem -noout -dates

# Set calendar reminder before expiration
# Add to monitoring: Check cert_expiration_seconds metric (if available)
```

---

## Validation and Testing

### Test MTLS Connectivity

```bash
# From one node, verify TLS handshake with peer
openssl s_client -connect node2.delos.local:50051 \
  -cert node1-cert.pem -key node1-key.pem \
  -CAfile ca-certificate.pem

# Should see: "Verify return code: 0 (ok)"
```

### Verify Certificate in Keystore

```bash
# List keystore
keytool -list -v -keystore node1-keystore.p12 \
  -storetype PKCS12 -storepass PASSWORD

# Should show:
# Owner: CN=node1.delos.local, O=Delos, C=US
# Issuer: [CA name]
# Valid from: [date]
# Valid until: [date]
```

### Test Trust Store

```bash
# Import test certificate into trust store
keytool -import -alias node2 -file node2-cert.pem \
  -keystore truststore.jks -storepass PASSWORD -noprompt

# List trust store contents
keytool -list -keystore truststore.jks -storepass PASSWORD
```

---

## Troubleshooting

### Issue: "CERTIFICATE_VERIFY_FAILED"

**Symptoms:**
- Nodes cannot communicate
- gRPC errors in logs
- Gossip connections fail

**Diagnosis:**
```bash
# 1. Verify certificate chain
openssl verify -CAfile ca-certificate.pem node1-cert.pem

# 2. Check if certificate is in trust store
keytool -list -keystore truststore.jks | grep node1

# 3. Compare certificate subjects
openssl x509 -in node1-cert.pem -noout -subject
keytool -printcert -file node1-cert.pem | grep Subject
```

**Resolution:**
- Import CA certificate into trust store
- Ensure all nodes have same trust store
- Verify certificate CN (Common Name) matches node hostname

### Issue: "WRONG_VERSION_NUMBER" or "UNEXPECTED_EOF"

**Symptoms:**
- Cannot establish TLS connection
- Connection hangs then times out

**Diagnosis:**
- Verify port is correct (default 50051)
- Check if gRPC server is actually running on that port
- Verify firewall allows traffic on TLS port

**Resolution:**
```bash
# Check if port is listening
netstat -tln | grep 50051

# Check gRPC server logs for startup errors
tail -f /var/log/delos/delos.log | grep "gRPC"
```

### Issue: "PEER_HANDSHAKE_FAILED"

**Symptoms:**
- Intermittent connection failures
- High latency on some peer connections
- Some nodes connect successfully, others fail

**Diagnosis:**
- Certificate may be expiring soon
- Cipher suite mismatch between nodes
- TLS version mismatch

**Resolution:**
```bash
# Check certificate expiration
openssl x509 -in node1-cert.pem -noout -dates

# If expiring soon: rotate certificate (see procedure above)
# If cipher mismatch: verify delos.yaml cipherSuites on all nodes match
```

---

## Security Best Practices

### Private Key Management

1. **Never commit private keys to version control**
   ```bash
   # Ensure .gitignore covers keys
   echo "*.key" >> .gitignore
   echo "*.p12" >> .gitignore
   echo "*.jks" >> .gitignore
   ```

2. **Restrict file permissions**
   ```bash
   chmod 600 node1-keystore.p12
   chmod 600 node1-key.pem
   chown delos:delos node1-keystore.p12
   ```

3. **Rotate keys regularly**
   - No standard rotation, but recommended annually
   - Immediately rotate if compromised

4. **Use Hardware Security Modules (HSM) for production**
   - Thales SafeNet, YubiKey, AWS CloudHSM
   - Protects against key theft
   - Enables certificate pinning

### Certificate Chain Validation

- Verify certificates chain correctly to trusted root
- Use `openssl verify` command
- Monitor for invalid or self-signed certificates in logs

### Monitoring and Alerts

Add to your monitoring:
- Certificate expiration (alert 30 days before)
- TLS handshake failures
- Untrusted certificate errors
- Certificate revocation (CRL/OCSP) failures

### Deployment Checklist

- [ ] All keystores protected with strong passwords (20+ chars)
- [ ] All trust stores contain correct CA and peer certificates
- [ ] Private keys not in version control
- [ ] File permissions restricted (600)
- [ ] Certificate validity verified before deployment
- [ ] Passwords stored in secrets manager (Vault, AWS Secrets Manager)
- [ ] TLS 1.3 enabled (minimum 1.2)
- [ ] Certificate rotation procedure documented and tested
- [ ] Expiration dates tracked in calendar
- [ ] All nodes using consistent cipher suites

---

## Hardware Security Module (HSM) Integration

### HSM Benefits and Use Cases

**When to use HSM**:
- Production clusters with critical data
- Compliance requirements (HIPAA, PCI-DSS, SOC 2)
- High-security deployments (financial, government)

**HSM Providers**:
- **Thales Luna Network HSM** - FIPS 140-2 Level 3
- **Yubico YubiHSM 2** - USB-connected, affordable
- **AWS CloudHSM** - Cloud-hosted managed service
- **Azure Key Vault** - Cloud-hosted with PKIX support

### HSM Configuration for Delos

**Java PKCS#11 Configuration**:

```properties
# /etc/delos/sun-pkcs11.cfg
name=Delos-HSM
description=Thales Luna Network HSM
library=/opt/thales/lunaclient/lib/libcryptoki.so

slot=0
slotListIndex=0
disabledMechanisms={
    CKM_SHA256_RSA_PKCS
}
```

**MTLS Configuration with HSM**:

```yaml
mtls:
  serverKeystore:
    type: PKCS11
    provider: SunPKCS11
    config: /etc/delos/sun-pkcs11.cfg

    # HSM slot and PIN
    slot: 0
    pinFile: /opt/delos/keys/hsm.pin  # Chmod 600

    # Certificate reference in HSM
    alias: delos-node1-cert

  trustStore:
    path: /opt/delos/keys/truststore.jks
    type: JKS
    password: ${TRUSTSTORE_PASSWORD}
```

**HSM Keystore Access** (Java):

```java
// Load HSM via PKCS#11
KeyStore hsm = KeyStore.getInstance("PKCS11", "SunPKCS11");
char[] pin = readPinFromSecureLocation();  // Never hardcode!
hsm.load(null, pin);

// Use HSM certificate for TLS
Key privateKey = hsm.getKey("delos-node1-cert", pin);
Certificate[] chain = hsm.getCertificateChain("delos-node1-cert");

// MTLS server uses HSM key
SSLContext sslContext = SSLContext.getInstance("TLS");
KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX");
kmf.init(hsm, pin);
sslContext.init(kmf.getKeyManagers(), trustManagers, null);
```

### HSM Operations Checklist

- [ ] HSM initialized with master PIN (stored securely off-site)
- [ ] Partition created for Delos with unique PIN
- [ ] PKCS#11 library installed and tested
- [ ] Key pair generated on HSM (never exported)
- [ ] Certificate signed by CA and imported to HSM
- [ ] Backup partition created (offline)
- [ ] HSM redundancy configured (two devices, same keys)
- [ ] Network HSM firewall rules enforced
- [ ] HSM activity logging enabled
- [ ] HSM health check integrated into monitoring

---

## Post-Quantum Migration Planning

### Current Status (2026)

**ED25519 & X25519 Security Timeline**:
- **2026-2030**: Secure (quantum computers not a threat)
- **2030-2040**: Monitor for quantum threat announcements
- **2040+**: Vulnerable to hypothetical quantum computers

**NIST Post-Quantum Cryptography Status**:
- Standardization completed for ML-KEM, ML-DSA, SLH-DSA (2024)
- Available in: OpenSSL 3.2+, BouncyCastle 1.78+
- Adoption timeline: 2024-2026 for early adopters

### Phase 1: Preparation (2026-2027)

**Year 1 Actions**:

1. **Establish PQC Roadmap**:
   - [ ] Identify NIST-approved algorithms for use
   - [ ] Evaluate hybrid signature schemes (ED25519 + ML-DSA)
   - [ ] Plan key rotation timeline

2. **Library Updates**:
   - [ ] Update BouncyCastle to 1.78+ (PQC support)
   - [ ] Test PQC implementations in development
   - [ ] Benchmark performance impact

3. **Standards & Compliance**:
   - [ ] Review NIST SP 800-208 (PQC migration)
   - [ ] Update security policies to address post-quantum
   - [ ] Plan for hybrid algorithms during transition

### Phase 2: Hybrid Deployment (2027-2029)

**Hybrid Signature Scheme**:

```
Traditional: ED25519 only
       ↓ (upgrade)
Hybrid: ED25519 + ML-DSA (both required)
       ├─ Signature size: ~64 bytes (ED25519) + ~2420 bytes (ML-DSA)
       ├─ Total: ~2484 bytes (overhead acceptable during transition)
       └─ Verification: Both algorithms must validate
       ↓ (after 3-year transition)
Post-Quantum: ML-DSA only
       ├─ Signature size: ~2420 bytes
       └─ Verification: Single algorithm
```

**Implementation Strategy**:

```java
// Hybrid signature structure
sealed interface UnifiedSignature permits
    ED25519Signature,           // Legacy only (2026)
    HybridSignature,            // ED25519 + ML-DSA (2027-2029)
    PostQuantumSignature;       // ML-DSA only (2029+)

record HybridSignature(
    JohnHancock ed25519Sig,     // 64 bytes
    byte[] mldsaSig             // ~2420 bytes
) implements UnifiedSignature {
    public boolean verify(byte[] message, PublicKey key) {
        return ed25519Verify(message, ed25519Sig, key)
            && mldsaVerify(message, mldsaSig, key);
    }
}
```

### Phase 3: Full Migration (2029-2030)

**Sunset ED25519** (if quantum threat emerges):
1. Set deprecation flag in 2029 release
2. Warn operators in 2029-2030
3. Require PQC-only in 2030+ releases

**Fallback Plan**:
- If quantum threat doesn't materialize, maintain ED25519 indefinitely
- Hybrid signatures can coexist safely with pure PQC

### Post-Quantum Algorithm Selection

| Algorithm | Purpose | Security | Standardized | Performance | Status |
|-----------|---------|----------|--------------|-------------|--------|
| **ML-KEM-768** | Key encapsulation | 128-bit | NIST (2024) | <1ms | Recommended |
| **ML-DSA-65** | Digital signatures | 128-bit | NIST (2024) | <2ms | Recommended |
| **SLH-DSA-SHA2-128s** | Backup signatures | 128-bit | NIST (2024) | ~10ms | Fallback |

**Recommended Migration Path**:
1. **Phase 2A (2027)**: Implement ML-KEM-768 for session keys
2. **Phase 2B (2028)**: Implement hybrid ED25519 + ML-DSA-65
3. **Phase 3 (2029)**: Sunset ED25519 if quantum threat confirmed
4. **Fallback**: Indefinite coexistence if no quantum threat

### Monitoring Post-Quantum Risk

**Track these indicators**:
1. **NIST quantum computing announcements**
2. **Industry PQC adoption rate**
3. **Quantum threat timeline updates**
4. **Library and OS support for PQC**

**Review Quarterly**:
- [ ] Check NIST PQC standardization progress
- [ ] Review quantum computing milestones
- [ ] Evaluate new PQC libraries and updates
- [ ] Assess competitor adoption of PQC

---

## Certificate Pinning for Critical Peers

### When to Use Certificate Pinning

**Pin certificates for**:
- Witness service nodes (high-criticality)
- Consensus leader nodes
- Identity bootstrap services

**Pin strategy**:
```java
// Pin specific peer certificate
X509Certificate trustedCert = loadCertificate("peer-node-1-cert.pem");
byte[] pinnedPublicKey = extractPublicKeyFromCert(trustedCert);

// During handshake
SSLSession session = sslSocket.getSession();
X509Certificate[] peerChain = session.getPeerCertificateChain();
byte[] peerPublicKey = extractPublicKeyFromCert(peerChain[0]);

if (!Arrays.equals(pinnedPublicKey, peerPublicKey)) {
    throw new SSLHandshakeException("Certificate pinning failed!");
}
```

**Pinning for Witness Service** (Phase 1B):

```yaml
mtls:
  pinnedPeers:
    witness-node-1:
      certificate: /opt/delos/certs/witness-1-cert.pem
      algorithm: SHA256
    witness-node-2:
      certificate: /opt/delos/certs/witness-2-cert.pem
      algorithm: SHA256
    witness-node-3:
      certificate: /opt/delos/certs/witness-3-cert.pem
      algorithm: SHA256
```

---

## Related Documentation

- **[CRYPTOGRAPHY_ALGORITHMS.md](CRYPTOGRAPHY_ALGORITHMS.md)** - Algorithm selection guide
- **[DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md)** - MTLS configuration section
- **[SECURITY_THREAT_MODEL.md](SECURITY_THREAT_MODEL.md)** - Threat model analysis
- **[GLOSSARY.md](GLOSSARY.md)** - MTLS definition
- **[TROUBLESHOOTING_GUIDE.md](TROUBLESHOOTING_GUIDE.md)** - TLS troubleshooting section
- OpenSSL documentation: https://www.openssl.org/docs/
- Java keytool reference: https://docs.oracle.com/en/java/javase/21/docs/specs/man/keytool.html
- NIST PQC Standardization: https://csrc.nist.gov/projects/post-quantum-cryptography/

---

**Last Updated:** January 27, 2026
**Status:** Production-Ready
**Owner:** Delos Operators
