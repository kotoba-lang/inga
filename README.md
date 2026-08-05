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
| `inga.retrieval` | **F3** — crediting that `:storage` role by asking a witness to produce blocks it claims to hold |
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
clojure -M:test      # 277 tests, 1,298 assertions
clojure -M:lint      # 0 errors, 0 warnings
clojure -M:parity    # and the same on nbb -- both must print one line
nbb --classpath "src:$(clojure -Spath | tr ':' '\n' | grep kotobase-storage)" \
    -e "(require '[inga.parity :as p]) (p/report)"
```

A JVM suite is not evidence about ClojureScript, and ClojureScript is the
runtime kotobase deploys on. `inga.parity` runs `head` / `fuel` / `power` /
`ref` on both and checks one digest:

```
head:70/3/false/false/false fuel:10,/3,3,6/0,0 power:2/1/1/4 ref:true/false,cid-1/true,1/cid-2
```

`inga.state` is not in the parity digest — `arrangement/commit!` returns a CID
on the JVM and a `js/Promise` on cljs, so there is no single synchronous value
to compare. It is covered instead by the full cljs suite:

```bash
npm run test:cljs   # shadow-cljs :node-test -- 247 tests, 1204 assertions
```

**Both runtimes run the same 247 tests.** That includes every namespace that
came from engi, which had no cljs suite for them.

Two things only the cljs build could find, both while the JVM suite was green:

- **nbb cannot run `inga.state` at all** — SCI dies with `Protocol not found:
  IEquiv` inside a transitive dependency. shadow-cljs is the compiler kotobase
  actually deploys with, so that is the vehicle; nbb still runs `inga.parity`.
- **arrangement's platform split is not only in what it returns.** On cljs it
  also expects `blind-fn` / `encrypt-fn` / `decrypt-fn` to return Promises.
  Passing the JVM-shaped synchronous seams fails inside arrangement with
  `.then is not a function`.

Where this lands in production: `inga.replica/state-root` is **reporting**, not
a consensus decision — no adopt or commit path reads it — so a Promise there is
a caller's `await`, not a protocol break.

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

### The oracle is gone: the commit rule is the CAS

`inga.ref`'s `propose!` is injected, and for a while the only thing that
implemented it was the cooperative oracle in its own tests — the adapter was
correct and connected to nothing.

What consensus gives a ref is not a round trip. It gives a **total order**,
and a total order already decides the question: for each `[ref seq]` the
**first** record in the committed prefix wins and every later one loses. There
is nothing to vote on separately. So `inga.ref/project` + `outcome` are the
pure half, and the waiting stays in the host, because
`-compare-and-set-ref!` is synchronous and a commit is not:

```
submit the record → await the block that carries it → (outcome projection record)
```

`two-writers-race-a-sequence-and-the-commit-rule-decides` runs that on the
real replica network: two writers propose **different** cids at sequence 0,
neither refusable on shape, and only the order can separate them. Every
replica projects the same winner, exactly one writer is certified, and the
loser is told which head actually holds its sequence — a caller that only
learns `false` retries against the same base forever.

First-wins, not last-wins, is the whole compare-and-set: last-wins would let a
writer that lost the ordering overwrite the winner by proposing again.

### What that still does NOT establish

**The conformance suite's quorum is still an oracle.** Safety — that two
conflicting certificates at the same height can never both form — is a
`kotobase.storage.contract/verify` runs against a cooperative reference
quorum that models at-most-one-per-height and nothing else. It checks that the
ADAPTER refuses a loser, reports the winning head, and never claims a publish
it cannot read back — it is not evidence about agreement. The evidence about
agreement is the test above, on the real commit rule.

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

Acceptance, as the ADR stated it — four replicas reach the same CID **and the
state hydrated from that CID answers Datalog**. Two levels, because the first
alone was not what the ADR promised:

**Standalone** — four folds of the same op list produce one root, so the root
is a function of the data:

```clojure
(is (= 1 (count (set roots))))
(let [restored ((:hydrate-fn m) root uncrypt)]     ; a reader with only the CID
  (state/query restored {:find '[?s] :where '[[?s "role" "witness"]]}))
;; => #{["alice"] ["bob"]}
```

**Through the consensus** (`four-replicas-that-had-to-agree-reach-a-hydratable-root`)
— four replicas that had to vote their way to a committed prefix, then
recomputed at a common height and hydrated. Folding four times proves the root
is deterministic; it does not prove replicas that had to *agree* on an order
arrive at one. The socket harnesses do go through a commit rule, and their
machine is an opaque digest — exactly what F1 replaced. So this half was
covered twice and joined nowhere until now.

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

**The arithmetic now has a Kotoba implementation.** `kotoba/fuel.kotoba`
compiles with `kotoba compile --target wasm32` and the resulting
`kotoba/fuel.wasm` is checked in, the same way `gftdcojp/engi` checks in its
settlement module. `inga.fuel-kotoba-test` instantiates that binary and
compares it against `inga.fuel` across a 120-case matrix; the Kotoba module is
the reference, and if they ever disagree the Kotoba answer is the correct one.

This was previously written up here as *blocked* on the compiler's capability
kits. That was wrong, and only checking found out: those blockers are about
**effects**, and a state machine performs none — so no capability is declared,
the deny-by-default policy has nothing to grant, and the module compiles as-is.

**What checking also found: the compiler's fuel is not inga's fuel.** A
compiled module carries its own budget per instance and traps with
`unreachable` when it runs out — measured at 42 calls of `applied(10,1,100)`
on one instance. That is the compiler doing its job, and it is exactly the
failure `inga.fuel` forbids:

| | exhaustion is | who can check it |
|---|---|---|
| `inga.fuel` | a **value** folded into the state root | any peer |
| compiler fuel | a **trap** | nobody, after the fact |

So any deployment running the machine as a Kotoba module must give every
replica the **same initial fuel** (`--fuel` / `--fuel-initial`), or replicas
trap at different call counts and diverge for a reason that has nothing to do
with the transactions. A test pins the trap behaviour so this note cannot go
stale silently.

Still not a full `.kotoba` machine body: the fold is metered in Kotoba, the
op application is still cljc.

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
nothing).

`inga.retrieval` credits the role. Filecoin needs PoRep and PoSt because a
storage proof there must survive an adversary who can fetch the data from
anywhere and is paid to look like a storer. A datom plane needs far less for a
much weaker claim, because **the data is content-addressed**: "return the
bytes for this CID" verifies itself — hash what came back and compare. No
setup, no sector, no proving time; the whole verifier is one hash and one
comparison.

A passing sample proves **at sample time, this witness could produce these
bytes** — and three things it does not:

1. **durable storage.** A witness that fetched the block from a peer the
   moment it was asked passes. Nothing distinguishes holding from fetching.
2. **unique storage.** Ten witnesses can pass on one physical copy.
3. **future availability.** The sample is about the instant it ran.

The economic argument against (1) is that fetching on demand costs more than
storing when samples are frequent and unpredictable enough — an *argument*,
not a measurement. **No deployment using this may claim Filecoin-equivalent
storage guarantees.**

The challenge is derived from a value fixed *after* the storage claim and by
nobody in particular (a committed block hash), because a witness that can
predict its challenge stores only what will be asked for. Deriving it from the
clock or from a caller's choice would be the same hole from the other side.
A witness claiming nothing is `:unproven`, not `:pass` — otherwise the
cheapest way to look like a storage provider is to claim to store nothing.

## The consensus — extracted from engi 2026-08-03

`inga.{consensus,quorum,pacemaker,replica,sync,wire,net,attest,stake}` came
from `kotoba-lang/engi`, where chained HotStuff had grown up inside a currency
repo. The reason to move it is in engi's own `consensus.cljc` docstring:

> engi does not know what a transaction is, and must not — a consensus layer
> that imports either application becomes "a consensus layer for exactly one
> application".

| ns | what it owns |
|---|---|
| `inga.consensus` | block / QC shape, `n=3f+1` and `quorum=2f+1`, the chained 3-chain commit rule, round-robin leader rotation |
| `inga.quorum` | quorum as one predicate, count-based and stake-weighted |
| `inga.pacemaker` | views, timeouts, timeout certificates, view change |
| `inga.replica` | the replica itself — adopt, commit, the machine seam, equivocation recording, **bounded `snapshot`/`resume`** |
| `inga.sync` | catch-up over segments a stranger hands you |
| `inga.wire` | total decode; JSON has no keywords, so `:inga.block/height` travels as `"height"` |
| `inga.net` (+ `net/server`, `net/ws`) | the WebSocket transport, both halves |
| `inga.attest` (+ `attest/ed25519`) | signatures on certificates — what makes a quorum a quorum rather than a list of names. Ed25519 through **WebCrypto**, so a Worker needs no dependency to verify one |
| `inga.stake` | permissionless admission by external collateral, stake-weighted quorum, equivocation-only slashing |

`inga.attest` is **not** a duplicate of
[`kotoba-lang/witness-quorum`](https://github.com/kotoba-lang/witness-quorum),
though a docstring here claimed a dependency on it for months. They solve
different problems: witness-quorum cosigns an already-written CID *after* the
fact (a Certificate-Transparency shape, with a 3-layer validation membrane);
`inga.attest` signs votes and certificates *before* a commit, inside the
protocol. They overlap only at "Ed25519", and differ there on purpose —
witness-quorum's cljs signer takes `@noble/curves` from npm, `inga.attest`
uses WebCrypto and takes nothing, because this has to run in a Worker.

**Because the namespaces moved, `:engi.block/*` keywords became
`:inga.block/*`. That is not a wire change** — `inga.wire`'s own docstring
notes JSON has no keywords, so only the local name changed.

`inga.power` (F3) was written before `inga.stake` arrived and had simplified
copies of `eligible` / `stake-for` / `quorum-met?`. They are gone: `inga.stake`
owns those, and two implementations of a quorum rule is not redundancy, it is
two answers to "did this block commit". What `power` keeps is the part `stake`
genuinely cannot do — making the bonds map a function of the committed prefix.

### Acceptance

```bash
nbb --classpath "src:<torihiki>/src:<bytes>/src" script/torihiki-on-inga.cljs
```

Four replicas over real WebSockets, each executing `torihiki.state/apply-block`
on the blocks inga commits, then asked whether they hold the same exchange.
Run before the extraction (from engi) and after (from inga); both pass:

```
  common committed blocks: 43
  every replica the same : true
  the thief's order      : refused {:not-your-account 2, :wrong-key 23}

TORIHIKI-ON-INGA: pass — four replicas, one exchange
```

## Restarting without folding the whole log — `snapshot` / `resume`

`replay` reconstructs a replica by re-executing every block it ever adopted.
That is the right thing for VERIFYING a chain and the wrong thing for STARTING
one: it costs O(chain), and a process that must pay that before it can answer
anything eventually cannot start at all.

Measured, not hypothetical. On 2026-08-04 a deployed validator running this
consensus layer spent long enough replaying its log to exceed a Cloudflare
Durable Object's CPU budget; the platform reset the object, the reset threw
away the in-memory state, and the next invocation replayed from zero and
exceeded it again. A crash loop that could not end, because recovery **was**
the work that killed it.

`snapshot` keeps the last `resume-tail` blocks (8 — the 3-chain rule needs
three to derive a commit and `ancestor?` walks parent links, so four is the
floor), the certificates naming them, the pacemaker, the machine state, and
nothing that grows with the chain.

### The dangerous part is `:voted`

A replica must never vote twice at one height — that is equivocation, the one
thing this system slashes for, and it is invisible from the inside: nothing in
the replica's own state looks wrong afterwards. `replay` was reconstructing
`:voted` by folding the blocks. A bounded snapshot cannot, by definition.

So the set is replaced by a **watermark**: `:voted-below` = the tip height, and
`voted?` answers true at or under it whether or not the set still names the
height. The watermark only ever makes that answer MORE often, and that is the
safe direction — refusing costs a vote at a height already decided; not
refusing is equivocation. A resumed replica cannot legitimately need to vote at
or below the tip it resumed on: it voted for the block it adopted at each of
those heights, and every proposal it sees from now on is above them.

`test/inga/resume_test.cljc` was written before the implementation and asserts
the property directly — every block the replica holds is offered back to it and
every vote that leaves is checked against the block it actually adopted.

### Two things this cost

**`commits` had to stop counting.** It compared `(count (:committed state))`
against `three-chain-commits` over the whole chain, which assumes both run
unbroken from genesis. Over a tail, the count exceeds the list, `drop` returns
nothing, and the replica commits nothing ever again while every number about it
looks healthy. It compares by height now — the correct formulation with or
without a tail.

**`:first-vote` is dropped**, and with it the equivocation evidence this
replica had collected about others below the tail. Evidence already broadcast
is held by peers; evidence not yet broadcast is gone. Stated rather than
hidden.

## Not here yet

**engi does not depend on inga.** Its remaining namespaces required nothing
from the consensus set, which is why the cut was clean — but it also means
inga's consumer today is the torihiki harness, not the ENGI ledger. Running
ENGI/EN *on* inga is future work.

### Evidence propagates

An equivocation used to be recorded by whichever replica happened to receive
both conflicting votes, and go no further. That punishes nobody: the
equivocator only has to keep any single peer from seeing both, which is a
routing property it can influence.

`:evidence` is now a wire message. A replica that detects an equivocation
broadcasts the proof; a replica that receives one **verifies it before
recording** (`inga.stake/verify-equivocation-evidence` re-checks the whole
claim — same witness, same height, different blocks, both signatures — because
otherwise evidence is a way to accuse anyone of anything), records once per
`[witness height]`, and forwards **only on first sight**, so one proof does not
become a permanent storm between peers.

`script/network.cljs` grew a `BYZANTINE_SPLIT=1` mode that sends the
equivocator's second vote to **one** peer instead of all — the case the
original harness never created, because broadcasting both votes to everyone
makes every replica an independent detector and never asks whether a proof can
travel. Measured over real sockets:

| `BYZANTINE_SPLIT=1` | w1 | w2 | w3 | |
|---|---|---|---|---|
| without propagation | 14 | **0** | **0** | `NETWORK: FAIL` |
| with propagation | 36 | 8 | 23 | `NETWORK: pass` |

Without it, an equivocator that routes its two votes to different peers escapes
entirely.

### The catch assertion is conditional, and says so

`script/network.cljs` used to report `NETWORK: FAIL — an honest replica holds
no proof against the equivocator` on about one run in four. I first wrote that
up as a timeout being too short. **That diagnosis was wrong.** Counting what
the byzantine validator actually cast:

| | equivocating votes cast | honest replicas holding a proof |
|---|---|---|
| passing runs (12 measured) | 75–147 | all 3 |
| the failing run | **12** | **0, 0, 0** |

All three at zero rather than some at zero is the shape of *it barely voted*,
not *the proof did not travel*. The validator sat out; the protocol did
nothing wrong.

So the assertion is now judged only when there was something to judge — below
`min-twins-to-judge` the run reports `INCONCLUSIVE`, loudly, with the count,
and everything else still has to pass. `deliver-all`'s own docstring says why
this matters: an intermittent test is worse than none, because it teaches you
to re-run it.

### What a quorum resists is declared, not inferred

`replica` used to take `:quorum` and, when omitted, silently fall back to
counting its own witness list. Head-counting is correct for a **managed** set
and is exactly what a Sybil defeats under permissionless admission — and
nothing in the built state said which one you had.

`inga.quorum/profiles` is now a closed set, modelled on
`kotobase.storage.core/ref-profiles` and for the reason that namespace gives:
*the failure mode of guessing is silent.*

| profile | what it resists |
|---|---|
| `:head-count` | nothing, against an adversary who can register witnesses. Correct when who may hold a key is decided outside the protocol. |
| `:stake-weighted` | Sybil identities: splitting a bond across more of them changes the head count and not the stake. Requires a bond source. |

The convenient default is unchanged — every managed deployment wants it — but
a replica now carries `:quorum-profile`, so what a deployment actually resists
is a value you can ask it for. An unlabelled predicate reports `:head-count`
rather than being upgraded on the caller's behalf.

### Slashing still does not fire, and that is a decision, not a bug

`inga.stake` implements bonding, stake-weighted quorum and equivocation-only
slashing; `inga.power` makes the bond table a function of the committed
prefix; `inga.quorum/stake-weighted` plugs into `replica`. The seam is
complete and unused, because there is no bond source.

[`kotoba-lang/engi-witness-escrow`](https://github.com/kotoba-lang/engi-witness-escrow)
is the on-chain custody half — reviewed, tested, and **deliberately not
deployed**. Its own README says why: *deploying a contract that custodies real
third-party money is a separate, higher-stakes decision requiring its own
explicit review.* That decision is the blocker, and it is not an engineering
one. Until it is made, deployments run `:head-count` and have no economic
security — which is now something the state says out loud.

Still open, from the same ADR: `:storage` power has no retrieval-sampling implementation; the `.kotoba`
machine body is blocked on the compiler; bond collateral is not deployed, so
**slashing is implemented and does not fire**; equivocation evidence is
recorded and does not propagate.

## License

See the workspace license policy.
