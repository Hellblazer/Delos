# Multi-Tenant Demo

Demonstrates building a multi-tenant application using Delos with per-tenant data isolation, schema separation, and consensus-backed operations.

## Overview

This example shows how to build a multi-tenant SaaS application using Delos where:
- Each tenant has its own schema for data isolation
- All tenants share a global consensus mechanism (CHOAM)
- Transactions are routed per-tenant with application-level routing
- Read operations query from tenant-specific schemas
- Write operations go through global consensus to maintain consistency

### Architecture

```
┌─────────────────────────────────────────────────────────┐
│               Multi-Tenant Application                   │
│  (Document Store with Per-Tenant Isolation)             │
└─────────────────────────────────────────────────────────┘
         │                    │                    │
    Tenant A            Tenant B              Tenant C
    (schema: t1)       (schema: t2)          (schema: t3)
         │                    │                    │
         └────────────────────┼────────────────────┘
                              │
              ┌───────────────┴────────────────┐
              │                                │
         Global Consensus                  Per-Tenant
         (CHOAM)                           Storage
    - Ensure consistent                   (SQL Schema)
      writes across all                   - Tenant A
      tenants                             - Tenant B
    - Byzantine fault                     - Tenant C
      tolerance
    - Replica coordination
```

## Key Concepts

### 1. Tenant Context

Each tenant has an associated context object:

```java
TenantContext tenant = demo.getTenant("tenant-1");
String schema = tenant.getSchemaName();      // "t1"
String table = tenant.getTableName("docs");  // "t1.docs"
```

### 2. Per-Schema Isolation

Tenants are isolated using separate database schemas:

```sql
-- Tenant 1 data
SELECT * FROM t1.documents WHERE tenant_id = 'tenant-1';

-- Tenant 2 data (separate schema)
SELECT * FROM t2.documents WHERE tenant_id = 'tenant-2';
```

This provides:
- **Strong isolation**: Even if access control fails, SQL schema separation prevents cross-tenant reads
- **Performance**: Query planner can optimize per-schema, fewer rows scanned
- **Compliance**: Clear data boundaries for auditing and compliance

### 3. Consensus-Backed Writes

All tenant writes go through global consensus:

```
Tenant A: putDocument() ──→ CHOAM Consensus ──→ All Replicas
Tenant B: putDocument() ──→ CHOAM Consensus ──→ All Replicas
                         (Global ordering)
```

Benefits:
- **Linearizability**: All reads see consistent ordering
- **Fault tolerance**: Survives Byzantine failures
- **Atomicity**: Transactions either commit on all replicas or none

### 4. Read Isolation

Reads are isolated per-tenant via schema routing:

```java
demo.getDocument("tenant-1", "doc-id");
// Queries: SELECT ... FROM t1.documents WHERE tenant_id = 'tenant-1'

demo.getDocument("tenant-2", "doc-id");
// Queries: SELECT ... FROM t2.documents WHERE tenant_id = 'tenant-2'
```

## Usage Example

### Basic Setup

```java
// Create multi-tenant instance
var properties = new Properties();
properties.setProperty("user", "sa");
var demo = new MultiTenantDemo("jdbc:h2:mem:app", properties, checkpointDir);

// Register tenants
demo.registerTenant("acme-corp", "acme", "ACME Corporation");
demo.registerTenant("widget-inc", "widget", "Widget Inc");
```

### Storing Documents

```java
// ACME stores document
demo.putDocument("acme-corp", "budget-2026", "Budget", "2026 Budget details...");

// Widget Inc stores document (same ID, different tenant)
demo.putDocument("widget-inc", "budget-2026", "Budget", "2026 Budget details...");

// Each tenant's document is isolated in their schema
```

### Retrieving Documents

```java
// ACME retrieves its document
String acmeDoc = demo.getDocument("acme-corp", "budget-2026");
// Queries from acme.documents

// Widget Inc retrieves its document
String widgetDoc = demo.getDocument("widget-inc", "budget-2026");
// Queries from widget.documents
```

### Listing Tenant Documents

```java
// List all documents for a tenant
List<String> acmeDocs = demo.listDocuments("acme-corp");
// Queries: SELECT doc_id FROM acme.documents WHERE tenant_id = 'acme-corp'
```

## Implementation Patterns

### 1. Tenant-Aware Queries

Use tenant context to build queries:

```java
TenantContext tenant = getTenant(tenantId);
String query = "SELECT * FROM " + tenant.getTableName("documents")
             + " WHERE tenant_id = ?";
```

### 2. Tenant Routing

Route operations to correct tenant context:

```java
public String getDocument(String tenantId, String docId) {
    var tenant = getTenant(tenantId);
    if (tenant == null) {
        throw new UnknownTenantException(tenantId);
    }
    // Use tenant.getSchemaName() for all queries
}
```

### 3. Consensus-Backed Writes

All writes go through CHOAM for global ordering:

```java
var mutator = sqlStateMachine.getMutator(null);
var txn = Txn.newBuilder()
    .setBatchUpdate(mutator.batchOf(
        "INSERT INTO " + tenant.getTableName("documents") + " (...) values (?)",
        batchData))
    .build();
// Submitted to CHOAM for consensus
```

## Testing

Run the multi-tenant test suite:

```bash
./mvnw test -pl examples/multi-tenant-demo
```

### Test Coverage

- **Tenant registration**: Verify tenants are registered and retrievable
- **Schema naming**: Confirm schema names are correctly assigned
- **Document isolation**: Test that documents can be stored per-tenant
- **Document retrieval**: Verify reads respect tenant boundaries
- **Genesis data**: Check schema migrations are included
- **End-to-end**: Demonstrate typical multi-tenant workflow

## Production Considerations

### 1. Authentication & Authorization

Integrate Delphinius for per-tenant access control:

```java
// Verify user has access to tenant
var oracle = new Oracle(delegate);
if (!oracle.canAccess(userId, "READ", "tenant-1")) {
    throw new UnauthorizedAccessException();
}
```

### 2. Schema Management

Liquibase handles schema creation for new tenants:

```xml
<!-- multi-tenant-changelog.xml -->
<changeSet>
    <sql>CREATE SCHEMA t1;</sql>
    <sql>CREATE TABLE t1.documents (...);</sql>
    <sql>CREATE SCHEMA t2;</sql>
    <sql>CREATE TABLE t2.documents (...);</sql>
</changeSet>
```

### 3. Performance Optimization

- **Schema per tenant**: Reduces query scope, improves planner efficiency
- **Schema replication**: Each replica maintains all schemas locally
- **Indexing**: Create indexes per-schema for common queries
- **Connection pooling**: Pool connections per tenant for better resource usage

### 4. Monitoring & Observability

Track per-tenant metrics:

```java
// Metrics to track per tenant:
// - Transactions per tenant per second
// - Document store/retrieve latency per tenant
// - Schema-specific query performance
// - Replica synchronization lag per tenant
```

### 5. Disaster Recovery

Backup strategy for multi-tenant:

```bash
# Each tenant's schema is independently checkpointed
# Restoring from checkpoint: choose tenant subset
# Full recovery: restore all schemas from checkpoint
```

## Related Documentation

- **Transaction Flow**: See `docs/TRANSACTION_FLOW_GUIDE.md` for understanding transaction stages
- **API Reference**: See `docs/API_REFERENCE.md` for SqlStateMachine and CHOAM APIs
- **Integration Patterns**: See `docs/INTEGRATION_PATTERNS.md` for other application patterns
- **SQL-State Module**: See `sql-state/README.md` for state machine details
- **CHOAM Module**: See `choam/README.md` for consensus details
- **Delphinius Access Control**: See `delphinius/README.md` for authorization

## Files

- **MultiTenantDemo.java** (260 lines): Multi-tenant application with per-tenant schema isolation
- **MultiTenantDemoTest.java** (150 lines): Test suite demonstrating tenant isolation
- **README.md** (this file): Usage guide and patterns

## Summary

This example demonstrates:
✅ Per-tenant data isolation using schema separation
✅ Context-based tenant routing in application code
✅ Consensus-backed multi-tenant transactions
✅ Read isolation using tenant-aware queries
✅ Testing multi-tenant scenarios
✅ Production considerations for SaaS applications

This pattern scales to hundreds of thousands of tenants by creating schemas on-demand and using application-level routing. For more advanced patterns, see `docs/INTEGRATION_PATTERNS.md`.
