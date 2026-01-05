---
kind: system
scope: KeyEventProcessor.java lines 51-71, KERI authentication
content_hash: 55df45f36f13baea765ba0f95a3cb426
---

# Hypothesis: Stereotomy KERI Auth Implementation Strategy

Implement KeyEventProcessor authentication (Delos-rsj) per KERI specification:

**Validation Steps (per KERI spec):**
1. **Signature Verification:** Verify event signature against current key state
2. **Event Chain Integrity:** Validate prior event hash (said) matches
3. **Sequence Validation:** Check sequence number is next expected
4. **Witness Threshold:** For applicable events, verify witness receipts meet threshold

**Implementation Approach:**
```java
public boolean authenticate(KeyEvent event, KeyState currentState) {
    // 1. Signature verification
    var verifier = currentState.getVerifier();
    if (!verifier.verify(event.getSignature(), event.toBytes())) {
        log.warn("Invalid signature for event: {}", event.getIdentifier());
        return false;
    }
    
    // 2. Event chain integrity
    if (!event.getPriorEventDigest().equals(currentState.getLastEventDigest())) {
        log.warn("Event chain broken for: {}", event.getIdentifier());
        return false;
    }
    
    // 3. Sequence validation
    if (event.getSequenceNumber() != currentState.getSequenceNumber() + 1) {
        log.warn("Invalid sequence for: {}", event.getIdentifier());
        return false;
    }
    
    // 4. Witness threshold (for establishment events)
    if (event.isEstablishment() && !meetsWitnessThreshold(event, currentState)) {
        log.warn("Insufficient witness receipts for: {}", event.getIdentifier());
        return false;
    }
    
    return true;
}
```

**Test Strategy:**
- Unit tests with valid/invalid events
- Test signature forgery rejection
- Test out-of-order event rejection
- Test insufficient witness rejection

## Rationale
{"anomaly": "Key events processed without authentication verification", "approach": "Implement per KERI specification for cryptographic integrity", "alternatives_rejected": ["Skip witness check - violates KERI spec", "Async validation - security risk"]}