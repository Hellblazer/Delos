# Analysis: [Topic]

**Date**: [YYYY-MM-DD]
**Analyst**: [Your name]
**Related Bead**: THOTH-xxx
**Status**: [draft/complete/integrated]

## Purpose

[Why are you doing this analysis? What question are you trying to answer?]

## Research Question

[Clear statement of what you're investigating]

## Background

### Current Understanding
[What do we already know about this topic?]

### Why This Matters
[Why should we care about understanding this better?]

### Related Prior Work
- Learning: [Link to .pm/learnings/Ln-title.md]
- Hypothesis: [Link to .pm/hypotheses/Hn-title.md]
- Prior Analysis: [Link to other analysis]

## Investigation Approach

### Method 1: Code Review
[What code are you reviewing and why?]

```
File: [Path]
Lines: [Range]
Focus: [What are you looking for?]
```

### Method 2: Test Analysis
[What tests illuminate this topic?]

```bash
Test Class: [TestClass]
Test Method: [testMethod]
What It Shows: [What does this test reveal?]
```

### Method 3: Byzantine FT Scenario
[What Byzantine scenario is relevant?]

```
Setup: f Byzantine nodes in 3f+1 setup
Scenario: [Describe the scenario]
Expected Behavior: [What should happen?]
Questions: [What does this help answer?]
```

### Method 4: Research/Documentation
[External sources or documentation consulted?]

- Source 1: [Title and link/reference]
- Source 2: [Title and link/reference]

## Findings

### Finding 1: [Title]

**Discovery**: [What did you find?]

**Evidence**:
- Code reference: [File and lines]
- Test results: [What tests show this?]
- Scenario outcome: [What happened in BFT scenario?]

**Implications**: [What does this mean for Thoth development?]

### Finding 2: [Title]

**Discovery**: [What did you find?]

**Evidence**:
- Code reference: [File and lines]
- Test results: [What tests show this?]
- Scenario outcome: [What happened in BFT scenario?]

**Implications**: [What does this mean for Thoth development?]

## Byzantine FT Analysis (If Applicable)

### Does This Finding Affect BFT Guarantees?

- [ ] Yes - affects consensus model
- [ ] Yes - affects rebalancing correctness
- [ ] Yes - affects witness majority requirements
- [ ] Yes - affects KERL consistency
- [ ] No - localized concern
- [ ] Unclear - needs further investigation

### BFT Risk Assessment

**Risk Level**: [Low/Medium/High]

**Potential Issues**:
1. [Issue 1]: [How could this fail with Byzantine nodes?]
2. [Issue 2]: [What consistency problem could arise?]

**Mitigation Strategy**: [How do we address this?]

## Conclusions

### Answer to Research Question

[Did you find the answer? What is it?]

### Key Takeaways

1. [Summary 1]
2. [Summary 2]
3. [Summary 3]

### Confidence Level

**Confidence**: [High/Medium/Low]

**Why**: [What gives you confidence (or lack thereof)?]

## Action Items

### Immediate Actions
- [ ] **Action 1**: [What should be done]
  - Related Bead: THOTH-xxx
  - Priority: [High/Medium/Low]
  - Complexity: [Simple/Medium/Complex]

### Follow-up Investigations
- [ ] [Question to explore further]
- [ ] [Hypothesis to test]
- [ ] [Code to review]

### Documentation/Knowledge Updates
- [ ] Create learning: `.pm/learnings/L{n}-{title}.md`
- [ ] Create hypothesis: `.pm/hypotheses/H{n}-{title}.md`
- [ ] Update CONTEXT_PROTOCOL.md if needed
- [ ] Store in ChromaDB with ID: `decision::thoth::{topic}`

## Related Analyses

### Prior Analyses
- [ANALYSIS-YYYY-MM-DD-topic.md]
- [ANALYSIS-YYYY-MM-DD-topic.md]

### Follow-up Analyses Needed
- [Topic to analyze next]
- [Related topic to investigate]

## Notes for Future Reference

[Any additional context, gotchas, or important details to remember]

---

## Analysis Metadata

- **Duration**: [Hours spent]
- **Thoroughness**: [Shallow/Medium/Deep]
- **Methods Used**: [List of investigation methods]
- **Artifacts Created**: [Files created/modified]
- **Status**: [draft/complete/needs-revision/integrated]

