---
scope: CHOAM.java threading fixes
kind: system
content_hash: 62df6903ec66a4b68da2e66ad4ae92d9
---

# Hypothesis: CHOAM Incremental Lock-Based Fixes

Fix each CHOAM race condition independently with targeted locking:

**Delos-qq9 (Block Processing Race):**
- Add ReentrantLock around consume() critical section
- Identify minimum lock scope (validation + acceptance)
- Use tryLock with timeout to prevent deadlock

**Delos-0ct (View Change Linearization):**
- Add AtomicReference for view state
- Use CAS for view transitions
- Add epoch counter for fencing

**Delos-an5 (Silent Consumer Failure):**
- Wrap consumer in try-catch with logging
- Add ScheduledExecutorService for health check
- Implement restart with exponential backoff

**Delos-yl3 (Bounded Queue):**
- Replace PriorityBlockingQueue with bounded variant
- Add capacity limit (configurable, default 1000)
- Log dropped blocks with metrics

**Method:**
1. Fix each in isolation with dedicated test
2. Run full CHOAM test suite after each fix
3. Integration test all 4 together
4. Benefits: Low risk, easy rollback per fix

**Dependencies:** None between fixes (can parallel)

## Rationale
{"anomaly": "CHOAM has 4 race conditions", "approach": "Incremental targeted fixes minimize blast radius", "alternatives_rejected": ["Big bang refactor - too risky", "Ignore some - all are P0"]}