---
scope: CHOAM.java complete threading redesign
kind: system
content_hash: c7f3cb7c9959d6b6afdc82de049ef9dc
---

# Hypothesis: CHOAM Unified Threading Model Refactor

Refactor CHOAM threading model holistically rather than patching individual races:

**Core Changes:**
1. Extract BlockProcessor class with single-threaded event loop
2. Extract ViewStateManager with immutable view snapshots
3. Replace shared mutable state with message passing
4. Use Disruptor pattern or actor-style for block processing

**Implementation:**
```java
// New: BlockProcessor with dedicated thread
class BlockProcessor {
    private final ExecutorService processor = 
        Executors.newSingleThreadExecutor();
    
    void submit(HashedCertifiedBlock block) {
        processor.execute(() -> processBlock(block));
    }
    
    private void processBlock(HashedCertifiedBlock block) {
        // All block processing in single thread
        // No races by construction
    }
}

// New: ViewStateManager with immutable views
class ViewStateManager {
    private final AtomicReference<ViewState> current;
    
    ViewState transition(ViewChange change) {
        return current.updateAndGet(v -> v.apply(change));
    }
}
```

**Benefits:**
- Eliminates race conditions by design
- Easier to reason about concurrency
- Prepares for Phase 3 CHOAM decomposition

**Risks:**
- Larger change scope
- More testing required
- May introduce performance regression

## Rationale
{"anomaly": "4 race conditions suggest systemic threading issue", "approach": "Fix root cause rather than symptoms", "alternatives_rejected": ["Patch individual races - may miss others", "Ignore until Phase 3 - too risky"]}