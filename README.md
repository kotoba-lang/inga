# inga — 因果

**The consensus plane of the kotoba stack.** Ordering, state, execution.

Implements [ADR-2608038000](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2608038000-inga-consensus-holochain-write-plane-filecoin-state-plane.edn)
(`com-junkawasaki/root`).

> ## ⚠ Three repos in this org use Buddhist causality words for different things
>
> | repo | 語 | what it is |
> |---|---|---|
> | [`engi`](https://github.com/kotoba-lang/engi) | 縁起 | **the ENGI/EN mutual-credit currency.** inga's first consumer, and where inga's consensus code is being extracted from. |
> | [`innen`](https://github.com/kotoba-lang/innen) | 因縁 | **a sourced dependency record over human history** (entities, contracts, events). Nothing to do with consensus. |
> | **`inga`** | 因果 | **this repo — the consensus plane.** Not a currency. Not a history record. |
>
> `inga` is one letter from `engi` and one concept from `innen`, and in
> `west.yml` `inga` and `innen` land on adjacent lines. The collision is
> known and accepted (superproject ADR-2608038000 D5, same treatment as
> `kotobase` / `kotobase-client` / `kotoba-client` in ADR-2607050900) — this
> table is the mitigation, so it is required to stay at the top of all three
> READMEs.

## Why the name

The structure is literally causal: a source chain is a causal history, a
committed state root is a causal cut, and validation asks whether this effect
followed from that cause.

## What is here today

| ns | what it owns |
|---|---|
| `inga.head` | the head record a quorum certifies — `kotobase.head/v1` with `issuer`/`sig` widened to a quorum certificate |
| `inga.ref` | a `kotobase.storage.core/IRefStore` whose compare-and-set is decided by the quorum, declaring `:linearizable-ref` |

Pure `.cljc`. No I/O, no crypto, no wall-clock — signature verification and
the quorum itself are injected, the same seam `kotobase.storage.signed-head`
uses for `sign-fn` and `engi.consensus` uses for `hash-fn`. The only
dependency is the storage contract it implements, which is itself
zero-dependency.

## The one idea

**A 2f+1 quorum certificate IS a conditional write.**

Two writers starting from the same observed head both ask the quorum to
certify sequence n+1. The quorum certifies at most one, so at most one
publishes. No `UPDATE … WHERE sequence = ?`, no `onlyIf.etagMatches`, no
`If-Match` — **no host primitive at all.**

That matters because the host primitive is exactly what was not portable.
Backblaze B2 has no conditional put on either API, IPNS publishes
unconditionally, and a content-addressed or erasure-coded network has nothing
to be conditional about. `kotobase.storage.signed-head` documented this
honestly, declared `:single-writer-ref`, and ended with:

> a correct deployment still puts one writer in front of it — a Durable
> Object, an actor, a lease.

**inga is that one writer, replaced by a quorum.** Same record shape, profile
raised to `:linearizable-ref`, and the object store underneath demoted to what
it is actually good at.

```clojure
(require '[kotobase.storage.core :as storage]
         '[inga.ref :as iref])

(storage/compose
 {:blocks <any immutable CID-addressed store>   ; B2 / R2 / S3 / IPFS / annex
  :refs   (iref/ref-store
           {:read-head!  (fn [ref-name] ...)     ; dumb read
            :write-head! (fn [ref-name head] ...) ; dumb UNCONDITIONAL write
            :propose!    (fn [record] ...)        ; -> {:certified? :cert :current}
            :verify-fn   (fn [bytes sig witness] ...)
            :quorum      3})})                    ; 2f+1 for n=3f+1
```

The block half needs `#{:immutable-blocks :cid-addressed-read}` and nothing
else. `compose` takes the ref profile from `:refs` alone, so a block store
that happens to implement `IRefStore` cannot lend its claim to the
composition — the separation is held by the type, not by convention.

This is what makes **ADR-2608039000** (`blockchain / 分散型経路に D1 を前提に
しない`) implementable rather than aspirational.

## Verification

```bash
clojure -M:test    # 15 tests, 45 assertions
```

The acceptance test is **kotobase's own conformance suite**, not one written
here:

```clojure
(contract/verify (storage/compose {:blocks (memory/memory-store) :refs refs})
                 check)
;; => {:profile :linearizable-ref :concurrency :verified}
```

`:concurrency :verified` means the suite ran its concurrent half — four real
JVM threads racing the same expected head — and found exactly one winner, with
every loser observing that winner.

### What that does NOT establish

**This repo is the adapter. It contains no consensus.** Safety — that two
conflicting certificates at the same height can never both form — is a
property of the quorum behind `propose!`, proved in that layer's own
equivocation tests. The suite here runs against a **reference quorum**: a
cooperative oracle that models at-most-one-per-height and nothing else. It
checks that the adapter refuses a loser, reports the winning head so a caller
can retry against the right base, and never claims a publish it cannot read
back.

Passing a conformance suite with an agreeable oracle is the easiest way to
believe something false, so the claim is stated narrowly on purpose.

Likewise the "signatures" in the tests are `witness|bytes` strings, not a
curve. `verify-fn` is injected precisely so the curve lives at the edge
(Workers already carry `@noble/curves`), and what the tests need to exercise
is **counting and distinctness**, which a real curve would not make sharper.

## Labelling

While every witness is under one operator this is **crash fault tolerance,
not Byzantine fault tolerance**, and superproject ADR-2607110300's rule says
to call it that. A protocol being BFT and a deployment satisfying BFT's
premises are different claims. Do not describe deployments using this library
as "distributed" or "decentralized" until independent third-party operators
with economic exposure exist (that ADR's Phase 4).

## Not here yet

Per ADR-2608038000, in order:

1. **Extraction** — `engi.{consensus,quorum,pacemaker,replica,sync,wire,net,attest,stake,parity}` (3,166 lines of chained HotStuff, already running: 4 replicas, real WebSockets, real keys, agreeing on a live exchange's state) move here. Not done yet: `engi` is under active daily development and the extraction has to land `engi` and `torihiki` together.
2. **F1** — state root from an opaque digest to a content-addressed CID (`prolly-tree` / `arrangement`). No new HAMT; the workspace already has content-addressed maps. **`inga.ref` is usable today, but wiring it to kotobase's actual datom plane needs F1**, because a ref must point at something hydratable.
3. **F2** — the state machine as a fuel-metered `.kotoba` module (the compiler's native backends already meter fuel).
4. **F3** — the power table inside committed state; a `:storage` role on the existing bond market.

## License

See the workspace license policy.
