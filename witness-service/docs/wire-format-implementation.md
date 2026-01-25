# Wire Format Implementation Summary

**Task**: Delos-4034 - Implement Wire Format Specification
**Date**: 2026-01-25
**Status**: ✅ Complete - All tests pass

## Overview

Implemented self-describing binary format for storing compressed segments in the Delos witness service. The wire format enables single-pass decompression by providing segment metadata upfront.

## Files Created

### 1. SegmentTableEntry.java
**Path**: `src/main/java/.../compression/SegmentTableEntry.java`
**Purpose**: Immutable record representing one segment table entry in wire format

```java
public record SegmentTableEntry(
    SegmentType type,      // LITERAL, RUN_LENGTH, or DELTA_BITMAP
    int epochCount,        // Number of epochs in this segment
    int dataLength         // Byte length of compressed segment data
)
```

**Features**:
- Validation in compact constructor (non-null type, non-negative counts)
- Immutable value object for thread safety

### 2. SegmentTable.java
**Path**: `src/main/java/.../compression/SegmentTable.java`
**Purpose**: Static utility class for encoding/decoding segment tables

**Methods**:
- `encode(List<Segment>, List<Integer>)` → `byte[]`
  - Encodes segments and their data lengths to wire format
  - Uses VarInt encoding for compact representation
  - Validates matching list sizes

- `decode(ByteBuffer)` → `List<SegmentTableEntry>`
  - Decodes segment table from buffer
  - Advances buffer position correctly
  - Validates type bytes and handles errors

### 3. SegmentTableTest.java
**Path**: `src/test/java/.../compression/SegmentTableTest.java`
**Purpose**: Comprehensive TDD test suite (9 tests)

## Wire Format Specification

```
SEGMENT TABLE:
  VarInt: segmentCount
  For each segment:
    byte:   segmentType (0=LITERAL, 1=RUN_LENGTH, 2=DELTA_BITMAP)
    VarInt: epochCount (epochs in this segment)
    VarInt: dataLength (compressed bytes for this segment)
```

## Test Coverage

| Test | Purpose | Status |
|------|---------|--------|
| testSegmentTableRoundTrip | 3 segments, different types, encode/decode | ✅ Pass |
| testSingleSegmentTable | Single LITERAL segment edge case | ✅ Pass |
| testLargeSegmentCount | 100 segments with varying types | ✅ Pass |
| testVarIntEdgeCases | 16383 (2-byte), 16384 (3-byte) boundaries | ✅ Pass |
| testSegmentTypeBytes | All 3 type bytes (0, 1, 2) | ✅ Pass |
| testEmptySegmentTable | Zero segments edge case | ✅ Pass |
| testBufferPositionAdvancement | Buffer position after decode | ✅ Pass |
| testMismatchedLengthsThrowsException | Input validation | ✅ Pass |
| testNullInputsThrowException | Null safety | ✅ Pass |

**Total**: 9/9 tests pass

## VarInt Encoding Details

Uses LEB128 (Little Endian Base 128) encoding from VarIntUtils:
- 0-127: 1 byte
- 128-16,383: 2 bytes
- 16,384-2,097,151: 3 bytes

Tested at boundaries:
- ✅ 16,383 epochs → 2-byte VarInt
- ✅ 16,384 epochs → 3-byte VarInt

## Segment Type Encoding

| Type | Ordinal | Byte Value |
|------|---------|------------|
| LITERAL | 0 | 0x00 |
| RUN_LENGTH | 1 | 0x01 |
| DELTA_BITMAP | 2 | 0x02 |

## Integration Points

This implementation prepares for:
- **Delos-4035**: HybridStrategy.encode() - uses `SegmentTable.encode()`
- **Delos-4036**: HybridStrategy.decode() - uses `SegmentTable.decode()`

## Build & Test Results

```bash
./mvnw test -pl witness-service -Dtest="SegmentTableTest"
# Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
# BUILD SUCCESS

./mvnw test -pl witness-service -Dtest="*Strategy*Test,SegmentTableTest"
# Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
# BUILD SUCCESS
```

## Success Criteria

✅ All test cases pass (9/9)
✅ Code compiles without warnings
✅ VarInt boundary conditions handled correctly (16383, 16384)
✅ All 3 segment types correctly encoded/decoded
✅ Empty and single-segment cases handled
✅ Round-trip: encode then decode produces identical data
✅ Thread-safe: All methods stateless
✅ Null-safe: Proper validation and exceptions
✅ Ready for Delos-4035 (HybridStrategy.encode)

## Key Design Decisions

1. **Immutable data structures**: SegmentTableEntry is a record, encode/decode return immutable lists
2. **Static utilities**: SegmentTable has no instance state, thread-safe
3. **Validation**: Comprehensive null checks and size validation
4. **Error handling**: Clear CompressionException messages for decode failures
5. **Buffer safety**: decode() properly advances buffer position for streaming use

## Dependencies

- `VarIntUtils.java` - LEB128 encoding/decoding (existing)
- `SegmentType.java` - Enum from Delos-4033
- `Segment.java` - Record from Delos-4033
- `CompressionException.java` - Runtime exception for compression errors

## Next Steps

1. **Delos-4035**: Implement HybridStrategy.encode() using SegmentTable.encode()
2. **Delos-4036**: Implement HybridStrategy.decode() using SegmentTable.decode()
3. Integration testing with full receipt compression pipeline
