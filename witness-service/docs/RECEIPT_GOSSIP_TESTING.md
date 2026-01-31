# Receipt Gossip Integration Testing Plan

**Phase**: F5 (Integration Tests)
**Status**: Foundational Tests Complete
**Date**: 2026-01-31

## Overview

This document outlines the testing strategy for receipt gossip protocol integration across the Fireflies witness network. It builds on the comprehensive unit tests already implemented in F1-F3.

---

## Completed Test Coverage (F1-F3)

### Unit Tests

**ReceiptGossipCodecTest** (15 tests):
- Message serialization/deserialization
- Bloom filter encoding
- Receipt digest computation
- Edge cases (empty lists, null handling)

**ReceiptGossipHandlerTest** (9 tests):
- Receipt validation and processing
- Error handling and rejection
- Metrics tracking
- Validator integration

**ReceiptGossipIntegrationTest** (10 tests):
- Full gossip round-trip
- Anti-entropy reconciliation
- Validation integration
- Concurrent processing
- Bloom filter integration
- Timestamp drift handling

**BloomFilterAntiEntropyTest** (8 tests):
- Bloom filter population
- Contains checks
- False positive rate validation
- Different seeds and algorithms

**ReceiptAntiEntropyServiceTest** (13 tests):
- Receipt add/remove
- Bloom filter building
- Missing receipt identification
- Gossip response generation
- Concurrent access
- Metrics tracking

**Total**: 55 unit/integration tests

---

## Multi-Node Integration Test Plan (F5)

### Test Infrastructure Requirements

1. **Cluster Setup**:
   - 7-node witness cluster (default)
   - Fireflies StaticContext for membership
   - Simulated gossip rounds (100ms intervals)
   - Dynamic topology (node join/leave/crash)

2. **GossipNode Class**:
   ```java
   class GossipNode {
       ReceiptAntiEntropyService antiEntropy;
       ReceiptGossipHandler handler;
       BasicReceiptValidator validator;
       Set<GossipableReceipt> receivedReceipts;
       boolean crashed;
       boolean byzantine;
   }
   ```

3. **Gossip Simulation**:
   - Random partner selection per round
   - Bi-directional gossip exchange
   - Configurable network delays
   - Message loss simulation

---

## Test Scenarios

### Happy Path Tests

#### T1: Single Receipt Propagation
**Goal**: Verify receipt reaches all nodes
**Setup**: Inject 1 receipt at node 0
**Execute**: 10 gossip rounds
**Verify**: All 7 nodes have the receipt
**Expected Latency**: < 1 second (95th percentile)

#### T2: Multiple Receipts Propagation
**Goal**: Verify batch propagation
**Setup**: Inject 10 receipts across nodes
**Execute**: 20 gossip rounds
**Verify**: All nodes have all 10 receipts
**Expected Latency**: < 2 seconds network-wide

#### T3: Concurrent Receipt Injection
**Goal**: Test throughput under load
**Setup**: Inject 50 receipts during ongoing gossip
**Execute**: 30 gossip rounds with concurrent injection
**Verify**: All receipts propagated, no loss
**Expected Throughput**: > 10 receipts/sec cluster-wide

###Anti-Entropy Tests

#### T4: Partitioned Reconciliation
**Goal**: Verify bloom filter anti-entropy
**Setup**:
- Nodes 0-3 have receipts 0-9
- Nodes 4-6 have receipts 10-19
**Execute**: 30 gossip rounds (cross-partition)
**Verify**: All nodes have all 20 receipts
**Metrics**: Bloom filter FPR < 2%, redundant sends < 10%

#### T5: Bloom Filter Efficiency
**Goal**: Measure network overhead reduction
**Setup**: All nodes except one have 100 receipts
**Execute**: 15 gossip rounds
**Verify**:
- Last node receives exactly 100 missing receipts
- Redundant receipt sends < 10 (1% FPR)
- Network messages < 150 total

#### T6: Large Receipt Sets
**Goal**: Scalability validation
**Setup**: All nodes have 1,000 receipts
**Execute**: 50 gossip rounds
**Verify**: Bloom filter size < 15 KB, convergence < 5 seconds

### Byzantine Fault Tests

#### T7: Byzantine Equivocation
**Goal**: Detect conflicting receipts from Byzantine node
**Setup**: Node 3 sends 2 different receipts for same event
**Execute**: 20 gossip rounds
**Verify**:
- Honest nodes detect equivocation
- Byzantine receipts rejected
- Valid receipts still propagate

#### T8: Signature Forgery
**Goal**: Reject invalid signatures
**Setup**: Byzantine node sends receipt with forged signature
**Execute**: 15 gossip rounds
**Verify**: All honest nodes reject forged receipt

#### T9: Receipt Flooding
**Goal**: Rate limiting under attack
**Setup**: Byzantine node floods with 1,000 receipts
**Execute**: 10 gossip rounds
**Verify**: Other nodes receive < 500 receipts (rate limited)

#### T10: Bloom Filter Poisoning
**Goal**: Detect malicious bloom filters
**Setup**: Byzantine node sends bloom filter with all bits set
**Execute**: 10 gossip rounds
**Verify**:
- Honest nodes detect poisoned filter (> 50% bits set)
- Reject poisoned gossip
- Anti-entropy via alternative paths

### Network Failure Tests

#### T11: Node Crash and Recovery
**Goal**: Verify catchup after crash
**Setup**:
- All nodes have 10 receipts
- Node 4 crashes
- 10 new receipts added during crash
**Execute**:
- 10 rounds while crashed
- Node 4 recovers
- 20 catchup rounds
**Verify**: Node 4 has all 20 receipts after recovery

#### T12: Network Partition
**Goal**: Test partition healing
**Setup**: Partition network [0,1,2] vs [3,4,5,6]
**Execute**:
- Each partition gets different receipts
- 10 rounds isolated
- Heal partition
- 20 rounds cross-partition gossip
**Verify**: All nodes have all receipts after heal

#### T13: Multiple Concurrent Failures
**Goal**: Byzantine fault tolerance (f=2, need 2f+1=5 nodes)
**Setup**: 7 nodes, crash 2 nodes simultaneously
**Execute**: 30 gossip rounds
**Verify**: Remaining 5 nodes achieve consistency

### Performance Tests

#### T14: Propagation Latency
**Goal**: Measure P50, P95, P99 latencies
**Setup**: Inject receipts with timestamps
**Execute**: 20 gossip rounds
**Verify**:
- P50 latency < 500ms
- P95 latency < 1000ms
- P99 latency < 2000ms

#### T15: Throughput Benchmark
**Goal**: Measure sustainable receipt rate
**Setup**: Inject 100 receipts rapidly
**Execute**: Gossip until all propagated
**Verify**: Throughput > 10 receipts/sec

#### T16: Scalability (10 Nodes)
**Goal**: Verify scaling behavior
**Setup**: 10-node cluster, 50 receipts
**Execute**: 40 gossip rounds
**Verify**:
- All receipts propagated
- Latency < 4 seconds (P95)
- Network overhead scales O(log n)

#### T17: Memory Overhead
**Goal**: Measure bloom filter memory cost
**Setup**: Track bloom filter sizes for 10, 100, 1000 receipts
**Verify**:
- 10 receipts: ~120 bytes
- 100 receipts: ~1.2 KB
- 1000 receipts: ~12 KB
- Growth rate matches theoretical O(n log n)

---

## Testing Infrastructure

### Existing Components (Reusable)

From `WitnessConsensusIntegrationTest.java`:
- `WitnessNode` inner class pattern
- `StaticContext` for Fireflies membership
- Cluster setup/teardown
- View change simulation

From `ReceiptGossipIntegrationTest.java`:
- `createTestReceipt()` helper
- `knownDigestsFrom()` bloom filter helper
- Gossip round-trip validation
- Timestamp drift testing

### New Components Needed

1. **GossipSimulator**:
   ```java
   class GossipSimulator {
       void runRounds(int count, Duration interval);
       void setNetworkDelay(Duration delay);
       void setMessageLossRate(double rate);
       void injectByzantineNode(int nodeId, ByzantineStrategy strategy);
   }
   ```

2. **MetricsCollector**:
   ```java
   class MetricsCollector {
       long getAveragePropagationLatency();
       long getP95Latency();
       long getThroughput();
       int getRedundantReceipts();
       int getBloomFilterSize();
   }
   ```

3. **ByzantineStrategies**:
   ```java
   interface ByzantineStrategy {
       Set<GossipableReceipt> attack(ReceiptGossip incoming);
   }

   class EquivocationStrategy implements ByzantineStrategy { ... }
   class SignatureForgeryStrategy implements ByzantineStrategy { ... }
   class FloodingStrategy implements ByzantineStrategy { ... }
   ```

---

## Implementation Phases

### Phase 1: Foundation (2 hours)
- [x] Unit tests (F1-F3)
- [x] Documentation (F4)
- [ ] Test infrastructure setup
- [ ] GossipNode class
- [ ] Cluster management

### Phase 2: Happy Path (3 hours)
- [ ] T1: Single receipt propagation
- [ ] T2: Multiple receipts
- [ ] T3: Concurrent injection

### Phase 3: Anti-Entropy (4 hours)
- [ ] T4: Partitioned reconciliation
- [ ] T5: Bloom filter efficiency
- [ ] T6: Large receipt sets

### Phase 4: Byzantine (4 hours)
- [ ] T7: Equivocation
- [ ] T8: Signature forgery
- [ ] T9: Receipt flooding
- [ ] T10: Bloom filter poisoning

### Phase 5: Network Failures (2 hours)
- [ ] T11: Crash and recovery
- [ ] T12: Network partition
- [ ] T13: Multiple failures

### Phase 6: Performance (2 hours)
- [ ] T14: Latency measurement
- [ ] T15: Throughput benchmark
- [ ] T16: Scalability test
- [ ] T17: Memory overhead

**Total Estimated Time**: 17 hours

---

## Current Status

### Completed (F1-F4)
- ✅ ReceiptGossipCodec (serialization)
- ✅ ReceiptGossipHandler (validation)
- ✅ BasicReceiptValidator
- ✅ ReceiptAntiEntropyService
- ✅ Comprehensive unit tests (55 tests)
- ✅ Protocol documentation
- ✅ Performance baselines
- ✅ Security considerations

### In Progress (F5)
- ⏸️ Multi-node integration tests
- ⏸️ Byzantine scenario tests
- ⏸️ Performance benchmarks

### Test Infrastructure Available
- Existing Fireflies integration patterns
- WitnessNode cluster simulation
- Gossip round simulation
- Receipt test helpers

---

## Next Steps

1. **Implement GossipSimulator**: Reusable infrastructure for all multi-node tests
2. **Happy Path Tests**: Validate basic propagation (T1-T3)
3. **Anti-Entropy Tests**: Verify bloom filter reconciliation (T4-T6)
4. **Byzantine Tests**: Security validation (T7-T10)
5. **Network Failure Tests**: Fault tolerance (T11-T13)
6. **Performance Tests**: Benchmarks and SLAs (T14-T17)

---

## Success Criteria

### Functional
- [ ] All 17 integration test scenarios pass
- [ ] Receipt propagation latency < 1s (P95)
- [ ] Byzantine fault detection works (f < n/3)
- [ ] Network partition recovery < 5s

### Performance
- [ ] Throughput > 10 receipts/sec (7-node cluster)
- [ ] Bloom filter FPR < 2% in practice
- [ ] Redundant receipt sends < 10%
- [ ] Memory overhead < 20KB for 1000 receipts

### Reliability
- [ ] Zero receipt loss under f < n/3 failures
- [ ] Crash recovery within 20 gossip rounds
- [ ] Partition healing verified
- [ ] No deadlocks or resource leaks

---

## References

1. **Existing Tests**:
   - `ReceiptGossipIntegrationTest.java` (unit/integration)
   - `WitnessConsensusIntegrationTest.java` (multi-node pattern)
   - `WitnessFirefliesIntegrationTest.java` (Fireflies integration)

2. **Documentation**:
   - `RECEIPT_GOSSIP_PROTOCOL.md` (protocol spec)
   - `PHASE_1B2_BLS_RECEIPT_AGGREGATION_ARCHITECTURE.md` (BLS integration)

3. **Code**:
   - `ReceiptGossipCodec.java`
   - `ReceiptGossipHandler.java`
   - `ReceiptAntiEntropyService.java`

---

**Document Version**: 1.0
**Last Updated**: 2026-01-31
**Status**: F5 Test Plan - Ready for Implementation
