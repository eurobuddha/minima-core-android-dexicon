# dexicon (APK) — working rules

## Versioning guardrail — every code change ships with a version bump

Real funds, real chain. NEVER change code without bumping the version
(`versionCode` + `versionName` in `app/build.gradle`), so every committed state is distinct, reversible and
trackable. One logical change = one version = one commit = one push, in order. Enforced by a pre-commit hook
(`.githooks/pre-commit`, install once: `sh .githooks/install.sh`) that blocks a code change with no version bump.
Do NOT bypass with `--no-verify`. Docs/config-only commits need no bump.

## Protocol pointers — the MiniDapp defines the wire format

- `mds/dexicon/SPEC.md` is the protocol: §2 identity (HKDF context `"decks"` — protocol constant, never rename),
  §3 signed envelope + canonical JSON subset, §4 relay API, §5 two-party fill.
- Reference implementation: `mds/dexicon/lib/{identity,envelope,relay,fill,maker}.js` + `service.js`. The Java here
  is a port; when the two disagree, the JS + `spec/vectors/order-v1-vector.json` win (VectorTest must stay green).
- `envelope/{Json,Envelope,Ed25519,Hex}.java` are verbatim copies of `maxima/dexrelay` (the relay cape). Re-copy,
  don't hand-edit, when the relay changes (only `Envelope.scripts` and the `BigInteger.TWO` → `valueOf(2)` minSdk
  patch differ).

## APK changes mirror to the MDS pair (and vice versa)

Never fix something here without mirroring it to `mds/dexicon` (and the desktop vendor copy, when it exists).
Fill math, verifier rules, envelope shape, relay semantics and CSV column names must stay identical across the pair.

## Hard rules

- Node access ONLY via the vendored `minimaapi` IPC (`NodeApi`), as PandaDEX does. Never the `sign` command.
- Every txn error path runs `txndelete`; `txnpost`'s txpowid is pre-mining — settle by output coin ids.
- Taker: `newscript … trackall:false` before `txninput`, `txninput … scriptmmr:true`; maker: no `txnbasics`.
- Identifiers are never truncated; tap-to-copy copies the full value. The dollar token is **MxUSD** (never MxUSDT).
- Never run minimaDesk; never install on a device or publish to a store from an agent session.

## DOORS — the vendored AtomiX taker engine (since 0.1.15)

- `com/eurobuddha/{atomix,comms,minimaapi/ethereum}/**` and their tests are AtomiX's own files, copied VERBATIM by
  `scripts/vendor-atomix.sh` (one header line each). Never hand-edit them; re-vendor after an AtomiX release and run
  `scripts/vendor-atomix.sh --check` before every commit that touches `app/src`. Dexicon-owned exceptions:
  `comms/NodeApi.java` (adapter onto `dexicon.NodeApi`) and `atomix/MainActivity.java` (only `SWEEP_ACTIVE`).
- **Ethereum RPC endpoint list: no fifth copy.** The list exists here ONLY inside the vendored `atomix/eth/EthRpc.java`
  / `EthNet.java`, byte-identical to apks/atomix (copy 1 of AtomiX's four-copy rule); `--check` fails on drift and on
  any other file containing the list. When AtomiX changes its list, re-vendor here (and in mds/dexicon `doors/`).
- AtomiX's operational rules apply unchanged: drain in-flight door swaps before any wallet / key-mode migration; the
  persisted `swap_addr`/`swap_pk` (prefs `dexicon_doors`) is adopted, never self-healed — IdentityWatch HALTS new door
  swaps on a mismatch while settlement continues. Dexicon only TAKES: `SwapEngine.setMyOrder` is never called.
- Both AtomiX markets: the engine takes in `TradingContext.active()` only, so `DoorHost` selects the route's market
  (`TradingContext.setActive(m, null)` + `MinimaHtlc.setActiveToken`) for a take and holds it — no book scan, no second
  take — until every part has called back; book scans run one market at a time. Settlement is market-agnostic.
