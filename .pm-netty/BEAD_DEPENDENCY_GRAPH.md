# Netty Elimination - Bead Dependency Graph

**Project**: Netty Native Library Elimination
**Updated**: 2026-01-15
**Epic Bead**: Delos-1b9y
**Status**: Ready to Create Beads

---

## Dependency Overview

```
Delos-1b9y (Epic: Eliminate Netty native libraries)
├── Phase 1: Code Migration
│   ├── P1-protocols
│   ├── P1-model
│   ├── P1-memberships
│   ├── P1-isolates
│   ├── P1-ftesting
│   └── P1-validate (depends on: all above)
│
├── Phase 2: Module Deletion
│   └── P2-delete (depends on: P1-validate)
│
├── Phase 3: Metadata Cleanup
│   └── P3-metadata (depends on: P2-delete)
│
└── Phase 4: Testing & Validation
    ├── P4-performance-baseline (no deps)
    ├── P4-testing (depends on: P3-metadata)
    ├── P4-performance-final (depends on: P4-testing)
    └── P4-validation (depends on: P4-performance-final)
```

---

## Phase 1: Code Migration Dependencies

### P1.1: protocols Module
**Bead**: `Delos-1b9y-p1-protocols`
**Description**: Update protocols module to use NioEventLoopGroup
**Depends On**: None (can start immediately)
**Blocks**: P1-validate
**Effort**: 4-6 hours

**Tasks**:
- Locate DomainSocketServerInterceptor.java
- Replace KQueue/Epoll transport with NIO
- Run: `./mvnw test -pl protocols`
- Verify: All tests pass

### P1.2: model Module
**Bead**: `Delos-1b9y-p1-model`
**Description**: Update model DemesneImpl transport selection
**Depends On**: P1-protocols (optional - good to complete sequentially)
**Blocks**: P1-validate
**Effort**: 4-6 hours

**Tasks**:
- Locate transport selection logic in DemesneImpl.java
- Update to select NIO transport
- Run: `./mvnw test -pl model`
- Verify: All tests pass

### P1.3: memberships Module
**Bead**: `Delos-1b9y-p1-memberships`
**Description**: Update Enclave/Portal socket creation
**Depends On**: P1-model (optional - good to complete sequentially)
**Blocks**: P1-validate
**Effort**: 4-6 hours

**Tasks**:
- Locate socket creation in Enclave.java, Portal.java
- Replace with NioDomainSocketChannel
- Run: `./mvnw test -pl memberships`
- Verify: All tests pass

### P1.4: isolates Module
**Bead**: `Delos-1b9y-p1-isolates`
**Description**: Update isolate-specific transport handling
**Depends On**: P1-memberships (optional - good to complete sequentially)
**Blocks**: P1-validate
**Effort**: 6-8 hours

**Tasks**:
- Locate isolate transport initialization
- Update for NIO compatibility
- Compile: `./mvnw compile -pl isolates -Pisolates`
- Note: Don't run tests yet (Phase 4)

### P1.5: isolate-ftesting Module
**Bead**: `Delos-1b9y-p1-ftesting`
**Description**: Update test infrastructure for NIO
**Depends On**: P1-isolates (optional - good to complete sequentially)
**Blocks**: P1-validate
**Effort**: 4-6 hours

**Tasks**:
- Locate test setup code
- Update for NIO transport
- Compile: `./mvnw compile -pl isolate-ftesting -Pisolates`
- Note: Don't run tests yet (Phase 4)

### P1.6: Phase 1 Validation
**Bead**: `Delos-1b9y-p1-validate`
**Description**: Validate all Phase 1 changes
**Depends On**: P1-protocols, P1-model, P1-memberships, P1-isolates, P1-ftesting (all 5)
**Blocks**: P2-delete
**Effort**: 2 hours

**Tasks**:
- Grep scan: No remaining KQueue/Epoll references
  ```bash
  grep -r "KQueueDomainSocketChannel\|EpollDomainSocketChannel\|domain-kqueue\|domain-epoll" --include="*.java" src/
  ```
- Full build: `./mvnw clean compile`
- Verify: All modules compile
- Create checkpoint: Phase 1 complete

---

## Phase 2: Module Deletion Dependencies

### P2.1: Module Deletion
**Bead**: `Delos-1b9y-p2-delete`
**Description**: Delete 3 domain socket modules
**Depends On**: P1-validate (STRICT - requires Phase 1 complete)
**Blocks**: P3-metadata
**Effort**: 1-2 hours

**Tasks**:
1. Delete domain-kqueue/ directory
2. Delete domain-epoll/ directory
3. Delete domain-sockets/ directory
4. Update parent pom.xml (remove from `<modules>` section)
5. Verify: `./mvnw clean install` (or `./mvnw compile` to be fast)
6. Grep: Confirm no references to deleted modules
7. Create checkpoint: Phase 2 complete

---

## Phase 3: Metadata Cleanup Dependencies

### P3.1: Metadata Cleanup
**Bead**: `Delos-1b9y-p3-metadata`
**Description**: Remove native library metadata from GraalVM
**Depends On**: P2-delete (STRICT - requires Phase 2 complete)
**Blocks**: P4-testing
**Effort**: 1 hour

**Tasks**:
1. Check isolates for `resource-config.json` (delete if present)
2. Review isolates/pom.xml for native library references
3. Remove or adapt native profiles if needed
4. Verify: `./mvnw compile -pl isolates -Pisolates`
5. Verify: No GraalVM native library loading code
6. Create checkpoint: Phase 3 complete

---

## Phase 4: Testing & Validation Dependencies

### P4.1: Performance Baseline (PREREQUISITE - No Dependencies)
**Bead**: `Delos-1b9y-p4-baseline`
**Description**: Record performance baseline BEFORE Phase 1
**Depends On**: None (should be done FIRST before Phase 1)
**Blocks**: None (informational)
**Effort**: 2-3 hours

**CRITICAL**: Must be recorded BEFORE Phase 1 starts for comparison

**Tasks**:
1. Design baseline test (ChurnTest or similar)
2. Record: Throughput (msgs/sec)
3. Record: Latency p50/p99 (ms)
4. Record: Memory usage (MB)
5. Record: GC pause time (ms)
6. Save to: `.pm-netty/metrics/baseline.txt`

### P4.2: Testing Suite
**Bead**: `Delos-1b9y-p4-testing`
**Description**: Run all test suites with NIO transport
**Depends On**: P3-metadata (STRICT), P4-baseline (informational)
**Blocks**: P4-performance-final
**Effort**: 3-5 hours

**Tasks**:
1. Protocol tests: `./mvnw test -pl protocols`
2. Model tests: `./mvnw test -pl model`
3. Memberships tests: `./mvnw test -pl memberships`
4. Full build: `./mvnw clean install`
5. Isolate tests (EXTREME CAUTION):
   ```bash
   timeout 30s ./mvnw test -pl isolate-ftesting -Pisolates
   ```
6. Record results in checkpoint

### P4.3: Performance Measurement
**Bead**: `Delos-1b9y-p4-performance`
**Description**: Measure performance post-Phase 4
**Depends On**: P4-testing (test must complete)
**Blocks**: P4-validation
**Effort**: 2-3 hours

**Tasks**:
1. Run identical baseline test
2. Record same metrics (throughput, latency, memory, GC)
3. Calculate degradation percentage
4. Document in `.pm-netty/metrics/performance.md`
5. Assessment:
   - < 10%: Excellent
   - 10-20%: Acceptable
   - > 20%: Investigate/escalate

### P4.4: Final Validation
**Bead**: `Delos-1b9y-p4-validation`
**Description**: Final validation and project completion
**Depends On**: P4-testing, P4-performance (both complete)
**Blocks**: None (project complete)
**Effort**: 2 hours

**Tasks**:
1. Verify all tests passed
2. Verify performance acceptable
3. Verify no terminal hangs
4. Verify isolates functional
5. Update documentation
6. Create final checkpoint: Project complete

---

## Dependency Summary Table

| Bead | Phase | Depends On | Blocks | Status |
|------|-------|-----------|--------|--------|
| P1-protocols | 1 | None | P1-validate | PENDING |
| P1-model | 1 | None (P1-protocols ideally) | P1-validate | PENDING |
| P1-memberships | 1 | None (P1-model ideally) | P1-validate | PENDING |
| P1-isolates | 1 | None (P1-memberships ideally) | P1-validate | PENDING |
| P1-ftesting | 1 | None (P1-isolates ideally) | P1-validate | PENDING |
| **P1-validate** | 1 | **All P1 tasks** | **P2-delete** | **GATE** |
| **P2-delete** | 2 | **P1-validate** | **P3-metadata** | **GATE** |
| **P3-metadata** | 3 | **P2-delete** | **P4-testing** | **GATE** |
| P4-baseline | 4 | None | None | PENDING |
| P4-testing | 4 | P3-metadata | P4-performance | PENDING |
| P4-performance | 4 | P4-testing | P4-validation | PENDING |
| P4-validation | 4 | P4-testing, P4-performance | None | PENDING |

---

## Critical Path

**Strict Sequence** (No parallelization):
```
P1-protocols → P1-model → P1-memberships → P1-isolates → P1-ftesting → P1-validate
→ P2-delete → P3-metadata → P4-testing → P4-performance → P4-validation
```

**Parallel Opportunities** (Can overlap):
- P1 modules: Can work on 2-3 in parallel if careful with merges
- P4 testing and performance: Sequential but fast (2-3 hours each)

**Recommended**: Sequential for Phase 1 (safer, easier to debug), Sequential for Phase 4 (testing must complete before perf measurement).

---

## How to Create Beads

### Template Command
```bash
# Phase 1 modules
bd create "Phase 1: Update protocols module to use NIO" -t task -p 1

bd create "Phase 1: Update model DemesneImpl transport selection" -t task -p 1

bd create "Phase 1: Update memberships Enclave/Portal sockets" -t task -p 1

bd create "Phase 1: Update isolates transport handling" -t task -p 1

bd create "Phase 1: Update isolate-ftesting infrastructure" -t task -p 1

bd create "Phase 1: Validate all modules updated, tests pass" -t task -p 1

# Phase 2
bd create "Phase 2: Delete domain-socket modules" -t task -p 2

# Phase 3
bd create "Phase 3: Clean GraalVM metadata" -t task -p 3

# Phase 4
bd create "Phase 4: Record performance baseline" -t task -p 4

bd create "Phase 4: Run comprehensive test suite" -t task -p 4

bd create "Phase 4: Measure post-Phase4 performance" -t task -p 4

bd create "Phase 4: Final validation and completion" -t task -p 4
```

### Add Dependencies
```bash
# P1-validate depends on all P1 modules
bd dep add Delos-1b9y-p1-validate Delos-1b9y-p1-protocols
bd dep add Delos-1b9y-p1-validate Delos-1b9y-p1-model
bd dep add Delos-1b9y-p1-validate Delos-1b9y-p1-memberships
bd dep add Delos-1b9y-p1-validate Delos-1b9y-p1-isolates
bd dep add Delos-1b9y-p1-validate Delos-1b9y-p1-ftesting

# P2 depends on P1 validation
bd dep add Delos-1b9y-p2-delete Delos-1b9y-p1-validate

# P3 depends on P2 completion
bd dep add Delos-1b9y-p3-metadata Delos-1b9y-p2-delete

# P4 depends on P3 completion
bd dep add Delos-1b9y-p4-testing Delos-1b9y-p3-metadata
bd dep add Delos-1b9y-p4-performance Delos-1b9y-p4-testing
bd dep add Delos-1b9y-p4-validation Delos-1b9y-p4-testing
bd dep add Delos-1b9y-p4-validation Delos-1b9y-p4-performance
```

---

## Phase Gates

### Phase 1 → Phase 2 Gate
**Requirement**: P1-validate bead closed successfully
**Verification**:
- All 5 modules updated
- Code compiles
- All protocol tests pass
- Grep: Zero KQueue/Epoll references
- Checkpoint created

### Phase 2 → Phase 3 Gate
**Requirement**: P2-delete bead closed successfully
**Verification**:
- 3 modules deleted
- Parent pom.xml cleaned
- Full build succeeds
- No dangling references
- Checkpoint created

### Phase 3 → Phase 4 Gate
**Requirement**: P3-metadata bead closed successfully
**Verification**:
- Native metadata removed
- GraalVM config updated
- Isolates compiles with -Pisolates
- No native library loading code
- Checkpoint created

### Phase 4 Completion Gate
**Requirement**: All P4 beads closed
**Verification**:
- All tests pass
- Performance < 20% degradation
- No terminal hangs
- Isolates functional
- Documentation updated

---

## Blocker Management

### P1 Blockers
If any module test fails:
1. Document failure in RISK_REGISTER.md
2. Debug and fix code
3. Re-run tests
4. Continue when tests pass

### P2 Blockers
If module deletion fails:
1. Verify all P1 modules updated
2. Manually search for remaining references
3. Fix any remaining code
4. Re-try build

### P3 Blockers
If GraalVM build fails:
1. Review build error output
2. Update metadata if needed
3. Test isolate compilation
4. Document any new requirements

### P4 Blockers
If tests fail:
1. Check if failure is NIO-related or pre-existing
2. If NIO-related: Debug, fix, re-test
3. If pre-existing: Escalate or document
4. If terminal hang: Recover, continue with different test

---

## Effort Estimates

| Bead | Phase | Effort | Critical | Notes |
|------|-------|--------|----------|-------|
| P1-protocols | 1 | 4-6h | No | Safe, basic replacement |
| P1-model | 1 | 4-6h | No | Logic update |
| P1-memberships | 1 | 4-6h | No | Multi-file update |
| P1-isolates | 1 | 6-8h | No | Platform-specific code |
| P1-ftesting | 1 | 4-6h | No | Test infrastructure |
| P1-validate | 1 | 2h | **YES** | Gate to Phase 2 |
| P2-delete | 2 | 1-2h | **YES** | Gate to Phase 3 |
| P3-metadata | 3 | 1h | **YES** | Gate to Phase 4 |
| P4-baseline | 4 | 2-3h | No | Should be first |
| P4-testing | 4 | 3-5h | **YES** | High-risk isolate tests |
| P4-performance | 4 | 2-3h | No | Measurement |
| P4-validation | 4 | 2h | **YES** | Project completion gate |

**Total Effort**: ~40-50 hours (3 weeks for one engineer)

---

**Document Control**:
- Created: 2026-01-15
- Version: 1.0
- Status: TEMPLATE (awaiting bead creation)
- Owner: Development Team
