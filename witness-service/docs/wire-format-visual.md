# Wire Format Visual Guide

## Segment Table Structure

```
┌─────────────────────────────────────────────────────────────────┐
│ SEGMENT TABLE                                                   │
├─────────────────────────────────────────────────────────────────┤
│ VarInt: segmentCount (e.g., 3)                                  │
├─────────────────────────────────────────────────────────────────┤
│ SEGMENT 0                                                       │
│   byte:   type = 1 (RUN_LENGTH)                                 │
│   VarInt: epochCount = 10                                       │
│   VarInt: dataLength = 250                                      │
├─────────────────────────────────────────────────────────────────┤
│ SEGMENT 1                                                       │
│   byte:   type = 2 (DELTA_BITMAP)                               │
│   VarInt: epochCount = 5                                        │
│   VarInt: dataLength = 180                                      │
├─────────────────────────────────────────────────────────────────┤
│ SEGMENT 2                                                       │
│   byte:   type = 0 (LITERAL)                                    │
│   VarInt: epochCount = 1                                        │
│   VarInt: dataLength = 320                                      │
└─────────────────────────────────────────────────────────────────┘
```

## Example Encoding

For 3 segments: RUN_LENGTH(10 epochs, 250 bytes), DELTA_BITMAP(5 epochs, 180 bytes), LITERAL(1 epoch, 320 bytes)

```
Byte Stream:
┌─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┬─────┐
│ 03  │ 01  │ 0A  │FA01 │ 02  │ 05  │B401 │ 00  │ 01  │C002 │     │     │     │
└─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┴─────┘
   │     │     │     │     │     │     │     │     │     │
   │     │     │     │     │     │     │     │     │     └─ Segment 2 dataLength: 320 (VarInt: 0xC002)
   │     │     │     │     │     │     │     │     └─ Segment 2 epochCount: 1
   │     │     │     │     │     │     │     └─ Segment 2 type: LITERAL (0)
   │     │     │     │     │     │     └─ Segment 1 dataLength: 180 (VarInt: 0xB401)
   │     │     │     │     │     └─ Segment 1 epochCount: 5
   │     │     │     │     └─ Segment 1 type: DELTA_BITMAP (2)
   │     │     │     └─ Segment 0 dataLength: 250 (VarInt: 0xFA01)
   │     │     └─ Segment 0 epochCount: 10
   │     └─ Segment 0 type: RUN_LENGTH (1)
   └─ Segment count: 3
```

## VarInt Encoding Examples

| Value | Bytes | Hex | Explanation |
|-------|-------|-----|-------------|
| 3 | 1 | `03` | Small values fit in 1 byte |
| 10 | 1 | `0A` | Values 0-127 use 1 byte |
| 127 | 1 | `7F` | Max 1-byte value |
| 128 | 2 | `80 01` | First 2-byte value |
| 180 | 2 | `B4 01` | (0xB4 & 0x7F) + ((0x01) << 7) = 52 + 128 = 180 |
| 250 | 2 | `FA 01` | (0xFA & 0x7F) + ((0x01) << 7) = 122 + 128 = 250 |
| 320 | 2 | `C0 02` | (0xC0 & 0x7F) + ((0x02) << 7) = 64 + 256 = 320 |
| 16,383 | 2 | `FF 7F` | Max 2-byte value |
| 16,384 | 3 | `80 80 01` | First 3-byte value |

## Segment Type Encoding

```
┌──────────────┬─────────┬────────────────────────────────────────┐
│ Type         │ Ordinal │ Wire Byte                              │
├──────────────┼─────────┼────────────────────────────────────────┤
│ LITERAL      │    0    │ 0x00                                   │
│ RUN_LENGTH   │    1    │ 0x01                                   │
│ DELTA_BITMAP │    2    │ 0x02                                   │
└──────────────┴─────────┴────────────────────────────────────────┘
```

## Usage Example

```java
// Encoding
var segments = List.of(
    new Segment(SegmentType.RUN_LENGTH, 0, 9),    // 10 epochs
    new Segment(SegmentType.DELTA_BITMAP, 10, 14), // 5 epochs
    new Segment(SegmentType.LITERAL, 15, 15)       // 1 epoch
);
var dataLengths = List.of(250, 180, 320);

byte[] encoded = SegmentTable.encode(segments, dataLengths);

// Decoding
ByteBuffer buffer = ByteBuffer.wrap(encoded);
List<SegmentTableEntry> decoded = SegmentTable.decode(buffer);

// Results in:
// decoded.get(0) = SegmentTableEntry(RUN_LENGTH, 10, 250)
// decoded.get(1) = SegmentTableEntry(DELTA_BITMAP, 5, 180)
// decoded.get(2) = SegmentTableEntry(LITERAL, 1, 320)
```

## Complete Receipt Wire Format (Future)

```
┌─────────────────────────────────────────────────────────────────┐
│ RECEIPT HEADER                                                  │
│   VarInt: baseAggregate.length                                  │
│   bytes:  baseAggregate (HierarchicalAggregate proto)           │
│   VarInt: startEpoch                                            │
│   VarInt: endEpoch                                              │
│   VarInt: totalUniqueSigners                                    │
│   VarInt: eventCoords.length                                    │
│   bytes:  eventCoords (EventCoords proto)                       │
├─────────────────────────────────────────────────────────────────┤
│ SEGMENT TABLE (implemented in Delos-4034)                       │
│   VarInt: segmentCount                                          │
│   For each segment:                                             │
│     byte:   segmentType                                         │
│     VarInt: epochCount                                          │
│     VarInt: dataLength                                          │
├─────────────────────────────────────────────────────────────────┤
│ SEGMENT DATA (Delos-4035/4036)                                  │
│   For each segment:                                             │
│     bytes[dataLength]: segment-specific compressed data         │
└─────────────────────────────────────────────────────────────────┘
```

## Edge Cases Handled

### Empty Segment Table
```
VarInt: 0  →  [0x00]
```
Decodes to empty list.

### Single Segment
```
VarInt: 1     →  [0x01]
byte:   0     →  [0x00]  (LITERAL)
VarInt: 1     →  [0x01]
VarInt: 42    →  [0x2A]
```
Total: 4 bytes

### VarInt Boundaries
- **16,383 epochs**: Uses 2 bytes `[0xFF, 0x7F]`
- **16,384 epochs**: Uses 3 bytes `[0x80, 0x80, 0x01]`

Tested and verified in testVarIntEdgeCases().

## Buffer Position Management

The decode() method properly advances the ByteBuffer position:

```java
ByteBuffer buffer = ByteBuffer.wrap(data);
int initialPosition = buffer.position();        // e.g., 0

List<SegmentTableEntry> entries = SegmentTable.decode(buffer);

int bytesConsumed = buffer.position() - initialPosition;
// buffer.position() now points to next data (e.g., segment data)
```

This allows streaming decoding of the complete wire format.
