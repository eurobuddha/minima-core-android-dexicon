# Dexicon (Android)

Native Android companion of the **Dexicon** MiniDapp (`mds/dexicon`): a decentralised DEX for **MINIMA ⇄ MxUSD**
where your own Minima node is the account. Ed25519-signed orders travel over interchangeable relays; a fill is one
atomic two-party Minima transaction (taker signs, maker's node co-signs), final after one block.

The protocol is defined by `mds/dexicon/SPEC.md` and this app interoperates with the MiniDapp byte-for-byte:
same identity derivation (HKDF from the node seed, context `"decks"` — a protocol constant), same canonical JSON and
signatures (`spec/vectors/order-v1-vector.json` is a unit test here), same relay API, same fill rules.

> Development software. Real funds, real chain. Every code change bumps `versionCode` + `versionName`
> (pre-commit hook: `sh .githooks/install.sh` once per clone).

## What it does

- **TRADE** — ticker (book mid / spread), grouped depth ladder with cumulative depth bars, BUY/SELL panel with
  percent chips, your open orders with edit / cancel. A price that crosses the book TAKES the best resting order
  (one maker per take, the amount capped at that order); otherwise the order RESTS on the relays backed by your
  wallet coins.
- **ORDERS** — open orders (+ takes awaiting the maker) and your fills with SETTLING / DONE / LOST badges.
- **HISTORY** — every fill, net totals, CSV export with stable column names (see below).
- **ASSETS** — available / in orders / settling per token, receive address and Dexicon publicId (full value,
  tap-to-copy), DEV override of the dollar token for test chains.
- **RELAYS** — the relay list with health, add / remove, and bootstrap discovery via `GET /dex/relays`
  (discovered entries are marked; a relay without that endpoint is tolerated).
- **Maker engine** — a foreground service (`maker/MakerService`) polls the relay inbox, verifies each sealed
  `take` against YOUR order (`FillPlan.verifyAsMaker`), co-signs, posts, replies `taken`/`refused`, republishes the
  remainder, re-locks backing coins older than `COIN_MAX_AGE` blocks, and tracks settlement by output coin ids —
  while the phone is in your pocket (Doze-proof heartbeat alarm, restarts after reboot / app swipe).

Identifiers (addresses, coin ids, txpowids, public ids, relay URLs) are NEVER truncated anywhere; a tap copies the
complete value. The dollar token is always **MxUSD**
(`0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90`).

## Layout

```
identity/   DexIdentity (seed → X25519 box + Ed25519 sign keypairs, publicId = boxPk‖signPk, seal/open), Hkdf, Hex, Sodium
envelope/   Json, Envelope, Ed25519, Hex — verbatim copies of maxima/dexrelay (the relay's shape + signature rules); Envelopes = signing
relay/      RelayClient (multi-relay union by (signPk, orderId, highest seq), long-poll book + inbox, fan-out publish, discovery), HttpTransport, RelayStore
fill/       FillPlan (plan + verifyAsMaker, exact 64-digit BigDecimal, maker-favour rounding), FillTxn (taker build / maker cosign via the node), Coin, Tokens
maker/      MyOrder, MakerEngine (create/edit/cancel/afterFill/refresh + relock), MakerService (foreground), HeartbeatReceiver, BootReceiver
            MainActivity, TradeView, OrdersTab, HistoryTab, AssetsTab, RelaysTab, TradeExport, DexDb, Design, NodeApi (+ NodeTransport*), SignGate
minimaapi/  the vendored Minima Core IPC SDK sources (from apks/pandadex) — the ONLY way this app reaches the node
```

Node access is broadcast-Intent IPC through `minimaapi` exactly as PandaDEX does (`NodeApi` → `NodeTransport` →
`NodeTransportService` in its own process → `MinimaAPI`). The node must grant Dexicon WRITE trust: the identity is
derived from `vault action:seed`, and fills are signed with `txnsign publickey:auto` (never the `sign` command).

## Fill protocol hard rules (from SPEC §5, implemented in `fill/FillTxn`)

- Taker runs `newscript script:"<maker script>" trackall:false` for every advertised script before `txninput`.
- `txninput … scriptmmr:true` puts script + MMR proof into the witness; the maker must NOT run `txnbasics` on import.
- Maker: `txnview` → verify → `txnimport` → `txnsign publickey:auto` → `txncheck` gate → `txnpost`; every error path
  runs `txndelete`.
- `txnpost`'s txpowid is PRE-MINING: settlement is proven by `coins coinid:<output coin>`, not by the txpowid.

## CSV export (HISTORY → EXPORT CSV)

Column names are stable — append, never rename or reorder:

```
timestamp_utc,block,side,role,minima_delta,mxusd_delta,minima_amount,price,mxusd_notional,order_id,txpowid,proceeds_coinids,counterparty,status
```

## Build

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM tests: vector signature, canonical JSON, fill plan + maker refusals, relay union
```

Gradle uses Android Studio's bundled JDK 21 (`gradle.properties`); `local.properties` must point at the Android SDK
(`sdk.dir=/Users/<you>/Library/Android/sdk`). macOS has no `timeout` — do not wrap gradle in one.

## Test relays / nodes

Never run it against minimaDesk (the slush node). Use a disposable `-solo` node and a relay
(`maxima-server.jar --dex-port <port>`); point the ASSETS dev override at a test token for the dollar leg.
