# Witness Service

Byzantine-resilient receipt aggregation and validation service for Delos distributed consensus.

## Overview

The witness service provides:
- **Hierarchical BLS signature aggregation** with Byzantine isolation
- **Recursive proof validation** for cross-epoch verification
- **Byzantine anomaly detection** (5 detectors with scoring)
- **Key rotation orchestration** with grace period support
- **Receipt persistence** with multi-backend storage (in-memory, JDBC, CHOAM-ready)
- **Automatic compression** (LZ4, ZSTD, with fallback)

## Core Components

### Aggregation (`aggregation/`)

**HierarchicalAggregator**: Multi-level signature aggregation
- 7-layer tree structure for scalable Byzantine-resilient verification
- Leaf nodes isolate Byzantine signers
- Commitment-based aggregation across tree levels

**SignatureAccumulator**: Receipt collection and coordination
- Threshold-based trigger for aggregation
- Byzantine detection and quorum validation
- Metrics instrumentation (arrival timing, Byzantine detection)

**BLSMetrics**: Performance and Byzantine tracking
- Signature processing latency
- Byzantine signer detection rates
- Threshold recalculation overhead
- Buffer drain timing

### Recursive Aggregation (`aggregation/recursive/`)

**RecursiveAggregationBuilder**: Fluent API for cross-epoch proofs
- Builder pattern for composing recursive proofs
- Epoch chaining and validation

**ChainAggregator**: Async service for epoch aggregation
- Background aggregation of epoch chains
- Resource-aware scheduling

**EpochTransitionValidator**: Validates epoch transitions
- Ensures valid state progression
- Detects Byzantine epoch violations

**RecursiveProofValidator**: BLS verification for recursive proofs
- Cross-epoch proof verification
- TemporalByzantineIsolator integration for anomaly detection

### Compression (`aggregation/compression/`)

**ProofCompressionCodec**: Pluggable compression strategies
- `NONE`: No compression (development)
- `FAST`: LZ4 codec (10-15% reduction, low CPU)
- `BEST`: ZSTD codec (15-20% reduction, tuned compression)
- Automatic fallback on decompression failure

### Storage (`aggregation/storage/`)

**ReceiptStore<T>**: Generic receipt storage interface
- Thread-safe operations (lock-free reads, atomic writes)
- "First Write Wins" idempotency semantics
- AggregateReceiptStore and RecursiveReceiptStore specializations

**Implementations**:
- `InMemoryAggregateReceiptStore`: In-memory (backward compatible)
- `InMemoryRecursiveReceiptStore`: Recursive receipts in-memory
- `JdbcAggregateReceiptStore`: JDBC with LRU cache (1000 entries, 1-hour TTL default)
- `JdbcRecursiveReceiptStore`: JDBC for recursive receipts
- `ReceiptStoreFactory`: Configuration-driven store creation

**Performance**:
- Store: <5ms (target <10ms)
- Cache hit: <1ms
- DB retrieve: <30ms (target <50ms)
- Throughput: >1500 receipts/sec (target >1000)

### Detection (`detection/`)

**Byzantine Detectors**: 5-detector framework with anomaly scoring

1. **EquivocationDetector**: Fork detection across epochs
2. **TimingAttackDetector**: Cohort correlation analysis
3. **CoalitionDetector**: Coordinated Byzantine attack patterns
4. **ReplayProtectionDetector**: Message replay prevention
5. **Coordinated Attack Detector**: Multi-phase attack scenarios

**ByzantineDetectionMetrics**: Scoring and escalation
- Anomaly scoring (0.0 - 1.0)
- Score >= 0.85: Key rotation escalation
- Score >= 0.90: View change escalation

### Key Rotation (`validation/`)

**KeyRotationOrchestrator**: 3-phase lifecycle management
- INITIATED → PRE_ROTATION → GRACE_PERIOD → ACTIVATED
- Concurrent rotation prevention per member
- Metrics recording for all transitions

**BLSKeyRotationLookup**: Dual-key validation
- Active key verification
- Deprecated key acceptance during grace period
- Post-expiration rejection

**WitnessSignatureValidator**: Dual-key integration
- Primary: KeyState-based validation
- Secondary: KeyLookup grace period fallback
- Dual-key validation metrics

### Bootstrap (`WitnessBootstrap.java`)

**WitnessBootstrap**: Integrated initialization
- Creates all components from configuration
- Wires Byzantine detection framework
- Initializes key rotation orchestration
- Starts storage persistence

```java
var bootstrap = new WitnessBootstrap(config);
bootstrap.start();

// Access components
var storage = bootstrap.getAggregateReceiptStore();
var detector = bootstrap.getByzantineDetectionOrchestrator();
var keyRotation = bootstrap.getKeyRotationOrchestrator();
```

## Usage Patterns

### Receipt Persistence

```java
// Create store via factory
var config = new WitnessReceiptConfiguration(
    backendType,           // IN_MEMORY or JDBC
    1000,                  // cache size
    Duration.ofHours(1),   // TTL
    CompressionCodec.FAST  // compression
);
var store = ReceiptStoreFactory.createStore(config, dataSource);

// Store with idempotent semantics (First Write Wins)
store.store("event-key", receipt);  // idempotent - no overwrite

// Retrieve
var result = store.retrieve("event-key");

// Query by epoch
var receipts = store.listByEpoch(5);
```

### Compression Selection

See `witness-service/docs/COMPRESSION_STRATEGY_GUIDE.md`:
- **Development/Testing**: `NONE` (zero overhead)
- **Production (fast paths)**: `FAST/LZ4` (10-15%, <5ms)
- **Archive/Batch**: `BEST/ZSTD` (15-20%, <50ms)
- Automatic fallback handles codec unavailability

### Byzantine Detection

```java
var detector = new TimingAttackDetector(config);
var anomaly = detector.detect(receipt);

if (anomaly.score() >= 0.85) {
    // Trigger key rotation
    keyRotation.requestRotation(memberId);
}
if (anomaly.score() >= 0.90) {
    // Trigger view change
    viewChange.requestChange(memberId);
}
```

### Key Rotation

```java
var result = keyRotation.startRotation(memberId);

// Wait for phases
Thread.sleep(preDurationMs);      // PRE_ROTATION
Thread.sleep(graceDurationMs);    // GRACE_PERIOD
// Automatically transitions to ACTIVATED

// Dual-key validation during grace period
var isValid = validator.validateSignature(sig, key);  // accepts deprecated keys
```

## Testing

### Contract Tests

Extend `ReceiptStoreContract` for new implementations:

```java
class MyReceiptStoreTest extends ReceiptStoreContract<MyReceipt> {
    @Override
    protected ReceiptStore<MyReceipt> createStore() {
        return new MyReceiptStore();
    }
    // Validates: thread-safety, idempotency, null handling, etc.
}
```

### Integration Tests

See `WitnessReceiptManagerIntegrationTest` for full workflow:
- Receipt collection → aggregation → compression → storage → retrieval
- Byzantine failure handling
- Multi-backend verification

### Benchmarks

`CompressionEffectivenessTest`: Measures codec effectiveness
`ReceiptStorageRegressionTest`: Detects performance degradation
- Baselines: in-memory 100ms/1000, JDBC 2000ms/100

## Documentation

**In Repository**:
- `ETHEREAL_TEST_CONSOLIDATION_SUMMARY.md` - Byzantine test patterns
- `TIMING_TESTS_ANALYSIS.md` - CI timeout strategy

**In Memory Bank** (Delos_active project):
- `WITNESS_SERVICE_STORAGE_INTEGRATION_PATTERN.md` - 5-layer architecture guide
- `COMPRESSION_STRATEGY_GUIDE.md` - Codec selection decision tree
- `CONCURRENT_STORAGE_PATTERNS_LESSONS.md` - Thread-safety models
- `BYZANTINE_TEST_RELIABILITY_LESSON_LEARNED.md` - Root cause analysis

## Configuration

**WitnessReceiptConfiguration**:
```java
new WitnessReceiptConfiguration(
    StorageBackendType.JDBC,      // IN_MEMORY or JDBC
    1000,                         // cache size (entries)
    Duration.ofHours(1),          // cache TTL
    CompressionCodec.FAST         // compression strategy
)
```

**Database Setup** (JDBC):
- Liquibase migrations: `db/changelog/db.changelog-master.yaml`
- Tables: `aggregate_receipts`, `recursive_receipts` with indexed lookups
- Automatic schema creation on bootstrap

## Performance

| Operation | Target | Actual |
|-----------|--------|--------|
| Store | <10ms | <5ms ✅ |
| Cache hit | <1ms | <1ms ✅ |
| DB retrieve | <50ms | <30ms ✅ |
| Throughput | >1000/sec | >1500/sec ✅ |
| Byzantine overhead | <1% | <1% ✅ |

## Related

- **Ethereal**: Consensus protocol (uses witness service for receipt validation)
- **Fireflies**: Membership service (member identification for key rotation)
- **CHOAM**: State machine replication (CHOAM-ready storage framework in Phase 4)

## Status

Experimental but well-tested foundation (2026-01-24):
- ✅ Multi-backend storage with compression
- ✅ Byzantine detection framework (5 detectors)
- ✅ Key rotation orchestration
- ✅ Thread-safe with comprehensive testing
- ✅ 60+ tests, 20+ Byzantine scenarios
- ✅ <1% Byzantine overhead on consensus
- 🔄 Production hardening in progress (state persistence validation, failure recovery patterns, scale testing)
