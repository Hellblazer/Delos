# Delos Strategic Opportunities: Phase 1 Execution Plan - FK-FIRST

**Document Status**: OPERATIONAL GUIDE
**Date Created**: 2026-01-18
**Timeline**: Week 0 (prep) through Week 3 (Phase 1A completion)
**Build Status**: GREEN (no test exemptions)
**Audit Status**: All 4 plans APPROVED with conditions
**Strategy Decision**: FK-FIRST locked as primary critical path

---

## Executive Summary

This document provides a detailed operational guide for Phase 1 execution of the 4 Delos strategic opportunities with **Fireflies-KERI Integration as the PRIMARY CRITICAL PATH**.

**Strategic Opportunities (Audit-Approved)**:
1. **Fireflies-KERI Integration** (10-14 weeks) - PRIMARY CRITICAL PATH
2. **GPU Batch Verification** (9-14 weeks) - SECONDARY (validated in parallel, Week 1)
3. **Zanzibar Query Rewriting** (6-8 weeks) - PARALLEL (independent)
4. **Dynamic Diameter Adaptation** (5-8 weeks) - BLOCKED by FK Phase 1

**Current State**:
- 20 unblocked beads ready for execution
- All P0 Week 0 tasks created and documented
- Gate conditions defined with precise thresholds
- Audit confidence: **85% (APPROVED)**

---

## Week 0: Prep Phase (Current Week)

### Timeline: 5 business days before Week 1 Monday

### Sequential Execution Order

```
Day 1-2: Delos-3901 (GPU Infrastructure)
    ↓
Day 2-3: Delos-3902 (Resource Validation) [can partially overlap]
    ↓
Day 3-4: Delos-3903 (Prerequisites Documentation)
    ↓
Day 5:   Delos-3904 (Final GO/NO-GO Checklist)
```

---

### Task 1: Delos-3901 - GPU Cloud Instance Provisioning

**Owner**: GPU Team Lead (TBD in Delos-3902)
**Duration**: 2 days
**Priority**: P0 CRITICAL
**Status**: IN PROGRESS

#### Deliverables

| # | Deliverable | Acceptance Criteria |
|---|-------------|---------------------|
| 1 | GPU hardware provisioned | Cloud instance running OR on-prem validated |
| 2 | Drivers installed | CUDA/ROCm/OpenCL verified with nvidia-smi or equivalent |
| 3 | GPU library identified | libsodium GPU, rustacrypto, or CUDA Ed25519 library selected |
| 4 | Test batch verified | 100-1000 signature batch runs successfully |
| 5 | Resource constraints documented | Memory, power, cost per hour documented |
| 6 | CPU fallback strategy | SIMD vectorization path documented |

#### Specific Actions

**Day 1 Morning (4 hours)**:
1. Review available GPU options:
   - AWS: p4d.24xlarge (A100), g5.xlarge (A10G)
   - GCP: a2-highgpu-1g (A100), n1-standard-4 + T4
   - Azure: NC series (T4, A100)
   - On-prem: Check existing GPU servers
2. Provision preferred option (cloud: spin up instance; on-prem: validate availability)
3. Document cost per hour and availability model

**Day 1 Afternoon (4 hours)**:
1. Install CUDA toolkit 12.x (or ROCm 5.x for AMD)
2. Validate with nvidia-smi / rocm-smi
3. Install Ed25519 GPU library dependencies:
   - Option A: Check libsodium GPU backend (sodium_init() with GPU acceleration)
   - Option B: Evaluate rustacrypto GPU via JNI
   - Option C: Evaluate ed25519-gpu-batch CUDA library
4. Document library selection rationale

**Day 2 Morning (4 hours)**:
1. Write GPU batch verification test:
   - Generate 1000 random Ed25519 key pairs
   - Sign 1000 messages
   - Batch verify all 1000 signatures on GPU
   - Measure throughput (sigs/sec)
2. Run test and capture:
   - Total time for 1000-sig batch
   - GPU memory usage (nvidia-smi)
   - Any errors or warnings

**Day 2 Afternoon (4 hours)**:
1. Document GPU resource constraints:
   - Available GPU memory (typically 16-80GB)
   - Thermal/power limits
   - Network bandwidth for data transfer
2. Design CPU batch fallback:
   - Identify SIMD-capable Ed25519 library (e.g., ed25519-donna)
   - Document vectorization approach
   - Estimate CPU batch throughput (2-5x single-sig)
3. Create GPU_INFRASTRUCTURE_REPORT.md

#### Exit Criteria

- [ ] GPU accessible and drivers working
- [ ] Ed25519 batch verification test runs successfully
- [ ] GPU library documentation reviewed
- [ ] CPU fallback path documented
- [ ] Delos-3894 Gate 0 is UNBLOCKED

---

### Task 2: Delos-3902 - Resource Availability & Skills Validation

**Owner**: Project Manager / Architecture Lead
**Duration**: 2 days (overlaps with Delos-3901 Day 2)
**Priority**: P0 CRITICAL
**Status**: PENDING

#### Deliverables

| # | Deliverable | Acceptance Criteria |
|---|-------------|---------------------|
| 1 | GPU team roster | 2-3 engineers named, hours/week confirmed |
| 2 | FK team roster | 2-3 engineers named, KERI knowledge validated |
| 3 | ZQ team roster | 1-2 engineers named, Oracle expertise confirmed |
| 4 | DA team roster | 1-2 engineers named (deferred start Week 4) |
| 5 | Skills assessment | GPU/KERI/RBAC/BFT expertise documented |
| 6 | Team leads confirmed | 4 team leads named and accepted |

#### Specific Actions

**Day 1: Team Availability Check**

| Team | Required | Skill Check |
|------|----------|-------------|
| GPU | 2-3 engineers, 40h/week each | CUDA/OpenCL experience, Java JNI |
| FK | 2-3 engineers, 40h/week each | Stereotomy/KERI, Fireflies gossip |
| ZQ | 1-2 engineers, 40h/week each | Delphinius Oracle, RBAC, SQL optimization |
| DA | 1-2 engineers, 20h/week (deferred) | BFT consensus, ring topology |

**Day 2: Skills Gap Analysis**

1. For each team, verify at least 1 person has domain expertise
2. Identify any training needed (e.g., KERI bootcamp for FK team)
3. Document hiring needs if critical skills missing
4. Confirm team leads accepted responsibility

#### Resource Allocation Table

| Role | Name | Availability | Start Week |
|------|------|--------------|------------|
| GPU Team Lead | [TBD] | Full-time | Week 0 |
| GPU Engineer 1 | [TBD] | Full-time | Week 1 |
| GPU Engineer 2 | [TBD] | Full-time | Week 1 |
| FK Team Lead | [TBD] | Full-time | Week 0 |
| FK Engineer 1 | [TBD] | Full-time | Week 1 |
| FK Engineer 2 | [TBD] | Full-time | Week 1 |
| ZQ Team Lead | [TBD] | Full-time | Week 0 |
| ZQ Engineer 1 | [TBD] | Part-time | Week 1 |
| DA Team Lead | [TBD] | Part-time prep | Week 0 (full Week 4) |
| Architecture Lead | [TBD] | 10h/week | Week 0+ |

#### Exit Criteria

- [ ] All 4 team leads confirmed and named
- [ ] All team members listed with availability
- [ ] Critical skills gaps identified with mitigation
- [ ] No blocking resource constraints

---

### Task 3: Delos-3903 - Execution Prerequisites Documentation

**Owner**: Architecture Lead
**Duration**: 2 days
**Priority**: P0 CRITICAL
**Status**: PENDING

#### Deliverables

| # | Deliverable | Location |
|---|-------------|----------|
| 1 | GPU_PREREQUISITES.md | .pm/ or Memory Bank |
| 2 | FK_PREREQUISITES.md | .pm/ or Memory Bank |
| 3 | ZQ_PREREQUISITES.md | .pm/ or Memory Bank |
| 4 | DA_PREREQUISITES.md | .pm/ or Memory Bank |
| 5 | CROSS_PLAN_COORDINATION.md | .pm/ or Memory Bank |

#### Specific Content per Plan

**GPU Prerequisites**:
- Gate 0 criteria (Delos-3894): ≥10x CPU, ≥1000 sigs/sec, <8GB memory
- Infrastructure: GPU hardware, drivers, library
- Team: 2-3 engineers with CUDA/JNI skills
- Fallback: CPU batch optimization path documented

**FK Prerequisites**:
- Data survey (Delos-3910): Determine if migration needed
- Baseline: Thoth DHT performance (throughput, latency)
- Ordering validation: Fireflies provides total ordering
- Team: 2-3 engineers with KERI/Fireflies knowledge

**ZQ Prerequisites**:
- Profiling (Delos-3898): 1 week extended analysis
- Baseline: Oracle query patterns documented
- Cache design (Delos-3899): Invalidation strategy
- Team: 1-2 engineers with Delphinius expertise

**DA Prerequisites**:
- DEFERRED until FK Phase 1 complete (Delos-3893 dependency)
- BFT safety analysis required (Delos-3900)
- Team: 1-2 engineers with BFT consensus expertise
- Pre-work: Review Fireflies ring topology

#### Cross-Plan Coordination Content

```markdown
## Fireflies Module Shared Ownership

Both GPU and FK plans integrate with Fireflies/Ethereal:
- GPU Phase 2: Ethereal.Adder signature verification hook
- FK Phase 1: Fireflies gossip layer for KERI events

CONFLICT RESOLUTION:
1. FK Phase 1 has priority (ordering must be proven first)
2. GPU Phase 2 waits for FK Phase 1 to complete, OR
3. If overlap required: Daily sync between teams, architecture lead arbitrates

## Escalation Process

| Severity | Response Time | Escalation Path |
|----------|---------------|-----------------|
| Critical | 4 hours | Architecture Lead -> Engineering Manager |
| High | 1 business day | Team Lead -> Architecture Lead |
| Medium | 2 business days | Within team |

## Communication Channels

- Slack: #delos-strategic-opportunities
- Daily standup: 10 AM, 15 min, all 4 team leads
- Weekly review: Friday 3 PM, 1 hour, full teams
```

#### Exit Criteria

- [ ] All 5 prerequisite documents created
- [ ] Cross-plan coordination documented
- [ ] Escalation process agreed upon
- [ ] All teams reviewed and acknowledged

---

### Task 4: Delos-3904 - Pre-Week-1 Validation Checklist

**Owner**: Architecture Lead
**Duration**: 1 day (Friday before Week 1)
**Priority**: P0 CRITICAL GATE
**Status**: PENDING

#### GO/NO-GO Checklist

**Section A: GPU Infrastructure (Delos-3901)**

| # | Criterion | Status |
|---|-----------|--------|
| A1 | GPU hardware provisioned | [ ] GO / [ ] NO-GO |
| A2 | Drivers installed and tested | [ ] GO / [ ] NO-GO |
| A3 | GPU library available and documented | [ ] GO / [ ] NO-GO |
| A4 | Test batch (1000 sigs) verified | [ ] GO / [ ] NO-GO |
| A5 | CPU fallback strategy documented | [ ] GO / [ ] NO-GO |
| **A-TOTAL** | **All A criteria met** | [ ] **GO** / [ ] **NO-GO** |

**Section B: Resource Availability (Delos-3902)**

| # | Criterion | Status |
|---|-----------|--------|
| B1 | GPU team available (2-3 engineers) | [ ] GO / [ ] NO-GO |
| B2 | FK team available (2-3 engineers) | [ ] GO / [ ] NO-GO |
| B3 | ZQ team available (1-2 engineers) | [ ] GO / [ ] NO-GO |
| B4 | All critical skills assessed | [ ] GO / [ ] NO-GO |
| B5 | Team leads confirmed | [ ] GO / [ ] NO-GO |
| **B-TOTAL** | **All B criteria met** | [ ] **GO** / [ ] **NO-GO** |

**Section C: Prerequisites Documentation (Delos-3903)**

| # | Criterion | Status |
|---|-----------|--------|
| C1 | GPU prerequisites documented | [ ] GO / [ ] NO-GO |
| C2 | FK prerequisites documented | [ ] GO / [ ] NO-GO |
| C3 | ZQ prerequisites documented | [ ] GO / [ ] NO-GO |
| C4 | DA prerequisites documented (deferred) | [ ] GO / [ ] NO-GO |
| C5 | Cross-plan coordination documented | [ ] GO / [ ] NO-GO |
| **C-TOTAL** | **All C criteria met** | [ ] **GO** / [ ] **NO-GO** |

**Section D: Week 1 Execution Readiness**

| # | Criterion | Status |
|---|-----------|--------|
| D1 | Daily standups scheduled | [ ] GO / [ ] NO-GO |
| D2 | Communication channels created | [ ] GO / [ ] NO-GO |
| D3 | Git workflow documented | [ ] GO / [ ] NO-GO |
| D4 | Week 1 kickoff scheduled (Delos-3909) | [ ] GO / [ ] NO-GO |
| D5 | Build is GREEN, all tests PASS | [ ] GO / [ ] NO-GO |
| **D-TOTAL** | **All D criteria met** | [ ] **GO** / [ ] **NO-GO** |

**FINAL DECISION**:

| Section | Status |
|---------|--------|
| A: GPU Infrastructure | [ ] GO / [ ] NO-GO |
| B: Resource Availability | [ ] GO / [ ] NO-GO |
| C: Prerequisites | [ ] GO / [ ] NO-GO |
| D: Execution Readiness | [ ] GO / [ ] NO-GO |
| **FINAL** | **[ ] PROCEED** / **[ ] DELAY 1 WEEK** |

**Sign-off**:
- Architecture Lead: _____________ Date: _______
- Engineering Manager: _____________ Date: _______

#### If NO-GO

1. Document specific failing criteria
2. Create remediation plan with 1-week timeline
3. Reschedule Week 1 to following Monday
4. Update all bead statuses (bd update <id> --status blocked)
5. Notify all teams via #delos-strategic-opportunities

---

## Week 1: Validation Phase

### Timeline: Monday-Friday of Week 1

### Parallel Execution Structure

```
WEEK 1 PARALLEL TRACKS:

Track 1: GPU Gate 0 (Delos-3894) ─────────────────────────────→ GO/NO-GO Friday
         │
         └─> If GO: Prepare Phase 0 infrastructure (Delos-3895)

Track 2: FK Phase 1A Start (Delos-3896) ──────────────────────→ 50% complete
         │
         └─> Data survey (Delos-3910) ────────────────────────→ Migration YES/NO

Track 3: ZQ Phase 1A Profiling (Delos-3898) ──────────────────→ Baseline established
```

---

### Track 1: GPU Gate 0 Validation (Delos-3894)

**Owner**: GPU Team Lead
**Duration**: Full Week 1
**Priority**: P1 CRITICAL GATE

#### Gate 0 Decision Criteria

**GO Criteria (ALL must be met)**:

| Criterion | Threshold | Measurement Method |
|-----------|-----------|-------------------|
| GPU vs CPU performance | ≥10x CPU single-sig | Benchmark: GPU batch 100 sigs vs CPU single |
| Absolute throughput | ≥1000 signatures/sec | Time 1000-sig batch, calculate sigs/sec |
| Memory constraint | <8GB for 1000-sig batch | Monitor with nvidia-smi during test |
| Library stability | Production-ready | Review docs, test error handling |
| Fallback viable | CPU batch documented | CPU batch estimate available |

**UNCERTAIN Criteria (Architecture Lead decides)**:

| Criterion | Threshold | Action |
|-----------|-----------|--------|
| GPU vs CPU | 7-9x CPU | Document tradeoff, leader decides |
| Absolute throughput | 700-1000 sigs/sec | Evaluate if acceptable for consensus |
| Memory | 8-12GB for 1000 sigs | May need smaller batches |

**NO-GO Criteria (ANY triggers NO-GO)**:

| Criterion | Threshold | Result |
|-----------|-----------|--------|
| GPU vs CPU | <7x CPU | Insufficient improvement |
| Absolute throughput | <700 sigs/sec | Below consensus impact threshold |
| Memory | >12GB for 1000 sigs | Impractical memory requirement |
| Library | Unstable/undocumented | Risk too high |
| Hardware | Unavailable | Cannot validate |

#### Day-by-Day Execution

**Monday (Gate 0 Setup)**:
- Review GPU infrastructure from Week 0 (Delos-3901)
- Verify GPU library is installed and accessible
- Create benchmark test harness:
  - CPU single-sig baseline
  - GPU batch test (100, 500, 1000 signatures)
- Run initial tests, document preliminary results

**Tuesday-Wednesday (Gate 0 Testing)**:
- Execute comprehensive benchmark suite:
  - Warm-up runs (discard first 5 iterations)
  - 10 runs each batch size, average results
  - Record: throughput, latency p50/p99, memory
- Test error handling:
  - What happens if GPU memory exhausted?
  - What happens if GPU fails mid-batch?
  - What happens with invalid signatures?
- Document library stability assessment

**Thursday (Gate 0 Analysis)**:
- Compile all benchmark results
- Calculate: GPU speedup vs CPU baseline
- Evaluate against GO/UNCERTAIN/NO-GO thresholds
- Prepare Gate 0 decision document:
  - GPU_GATE_0_RESULTS.md
  - Include all raw data and analysis
  - Recommendation: GO / UNCERTAIN / NO-GO

**Friday (Gate 0 Decision)**:
- Present Gate 0 results to Architecture Lead
- Make GO/NO-GO decision:
  - **If GO**: Proceed to GPU Phase 0 (Delos-3895) in Week 2
  - **If UNCERTAIN**: Architecture Lead makes final call
  - **If NO-GO**: Pivot to CPU batch optimization, archive GPU plan
- Broadcast decision to all teams
- Update bead status:
  - GO: `bd update Delos-3894 --status closed` + `bd update Delos-3890.1 --status in_progress`
  - NO-GO: `bd update Delos-3894 --status closed` + `bd update Delos-3890 --status blocked`

#### Contingency: If NO-GO

1. **Immediate Actions**:
   - Document failure reason in GPU_GATE_0_NO_GO.md
   - Archive GPU plan (Delos-3890 epic)
   - Reallocate GPU team resources

2. **Pivot Options**:
   - **Option A**: CPU Batch Optimization (2-3 weeks)
     - SIMD vectorization for Ed25519
     - Estimated 2-5x improvement over single-sig
     - Lower effort, lower reward
   - **Option B**: Abandon signature acceleration
     - Focus on other strategic opportunities
     - GPU team joins FK or ZQ teams

3. **Decision Authority**: Architecture Lead + Engineering Manager

---

### Track 2: FK Phase 1A Start (Delos-3896)

**Owner**: FK Team Lead
**Duration**: Week 1-2 (50% complete by end of Week 1)
**Priority**: P1 PRIMARY CRITICAL PATH

#### Week 1 Objectives

**1A-0: Data Survey (Delos-3910)** - NEW TASK (1-2 days)
- Purpose: Determine if existing Thoth KERL data exists
- Actions:
  1. Check Thoth test fixtures for data schema
  2. Query production/staging for KERL records
  3. Estimate data volume if migration needed
- Outcome:
  - **Greenfield**: No existing data -> Skip Phase 1D migration (saves 1 week)
  - **Migration Required**: Existing data -> Phase 1D is CRITICAL

**1A-1: Establish DHT Performance Baseline** (2 days)
- Benchmark current Thoth throughput:
  - Event append latency (p50, p99, p999)
  - Query throughput (events/sec)
  - Memory footprint
- Document in DHT_BASELINE.md
- Set target: FirefliesKerlService must achieve ≥80% of baseline

**1A-2: Validate Fireflies Ordering** (2 days)
- Study Fireflies View.java ordering guarantees
- Map to KERI key event ordering requirements:
  - Total ordering required for key rotations
  - Causal ordering for witness receipts
- Document findings in FIREFLIES_ORDERING_ANALYSIS.md
- **Gate Check**: If ordering insufficient, escalate immediately

#### Deliverables by End of Week 1

| # | Deliverable | Status |
|---|-------------|--------|
| 1 | Data survey complete (Delos-3910) | Migration YES/NO determined |
| 2 | DHT baseline documented | Concrete numbers for comparison |
| 3 | Fireflies ordering analysis | 50% complete (continue Week 2) |
| 4 | KERI requirements draft | Initial domain mapping started |

---

### Track 3: ZQ Phase 1A Profiling (Delos-3898)

**Owner**: ZQ Team Lead
**Duration**: Full Week 1
**Priority**: P1

#### Week 1 Objectives

**Extended Profiling (1 full week)**:
- Profile current Oracle.query() behavior:
  - Instrument DirectOracle with query logging
  - Capture 100K+ queries over realistic workload
  - Record: query type, parameters, latency, result count

**Day-by-Day**:

| Day | Activity | Deliverable |
|-----|----------|-------------|
| Mon | Set up profiling infrastructure | OracleProfiler.java instrumentation |
| Tue | Run production-like workload | Query capture (target 50K queries) |
| Wed | Continue capture, begin analysis | Query capture (target 100K queries) |
| Thu | Analyze patterns, identify top 20 | Pattern analysis spreadsheet |
| Fri | Document baseline, validate targets | CURRENT_BEHAVIOR_PROFILE.md |

#### Profiling Metrics to Capture

| Metric | Target Capture | Purpose |
|--------|----------------|---------|
| Query frequency by type | Per-type counts | Identify caching opportunities |
| Query latency (p50, p99) | Per-type percentiles | Identify optimization targets |
| Result cardinality | Average, max | Understand selectivity |
| Database round-trips | Per-operation count | Target for reduction |
| Memory allocation | Per-query bytes | Identify memory pressure |

#### Deliverables by End of Week 1

| # | Deliverable | Status |
|---|-------------|--------|
| 1 | Profiling data collected (100K+ queries) | Raw data available |
| 2 | Top 20 query patterns identified | Ranked by frequency/latency |
| 3 | Baseline metrics established | Query count, latency documented |
| 4 | Optimization targets validated | 30-50% reduction achievable? |
| 5 | CURRENT_BEHAVIOR_PROFILE.md | Complete profiling report |

---

## Weeks 2-3: Phase 1A Parallel Execution

### Timeline: Week 2 Monday through Week 3 Friday

### Parallel Execution Structure

```
WEEKS 2-3 PARALLEL TRACKS:

Track 1: GPU Phase 0 Infrastructure (if Gate 0 GO)
         ├─ Delos-3895: GPU infrastructure setup (1 week)
         └─ Delos-3890.1: GPU Phase 1 research (starts Week 2-3)

Track 2: FK Phase 1 Completion
         ├─ Delos-3896: KERI requirements (complete by Week 2)
         └─ Delos-3897: Migration strategy (1 week, CRITICAL)

Track 3: ZQ Phase 1 Continuation
         ├─ Delos-3898: Complete baseline (by Week 2 mid)
         └─ Delos-3899: Cache invalidation design (by Week 3)

Track 4: DA Pre-Work (BLOCKED)
         └─ Pre-study only, no active development
```

---

### Track 2: FK Phase 1 Completion (Weeks 2-3) - PRIMARY

#### Week 2: FK Phase 1A Completion (Delos-3896)

**Objectives**:
1. Complete Fireflies ordering validation
2. Complete KERI domain mapping
3. Document all integration points

**Deliverables**:

| # | Deliverable | Source Task |
|---|-------------|-------------|
| 1 | KERI_REQUIREMENTS.md | PLAN-FK-1A-1 |
| 2 | FIREFLIES_KERI_MAPPING.md | PLAN-FK-1A-2 |
| 3 | FIREFLIES_ORDERING_VALIDATION.md | PLAN-FK-1A-2 |
| 4 | INTEGRATION_POINTS.md | PLAN-FK-1A-3 |

**Phase 1A Exit Gate (Delos-3906)**:
- [ ] Fireflies ordering validated to satisfy KERI requirements
- [ ] Domain mapping complete and reviewed
- [ ] Performance baseline established
- [ ] No architectural blockers identified

#### Weeks 2-3: FK Phase 1D Migration Strategy (Delos-3897) - CRITICAL PATH

**Added by Audit - CRITICAL FOR FK SUCCESS**

**Objectives**:
1. Analyze existing Thoth KERL data (if any from Delos-3910)
2. Design dual-mode migration strategy
3. Define rollback procedures
4. Document backward compatibility

**Deliverables**:

| # | Deliverable | Description |
|---|-------------|-------------|
| 1 | THOTH_DATA_ANALYSIS.md | Current schema, volume, access patterns |
| 2 | MIGRATION_STRATEGY.md | Dual-mode phases A-D |
| 3 | ROLLBACK_PROCEDURES.md | Automatic rollback triggers |
| 4 | COMPATIBILITY_LAYER_DESIGN.md | Adapter pattern for existing code |

**Migration Strategy Overview**:

```
Phase A: Dual Operation (Week N)
├─ Both Thoth and Fireflies operational
├─ Reads query both (Fireflies first, Thoth fallback)
└─ Writes go to both

Phase B: Gradual Migration (Week N+1)
├─ Migrate existing KELs from Thoth to Fireflies
├─ Batch migration with consistency checks
└─ Progress tracking and validation

Phase C: Fireflies Primary (Week N+2)
├─ Fireflies is primary for reads
├─ Thoth is fallback only
└─ Write traffic fully to Fireflies

Phase D: Thoth Decommission (Week N+3)
├─ Verify all KELs migrated
├─ Shutdown Thoth writes
└─ Eventually remove Thoth dependency
```

**Rollback Triggers**:
- Consistency mismatch detected (Fireflies != Thoth)
- Fireflies latency >2x Thoth baseline
- Error rate >1% on Fireflies operations
- Manual trigger by operations team

---

## Success Metrics

### Week 0 Success Criteria

| Metric | Target | Validation |
|--------|--------|------------|
| GPU infrastructure ready | Hardware + drivers working | Test run successful |
| Teams confirmed | All 4 teams named | Resource spreadsheet complete |
| Prerequisites documented | All 5 docs created | .pm/ files exist |
| Final GO decision | All sections GO | Delos-3904 signed |

### Week 1 Success Criteria

| Metric | Target | Validation |
|--------|--------|------------|
| GPU Gate 0 decision | GO, UNCERTAIN, or NO-GO | Documented decision |
| FK Phase 1A progress | 50% complete | Data survey + baseline done |
| ZQ profiling complete | 100K queries analyzed | Profile report delivered |
| DA pre-work started | Literature review begun | Team prepared for Week 4 |

### Weeks 2-3 Success Criteria

| Metric | Target | Validation |
|--------|--------|------------|
| GPU Phase 0/1 progress | If GO: 70% Phase 1 complete | Deliverables reviewed |
| FK Phase 1 complete | Exit gate passed | Delos-3906 signed |
| FK migration strategy | Documented and reviewed | MIGRATION_STRATEGY.md approved |
| ZQ Phase 1B complete | Cache design reviewed | Security audit passed |
| DA unblocked | FK exit gate passed | Ready for Week 4 start |

### Overall Phase 1 Success

| Criterion | Measurement |
|-----------|-------------|
| All Phase 1 exit gates passed | 4/4 plans at Phase 2 ready |
| No critical blockers | Zero escalations unresolved |
| Timeline maintained | Within 1 week of plan |
| Team confidence >80% | Survey all leads |

---

## Risk Mitigation

### Tier 1: Critical Risks (Weeks 0-1)

| Risk | Trigger | Impact | Mitigation | Owner |
|------|---------|--------|------------|-------|
| GPU library unavailable | Gate 0 NO-GO | GPU plan stops | CPU batch fallback | GPU Lead |
| GPU hardware unavailable | Week 0 provisioning fails | Gate 0 cannot proceed | Backup cloud provider | GPU Lead |
| FK ordering unproven | Week 1 validation fails | FK plan at risk | Alternative KERL backend | FK Lead |
| Resource conflict | Teams not available | All plans delayed | Early confirmation | PM |

### Tier 2: High Risks (Weeks 2-3)

| Risk | Trigger | Impact | Mitigation | Owner |
|------|---------|--------|------------|-------|
| FK migration complexity | Thoth data volume 10x expected | Phase 1D extends 2+ weeks | Phased migration, scope negotiation | FK Lead |
| ZQ cache correctness | Security review fails | Design rework needed | Conservative invalidation strategy | ZQ Lead |
| Fireflies module conflict | GPU + FK both need Ethereal | Integration delays | FK priority, daily sync | Arch Lead |

### Tier 3: Medium Risks (Ongoing)

| Risk | Trigger | Impact | Mitigation | Owner |
|------|---------|--------|------------|-------|
| Timeline slip | Phase 1 takes 25% longer | Weeks 2-3 extend to Week 4 | Built-in 20% buffer | PM |
| Skills gap | Team lacks domain expertise | Slower progress | Training, pair programming | Team Leads |
| Communication breakdown | Daily standups skipped | Hidden blockers | Enforce cadence | PM |

---

## Team Allocation

### Resource Summary

| Team | Size | Start | Duration | Key Skills |
|------|------|-------|----------|------------|
| GPU | 2-3 | Week 0 | 14 weeks | CUDA, JNI, Ed25519 |
| FK | 2-3 | Week 0 | 14 weeks | KERI, Fireflies, BFT |
| ZQ | 1-2 | Week 0 | 8 weeks | Delphinius, RBAC, SQL |
| DA | 1-2 | Week 4 | 8 weeks | BFT, ring topology |

### Weekly Allocation (Hours)

| Role | Week 0 | Week 1 | Week 2 | Week 3 |
|------|--------|--------|--------|--------|
| GPU Lead | 40 | 40 | 40 | 40 |
| GPU Eng (2) | 0 | 80 | 80 | 80 |
| FK Lead | 40 | 40 | 40 | 40 |
| FK Eng (2) | 0 | 80 | 80 | 80 |
| ZQ Lead | 40 | 40 | 40 | 40 |
| ZQ Eng (1) | 0 | 40 | 40 | 40 |
| DA Lead | 20 | 20 | 20 | 20 |
| Arch Lead | 20 | 20 | 20 | 20 |
| **Total** | **160** | **360** | **360** | **360** |

---

## Bead Tracking

### Phase 1 Beads Summary

| Bead ID | Title | Owner | Week | Status |
|---------|-------|-------|------|--------|
| Delos-3901 | GPU Infrastructure | GPU Lead | 0 | IN PROGRESS |
| Delos-3902 | Resource Validation | PM | 0 | PENDING |
| Delos-3903 | Prerequisites Docs | Arch Lead | 0 | PENDING |
| Delos-3904 | Pre-Week-1 Checklist | Arch Lead | 0 | PENDING |
| Delos-3894 | GPU Gate 0 | GPU Lead | 1 | PENDING |
| Delos-3910 | FK Data Survey | FK Lead | 1 | PENDING |
| Delos-3896 | FK Phase 1A | FK Lead | 1-2 | PENDING |
| Delos-3898 | ZQ Phase 1A | ZQ Lead | 1 | PENDING |
| Delos-3895 | GPU Phase 0 | GPU Lead | 2 | PENDING |
| Delos-3897 | FK Phase 1D | FK Lead | 2-3 | PENDING |
| Delos-3899 | ZQ Cache Design | ZQ Lead | 2-3 | PENDING |
| Delos-3890.1 | GPU Phase 1 | GPU Lead | 2-3 | PENDING |

---

## Communication Plan

### Daily (Async-First)
- **Standup**: 10 AM, 15 min, Slack #strategic-execution
- **Content**: What was done, blockers, help needed

### Weekly (Friday 3 PM, 1 hour)
- **Full team review**: Week summary, gate decisions, next week planning
- **Risk review**: Any Tier 1/2 risks materialized?
- **Gate status**: Upcoming gate due dates, readiness assessment

### On-Demand (Escalation)
1. Blocker → Team lead (immediate)
2. Team lead → PM (within 4 hours)
3. PM → Architecture lead (within 8 hours)
4. Architecture lead → Executive sponsor (within 24 hours)

### Slack Channels
- `#strategic-execution` (main coordination)
- `#fireflies-keri` (FK team)
- `#gpu-verification` (GPU team)
- `#zanzibar-query` (ZQ team)

---

## Document Control

| Version | Date | Status |
|---------|------|--------|
| 1.0 | 2026-01-18 | APPROVED (85% confidence) |

**Status**: READY FOR EXECUTION
**Build**: GREEN
**Teams**: Standing by
**Next Checkpoint**: Friday Week 0 (GO/NO-GO Checklist)

---

**EXECUTION READY**

Delos-3901 (GPU Infrastructure Provisioning) is IN PROGRESS.
All P0 tasks defined and ready for execution.
Week 1 FK Phase 1A ready to launch Monday.

🚀 **Let's build this!**
