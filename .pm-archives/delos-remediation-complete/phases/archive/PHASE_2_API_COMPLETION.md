# Phase 2: API Completion

## Phase Overview

**Status**: NOT STARTED
**Priority**: P2 - MEDIUM-HIGH
**Target Duration**: Week 3-4
**Dependencies**: Phase 0 complete

---

## Objectives

1. Complete BlockDate implementation or remove if redundant
2. Implement Watch API for Delphinius change notifications
3. Enforce witness thresholds in KERL interface
4. Implement content-change checks for Delphinius
5. Implement proper Zookie protocol (replace raw timestamps)

---

## Task 1: BlockDate Implementation

### Description

The BlockDate class has a TODO in the constructor and simply delegates to `java.util.Date`, which may not be deterministic in a replicated environment.

### Location

**File**: `sql-state/src/main/java/com/hellblazer/delos/sql/BlockDate.java`
**Lines**: 19-21

### Current Code

```java
// TODO: Implement properly
public BlockDate(long millis) {
    super(millis);  // Delegates to java.util.Date
}
```

### Decision Required

1. **Complete implementation**: Make BlockDate deterministic using BlockClock
2. **Remove if redundant**: If BlockClock.toNanos() is sufficient for all use cases

### Analysis Needed

- How is BlockDate used in the codebase?
- Is java.util.Date behavior acceptable?
- Does BlockClock provide all needed functionality?

### Test Strategy

```java
@Test
void shouldBeDeterministic() {
    var clock = new BlockClock();
    var date1 = new BlockDate(clock);
    var date2 = new BlockDate(clock);

    // Same clock state should produce identical dates
    assertEquals(date1, date2);
}
```

### Bead

- ID: TBD
- Type: task
- Priority: 3 (medium)

---

## Task 2: Watch API for Delphinius

### Description

Zanzibar provides a Watch API for clients to receive notifications when ACLs change. This is not implemented in Delphinius.

### Location

**File**: `delphinius/src/main/java/com/hellblazer/delos/delphinius/Oracle.java`

### Required Implementation

```java
public interface Oracle {
    // Existing methods...

    /**
     * Watch for changes to relations matching the filter.
     * @param filter Filter for which changes to watch
     * @return Stream of change events
     */
    Stream<RelationChange> watch(RelationFilter filter);

    /**
     * Watch for changes affecting a specific subject.
     * @param subject The subject to watch
     * @return Stream of permission changes
     */
    Stream<PermissionChange> watchSubject(Subject subject);
}

public record RelationChange(
    ChangeType type,  // CREATE, UPDATE, DELETE
    Relation relation,
    ULong timestamp
) {}
```

### Paper Reference

Zanzibar paper describes Watch API for:
- Client-side caching invalidation
- Audit logging
- Real-time permission updates

### Test Strategy

```java
@Test
void shouldNotifyOnRelationChange() {
    var watcher = oracle.watch(RelationFilter.all());

    // Create relation
    oracle.add(new Relation(subject, object, permission));

    // Verify change notification received
    var change = watcher.findFirst().orElseThrow();
    assertEquals(ChangeType.CREATE, change.type());
}
```

### Bead

- ID: TBD
- Type: feature
- Priority: 2 (high)

---

## Task 3: Witness Threshold Enforcement

### Description

KERI specifies witness threshold requirements for key events. The KERL interface does not enforce these thresholds.

### Location

**File**: `stereotomy/src/main/java/com/hellblazer/delos/stereotomy/KERL.java`

### Required Implementation

```java
public interface KERL {
    // Add threshold configuration
    void setWitnessThreshold(int threshold);

    // Existing methods should enforce threshold
    void append(KeyEvent event) throws InsufficientWitnessesException;
}
```

### Paper Reference

KERI paper specifies:
- Witness threshold for event validation
- Requirements for key rotation
- Recovery procedures

### Test Strategy

```java
@Test
void shouldRejectEventWithInsufficientWitnesses() {
    kerl.setWitnessThreshold(3);

    var event = createEventWithWitnesses(2);  // Only 2 witnesses

    assertThrows(InsufficientWitnessesException.class,
        () -> kerl.append(event));
}
```

### Bead

- ID: TBD
- Type: feature
- Priority: 3 (medium)

---

## Task 4: Content-Change Checks

### Description

Delphinius should support checks that consider whether content has changed since a specific timestamp, preventing stale authorization decisions.

### Location

**File**: `delphinius/src/main/java/com/hellblazer/delos/delphinius/Oracle.java`

### Required Implementation

```java
public interface Oracle {
    // Existing check method
    boolean check(Assertion assertion);

    // New: Check with content-change awareness
    CheckResult checkWithContentAwareness(
        Assertion assertion,
        ULong contentVersion
    );
}

public record CheckResult(
    boolean allowed,
    boolean contentChanged,
    ULong currentContentVersion
) {}
```

### Paper Reference

Zanzibar uses Zookies to track content versions and prevent stale authorization.

### Test Strategy

```java
@Test
void shouldDetectContentChange() {
    var version1 = oracle.getContentVersion(object);

    // Modify content
    modifyContent(object);

    var result = oracle.checkWithContentAwareness(
        assertion, version1);

    assertTrue(result.contentChanged());
}
```

### Bead

- ID: TBD
- Type: feature
- Priority: 3 (medium)

---

## Task 5: Zookie Protocol

### Description

Current implementation uses raw timestamps for consistency. Zanzibar uses Zookies - opaque tokens that encode consistency requirements.

### Location

**File**: `delphinius/src/main/java/com/hellblazer/delos/delphinius/Oracle.java`

### Current State

```java
// Raw timestamp usage
public boolean check(Assertion assertion, ULong timestamp);
```

### Required Implementation

```java
// Opaque Zookie token
public record Zookie(
    byte[] token  // Encoded timestamp + version + signature
) {
    public static Zookie now() { ... }
    public static Zookie at(ULong timestamp) { ... }
}

public interface Oracle {
    // New Zookie-based API
    CheckResponse check(Assertion assertion, Zookie zookie);
    Zookie getZookie(Object object);  // Get consistency token for object
}
```

### Benefits

- Clients cannot forge timestamps
- Encapsulates consistency semantics
- Matches Zanzibar's external API

### Test Strategy

```java
@Test
void shouldRejectInvalidZookie() {
    var forgedZookie = new Zookie(randomBytes());

    assertThrows(InvalidZookieException.class,
        () -> oracle.check(assertion, forgedZookie));
}

@Test
void shouldAcceptValidZookie() {
    var zookie = oracle.getZookie(object);
    var result = oracle.check(assertion, zookie);

    assertNotNull(result);
}
```

### Bead

- ID: TBD
- Type: feature
- Priority: 3 (medium)

---

## Definition of Done

### Per Task

- [ ] Design documented
- [ ] Tests written first
- [ ] Implementation complete
- [ ] Integration tests pass
- [ ] JavaDoc complete
- [ ] Code reviewed
- [ ] Bead closed

### Phase Complete

- [ ] All 5 tasks complete
- [ ] Full test suite passes
- [ ] API documentation updated
- [ ] EXECUTION_STATE.md updated
- [ ] Ready for Phase 3

---

## Estimated Effort

| Task | Research | Design | Implementation | Review | Total |
|------|----------|--------|----------------|--------|-------|
| BlockDate | 1h | 1h | 2h | 1h | 5h |
| Watch API | 2h | 3h | 8h | 2h | 15h |
| Witness threshold | 1h | 2h | 4h | 1h | 8h |
| Content-change | 1h | 2h | 4h | 1h | 8h |
| Zookie protocol | 2h | 3h | 6h | 2h | 13h |
| **Total** | **7h** | **11h** | **24h** | **7h** | **49h** |

---

## Dependencies

### Blocking

This phase blocked until Phase 0 complete.

### Can Run In Parallel

- Phase 1 (Security) - independent work

### Blocks

- Phase 3 (Documentation) - API docs depend on implementation

### Required Resources

- java-architect-planner for API design
- java-developer for implementation
- code-review-expert for review
- Zanzibar paper reference

---

## Risk Summary

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| API breaking changes | HIGH | HIGH | Version carefully |
| Complex Watch implementation | MEDIUM | MEDIUM | Incremental approach |
| Performance overhead | MEDIUM | MEDIUM | Benchmark |

---

*Last Updated: 2025-12-30*
