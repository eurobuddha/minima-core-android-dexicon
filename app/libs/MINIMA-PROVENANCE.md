# minima.jar (vendored from apks/pandapools/app/libs)

Byte-identical copy of PandaPools' pinned Minima wire codecs, used only by the vendored PandaPools MegaMMR recovery
(`MegaArchive`, `MegaArchiveProofs`): it asks the unpruned MegaMMR on eurobuddha.com:9001 for a pool's public reserve
proofs. No embedded node is started. `scripts/vendor-pandapools.sh --check` fails if this file drifts from PandaPools'.
Provenance (source tree, SHA-256) is recorded in apks/pandapools/app/libs/MINIMA-PROVENANCE.md.

SHA-256: `2fc973bcbf37ebc8581e25a2a2b0669ffe92d528fb28af47d5d5582265d59532`
