# Plan: MTLS Three-Tier Bootstrap Test

## Overview

Extend `ThreeTierBootstrapTest` to use real network MTLS communication instead of in-process gRPC, following the pattern established in `fireflies/src/test/java/.../MtlsTest.java`.

## Key Differences: LocalServer vs MtlsServer

| Aspect | LocalServer (current) | MtlsServer (target) |
|--------|----------------------|---------------------|
| Transport | In-process channels | Real TCP sockets |
| TLS | None | Mutual TLS (MTLS) |
| Endpoints | "0" (ignored) | "localhost:port" |
| Certificates | Not needed | X509 per node |
| Port allocation | N/A | Real ports required |
| Endpoint resolution | N/A | Function: Member → endpoint |

## Implementation Plan

### 1. Certificate Provisioning (BeforeAll)

```java
// For each identity, provision an X509 certificate
identities.entrySet().forEach(e -> {
    certs.put(e.getKey(),
        e.getValue().provision(Instant.now(), Duration.ofDays(1), SignatureAlgorithm.DEFAULT));
    endpoints.put(e.getKey(), "localhost:" + portCounter.getAndAdd(10));
});
```

**Key API**: `ControlledIdentifier.provision()` creates a `CertificateWithPrivateKey`

### 2. Endpoint Mapping

```java
private static final int BASE_PORT = 50000;  // Avoid conflicts
private static final Map<Digest, String> endpoints = new HashMap<>();
private static final Map<Digest, CertificateWithPrivateKey> certs = new HashMap<>();

// Allocate ports during setup
var portCounter = new AtomicInteger(BASE_PORT);
identities.forEach((id, identity) -> {
    endpoints.put(id, "localhost:" + portCounter.getAndAdd(10));
});
```

### 3. Endpoint Provider

```java
// Resolve member ID to endpoint string
private static String endpoint(Member m) {
    return ((Participant) m).endpoint();
}

// Create provider for each node
EndpointProvider ep = new StandardEpProvider(
    endpoints.get(node.getId()),     // This node's bind address
    ClientAuth.REQUIRE,               // Require client certificates
    CertificateValidator.NONE,        // Accept all certs (test mode)
    MtlsThreeTierTest::endpoint       // Resolver function
);
```

### 4. SSL Context Suppliers

```java
// Client context - used when connecting to other nodes
private Function<Member, ClientContextSupplier> clientContextSupplier() {
    return m -> new ClientContextSupplier() {
        @Override
        public SslContext forClient(ClientAuth clientAuth, String alias,
                                    CertificateValidator validator, String tlsVersion) {
            var certWithKey = certs.get(m.getId());
            return MtlsServer.forClient(clientAuth, alias,
                certWithKey.getX509Certificate(), certWithKey.getPrivateKey(), validator);
        }
    };
}

// Server context - used when accepting connections
private ServerContextSupplier serverContextSupplier(CertificateWithPrivateKey certWithKey) {
    return new ServerContextSupplier() {
        @Override
        public SslContext forServer(ClientAuth clientAuth, String alias,
                                    CertificateValidator validator, Provider provider) {
            return MtlsServer.forServer(clientAuth, alias,
                certWithKey.getX509Certificate(), certWithKey.getPrivateKey(), validator);
        }

        @Override
        public Digest getMemberId(X509Certificate key) {
            return ((SelfAddressingIdentifier) Stereotomy.decode(key).get().identifier()).getDigest();
        }
    };
}
```

### 5. Router Creation with MtlsServer

```java
// Replace LocalServer with MtlsServer
var certWithKey = certs.get(node.getId());
Router comms = new MtlsServer(
    node,                                    // Member identity
    ep,                                      // Endpoint provider
    clientContextSupplier(),                 // Client TLS context
    serverContextSupplier(certWithKey)       // Server TLS context
).router(cacheBuilder, executor);
```

### 6. View with Real Endpoints

```java
// View now uses real endpoint string
return new View(
    context,
    node,
    endpoints.get(node.getId()),  // Real endpoint, not "0"
    EventValidation.NONE,
    Verifiers.NONE,               // Or Verifiers.from(kerl) for KERI validation
    comms,
    parameters,
    DigestAlgorithm.DEFAULT,
    metrics
);
```

### 7. Seeds with Real Endpoints

```java
// Seeds include actual network endpoints
var bootstrapSeed = new Seed(
    bootstrapMember.getIdentifier().getIdentifier(),
    endpoints.get(bootstrapMember.getId())  // "localhost:50000"
);
```

## File Structure

```
examples/local-demo/src/test/java/com/hellblazer/delos/demo/
├── ThreeTierBootstrapTest.java      # Existing in-process test
└── MtlsThreeTierBootstrapTest.java  # New MTLS network test
```

## Test Structure

```java
@TestMethodOrder(OrderAnnotation.class)
public class MtlsThreeTierBootstrapTest {

    // Static setup - certificates and endpoints
    private static Map<Digest, ControlledIdentifier<SelfAddressingIdentifier>> identities;
    private static Map<Digest, CertificateWithPrivateKey> certs = new HashMap<>();
    private static Map<Digest, String> endpoints = new HashMap<>();
    private static KERL.AppendKERL kerl;

    // Instance fields
    private List<Router> communications = new ArrayList<>();
    private List<View> views;
    private ExecutorService executor;

    @BeforeAll
    static void beforeAll() {
        // 1. Create identities via Stereotomy
        // 2. Provision certificates for each
        // 3. Allocate ports and create endpoint map
    }

    @AfterEach
    void afterEach() {
        // Stop views, close routers, shutdown executor
    }

    @Test
    void testMtlsThreeTierBootstrap() {
        // Phase 1: Bootstrap with MTLS
        // Phase 2: Kernel nodes join via MTLS
        // Phase 3: Member nodes join via MTLS
        // Verify cluster properties
    }
}
```

## Key Dependencies

Already in `local-demo/pom.xml`:
- `com.hellblazer.delos:memberships` (includes MtlsServer, StandardEpProvider)
- `com.hellblazer.delos:cryptography` (includes CertificateWithPrivateKey)
- `com.hellblazer.delos:stereotomy` (includes identity provisioning)

May need to add:
- `io.netty:netty-handler` (for SslContext)

## Verification Criteria

- [ ] All nodes establish MTLS connections
- [ ] Three-tier bootstrap completes successfully
- [ ] Cluster stabilizes with expected node count
- [ ] TLS handshakes use proper certificates
- [ ] Endpoint resolution works correctly
- [ ] View changes propagate over network

## Execution

```bash
# Run MTLS test
./mvnw test -pl examples/local-demo -Dtest=MtlsThreeTierBootstrapTest

# Run with metrics reporting
./mvnw test -pl examples/local-demo -Dtest=MtlsThreeTierBootstrapTest -DreportMetrics=true
```

## Notes

1. **Port Conflicts**: Use high port range (50000+) to avoid conflicts with other tests
2. **Certificate Validity**: Certificates valid for 1 day (sufficient for tests)
3. **CertificateValidator.NONE**: Acceptable for tests; production would validate
4. **No Gateway Router**: MtlsTest doesn't use separate gateway router (simplification)
5. **Virtual Threads**: Use `UnsafeExecutors.newVirtualThreadPerTaskExecutor()` for efficiency
