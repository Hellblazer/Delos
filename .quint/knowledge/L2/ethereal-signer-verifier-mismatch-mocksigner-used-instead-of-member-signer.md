---
scope: ethereal module signature validation in CHOAM integration
kind: system
content_hash: 2260d64a818aab233eb3e3a85115b1db
---

# Hypothesis: Ethereal signer/verifier mismatch - MockSigner used instead of member signer

Producer.java and GenesisAssembly.java create Ethereal Config using producerParams.ethereal().clone() which inherits the default MockSigner from Config.Builder line 51. Neither class calls setSigner() with the member's actual signer. The verifiers array from view.verifiersByPid() contains real Member verifiers that expect signatures from the member's actual key. Result: all commit/prevote signature validations fail because MockSigner signs with a mock key that doesn't match any verifier.

## Rationale
{"anomaly":"CI tests DynamicTest.smokin, TestCHOAM.submitMultiplTxn, MembershipTests.genesisBootstrap all stuck in REGENERATING state - consensus never starts","approach":"Trace signer used in Adder.commit/prevote (conf.signer()) back to Config.Builder default, compare with verifiers from view.verifiersByPid()","alternatives_rejected":"Verifier array indexing bug - ruled out because code looks correct; Signature algorithm mismatch - ruled out because both use DEFAULT"}