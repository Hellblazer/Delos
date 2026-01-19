# Phase 1B-2: BLS Receipt Aggregation Layer Architecture

## Executive Summary

This document specifies the detailed Java architecture for Phase 1B-2 BLS Receipt Aggregation.
The design enables 80% storage reduction (448 to 97 bytes per receipt) while maintaining backward
compatibility with Ed25519 signatures and ensuring thread-safety for virtual threads.

**Scope**: 48 hours, 4 sub-phases
**Target**: 125+ tests, >90% code coverage

## 1. Architectural Overview

### 1.1 Module Organization

```
witness-service/src/main/java/com/hellblazer/delos/witness/aggregation/
    SignatureFormat.java              # Sealed discriminated union for signature types
    AccumulationResult.java           # Sealed result types from accumulation
    AggregationResult.java            # Sealed result types from aggregation
    ValidationResult.java             # Sealed validation outcome types
    SignatureAccumulator.java         # Thread-safe BLS signature accumulator
    BLSReceiptAggregator.java         # Orchestration layer for aggregation pipeline
    AggregateValidator.java           # Validation pipeline for aggregates
    AggregateWitnessReceipt.java      # Immutable BLS aggregate receipt record
    ReceiptCompatibilityLayer.java    # Ed25519/BLS compatibility handling
    MigrationStateTracker.java        # Ed25519 to BLS migration state
    internal/
        AccumulatorState.java         # Internal immutable state
        CompressedBitmap.java         # Bitmap encoding utilities
```

### 1.2 Component Diagram

```
+------------------+     +---------------------+     +-------------------+
|  WitnessService  |---->| BLSReceiptAggregator|---->| AggregateValidator|
+------------------+     +---------------------+     +-------------------+
         |                        |                           |
         |                        v                           |
         |               +-------------------+                |
         |               |SignatureAccumulator|               |
         |               +-------------------+                |
         |                        |                           |
         v                        v                           v
+------------------+     +---------------------+     +-------------------+
|WitnessReceiptMgr |     | BLSOperations       |     | CommitteeProvider |
|      V2          |     | (cryptography)      |     | (WitnessContext)  |
+------------------+     +---------------------+     +-------------------+
         |
         v
+------------------+
| WitnessCHOAM     |
| (persistence)    |
+------------------+
```

### 1.3 Data Flow

```
[Witness signs event]
         |
         v
[BLSReceiptAggregator.accumulateSignature()]
         |
         v
[SignatureAccumulator.accumulate()] -----> [AccumulationResult]
         |                                          |
         | (threshold met)                          | (accumulated)
         v                                          v
[BLSReceiptAggregator.createAggregate()]    [continue collecting]
         |
         v
[BLSOperations.aggregateSignatures()]
         |
         v
[AggregateValidator.validateAggregate()]
         |
         v
[AggregationResult.Success]
         |
         v
[AggregateWitnessReceipt.toProto()]
         |
         v
[WitnessCHOAM persistence]
```

---

## 2. Core Abstractions (Sealed Interfaces)

### 2.1 SignatureFormat

```java
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * Discriminated union for witness receipt signature formats.
 * Enables exhaustive pattern matching in Java 21+.
 */
public sealed interface SignatureFormat
    permits SignatureFormat.Ed25519Signatures, SignatureFormat.BLSAggregateFormat {

    /**
     * Individual Ed25519 signatures (legacy format).
     *
     * @param signatures List of individual witness signatures (64 bytes each)
     * @param signerBitmap Bitmap indicating which committee members signed
     */
    record Ed25519Signatures(
        List<JohnHancock> signatures,
        BitSet signerBitmap
    ) implements SignatureFormat {

        public Ed25519Signatures {
            Objects.requireNonNull(signatures, "signatures cannot be null");
            Objects.requireNonNull(signerBitmap, "signerBitmap cannot be null");
            signatures = List.copyOf(signatures); // Defensive copy
        }

        @Override
        public int signerCount() {
            return signatures.size();
        }

        @Override
        public byte[] signerBitmapBytes() {
            return signerBitmap.toByteArray();
        }

        @Override
        public boolean isAggregate() {
            return false;
        }

        @Override
        public int storageSizeBytes() {
            return signatures.size() * 64; // 64 bytes per Ed25519 signature
        }
    }

    /**
     * BLS aggregate signature (compact format).
     *
     * @param aggregate BLS aggregate containing signature and bitmap
     */
    record BLSAggregateFormat(BLSAggregate aggregate) implements SignatureFormat {

        public BLSAggregateFormat {
            Objects.requireNonNull(aggregate, "aggregate cannot be null");
        }

        @Override
        public int signerCount() {
            return aggregate.getSignerIndices().size();
        }

        @Override
        public byte[] signerBitmapBytes() {
            return aggregate.signerBitmap();
        }

        @Override
        public boolean isAggregate() {
            return true;
        }

        @Override
        public int storageSizeBytes() {
            return 96 + aggregate.getSignerBitmapSize(); // 96 bytes sig + bitmap
        }
    }

    /** Number of witnesses who signed. */
    int signerCount();

    /** Signer bitmap as byte array. */
    byte[] signerBitmapBytes();

    /** True if this is a BLS aggregate, false for Ed25519. */
    boolean isAggregate();

    /** Estimated storage size in bytes. */
    int storageSizeBytes();
}
```

### 2.2 AccumulationResult

```java
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.Objects;

/**
 * Result types from signature accumulation.
 * Enables type-safe handling of all accumulation outcomes.
 */
public sealed interface AccumulationResult
    permits AccumulationResult.Accumulated,
            AccumulationResult.ThresholdMet,
            AccumulationResult.AlreadyPresent,
            AccumulationResult.InvalidSignature {

    /**
     * Signature successfully accumulated, threshold not yet met.
     *
     * @param currentCount Current number of accumulated signatures
     * @param threshold Required threshold for completion
     */
    record Accumulated(int currentCount, int threshold) implements AccumulationResult {
        public Accumulated {
            if (currentCount < 0) throw new IllegalArgumentException("currentCount must be >= 0");
            if (threshold < 1) throw new IllegalArgumentException("threshold must be >= 1");
        }

        /** Progress as percentage (0.0 to 1.0). */
        public double progress() {
            return (double) currentCount / threshold;
        }

        /** Signatures still needed to reach threshold. */
        public int remaining() {
            return Math.max(0, threshold - currentCount);
        }
    }

    /**
     * Threshold reached with this signature.
     * Includes snapshot for immediate aggregation.
     *
     * @param count Final signature count
     * @param snapshot Accumulator snapshot for aggregation
     */
    record ThresholdMet(
        int count,
        SignatureAccumulator.Snapshot snapshot
    ) implements AccumulationResult {
        public ThresholdMet {
            if (count < 1) throw new IllegalArgumentException("count must be >= 1");
            Objects.requireNonNull(snapshot, "snapshot cannot be null");
        }
    }

    /**
     * Member has already contributed a signature.
     * Duplicate signatures are ignored (idempotent).
     *
     * @param member The member who already signed
     */
    record AlreadyPresent(Identifier member) implements AccumulationResult {
        public AlreadyPresent {
            Objects.requireNonNull(member, "member cannot be null");
        }
    }

    /**
     * Signature validation failed.
     *
     * @param member The member whose signature was invalid
     * @param reason Description of validation failure
     */
    record InvalidSignature(
        Identifier member,
        String reason
    ) implements AccumulationResult {
        public InvalidSignature {
            Objects.requireNonNull(member, "member cannot be null");
            Objects.requireNonNull(reason, "reason cannot be null");
        }
    }

    /** Check if accumulation was successful (Accumulated or ThresholdMet). */
    default boolean isSuccess() {
        return this instanceof Accumulated || this instanceof ThresholdMet;
    }

    /** Check if threshold was reached. */
    default boolean isThresholdMet() {
        return this instanceof ThresholdMet;
    }
}
```

### 2.3 AggregationResult

```java
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.bls.BLSAggregate;

import java.time.Duration;
import java.util.Objects;

/**
 * Result types from BLS signature aggregation.
 */
public sealed interface AggregationResult
    permits AggregationResult.Success,
            AggregationResult.InsufficientSignatures,
            AggregationResult.AggregationError {

    /**
     * Aggregation succeeded.
     *
     * @param aggregate The resulting BLS aggregate
     * @param signerCount Number of signatures aggregated
     * @param elapsed Time taken for aggregation
     */
    record Success(
        BLSAggregate aggregate,
        int signerCount,
        Duration elapsed
    ) implements AggregationResult {
        public Success {
            Objects.requireNonNull(aggregate, "aggregate cannot be null");
            Objects.requireNonNull(elapsed, "elapsed cannot be null");
            if (signerCount < 1) throw new IllegalArgumentException("signerCount must be >= 1");
        }

        /** Aggregation met performance target (<1ms). */
        public boolean metPerformanceTarget() {
            return elapsed.toMillis() < 1;
        }
    }

    /**
     * Not enough signatures to meet threshold.
     *
     * @param have Number of signatures available
     * @param need Threshold required
     */
    record InsufficientSignatures(int have, int need) implements AggregationResult {
        public InsufficientSignatures {
            if (have < 0) throw new IllegalArgumentException("have must be >= 0");
            if (need < 1) throw new IllegalArgumentException("need must be >= 1");
            if (have >= need) throw new IllegalArgumentException("have must be < need for insufficient");
        }

        /** Signatures still needed. */
        public int deficit() {
            return need - have;
        }
    }

    /**
     * Aggregation failed due to error.
     *
     * @param message Error description
     * @param cause Optional underlying exception
     */
    record AggregationError(
        String message,
        Throwable cause
    ) implements AggregationResult {
        public AggregationError {
            Objects.requireNonNull(message, "message cannot be null");
            // cause may be null
        }

        /** Convenience constructor without cause. */
        public AggregationError(String message) {
            this(message, null);
        }
    }

    /** Check if aggregation succeeded. */
    default boolean isSuccess() {
        return this instanceof Success;
    }

    /** Get aggregate if successful, empty otherwise. */
    default java.util.Optional<BLSAggregate> getAggregate() {
        return this instanceof Success s ? java.util.Optional.of(s.aggregate()) : java.util.Optional.empty();
    }
}
```

### 2.4 ValidationResult

```java
package com.hellblazer.delos.witness.aggregation;

import java.util.Objects;

/**
 * Result types from aggregate validation.
 */
public sealed interface ValidationResult
    permits ValidationResult.Valid,
            ValidationResult.ThresholdNotMet,
            ValidationResult.InvalidSignature,
            ValidationResult.InvalidBitmap,
            ValidationResult.StaleEpoch {

    /**
     * Validation passed.
     *
     * @param signerCount Number of valid signers
     * @param epoch Epoch of the receipt
     */
    record Valid(int signerCount, long epoch) implements ValidationResult {
        public Valid {
            if (signerCount < 1) throw new IllegalArgumentException("signerCount must be >= 1");
            if (epoch < 0) throw new IllegalArgumentException("epoch must be >= 0");
        }
    }

    /**
     * Threshold not satisfied.
     *
     * @param actual Actual signer count
     * @param required Required threshold
     */
    record ThresholdNotMet(int actual, int required) implements ValidationResult {
        public ThresholdNotMet {
            if (actual < 0) throw new IllegalArgumentException("actual must be >= 0");
            if (required < 1) throw new IllegalArgumentException("required must be >= 1");
        }
    }

    /**
     * Cryptographic signature verification failed.
     *
     * @param reason Description of failure
     */
    record InvalidSignature(String reason) implements ValidationResult {
        public InvalidSignature {
            Objects.requireNonNull(reason, "reason cannot be null");
        }
    }

    /**
     * Signer bitmap is malformed or inconsistent.
     *
     * @param reason Description of bitmap error
     */
    record InvalidBitmap(String reason) implements ValidationResult {
        public InvalidBitmap {
            Objects.requireNonNull(reason, "reason cannot be null");
        }
    }

    /**
     * Receipt epoch is too old.
     *
     * @param receiptEpoch Epoch in the receipt
     * @param currentEpoch Current system epoch
     */
    record StaleEpoch(long receiptEpoch, long currentEpoch) implements ValidationResult {
        public StaleEpoch {
            if (receiptEpoch < 0) throw new IllegalArgumentException("receiptEpoch must be >= 0");
            if (currentEpoch < 0) throw new IllegalArgumentException("currentEpoch must be >= 0");
        }

        /** Epoch drift amount. */
        public long drift() {
            return currentEpoch - receiptEpoch;
        }
    }

    /** Check if validation passed. */
    default boolean isValid() {
        return this instanceof Valid;
    }

    /** Get error message if validation failed, empty if valid. */
    default java.util.Optional<String> getErrorMessage() {
        return switch (this) {
            case Valid v -> java.util.Optional.empty();
            case ThresholdNotMet t -> java.util.Optional.of("Threshold not met: " + t.actual() + "/" + t.required());
            case InvalidSignature s -> java.util.Optional.of("Invalid signature: " + s.reason());
            case InvalidBitmap b -> java.util.Optional.of("Invalid bitmap: " + b.reason());
            case StaleEpoch e -> java.util.Optional.of("Stale epoch: " + e.receiptEpoch() + " (current: " + e.currentEpoch() + ")");
        };
    }
}
```

---

## 3. SignatureAccumulator

### 3.1 Design Principles

- **Lock-free accumulation**: Uses `ConcurrentHashMap.putIfAbsent()` for O(1) deduplication
- **Atomic threshold detection**: Single snapshot via `AtomicBoolean.compareAndSet()`
- **Immutable snapshots**: Thread-safe observation without locks
- **Virtual thread compatible**: No blocking locks or synchronized blocks

### 3.2 Implementation

```java
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe accumulator for BLS signatures during receipt collection.
 * <p>
 * Design:
 * - Lock-free accumulation using ConcurrentHashMap
 * - Atomic threshold detection with single snapshot creation
 * - Immutable state snapshots for safe observation
 * - Virtual thread compatible (no blocking locks)
 * <p>
 * Lifecycle: Create per-event, accumulate signatures, snapshot when threshold met, discard.
 * <p>
 * Performance:
 * - Accumulation: O(1) per signature
 * - Threshold check: O(1)
 * - Snapshot creation: O(n) where n = signer count
 * - Memory: ~200 bytes per accumulated entry
 */
public final class SignatureAccumulator {

    /**
     * Immutable snapshot of accumulator state.
     * Thread-safe for observation and aggregation.
     */
    public record Snapshot(
        EventCoordinates event,
        Map<Identifier, SignatureEntry> signatures,
        int threshold,
        long epoch,
        Instant createdAt,
        boolean thresholdMet
    ) {
        public Snapshot {
            Objects.requireNonNull(event, "event cannot be null");
            Objects.requireNonNull(signatures, "signatures cannot be null");
            Objects.requireNonNull(createdAt, "createdAt cannot be null");
            if (threshold < 1) throw new IllegalArgumentException("threshold must be >= 1");
            if (epoch < 0) throw new IllegalArgumentException("epoch must be >= 0");
            // Defensive copy to ensure immutability
            signatures = Map.copyOf(signatures);
        }

        /** Number of signatures accumulated. */
        public int signerCount() {
            return signatures.size();
        }

        /** Committee indices of signers, sorted ascending. */
        public List<Integer> signerIndices() {
            return signatures.values().stream()
                .sorted()
                .map(SignatureEntry::committeeIndex)
                .toList();
        }

        /** BLS signatures ordered by committee index. */
        public List<BLSSignature> signatureList() {
            return signatures.values().stream()
                .sorted()
                .map(SignatureEntry::signature)
                .toList();
        }

        /** Identifiers of all signers. */
        public Set<Identifier> signerIds() {
            return signatures.keySet();
        }
    }

    /**
     * Individual signature entry with metadata.
     */
    public record SignatureEntry(
        Identifier member,
        int committeeIndex,
        BLSSignature signature,
        Instant receivedAt
    ) implements Comparable<SignatureEntry> {

        public SignatureEntry {
            Objects.requireNonNull(member, "member cannot be null");
            Objects.requireNonNull(signature, "signature cannot be null");
            Objects.requireNonNull(receivedAt, "receivedAt cannot be null");
            if (committeeIndex < 0) throw new IllegalArgumentException("committeeIndex must be >= 0");
        }

        @Override
        public int compareTo(SignatureEntry other) {
            return Integer.compare(this.committeeIndex, other.committeeIndex);
        }
    }

    // Configuration (immutable after construction)
    private final EventCoordinates event;
    private final int threshold;
    private final long epoch;
    private final Instant createdAt;

    // Thread-safe state
    private final ConcurrentHashMap<Identifier, SignatureEntry> signatures = new ConcurrentHashMap<>();
    private final AtomicBoolean thresholdReached = new AtomicBoolean(false);
    private final AtomicReference<Snapshot> thresholdSnapshot = new AtomicReference<>();

    /**
     * Create accumulator for an event.
     *
     * @param event Event coordinates being witnessed
     * @param threshold Required signature count (M)
     * @param epoch Fireflies epoch
     * @throws NullPointerException if event is null
     * @throws IllegalArgumentException if threshold < 1 or epoch < 0
     */
    public SignatureAccumulator(EventCoordinates event, int threshold, long epoch) {
        this.event = Objects.requireNonNull(event, "event cannot be null");
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be >= 1, got: " + threshold);
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must be >= 0, got: " + epoch);
        }
        this.threshold = threshold;
        this.epoch = epoch;
        this.createdAt = Instant.now();
    }

    /**
     * Accumulate a BLS signature from a committee member.
     * <p>
     * Thread-safe and idempotent per member.
     * First signature from each member is accepted; duplicates are ignored.
     *
     * @param member Committee member identifier
     * @param committeeIndex Member's index in committee (for bitmap)
     * @param signature BLS signature from member
     * @return AccumulationResult indicating outcome
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if committeeIndex < 0
     */
    public AccumulationResult accumulate(Identifier member, int committeeIndex, BLSSignature signature) {
        Objects.requireNonNull(member, "member cannot be null");
        Objects.requireNonNull(signature, "signature cannot be null");
        if (committeeIndex < 0) {
            throw new IllegalArgumentException("committeeIndex must be >= 0, got: " + committeeIndex);
        }

        // Create entry
        var entry = new SignatureEntry(member, committeeIndex, signature, Instant.now());

        // Atomic deduplication - putIfAbsent returns null if inserted
        var existing = signatures.putIfAbsent(member, entry);
        if (existing != null) {
            return new AccumulationResult.AlreadyPresent(member);
        }

        // Check threshold with atomic compareAndSet to ensure single snapshot
        var count = signatures.size();
        if (count >= threshold && thresholdReached.compareAndSet(false, true)) {
            // First thread to reach threshold creates the snapshot
            var snapshot = createSnapshot();
            thresholdSnapshot.set(snapshot);
            return new AccumulationResult.ThresholdMet(count, snapshot);
        }

        return new AccumulationResult.Accumulated(count, threshold);
    }

    /**
     * Create immutable snapshot of current state.
     *
     * @return Snapshot of accumulated signatures
     */
    public Snapshot snapshot() {
        return createSnapshot();
    }

    /**
     * Get the threshold snapshot if threshold was met.
     *
     * @return Optional containing snapshot if threshold met, empty otherwise
     */
    public Optional<Snapshot> getThresholdSnapshot() {
        return Optional.ofNullable(thresholdSnapshot.get());
    }

    /** Current signature count. */
    public int signerCount() {
        return signatures.size();
    }

    /** Check if threshold has been reached. */
    public boolean isThresholdMet() {
        return thresholdReached.get();
    }

    /** Get the event being accumulated. */
    public EventCoordinates getEvent() {
        return event;
    }

    /** Get the required threshold. */
    public int getThreshold() {
        return threshold;
    }

    /** Get the epoch. */
    public long getEpoch() {
        return epoch;
    }

    /** Get creation timestamp. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    // Internal helper to create snapshot
    private Snapshot createSnapshot() {
        return new Snapshot(
            event,
            new HashMap<>(signatures), // Copy for immutability
            threshold,
            epoch,
            createdAt,
            thresholdReached.get()
        );
    }
}
```

### 3.3 Concurrency Analysis

| Operation | Complexity | Concurrency Safety |
|-----------|------------|-------------------|
| `accumulate()` | O(1) | Lock-free via `ConcurrentHashMap.putIfAbsent()` |
| Threshold check | O(1) | Atomic via `AtomicBoolean.compareAndSet()` |
| `snapshot()` | O(n) | Creates defensive copy |
| `signerCount()` | O(1) | ConcurrentHashMap.size() is O(1) |

**Virtual Thread Compatibility**: No blocking locks, no synchronized blocks. Safe for use with virtual threads without carrier thread pinning.

---

## 4. BLSReceiptAggregator

### 4.1 Design Principles

- **Factory pattern**: Creates and manages SignatureAccumulators per event
- **Automatic cleanup**: Expired accumulators are removed periodically
- **Metrics collection**: Tracks aggregation performance and errors
- **Configurable validation**: Optional post-aggregation validation

### 4.2 Implementation

```java
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSOperations;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.WitnessParameters;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Orchestrates BLS receipt aggregation pipeline.
 * <p>
 * Responsibilities:
 * - Manages SignatureAccumulator instances per event
 * - Triggers aggregation when threshold met
 * - Validates aggregates before returning
 * - Handles graceful degradation
 * <p>
 * Thread-safety: All operations are thread-safe, virtual thread compatible.
 */
public final class BLSReceiptAggregator {

    /**
     * Configuration for the aggregator.
     */
    public record AggregatorConfig(
        int defaultThreshold,
        long epoch,
        Duration accumulatorTtl,
        boolean validateOnCreate,
        int maxAccumulators
    ) {
        public AggregatorConfig {
            if (defaultThreshold < 1) throw new IllegalArgumentException("defaultThreshold must be >= 1");
            if (epoch < 0) throw new IllegalArgumentException("epoch must be >= 0");
            Objects.requireNonNull(accumulatorTtl, "accumulatorTtl cannot be null");
            if (maxAccumulators < 1) throw new IllegalArgumentException("maxAccumulators must be >= 1");
        }

        /** Create config from WitnessParameters. */
        public static AggregatorConfig fromWitnessParameters(WitnessParameters params) {
            return new AggregatorConfig(
                params.threshold(),
                params.epoch(),
                params.drainPeriod().multipliedBy(2), // TTL = 2x drain period
                true,  // Validate by default
                10000  // Max concurrent collections
            );
        }

        /** Builder for custom configuration. */
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int defaultThreshold = 3;
            private long epoch = 0;
            private Duration accumulatorTtl = Duration.ofMinutes(5);
            private boolean validateOnCreate = true;
            private int maxAccumulators = 10000;

            public Builder defaultThreshold(int threshold) { this.defaultThreshold = threshold; return this; }
            public Builder epoch(long epoch) { this.epoch = epoch; return this; }
            public Builder accumulatorTtl(Duration ttl) { this.accumulatorTtl = ttl; return this; }
            public Builder validateOnCreate(boolean validate) { this.validateOnCreate = validate; return this; }
            public Builder maxAccumulators(int max) { this.maxAccumulators = max; return this; }
            public AggregatorConfig build() { return new AggregatorConfig(defaultThreshold, epoch, accumulatorTtl, validateOnCreate, maxAccumulators); }
        }
    }

    // Internal entry tracking
    private record AccumulatorEntry(
        SignatureAccumulator accumulator,
        Instant createdAt,
        AtomicBoolean aggregated
    ) {}

    // Configuration
    private final AggregatorConfig config;
    private final AggregateValidator validator;

    // State: Event key -> Accumulator entry
    private final ConcurrentHashMap<String, AccumulatorEntry> accumulators = new ConcurrentHashMap<>();

    // Metrics
    private final AtomicLong aggregationsCreated = new AtomicLong();
    private final AtomicLong aggregationsFailed = new AtomicLong();
    private final LongAdder totalAggregationTimeNanos = new LongAdder();

    /**
     * Create aggregator with configuration and validator.
     *
     * @param config Aggregator configuration
     * @param validator Aggregate validator (may be null to skip validation)
     */
    public BLSReceiptAggregator(AggregatorConfig config, AggregateValidator validator) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.validator = validator; // May be null
    }

    /**
     * Get or create accumulator for an event.
     * Thread-safe, creates accumulator atomically if absent.
     *
     * @param event Event coordinates
     * @param threshold Required signature threshold (uses default if <= 0)
     * @return SignatureAccumulator for the event
     */
    public SignatureAccumulator getOrCreateAccumulator(EventCoordinates event, int threshold) {
        Objects.requireNonNull(event, "event cannot be null");
        var effectiveThreshold = threshold > 0 ? threshold : config.defaultThreshold();

        var key = eventKey(event);
        var entry = accumulators.computeIfAbsent(key, k -> {
            var acc = new SignatureAccumulator(event, effectiveThreshold, config.epoch());
            return new AccumulatorEntry(acc, Instant.now(), new AtomicBoolean(false));
        });

        return entry.accumulator();
    }

    /**
     * Accumulate a BLS signature for an event.
     *
     * @param event Event being witnessed
     * @param member Witness member
     * @param committeeIndex Member's committee index
     * @param signature BLS signature
     * @return AccumulationResult with aggregation outcome
     */
    public AccumulationResult accumulateSignature(
        EventCoordinates event,
        Identifier member,
        int committeeIndex,
        BLSSignature signature
    ) {
        var accumulator = getOrCreateAccumulator(event, -1);
        return accumulator.accumulate(member, committeeIndex, signature);
    }

    /**
     * Create aggregate from accumulator snapshot.
     *
     * @param snapshot Accumulator snapshot with signatures
     * @return AggregationResult with aggregate or error
     */
    public AggregationResult createAggregate(SignatureAccumulator.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");

        if (snapshot.signerCount() < snapshot.threshold()) {
            return new AggregationResult.InsufficientSignatures(
                snapshot.signerCount(), snapshot.threshold()
            );
        }

        var startTime = System.nanoTime();
        try {
            // Extract signatures and indices
            var signatures = snapshot.signatureList();
            var indices = snapshot.signerIndices();

            // Use BLSOperations to aggregate
            var aggregate = BLSOperations.aggregateSignatures(signatures, indices);

            var elapsed = Duration.ofNanos(System.nanoTime() - startTime);
            aggregationsCreated.incrementAndGet();
            totalAggregationTimeNanos.add(elapsed.toNanos());

            // Optionally validate
            if (config.validateOnCreate() && validator != null) {
                var validationResult = validator.validateAggregate(aggregate, snapshot);
                if (!validationResult.isValid()) {
                    aggregationsFailed.incrementAndGet();
                    return new AggregationResult.AggregationError(
                        "Post-creation validation failed: " + validationResult.getErrorMessage().orElse("unknown")
                    );
                }
            }

            return new AggregationResult.Success(aggregate, snapshot.signerCount(), elapsed);

        } catch (Exception e) {
            aggregationsFailed.incrementAndGet();
            return new AggregationResult.AggregationError(e.getMessage(), e);
        }
    }

    /**
     * Get aggregate for event if threshold met.
     *
     * @param event Event coordinates
     * @return Optional containing aggregate if available
     */
    public Optional<BLSAggregate> getAggregateIfReady(EventCoordinates event) {
        var key = eventKey(event);
        var entry = accumulators.get(key);
        if (entry == null) return Optional.empty();

        var snapshot = entry.accumulator().getThresholdSnapshot();
        if (snapshot.isEmpty()) return Optional.empty();

        // Only aggregate once
        if (!entry.aggregated().compareAndSet(false, true)) {
            return Optional.empty(); // Another thread is aggregating
        }

        var result = createAggregate(snapshot.get());
        return result.getAggregate();
    }

    /**
     * Check if accumulator exists for event.
     */
    public boolean hasAccumulator(EventCoordinates event) {
        return accumulators.containsKey(eventKey(event));
    }

    /**
     * Complete and remove accumulator for event.
     * Should be called when receipt is finalized.
     *
     * @param event Event coordinates
     */
    public void complete(EventCoordinates event) {
        accumulators.remove(eventKey(event));
    }

    /**
     * Cleanup expired accumulators.
     * Should be called periodically (e.g., every drain period).
     *
     * @return Number of accumulators removed
     */
    public int cleanupExpired() {
        var now = Instant.now();
        var removed = new java.util.concurrent.atomic.AtomicInteger(0);

        accumulators.entrySet().removeIf(e -> {
            var expired = Duration.between(e.getValue().createdAt(), now)
                .compareTo(config.accumulatorTtl()) > 0;
            if (expired) removed.incrementAndGet();
            return expired;
        });

        return removed.get();
    }

    /**
     * Get current accumulator count.
     */
    public int getAccumulatorCount() {
        return accumulators.size();
    }

    /**
     * Aggregator metrics for monitoring.
     */
    public record Metrics(
        long aggregationsCreated,
        long aggregationsFailed,
        double avgAggregationMs,
        int activeAccumulators
    ) {}

    /**
     * Get current metrics.
     */
    public Metrics getMetrics() {
        var created = aggregationsCreated.get();
        var totalNanos = totalAggregationTimeNanos.sum();
        var avgMs = created > 0 ? (totalNanos / created) / 1_000_000.0 : 0.0;

        return new Metrics(
            created,
            aggregationsFailed.get(),
            avgMs,
            accumulators.size()
        );
    }

    // Internal helper for event key
    private String eventKey(EventCoordinates event) {
        return event.getDigest().toString() + ":" + event.getSequenceNumber();
    }
}
```

---

## 5. AggregateValidator

### 5.1 Validation Pipeline

```
[Bitmap Integrity] -> [Threshold Check] -> [Epoch Freshness] -> [Crypto Verification]
      O(1)               O(1)                  O(1)                  O(n) verify
```

Short-circuit on first failure (fail-fast). Cheap checks before expensive crypto.

### 5.2 Implementation

```java
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSOperations;
import com.hellblazer.delos.cryptography.bls.BLSPublicKey;
import com.hellblazer.delos.stereotomy.EventCoordinates;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Validates BLS aggregate receipts before persistence.
 * <p>
 * Validation Pipeline (fail-fast order):
 * 1. Bitmap integrity (cardinality matches signature)
 * 2. Threshold satisfaction (signers >= M)
 * 3. Epoch freshness (receipt not stale)
 * 4. Cryptographic verification (aggregate signature valid)
 * <p>
 * Thread-safe, stateless validation.
 */
public final class AggregateValidator {

    /**
     * Provider for committee public keys.
     */
    @FunctionalInterface
    public interface CommitteeProvider {
        /**
         * Get committee public keys for an event at an epoch.
         *
         * @param event Event coordinates
         * @param epoch Epoch to get committee for
         * @return List of committee member public keys (ordered by index)
         */
        List<BLSPublicKey> getCommitteeKeys(EventCoordinates event, long epoch);
    }

    private final CommitteeProvider committeeProvider;
    private final long currentEpoch;
    private final int maxEpochDrift;

    /**
     * Create validator with committee provider.
     *
     * @param committeeProvider Provider for committee keys
     * @param currentEpoch Current system epoch
     * @param maxEpochDrift Maximum allowed epoch drift
     */
    public AggregateValidator(CommitteeProvider committeeProvider, long currentEpoch, int maxEpochDrift) {
        this.committeeProvider = Objects.requireNonNull(committeeProvider);
        if (currentEpoch < 0) throw new IllegalArgumentException("currentEpoch must be >= 0");
        if (maxEpochDrift < 0) throw new IllegalArgumentException("maxEpochDrift must be >= 0");
        this.currentEpoch = currentEpoch;
        this.maxEpochDrift = maxEpochDrift;
    }

    /**
     * Comprehensive validation of a BLS aggregate.
     *
     * @param aggregate BLS aggregate to validate
     * @param snapshot Accumulator snapshot (for cross-checking)
     * @return ValidationResult indicating outcome
     */
    public ValidationResult validateAggregate(BLSAggregate aggregate, SignatureAccumulator.Snapshot snapshot) {
        Objects.requireNonNull(aggregate, "aggregate cannot be null");
        Objects.requireNonNull(snapshot, "snapshot cannot be null");

        // 1. Bitmap integrity check (O(1))
        var bitmapResult = validateBitmap(aggregate);
        if (!bitmapResult.isValid()) {
            return bitmapResult;
        }

        // 2. Threshold check (O(1))
        var signerCount = aggregate.getSignerIndices().size();
        if (signerCount < snapshot.threshold()) {
            return new ValidationResult.ThresholdNotMet(signerCount, snapshot.threshold());
        }

        // 3. Epoch freshness check (O(1))
        if (snapshot.epoch() + maxEpochDrift < currentEpoch) {
            return new ValidationResult.StaleEpoch(snapshot.epoch(), currentEpoch);
        }

        // 4. Cryptographic verification (O(n) where n = signer count)
        return verifyCryptographically(aggregate, snapshot);
    }

    /**
     * Validate bitmap integrity.
     * Ensures bitmap cardinality and encoding are correct.
     */
    public ValidationResult validateBitmap(BLSAggregate aggregate) {
        Objects.requireNonNull(aggregate, "aggregate cannot be null");

        var bitmap = aggregate.signerBitmap();
        var indices = aggregate.getSignerIndices();

        // Check for empty bitmap
        if (bitmap.length == 0) {
            return new ValidationResult.InvalidBitmap("Empty bitmap");
        }

        // Check indices are within reasonable bounds (committee size limit)
        var maxIndex = indices.stream().mapToInt(i -> i).max().orElse(-1);
        if (maxIndex >= 256) {
            return new ValidationResult.InvalidBitmap("Signer index " + maxIndex + " exceeds limit (256)");
        }

        // Verify bitmap encodes exactly the claimed indices
        var expectedBitmap = createBitmap(indices);
        if (!Arrays.equals(trimTrailingZeros(bitmap), trimTrailingZeros(expectedBitmap))) {
            return new ValidationResult.InvalidBitmap("Bitmap does not match signer indices");
        }

        return new ValidationResult.Valid(indices.size(), 0);
    }

    /**
     * Cryptographically verify the aggregate signature.
     * Uses BLSOperations.verifyAggregate with committee keys.
     */
    public ValidationResult verifyCryptographically(BLSAggregate aggregate, SignatureAccumulator.Snapshot snapshot) {
        try {
            // Get committee keys for the epoch
            var committeeKeys = committeeProvider.getCommitteeKeys(snapshot.event(), snapshot.epoch());
            if (committeeKeys == null || committeeKeys.isEmpty()) {
                return new ValidationResult.InvalidSignature("No committee keys available for epoch " + snapshot.epoch());
            }

            // Build message from event digest
            var message = buildVerificationMessage(snapshot.event());

            // Verify using BLSOperations (handles bitmap filtering internally)
            var valid = BLSOperations.verifyAggregate(committeeKeys, message, aggregate);

            if (!valid) {
                return new ValidationResult.InvalidSignature("Aggregate signature verification failed");
            }

            return new ValidationResult.Valid(aggregate.getSignerIndices().size(), snapshot.epoch());

        } catch (Exception e) {
            return new ValidationResult.InvalidSignature("Verification error: " + e.getMessage());
        }
    }

    /**
     * Quick validation (skip crypto verification).
     * Used for performance-critical paths where crypto was already verified.
     */
    public ValidationResult validateQuick(BLSAggregate aggregate, int threshold, long epoch) {
        Objects.requireNonNull(aggregate, "aggregate cannot be null");

        var bitmapResult = validateBitmap(aggregate);
        if (!bitmapResult.isValid()) {
            return bitmapResult;
        }

        var signerCount = aggregate.getSignerIndices().size();
        if (signerCount < threshold) {
            return new ValidationResult.ThresholdNotMet(signerCount, threshold);
        }

        if (epoch + maxEpochDrift < currentEpoch) {
            return new ValidationResult.StaleEpoch(epoch, currentEpoch);
        }

        return new ValidationResult.Valid(signerCount, epoch);
    }

    /**
     * Update validator with new epoch.
     * Creates new validator instance (immutable).
     */
    public AggregateValidator withEpoch(long newEpoch) {
        return new AggregateValidator(committeeProvider, newEpoch, maxEpochDrift);
    }

    // Helper: Create bitmap from indices
    private byte[] createBitmap(List<Integer> indices) {
        if (indices.isEmpty()) {
            return new byte[1];
        }

        var maxIndex = indices.stream().mapToInt(i -> i).max().orElse(0);
        var bitmapSizeBytes = (maxIndex / 8) + 1;
        var bitmap = new byte[bitmapSizeBytes];

        for (var index : indices) {
            var byteIndex = index / 8;
            var bitIndex = index % 8;
            bitmap[byteIndex] |= (1 << bitIndex);
        }

        return bitmap;
    }

    // Helper: Trim trailing zero bytes from bitmap
    private byte[] trimTrailingZeros(byte[] bitmap) {
        var lastNonZero = bitmap.length - 1;
        while (lastNonZero >= 0 && bitmap[lastNonZero] == 0) {
            lastNonZero--;
        }
        return Arrays.copyOf(bitmap, lastNonZero + 1);
    }

    // Helper: Build verification message from event coordinates
    private byte[] buildVerificationMessage(EventCoordinates event) {
        // Use event digest as the message
        return event.getDigest().getBytes();
    }
}
```

---

## 6. Proto Integration

### 6.1 witness.proto Updates

Add the following to `grpc/src/main/proto/witness.proto`:

```protobuf
/**
 * BLS aggregate signature for witness receipts.
 * Compact representation: 96 bytes signature + ceil(n/8) bytes bitmap.
 */
message BLSAggregateSignature {
  // Aggregated BLS-12-381 signature (96 bytes G2 compressed)
  bytes aggregatedSignature = 1;

  // Signer bitmap: bit[i] = 1 if witness[i] signed
  bytes signerBitmap = 2;

  // Number of signers (for quick threshold check)
  uint32 signerCount = 3;
}

/**
 * Individual Ed25519 signatures wrapper (legacy format).
 */
message Ed25519Signatures {
  // List of individual witness signatures
  repeated crypto.Sig signatures = 1;

  // Signer bitmap (same encoding as BLS)
  bytes signerBitmap = 2;
}

/**
 * Enhanced WitnessReceipt with BLS aggregate support.
 * Backward compatible with legacy receipts.
 */
message WitnessReceiptV2 {
  // Event coordinates identifying which event is receipted
  stereotomy.EventCoords eventCoordinates = 1;

  // Hash of the event content for validation
  crypto.Digeste eventDigest = 2;

  // Signature format: Ed25519 (legacy) or BLS aggregate
  oneof signatureFormat {
    Ed25519Signatures ed25519 = 3;
    BLSAggregateSignature blsAggregate = 4;
  }

  // Fireflies epoch for staleness detection
  uint64 epoch = 5;

  // Fireflies view reference for validation
  crypto.Digeste viewRef = 6;

  // Timestamp when receipt was created
  google.protobuf.Timestamp timestamp = 7;

  // Threshold used for this receipt
  uint32 threshold = 8;

  // Committee size at time of receipt
  uint32 committeeSize = 9;
}
```

### 6.2 AggregateWitnessReceipt Java Record

```java
package com.hellblazer.delos.witness.aggregation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.witness.proto.BLSAggregateSignature;
import com.hellblazer.delos.witness.proto.WitnessReceiptV2;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable witness receipt with BLS aggregate signature.
 * <p>
 * Provides efficient storage: ~100 bytes vs ~450 bytes for 7 Ed25519 signatures.
 * <p>
 * Supports serialization to/from WitnessReceiptV2 proto.
 */
public record AggregateWitnessReceipt(
    EventCoordinates eventCoordinates,
    Digest eventDigest,
    BLSAggregate blsAggregate,
    long epoch,
    Digest viewRef,
    Instant timestamp,
    int threshold,
    int committeeSize
) {
    /**
     * Compact constructor with validation.
     */
    public AggregateWitnessReceipt {
        Objects.requireNonNull(eventCoordinates, "eventCoordinates cannot be null");
        Objects.requireNonNull(eventDigest, "eventDigest cannot be null");
        Objects.requireNonNull(blsAggregate, "blsAggregate cannot be null");
        Objects.requireNonNull(viewRef, "viewRef cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be >= 1, got: " + threshold);
        }
        if (committeeSize < threshold) {
            throw new IllegalArgumentException("committeeSize must be >= threshold");
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must be >= 0, got: " + epoch);
        }
    }

    /**
     * Create from proto message.
     *
     * @throws IllegalArgumentException if proto does not contain BLS aggregate
     */
    public static AggregateWitnessReceipt fromProto(WitnessReceiptV2 proto) {
        Objects.requireNonNull(proto, "proto cannot be null");

        if (!proto.hasBlsAggregate()) {
            throw new IllegalArgumentException("Proto does not contain BLS aggregate");
        }

        var blsProto = proto.getBlsAggregate();
        var aggregate = new BLSAggregate(
            new BLSSignature(blsProto.getAggregatedSignature().toByteArray()),
            blsProto.getSignerBitmap().toByteArray()
        );

        return new AggregateWitnessReceipt(
            EventCoordinates.from(proto.getEventCoordinates()),
            Digest.from(proto.getEventDigest()),
            aggregate,
            proto.getEpoch(),
            Digest.from(proto.getViewRef()),
            Instant.ofEpochSecond(
                proto.getTimestamp().getSeconds(),
                proto.getTimestamp().getNanos()
            ),
            proto.getThreshold(),
            proto.getCommitteeSize()
        );
    }

    /**
     * Convert to proto message.
     */
    public WitnessReceiptV2 toProto() {
        return WitnessReceiptV2.newBuilder()
            .setEventCoordinates(eventCoordinates.toEventCoords())
            .setEventDigest(eventDigest.toDigeste())
            .setBlsAggregate(BLSAggregateSignature.newBuilder()
                .setAggregatedSignature(ByteString.copyFrom(
                    blsAggregate.aggregatedSignature().toBytes()))
                .setSignerBitmap(ByteString.copyFrom(blsAggregate.signerBitmap()))
                .setSignerCount(blsAggregate.getSignerIndices().size())
                .build())
            .setEpoch(epoch)
            .setViewRef(viewRef.toDigeste())
            .setTimestamp(Timestamp.newBuilder()
                .setSeconds(timestamp.getEpochSecond())
                .setNanos(timestamp.getNano())
                .build())
            .setThreshold(threshold)
            .setCommitteeSize(committeeSize)
            .build();
    }

    /**
     * Estimated storage size in bytes.
     */
    public int storageSizeBytes() {
        // 96 (BLS sig) + bitmap + ~32 bytes overhead
        return 96 + blsAggregate.getSignerBitmapSize() + 32;
    }

    /**
     * Number of witnesses who signed.
     */
    public int signerCount() {
        return blsAggregate.getSignerIndices().size();
    }

    /**
     * Validate this receipt using quick validation.
     */
    public ValidationResult validate(AggregateValidator validator) {
        return validator.validateQuick(blsAggregate, threshold, epoch);
    }

    /**
     * Create builder for fluent construction.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private EventCoordinates eventCoordinates;
        private Digest eventDigest;
        private BLSAggregate blsAggregate;
        private long epoch;
        private Digest viewRef;
        private Instant timestamp;
        private int threshold;
        private int committeeSize;

        public Builder eventCoordinates(EventCoordinates ec) { this.eventCoordinates = ec; return this; }
        public Builder eventDigest(Digest d) { this.eventDigest = d; return this; }
        public Builder blsAggregate(BLSAggregate a) { this.blsAggregate = a; return this; }
        public Builder epoch(long e) { this.epoch = e; return this; }
        public Builder viewRef(Digest v) { this.viewRef = v; return this; }
        public Builder timestamp(Instant t) { this.timestamp = t; return this; }
        public Builder threshold(int t) { this.threshold = t; return this; }
        public Builder committeeSize(int c) { this.committeeSize = c; return this; }

        public AggregateWitnessReceipt build() {
            return new AggregateWitnessReceipt(
                eventCoordinates, eventDigest, blsAggregate,
                epoch, viewRef, timestamp, threshold, committeeSize
            );
        }
    }
}
```

---

## 7. Integration Architecture

### 7.1 WitnessReceiptManagerV2

Extended manager supporting both Ed25519 and BLS signatures.

```java
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.aggregation.*;

import java.util.Optional;
import java.util.Set;

/**
 * Extended WitnessReceiptManager with BLS aggregation support.
 * Backward compatible with Ed25519 while adding BLS path.
 */
public class WitnessReceiptManagerV2 extends WitnessReceiptManager {

    private final BLSReceiptAggregator blsAggregator;
    private final ReceiptCompatibilityLayer compatibilityLayer;
    private final MigrationStateTracker migrationTracker;
    private final boolean blsEnabled;

    public WitnessReceiptManagerV2(
        WitnessParameters parameters,
        BLSReceiptAggregator blsAggregator,
        MigrationStateTracker migrationTracker,
        boolean blsEnabled
    ) {
        super(parameters);
        this.blsAggregator = blsAggregator;
        this.compatibilityLayer = new ReceiptCompatibilityLayer();
        this.migrationTracker = migrationTracker;
        this.blsEnabled = blsEnabled;
    }

    /**
     * Add BLS signature to collection.
     */
    public AccumulationResult addBLSSignature(
        EventCoordinates event,
        Identifier member,
        int committeeIndex,
        BLSSignature signature
    ) {
        if (!blsEnabled) {
            throw new IllegalStateException("BLS signatures not enabled");
        }
        return blsAggregator.accumulateSignature(event, member, committeeIndex, signature);
    }

    /**
     * Get receipt in unified format.
     */
    public Optional<UnifiedReceipt> getUnifiedReceipt(EventCoordinates event) {
        if (blsEnabled && migrationTracker.shouldUseBLS()) {
            var aggregate = blsAggregator.getAggregateIfReady(event);
            if (aggregate.isPresent()) {
                return Optional.of(UnifiedReceipt.bls(aggregate.get()));
            }
        }

        // Fall back to Ed25519
        var state = getCollectionState(event);
        if (state.isThresholdAchieved()) {
            return Optional.of(UnifiedReceipt.ed25519(state.getSigners()));
        }

        return Optional.empty();
    }

    /**
     * Check if BLS is enabled and should be used.
     */
    public boolean shouldUseBLS() {
        return blsEnabled && migrationTracker.shouldUseBLS();
    }

    /**
     * Unified receipt interface.
     */
    public sealed interface UnifiedReceipt
        permits UnifiedReceipt.BLSReceipt, UnifiedReceipt.Ed25519Receipt {

        record BLSReceipt(BLSAggregate aggregate) implements UnifiedReceipt {
            @Override public SignatureFormat format() {
                return new SignatureFormat.BLSAggregateFormat(aggregate);
            }
        }

        record Ed25519Receipt(Set<Identifier> signers) implements UnifiedReceipt {
            @Override public SignatureFormat format() {
                // Would need to convert signers to signatures
                throw new UnsupportedOperationException("Conversion requires signature lookup");
            }
        }

        SignatureFormat format();

        static UnifiedReceipt bls(BLSAggregate aggregate) { return new BLSReceipt(aggregate); }
        static UnifiedReceipt ed25519(Set<Identifier> signers) { return new Ed25519Receipt(signers); }
    }
}
```

### 7.2 MigrationStateTracker

```java
package com.hellblazer.delos.witness.aggregation;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks Ed25519 to BLS migration state across epochs.
 */
public final class MigrationStateTracker {

    public enum MigrationPhase {
        ED25519_ONLY,       // All receipts use Ed25519
        HYBRID_ACCEPTING,   // Accept both, prefer Ed25519
        HYBRID_PREFERRING,  // Accept both, prefer BLS
        BLS_ONLY           // All receipts use BLS
    }

    public record PhaseTransition(
        MigrationPhase from,
        MigrationPhase to,
        long epoch,
        Instant timestamp,
        String reason
    ) {}

    private final AtomicReference<MigrationPhase> currentPhase;
    private final ConcurrentHashMap<Long, PhaseTransition> transitions = new ConcurrentHashMap<>();

    public MigrationStateTracker() {
        this(MigrationPhase.ED25519_ONLY);
    }

    public MigrationStateTracker(MigrationPhase initialPhase) {
        this.currentPhase = new AtomicReference<>(initialPhase);
    }

    public void transitionTo(MigrationPhase newPhase, long epoch, String reason) {
        var current = currentPhase.get();
        if (validateTransition(current, newPhase)) {
            if (currentPhase.compareAndSet(current, newPhase)) {
                transitions.put(epoch, new PhaseTransition(current, newPhase, epoch, Instant.now(), reason));
            }
        }
    }

    public MigrationPhase getCurrentPhase() {
        return currentPhase.get();
    }

    public boolean shouldUseBLS() {
        var phase = currentPhase.get();
        return phase == MigrationPhase.HYBRID_PREFERRING || phase == MigrationPhase.BLS_ONLY;
    }

    public boolean acceptsBLS() {
        var phase = currentPhase.get();
        return phase != MigrationPhase.ED25519_ONLY;
    }

    public Map<Long, PhaseTransition> getTransitionHistory() {
        return Map.copyOf(transitions);
    }

    private boolean validateTransition(MigrationPhase from, MigrationPhase to) {
        // Only forward transitions allowed
        return to.ordinal() > from.ordinal();
    }
}
```

---

## 8. Testing Architecture

### 8.1 Test Structure

```
witness-service/src/test/java/com/hellblazer/delos/witness/aggregation/
    SignatureAccumulatorTest.java          (30 tests)
    BLSReceiptAggregatorTest.java          (25 tests)
    AggregateValidatorTest.java            (20 tests)
    AggregateWitnessReceiptTest.java       (15 tests)
    ReceiptCompatibilityLayerTest.java     (10 tests)
    MigrationStateTrackerTest.java         (10 tests)
    AggregationIntegrationTest.java        (20 tests)
    AggregationConcurrencyTest.java        (15 tests)
    AggregationPerformanceTest.java        (10 tests)
    support/
        BLSTestFixtures.java
        MockCommitteeProvider.java
```

### 8.2 Test Coverage Requirements

| Component | Line Coverage | Branch Coverage |
|-----------|--------------|-----------------|
| SignatureAccumulator | >95% | >90% |
| BLSReceiptAggregator | >90% | >85% |
| AggregateValidator | >95% | >90% |
| AggregateWitnessReceipt | >95% | >90% |
| Overall | >90% | >85% |

### 8.3 Performance Benchmarks

| Metric | Target | Measurement Method |
|--------|--------|-------------------|
| Accumulation latency | <50us | JMH microbenchmark |
| Aggregate creation | <1ms | JMH microbenchmark |
| Aggregate verification | <5ms | JMH microbenchmark |
| Memory per signature | <200 bytes | Profiler measurement |
| Throughput | >10,000/sec | JMH throughput |

---

## 9. Implementation Guide

### 9.1 Sub-Phase Breakdown

**Sub-Phase 1: Core Abstractions (8 hours)**
1. Create sealed interfaces
2. Write unit tests for each
3. Create internal support classes

**Sub-Phase 2: SignatureAccumulator (12 hours)**
1. Write SignatureAccumulatorTest (RED)
2. Implement SignatureAccumulator (GREEN)
3. Refactor for optimal concurrency
4. Concurrency stress tests

**Sub-Phase 3: BLSReceiptAggregator + Validator (16 hours)**
1. Write BLSReceiptAggregatorTest (RED)
2. Implement BLSReceiptAggregator (GREEN)
3. Write AggregateValidatorTest (RED)
4. Implement AggregateValidator (GREEN)
5. Integration tests

**Sub-Phase 4: Proto + Integration (12 hours)**
1. Update witness.proto
2. Regenerate proto classes
3. Implement AggregateWitnessReceipt
4. Implement compatibility layer
5. WitnessReceiptManagerV2 integration
6. End-to-end tests

### 9.2 File Creation Order

1. `SignatureFormat.java`
2. `AccumulationResult.java`
3. `AggregationResult.java`
4. `ValidationResult.java`
5. `internal/CompressedBitmap.java`
6. `SignatureAccumulatorTest.java`
7. `SignatureAccumulator.java`
8. `AggregateValidatorTest.java`
9. `AggregateValidator.java`
10. `BLSReceiptAggregatorTest.java`
11. `BLSReceiptAggregator.java`
12. `witness.proto` (update)
13. `AggregateWitnessReceiptTest.java`
14. `AggregateWitnessReceipt.java`
15. `ReceiptCompatibilityLayer.java`
16. `MigrationStateTracker.java`
17. `WitnessReceiptManagerV2.java`
18. `AggregationIntegrationTest.java`
19. `AggregationConcurrencyTest.java`
20. `AggregationPerformanceTest.java`

### 9.3 Checkpoint Validation

After each sub-phase:
- [ ] All new tests pass
- [ ] No existing tests broken
- [ ] Code coverage >90% for new code
- [ ] No compiler warnings
- [ ] Performance targets met (if applicable)

---

## 10. Quality Checklists

### 10.1 Code Review Criteria

- [ ] All public methods have JavaDoc with @throws
- [ ] No synchronized blocks (use concurrent collections)
- [ ] Defensive copies for all byte arrays
- [ ] Null checks with Objects.requireNonNull
- [ ] Sealed interfaces for result types
- [ ] Records for immutable data
- [ ] var used for local variables
- [ ] No raw types

### 10.2 Security Review

- [ ] PoP verified before using public keys
- [ ] Bitmap bounds checked
- [ ] Epoch freshness validated
- [ ] Committee membership verified
- [ ] No timing side channels

### 10.3 Performance Review

- [ ] Accumulation: O(1) per signature
- [ ] Aggregate creation: <1ms
- [ ] Aggregate verification: <5ms
- [ ] Memory: <200 bytes per accumulated signature
- [ ] Virtual thread compatible (no pinning)

---

## Appendix A: Storage Comparison

| Format | 7 Witnesses | Formula |
|--------|-------------|---------|
| Ed25519 | 448 bytes | 7 * 64 bytes |
| BLS Aggregate | 97 bytes | 96 + ceil(7/8) bytes |
| **Reduction** | **78%** | |

## Appendix B: Dependencies

```xml
<!-- Required dependencies in witness-service/pom.xml -->
<dependency>
    <groupId>com.hellblazer.delos</groupId>
    <artifactId>cryptography</artifactId>
    <!-- BLSOperations, BLSSignature, BLSAggregate, BLSPublicKey -->
</dependency>
<dependency>
    <groupId>com.hellblazer.delos</groupId>
    <artifactId>grpc</artifactId>
    <!-- Generated proto classes -->
</dependency>
```

## Appendix C: Glossary

| Term | Definition |
|------|------------|
| BLS | Boneh-Lynn-Shacham signature scheme |
| M-of-N | Threshold signature (M signatures from N committee) |
| Bitmap | Compact encoding of signer positions |
| PoP | Proof of Possession (prevents rogue key attacks) |
| Epoch | Fireflies view identifier |
| CHOAM | Committee-based replicated state machine |

---

*Document Version: 1.0*
*Phase: 1B-2*
*Author: java-architect-planner*
*Date: 2026-01-19*
