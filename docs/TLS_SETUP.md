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

## Related Documentation

- **[DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md)** - MTLS configuration section
- **[GLOSSARY.md](GLOSSARY.md)** - MTLS definition
- **[TROUBLESHOOTING_GUIDE.md](TROUBLESHOOTING_GUIDE.md)** - TLS troubleshooting section
- OpenSSL documentation: https://www.openssl.org/docs/
- Java keytool reference: https://docs.oracle.com/en/java/javase/21/docs/specs/man/keytool.html

---

**Last Updated:** 2026-01-06
**Status:** Production-Ready
**Owner:** Delos Operators
