# Delphinius Runtime Rewrites Design Document

**Bead**: Delos-868.20
**Status**: Draft
**Author**: Claude (AI Assistant)
**Date**: 2025-12-31

## 1. Overview

This document proposes adding Zanzibar-style userset rewrite rules to Delphinius while maintaining backward compatibility with the existing pre-computed transitive closure architecture.

### 1.1 Problem Statement

Current Delphinius limitations:
- No support for "concentric relations" (viewer includes editor includes owner)
- Cannot express "inherit ACL from parent object" without explicit Edge mappings
- Relation inheritance via Edge table is inflexible and storage-heavy

Zanzibar provides these capabilities through runtime rewrite rules evaluated at check time.

### 1.2 Goals

1. Enable `computed_userset` - relations that include other relations
2. Enable `tuple_to_userset` - follow object hierarchy for permission inheritance
3. Support set operators (union, intersection, exclusion)
4. Maintain O(1) check performance for simple cases
5. Backward compatible - existing code continues to work

### 1.3 Non-Goals

- Full Leopard-style denormalized indexing (future work)
- Replacing Edge table entirely (keep for group memberships)
- Distributed caching infrastructure

## 2. Background

### 2.1 Current Architecture

Delphinius uses pre-computed transitive closure stored in the EDGE table:

```
EDGE(id, type, parent, child, transitive, mark)
- type: 's' (subject), 'o' (object), 'r' (relation)
- transitive: true if this is a derived edge
```

**check(assertion)** workflow:
1. Resolve subject/object to IDs
2. Expand subject via EDGE (find all groups subject belongs to)
3. Expand object via EDGE (find all parent objects)
4. Check if any (expanded_subject, expanded_object) pair has assertion

**Pros**: O(1) check via pre-computed closure
**Cons**: O(n^2) storage for dense graphs, write-heavy on hierarchy changes

### 2.2 Zanzibar Runtime Rewrites

Zanzibar namespace configuration defines relations with rewrite rules:

```protobuf
relation {
  name: "viewer"
  userset_rewrite {
    union {
      child { _this {} }
      child { computed_userset { relation: "editor" } }
      child { tuple_to_userset {
        tupleset { relation: "parent" }
        computed_userset { relation: "viewer" }
      } }
    }
  }
}
```

**Primitives**:
- `_this`: Direct relation tuples (default)
- `computed_userset`: Reference another relation on same object
- `tuple_to_userset`: Follow relation to another object, then check there

**Operators**: union (OR), intersection (AND), exclusion (AND NOT)

## 3. Proposed Design

### 3.1 Hybrid Approach

Keep pre-computed TC for Subject and Object hierarchies (group memberships work well). Add runtime rewrites only for Relation configuration.

**Rationale**:
- Group memberships benefit from pre-computed TC (frequent checks, stable structure)
- Relation rewrites are typically shallow (2-3 levels) and benefit from flexibility
- Minimizes architectural disruption

### 3.2 Data Model

New table for relation configuration:

```sql
CREATE TABLE RELATION_CONFIG (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    relation_id BIGINT NOT NULL REFERENCES RELATION(id),
    rewrite_type VARCHAR(32) NOT NULL,
    -- For computed_userset: relation to include
    target_relation_id BIGINT REFERENCES RELATION(id),
    -- For tuple_to_userset: relation to follow
    tupleset_relation_id BIGINT REFERENCES RELATION(id),
    -- For set operators: operator type
    set_operator VARCHAR(16),
    -- Ordering for union/intersection evaluation
    priority INT DEFAULT 0,

    CONSTRAINT unique_rewrite UNIQUE (relation_id, rewrite_type, target_relation_id, tupleset_relation_id)
);

-- Index for relation config lookups (hot path)
CREATE INDEX idx_relation_config_relation ON RELATION_CONFIG(relation_id);
```

### 3.3 Rewrite Types

| Type | Description | Example |
|------|-------------|---------|
| `COMPUTED_USERSET` | Include another relation on same object | viewer includes editor |
| `TUPLE_TO_USERSET` | Follow tupleset, check computed relation | doc viewer = parent folder viewer |
| `UNION` | Any child must match (default) | viewer = editor OR owner |
| `INTERSECTION` | All children must match | can_publish = editor AND reviewer |
| `EXCLUSION` | Must match A but not B | viewer = shared_with EXCEPT blocked |

### 3.4 Check Algorithm

```java
public boolean checkWithRewrites(Subject subject, Object object) throws SQLException {
    var relationConfig = loadRelationConfig(object.relation());

    // Fast path: no rewrite rules, use existing TC-based check
    if (relationConfig.isEmpty()) {
        return check(subject.assertion(object));
    }

    // Track visited to prevent cycles
    var visited = new HashSet<String>();
    return evaluateRewrite(subject, object, relationConfig, visited, 0);
}

private boolean evaluateRewrite(Subject subject, Object object,
                                 List<RelationConfig> configs,
                                 Set<String> visited, int depth) {
    // Depth limit to prevent runaway evaluation
    if (depth > MAX_REWRITE_DEPTH) return false;

    // Cycle detection
    var key = subject.id() + ":" + object.id() + "#" + object.relation().id();
    if (!visited.add(key)) return false;

    // _this: check direct assertion (always included implicitly)
    if (check(subject.assertion(object))) return true;

    for (var config : configs) {
        switch (config.rewriteType()) {
            case COMPUTED_USERSET:
                // Check target relation on same object
                var targetObject = object.withRelation(config.targetRelation());
                if (checkWithRewrites(subject, targetObject)) return true;
                break;

            case TUPLE_TO_USERSET:
                // Follow tupleset relation to find parent objects
                var parents = getParentObjects(object, config.tuplesetRelation());
                for (var parent : parents) {
                    var targetObj = parent.withRelation(config.computedRelation());
                    if (checkWithRewrites(subject, targetObj)) return true;
                }
                break;

            case INTERSECTION:
                // All referenced relations must be satisfied
                if (!checkAllRelations(subject, object, config.targetRelations())) {
                    return false;
                }
                break;

            case EXCLUSION:
                // Must satisfy include but not exclude
                var includeObj = object.withRelation(config.includeRelation());
                var excludeObj = object.withRelation(config.excludeRelation());
                if (checkWithRewrites(subject, includeObj) &&
                    !checkWithRewrites(subject, excludeObj)) {
                    return true;
                }
                break;
        }
    }

    return false;
}
```

### 3.5 API Additions

```java
// Oracle interface additions
interface Oracle {
    // ... existing methods ...

    // Configure relation to include another (computed_userset)
    CompletableFuture<ULong> includeRelation(Relation target, Relation source);

    // Configure relation to follow hierarchy (tuple_to_userset)
    CompletableFuture<ULong> followRelation(Relation target,
                                            Relation tupleset,
                                            Relation computed);

    // Intersection: all must match
    CompletableFuture<ULong> requireAll(Relation target, Relation... required);

    // Exclusion: must match first but not second
    CompletableFuture<ULong> excludeFrom(Relation target,
                                          Relation include,
                                          Relation exclude);

    // Query configuration
    List<RelationConfig> getRelationConfig(Relation relation);

    // Remove configuration
    CompletableFuture<ULong> removeRelationConfig(Relation relation,
                                                   RewriteType type,
                                                   Relation target);
}

// Rewrite type enumeration
enum RewriteType {
    COMPUTED_USERSET,
    TUPLE_TO_USERSET,
    UNION,
    INTERSECTION,
    EXCLUSION
}

// Configuration record
record RelationConfig(
    long id,
    Relation relation,
    RewriteType rewriteType,
    Relation targetRelation,
    Relation tuplesetRelation,
    Relation computedRelation,
    int priority
) {}
```

## 4. Usage Examples

### 4.1 Concentric Relations

```java
// Setup: owner ⊂ editor ⊂ viewer
var docs = Oracle.namespace("docs");
var owner = docs.relation("owner");
var editor = docs.relation("editor");
var viewer = docs.relation("viewer");

// editor includes owner
oracle.includeRelation(editor, owner);

// viewer includes editor (transitively includes owner)
oracle.includeRelation(viewer, editor);

// Now: checking viewer will also check editor and owner
var alice = docs.subject("alice");
var doc = docs.object("doc1", owner);

oracle.add(alice.assertion(doc));  // alice is owner

// All these checks return true:
oracle.check(alice.assertion(doc.withRelation(owner)));   // direct
oracle.check(alice.assertion(doc.withRelation(editor)));  // via rewrite
oracle.check(alice.assertion(doc.withRelation(viewer)));  // via rewrite chain
```

### 4.2 Folder Hierarchy Inheritance

```java
// Setup: document inherits viewer from parent folder
var folders = Oracle.namespace("folders");
var docs = Oracle.namespace("docs");

var viewer = docs.relation("viewer");
var parent = docs.relation("parent");
var folderViewer = folders.relation("viewer");

// doc#viewer includes viewers of doc's parent folder
oracle.followRelation(viewer, parent, folderViewer);

// Create hierarchy
var folder = folders.object("shared-folder", folderViewer);
var doc = docs.object("readme.md", viewer);
oracle.map(folder, doc);  // folder is parent of doc

// Grant folder access
var bob = folders.subject("bob");
oracle.add(bob.assertion(folder));

// Bob can view the doc (inherited from folder)
oracle.check(bob.assertion(doc));  // true via tuple_to_userset
```

### 4.3 Exclusion (Blocked Users)

```java
// Setup: viewer = shared_with EXCEPT blocked
var shared = docs.relation("shared_with");
var blocked = docs.relation("blocked");

oracle.excludeFrom(viewer, shared, blocked);

// Grant and block
oracle.add(charlie.assertion(doc.withRelation(shared)));
oracle.add(charlie.assertion(doc.withRelation(blocked)));

// Charlie cannot view despite being shared_with
oracle.check(charlie.assertion(doc.withRelation(viewer)));  // false
```

## 5. Performance Considerations

### 5.1 Caching Strategy

1. **Relation Config Cache**: Load relation configs once per namespace, invalidate on config changes
2. **Evaluation Cache**: Cache (subject, object#relation) → boolean results with TTL
3. **Parallel Evaluation**: Evaluate union branches concurrently (future optimization)

### 5.2 Complexity Analysis

| Operation | Current (TC) | With Rewrites |
|-----------|-------------|---------------|
| check (no rewrites) | O(1) | O(1) |
| check (computed_userset, depth d) | N/A | O(d) |
| check (tuple_to_userset, k parents) | N/A | O(k * d) |
| expand | O(n) | O(n * d) |
| add assertion | O(1) | O(1) |
| add edge | O(n) recompute TC | O(n) recompute TC |

### 5.3 Safeguards

- **MAX_REWRITE_DEPTH**: Default 10, configurable per namespace
- **Cycle Detection**: Track visited (subject, object#relation) pairs
- **Timeout**: Per-check timeout to prevent runaway evaluation

## 6. Implementation Phases

### Phase 1: Core Infrastructure (XL)
- Add RELATION_CONFIG table and Liquibase migration
- Add RelationConfig record and RewriteType enum to Oracle.java
- Implement config CRUD methods in DirectOracle
- Implement `computed_userset` evaluation in check()
- Unit tests for concentric relations

### Phase 2: tuple_to_userset (L)
- Implement tuple_to_userset evaluation
- Integrate with Object EDGE table for parent lookups
- Tests for folder hierarchy inheritance

### Phase 3: Set Operators (M)
- Implement intersection and exclusion operators
- Tests for complex policies

### Phase 4: Expand Updates (M)
- Update expand(Object) to honor rewrite rules
- Return userset expression tree
- Tests for search index building

### Phase 5: ShardedOracle Integration (L)
- Add rewrite support to ShardedOracle
- Ensure consistent config replication via CHOAM

## 7. Migration Path

1. **Backward Compatible**: Existing code without relation configs continues to work
2. **Incremental Adoption**: Add configs per-relation as needed
3. **Edge Table Coexistence**: Keep Edge table for Subject hierarchies
4. **Future**: Optionally migrate Relation Edge mappings to rewrite configs

## 8. Alternatives Considered

### 8.1 Full Runtime Rewrites (Rejected)
Remove Edge table entirely, evaluate all hierarchies at runtime.
- **Pro**: Matches Zanzibar closely
- **Con**: Major architectural change, performance regression for groups

### 8.2 Materialized Views (Rejected)
Use SQL materialized views for rewrite evaluation.
- **Pro**: Leverage database optimization
- **Con**: H2 doesn't support materialized views well, complex refresh logic

### 8.3 Graph Database (Rejected)
Replace SQL with a graph database for relationship queries.
- **Pro**: Natural fit for ReBAC
- **Con**: Major infrastructure change, loses CHOAM integration

## 9. References

- [Google Zanzibar Paper](https://research.google/pubs/pub48190/)
- [OpenFGA Documentation](https://openfga.dev/docs)
- [SpiceDB Authorization](https://authzed.com/docs)
- Delos ChromaDB: `critique::delphinius::zanzibar-comparison`
