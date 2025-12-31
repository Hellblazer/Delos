# CHOAM Recovery Protocol Mapping

## Summary

The CHOAM recovery protocol enables nodes to join or rejoin a running consensus cluster by synchronizing their state with the current network state. Recovery is **checkpoint-based** and **pull-oriented**, where recovering nodes actively fetch missing blocks and state from existing cluster members.

The recovery mechanism is implemented primarily in `Bootstrapper.java` with supporting infrastructure in `Store.java`, `Combine.java` (FSM), and `CHOAM.java`.

## Algorithm 6 Reference

> **Note**: This document was created to map Delos's recovery implementation to "Algorithm 6" from the "Lightweight-SMR" paper. The specific paper reference needs clarification. The implementation mapping below uses standard BFT recovery terminology and can be updated when the paper is identified.
>
> Candidates:
> - Mir-BFT: "Mir-BFT: Scalable and Robust BFT for Decentralized Networks"
> - Custom Salesforce/Apollo internal paper
> - Related Aleph-BFT literature

## Implementation Mapping

### Standard BFT Recovery Protocol Steps

| Step | Algorithm Phase | Delos Implementation | Location |
|------|----------------|---------------------|----------|
| 1 | **Detect Recovery Needed** | FSM transitions to RECOVERING state | `Combine.java` |
| 2 | **Request Checkpoint** | Terminal record carries anchor block | `CHOAM.java` |
| 3 | **Validate Checkpoint** | Signature verification via majority | `Bootstrapper.java` |
| 4 | **Fetch Checkpoint Blocks** | Parallel batch fetching | `Bootstrapper.java` |
| 5 | **Apply Checkpoint State** | State reconstruction from checkpoint | `Bootstrapper.java` |
| 6 | **Sync Missing Blocks** | Post-checkpoint block fetch | `Bootstrapper.java` |
| 7 | **Verify Block Chain** | Block digest validation | `Bootstrapper.java` |
| 8 | **Complete Recovery** | Transition to operational state | `Combine.java` |

### Detailed Phase Analysis

#### Phase 1: Recovery Detection and Initiation

**Trigger Conditions:**
- New node joining cluster
- Node restart after crash
- Node falling behind beyond threshold

**Implementation:**
```
Combine FSM State: RECOVERING
├── Entry: transitions.recover() called
├── Action: Bootstrapper.assemble(terminal, lastCheckpoint)
└── Exit: To OPERATIONAL on success
```

**Code Reference:**
- `choam/src/main/java/com/salesforce/apollo/choam/fsm/Combine.java` - RECOVERING state
- `choam/src/main/java/com/salesforce/apollo/choam/CHOAM.java` - Recovery trigger handling

#### Phase 2: Checkpoint Discovery

The recovering node receives a `Terminal` record containing the anchor block, which identifies the most recent checkpoint.

**Terminal Record:**
```java
public record Terminal(HashedCertifiedBlock anchor, ULong lastCheckpoint) {}
```
- `anchor`: Most recent certified block (checkpoint header)
- `lastCheckpoint`: Height of the last checkpoint

**Code Reference:**
- `choam/src/main/java/com/salesforce/apollo/choam/CHOAM.java` - Terminal record definition

#### Phase 3: Checkpoint Validation

**Validation Steps:**
1. Verify checkpoint block signature (requires 2f+1 attestations)
2. Validate checkpoint height against expected sequence
3. Confirm checkpoint state hash matches declared hash

**Code Reference:**
- `choam/src/main/java/com/salesforce/apollo/choam/support/Bootstrapper.java`
- `choam/src/main/java/com/salesforce/apollo/choam/support/BootstrapService.java`

#### Phase 4: Checkpoint Block Assembly

Blocks are fetched in parallel batches for efficiency using `BootstrapService.fetchBlocks()` for batched retrieval.

**Code Reference:**
- `choam/src/main/java/com/salesforce/apollo/choam/support/Bootstrapper.java`
- `choam/src/main/java/com/salesforce/apollo/choam/support/Store.java`

#### Phase 5: State Application

After checkpoint blocks are assembled, state is reconstructed:

**Process:**
1. Load checkpoint state snapshot
2. Replay checkpoint blocks if needed
3. Verify reconstructed state hash matches checkpoint declaration

**Code Reference:**
- `choam/src/main/java/com/salesforce/apollo/choam/support/Bootstrapper.java`
- `choam/src/main/java/com/salesforce/apollo/choam/support/Store.java`

#### Phase 6: Block Synchronization

Post-checkpoint blocks are fetched to reach current height:

**Implementation Details:**
- Gossip-style fetching from multiple peers
- Handles view boundaries (reconfiguration blocks)
- Tolerates Byzantine peers through majority validation

**Code Reference:**
- `choam/src/main/java/com/salesforce/apollo/choam/support/Bootstrapper.java`
- `choam/src/main/java/com/salesforce/apollo/choam/support/Store.java`

#### Phase 7: Block Chain Verification

Each fetched block is verified:
- Verify block digest matches expected
- Verify block signature (producer attestation)
- Verify block sequence (height, previous hash)

**Code Reference:**
- `choam/src/main/java/com/salesforce/apollo/choam/support/Bootstrapper.java`

#### Phase 8: Recovery Completion

**Completion Steps:**
1. Verify all blocks assembled
2. Apply remaining blocks to state machine
3. Transition FSM to OPERATIONAL
4. Begin participating in consensus

**Code Reference:**
- `choam/src/main/java/com/salesforce/apollo/choam/fsm/Combine.java`

## Recovery Time Analysis

### Guarantees

**Liveness**: A correct recovering node will complete recovery provided:
- At least 2f+1 correct nodes are available
- Network eventually delivers messages

**Safety**: A recovering node will never:
- Accept an invalid checkpoint (requires 2f+1 signatures)
- Apply blocks out of order
- Skip required state verification

### Time Complexity

| Phase | Complexity | Notes |
|-------|------------|-------|
| Checkpoint Discovery | O(1) | Single anchor block |
| Block Fetching | O(blocks) | Parallelized batches |
| State Transfer | O(state_size) | Checkpoint snapshot |
| Verification | O(blocks) | Linear in catch-up distance |

**Total Recovery Time**: `O(catch_up_blocks + state_size)`

### Optimization Techniques

1. **Parallel Block Fetching**: Multiple concurrent requests to different peers
2. **Checkpoint-Based**: Avoids replaying full history
3. **Batch Operations**: Amortizes network overhead
4. **Incremental Progress**: Can resume from partial progress

## Deviations and Extensions

### Compared to Standard BFT Recovery

| Aspect | Standard | Delos | Rationale |
|--------|----------|-------|-----------|
| State Transfer | Push-based | Pull-based | Better for large state |
| Checkpoint Format | Full state | Incremental | Space efficiency |
| Block Fetching | Sequential | Parallel batches | Performance |
| View Handling | Implicit | Explicit view chain | Supports reconfiguration |

### Extensions for CHOAM

1. **View Chain Tracking**: Explicit handling of committee reconfiguration
2. **Multi-Tenant Support**: Recovery per context/domain
3. **Deterministic State**: SQL-based state machines with deterministic replay

## Code References

### Primary Recovery Classes

| File | Purpose |
|------|---------|
| `choam/src/main/java/com/salesforce/apollo/choam/support/Bootstrapper.java` | Main recovery coordinator |
| `choam/src/main/java/com/salesforce/apollo/choam/support/BootstrapService.java` | RPC interface for recovery |
| `choam/src/main/java/com/salesforce/apollo/choam/support/Store.java` | Block and state storage |
| `choam/src/main/java/com/salesforce/apollo/choam/fsm/Combine.java` | Lifecycle FSM with RECOVERING state |
| `choam/src/main/java/com/salesforce/apollo/choam/CHOAM.java` | Top-level coordinator |

### Related Protobuf Definitions

Located in `grpc/src/main/proto/`:
- `choam.proto` - Block, Checkpoint, and Bootstrap message definitions
- Service: `BootstrapService` - RPC methods for recovery

## References

1. **Delos Architecture**: `choam/docs/CHOAM_ARCHITECTURE.md`
2. **Bootstrap Protocol**: `choam/docs/CHOAM_BOOTSTRAP_PROTOCOL.md`
3. **Aleph-BFT**: "Aleph: Efficient Atomic Broadcast in Asynchronous Networks with Byzantine Nodes" - https://arxiv.org/abs/1908.05156
4. **Mir-BFT**: "Mir-BFT: Scalable and Robust BFT for Decentralized Networks" - https://arxiv.org/abs/2105.05979

> **TODO**: Add specific "Lightweight-SMR Algorithm 6" paper reference when identified.

---

*Document Version*: 1.0
*Last Updated*: 2025-12-31
*Related Beads*: Delos-868.15
