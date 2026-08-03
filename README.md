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
| `inga.state` | **F1** — the committed state root as a real CID, over `arrangement`'s 4 content-addressed indices; plus the `:cid` / `:opaque` distinction that gates what may back a kotobase ref |
| `inga.fuel` | **F2** — metered execution where running out is a *state transition*, never an exception |
| `inga.power` | **F3** — the power table as committed state, and a `:storage` role on the existing bond market |
| `inga.parity` | one scenario over the pure namespaces, run on JVM **and** nbb, printing one digest |

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
clojure -M:test      # 47 tests, 131 assertions
clojure -M:lint      # 0 errors, 0 warnings
clojure -M:parity    # and the same on nbb -- both must print one line
nbb --classpath "src:$(clojure -Spath | tr ':' '\n' | grep kotobase-storage)" \
    -e "(require '[inga.parity :as p]) (p/report)"
```

A JVM suite is not evidence about ClojureScript, and ClojureScript is the
runtime kotobase deploys on. `inga.parity` runs `head` / `fuel` / `power` /
`ref` on both and checks one digest:

```
head:70/3/false/false fuel:10,/3,3,6/0,0 power:100/1/1/true/4 ref:true/false,cid-1/true,1/cid-2
```

**`inga.state` is not covered by parity, and that is a real gap, not an
omission.** `arrangement/commit!` returns a CID on the JVM and a `js/Promise`
on cljs, so there is no single synchronous digest to compare. The split is
documented in `inga.state`'s docstring rather than papered over; verifying its
cljs path needs arrangement's own cljs deps and is open work.

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

## The Filecoin half — F1 / F2 / F3

ADR-2608038000 names three things Filecoin's SPC / FVM / FEVM contribute that
a chained-HotStuff ordering layer does not. All three are here; each composes
with something that already existed rather than reinventing it.

### F1 — the state root is a CID, not a digest

A machine seam returning `"113:c51298e1"` lets replicas **compare** state and
nothing else — they cannot sync it, query it, serve it, or point a ref at it.
Filecoin's on-chain state is an IPLD structure, so a state root is something
you can walk; FEVM maps `SLOAD`/`SSTORE` onto that instead of a
Merkle-Patricia Trie, and pays exactly one method for it (`eth_getProof`).

`inga.state` takes the same shape and **introduces no HAMT** — the workspace
already has content-addressed maps. `kotoba-lang/arrangement` already
snapshots a 4-index datom db into prolly-trees, CID-addresses the commit
(dag-cbor of `{schema-version index-roots prev}`, every root a real tag-42
IPLD link), restores from it, and queries it with Datalog. None of that is
rewritten; `inga.state` wires it to the machine seam.

The added distinction is **`:root-kind`**. A machine declares `:cid`
(hydratable) or `:opaque` (a digest — still legal; a typed-array order book is
not obliged to become datoms to reach consensus). `assert-hydratable!` refuses
`:opaque` for anything backing a kotobase ref, which turns ADR-2608038000 D6's
"F1 must come before D6" from something a reader remembers into something the
code holds.

Acceptance test, as the ADR stated it — four replicas reach the same CID **and
the state hydrated from that CID answers Datalog**:

```clojure
(is (= 1 (count (set roots))))                     ; one root across four runs
(let [restored ((:hydrate-fn m) root identity)]    ; a reader with only the CID
  (state/query restored {:find '[?s] :where '[[?s "role" "witness"]]}))
;; => #{["alice"] ["bob"]}
```

### F2 — running out of fuel is a state transition, not an exception

An arbitrary `apply-fn` bounds no work and guarantees no agreement between two
implementations. engi hit the second half for real: a machine map holding a
ready-made order book handed every replica the *same mutable structure*, and
four replicas agreeing on 123 committed blocks differed by 200 resting orders.

Filecoin's FVM answers this by metering every operation, so determinism is a
property of the VM rather than of each actor's care. Kotoba already has the
primitive — the compiler's native backends implement fuel accounting.

`inga.fuel`'s one rule: **exhaustion is a value in the state, never a throw.**
A replica that throws has left the protocol — it produces no state and no root
while its peers produce both. So `apply-metered` stops, records where, and
`record` folds that into the state the root commits to. Determinism depends on
exactly three inputs — budget, cost function, op order — and nothing else. Cost
is charged *before* the op, which is what makes the budget a real ceiling
rather than an approximate one.

**Not yet a `.kotoba` machine.** F2's endpoint is the machine body compiled to
fuel-metered Kotoba; today the compiler's capability kits are `:reference
:implemented` with `:wasm-aot`/`:native-aot` pending, and there is no
fs/process capability or Kotoba script host to run a build from. What is here
is the metering contract and the determinism property at the seam — which is
what consensus needs — and the remaining step is named rather than implied.

### F3 — the power table is committed state

`engi.stake` already implements permissionless admission by external
collateral, stake-weighted quorum, equivocation-only slashing, and a
role-tagged single bond market. **None of that economics is reimplemented
here.** The one thing it cannot do by itself is the thing F3 is about: it is
*handed* the bond map from outside, so who is a witness is decided somewhere
the consensus does not order — and a validator set is not a thing peers may
disagree about, because it is what quorum is counted against.

`inga.power` is the table plus the transition function a machine applies, so
the table at height *h* is a function of the committed prefix and nothing
else. `:storage` joins `:ordering` and `:recompute` on the **existing** market
— SPC's idea that the Sybil-resistant resource should be the useful work the
network does, which here is retaining and serving datom blocks.

It does **not** replace external collateral (engi's reason for bonding USDC
rather than EN is unchanged: EN nets to zero, so bonding it disincentivises
nothing), and `:storage` power is credited by attested retrieval sampling —
the shape `:recompute` already uses — **not** PoRep/PoSt. No deployment using
this may claim Filecoin-equivalent storage guarantees.

## Not here yet

**Extraction** — `engi.{consensus,quorum,pacemaker,replica,sync,wire,net,attest,stake,parity}`
(3,166 lines of chained HotStuff, already running: 4 replicas, real WebSockets,
real keys, agreeing on a live exchange's state) move here. `engi` is under
active daily development and the extraction has to land `engi` and `torihiki`
together, so it waits for that repo to quiesce.

Until then **inga contains no consensus** — F1/F2/F3 are the state, execution
and membership planes a consensus drives, and the reference quorum in the
tests is a cooperative oracle, not agreement.

## License

See the workspace license policy.
