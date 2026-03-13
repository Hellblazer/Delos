# Phase 2 P1 Production Stubs — Design

**Beads**: Delos-izm.2.1 (ShardedKERL appendValidations), Delos-izm.2.2 (Enforcement Mode)
**RDR**: RDR-001 (accepted)
**Date**: 2026-03-13

## 2.1 — ShardedKERL appendValidations Stub

### Problem

`ShardedKERL.appendValidations` returns `null` with `// TODO Auto-generated method stub`. Active callers in Publisher, DirectPublisher, KerlSpace, and DemesneKERLServer silently drop validations.

### Design

Delegate to `UniKERL.appendValidations(DSLContext, coordinates, validations)` — the static method on the superclass that already knows how to persist validations via JOOQ/DSL. ShardedKERL extends UniKERL and has a Connection, so we can create a DSLContext and call the existing implementation.

Unlike other ShardedKERL methods that go through `Mutator.call()` for state machine replication, validations are KERL metadata (not state-machine transitions), so direct DSL persistence is appropriate.

### Files to Modify

- `model/src/main/java/com/hellblazer/delos/model/stereotomy/ShardedKERL.java` — implement appendValidations
- New: test class for ShardedKERL.appendValidations

## 2.2 — Enforcement Mode Implementation

### Problem

`ValidatingCombineTransitions.handleViolation` (line 238) logs violations but never rejects them. The `FeatureFlags` javadoc documents three modes (LOG_ONLY, ENFORCE, METRICS_ONLY) but only LOG_ONLY is implemented.

### Design

Add a `ValidationMode` enum to FeatureFlags or as a standalone type. Extend `handleViolation` to switch on mode:
- `LOG_ONLY` (default): Log violation, continue (current behavior)
- `ENFORCE`: Log violation, throw `IllegalStateException`
- `METRICS_ONLY`: Record counter only, no logging

Mode controlled via system property: `-Dfeature.state.validation.mode=ENFORCE`

### Files to Modify

- `choam/src/main/java/com/hellblazer/delos/choam/support/ValidatingCombineTransitions.java` — implement enforcement in handleViolation
- `choam/src/main/java/com/hellblazer/delos/choam/FeatureFlags.java` — add ValidationMode or extend STATE_VALIDATION
- New: test class for enforcement mode behavior

## Success Criteria

- [ ] ShardedKERL.appendValidations persists validations (not null stub)
- [ ] Enforcement mode rejects invalid transitions when ENFORCE
- [ ] LOG_ONLY mode unchanged (default behavior preserved)
- [ ] All existing tests pass in model and choam modules
