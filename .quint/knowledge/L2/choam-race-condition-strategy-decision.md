---
scope: CHOAM module threading model, lines 91, 543-627, 754-793
kind: system
content_hash: c4ea501c63cfb45a4818e2e259295b6d
---

# Hypothesis: CHOAM Race Condition Strategy Decision

Decision point: How to fix the 4 CHOAM race conditions (qq9, 0ct, an5, yl3). All affect CHOAM.java and share threading model. Options include unified refactor, incremental fixes, or lock-free redesign.

## Rationale
{"anomaly": "4 related race conditions in CHOAM blocking consensus stability and architecture refactoring", "approach": "Group related technical decisions", "alternatives_rejected": []}