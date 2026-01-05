---
assurance_level: L2
carrier_ref: test-runner
valid_until: 2026-03-31
date: 2025-12-31
id: 2025-12-31-internal-ethereal-signer-verifier-mismatch-mocksigner-used-instead-of-member-signer.md
type: internal
target: ethereal-signer-verifier-mismatch-mocksigner-used-instead-of-member-signer
verdict: pass
content_hash: a4514ba78563b8fc22537b4bd9842d3b
---

Test TestCHOAM#submitMultiplTxn FAILED with all 5 nodes stuck in 'Formation state: REGENERATING'. Log shows 'Gathering next assembly' but GenesisAssembly never completes because Ethereal consensus fails to make progress. Code trace confirms: (1) Producer.java line 76 clones ethereal config but never calls setSigner(), (2) Config.Builder line 51 defaults signer to MockSigner, (3) Adder.commit() line 550 and prevote() line 713 use conf.signer() which is MockSigner, (4) Adder validate() lines 775-815 use verifiers[] from view.verifiersByPid() which returns real Member verifiers. MockSigner signature cannot be validated by Member verifiers - signature mismatch causes all commits/prevotes to be rejected, consensus never reaches quorum.