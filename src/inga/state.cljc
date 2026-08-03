(ns inga.state
  "F1 — the committed state root as a real CID, not an opaque digest.

  ## What was wrong

  A consensus machine seam that returns `(root-fn state) -> \"113:c51298e1\"`
  lets replicas COMPARE state and nothing else. They cannot sync it, query
  it, serve it, or prove anything about it, and a caller downstream cannot
  point a ref at it. Agreement on a digest is agreement that two processes
  computed the same number — which is necessary and nowhere near sufficient
  for the thing this stack sells, which is a queryable database.

  Filecoin's answer is that on-chain state IS an IPLD structure (a HAMT of
  address -> actor), so a state root is a CID you can walk. FEVM maps
  `SLOAD`/`SSTORE` onto that rather than onto a Merkle-Patricia Trie, and
  pays exactly one method for it (`eth_getProof`). Superproject
  ADR-2608038000 F1 takes the same shape and explicitly does NOT introduce a
  HAMT, because this workspace already has three content-addressed maps.

  ## So this namespace composes rather than invents

  `kotoba-lang/arrangement` already snapshots a 4-index datom db into
  prolly-trees and CID-addresses the commit itself (dag-cbor of
  `{schema-version index-roots prev}`, every root a real tag-42 IPLD link),
  returns the commit CID, and restores from it. `arrangement.datalog/q`
  already queries it. None of that is rewritten here.

  What this namespace adds is the SEAM and one enforced distinction:

  ## `:root-kind` — the distinction that makes ADR-2608038000 D6 checkable

  A machine declares `:root-kind`:

    `:cid`     the root is a content-addressed commit that `hydrate` can
               restore and `arrangement.datalog/q` can query.
    `:opaque`  the root is a digest. Replicas can compare it. Nothing else.

  `:opaque` stays legal — a machine whose state is a typed-array order book
  is not obliged to become datoms to reach consensus. What is NOT legal is
  wiring an `:opaque` machine to kotobase's ref plane, because a kotobase
  ref must point at something hydratable. `assert-hydratable!` is that gate,
  and it exists so the ordering constraint in D6 is enforced by code instead
  of remembered by a reader.

  ## Platform split — `root-fn` and `hydrate-fn` are async on ClojureScript

  `arrangement/commit!` returns the commit CID DIRECTLY on the JVM and a
  `js/Promise` of it on ClojureScript, and `restore` likewise. That split is
  arrangement's, not something introduced here, and it is not hidden: the
  runtime kotobase actually deploys on is a Cloudflare Worker, so the
  Promise path is the important one and a caller that treats the return as a
  string will get `#object[Promise]` in its state root and agree with nobody.

  `inga.head`, `inga.ref`, `inga.fuel` and `inga.power` are pure and
  synchronous on both runtimes — `inga.parity` runs them on JVM and nbb and
  checks one digest. `inga.state` is the namespace where the split lives.

  ## Fuel

  When `:fuel` is supplied, ops are metered by `inga.fuel` and the outcome is
  written into the db AS DATOMS, not hung off the state map. This is not a
  stylistic choice: `root-fn` commits `(:db state)`, so a fuel ledger kept
  anywhere else would not be covered by the root, and two replicas that
  stopped at different ops could still produce identical CIDs. Recording it
  as datoms makes exhaustion change the root, which is the only version of
  this that a peer can check."
  (:require [arrangement.core :as arr]
            [arrangement.datalog :as adl]
            [inga.fuel :as fuel]))

(def root-kinds #{:cid :opaque})

(def schema-version arr/current-schema-version)

(defn- apply-op [db {:keys [op s p o]}]
  (case op
    :assert (arr/assert-quad db {:s s :p p :o o})
    :retract (arr/retract-quad db {:s s :p p :o o})
    (throw (ex-info "inga.state: unknown op"
                    {:type :inga.state/unknown-op :op op}))))

(defn- fuel-datoms
  "Write one block's fuel outcome into the db.

  Only `spent`/`applied` for a block that finished, plus `exhausted-at` and
  `dropped` when it did not. Writing an explicit `exhausted-at = -1` for the
  normal case was tempting and wrong: it puts a datom per block into a
  content-addressed index forever to record that nothing happened."
  [db height {:keys [spent applied exhausted-at dropped]}]
  (let [s (str "inga.fuel/block/" height)
        base [{:op :assert :s s :p "inga.fuel/spent" :o spent}
              {:op :assert :s s :p "inga.fuel/applied" :o applied}]]
    (reduce apply-op db
            (cond-> base
              exhausted-at
              (conj {:op :assert :s s :p "inga.fuel/exhausted-at" :o exhausted-at}
                    {:op :assert :s s :p "inga.fuel/dropped" :o dropped})))))

(defn machine
  "A consensus machine whose root is a real CID.

  `decode-block` is `(fn [block] -> [{:op :assert|:retract :s _ :p _ :o _} …])`
  — the application's own reading of what a committed block means. inga does
  not learn what a transaction is (the seam `engi.replica` already draws, for
  the reason its own ADR-2608022500 gives: a consensus layer that imports one
  application becomes that application's consensus layer).

  `put!` is prolly-tree shaped `(fn [cid bytes])`. `blind-fn`/`encrypt-fn`
  are arrangement's required keyed-MAC and AEAD seams — required there
  precisely so nobody gets an unblinded index by forgetting an argument, and
  passed straight through here.

  `:fuel` is optional `{:budget-fn (fn [block] -> int) :cost-fn (fn [op] -> int)
  :height-fn (fn [block] -> int)}`. Seams are checked with `ifn?` rather than
  `fn?` throughout: a keyword is a perfectly good accessor (`:height-fn
  :height`) and rejecting one would be a restriction with no safety behind it
  — what the check is for is catching a seam that was forgotten, and `nil` is
  not `ifn?` either way. `:height-fn` is required alongside the
  others because a fuel record without the height it happened at is a fact
  nobody can locate in the chain.

  Returns `{:init-fn :apply-fn :root-fn :root-kind :hydrate-fn}`. The first
  three are the shape the replica already takes."
  [{:keys [decode-block put! get-fn blind-fn encrypt-fn fuel]}]
  (doseq [[k v] {:decode-block decode-block :put! put! :get-fn get-fn
                 :blind-fn blind-fn :encrypt-fn encrypt-fn}]
    (when-not (ifn? v)
      (throw (ex-info "inga.state/machine: missing or non-callable seam"
                      {:type :inga.state/invalid-seam :seam k}))))
  (when fuel
    (doseq [k [:budget-fn :cost-fn :height-fn]]
      (when-not (ifn? (get fuel k))
        (throw (ex-info "inga.state/machine: :fuel needs budget-fn, cost-fn and height-fn"
                        {:type :inga.state/invalid-fuel :seam k})))))
  {:root-kind :cid
   ;; A THUNK, not a value. engi's own ADR-2608022600 found this the hard
   ;; way: a machine map holding a ready-made exchange handed every replica
   ;; the SAME mutable order book, and four replicas agreed on committed
   ;; blocks while their boards differed by 200 resting orders. "Do not
   ;; share this" must not be something the caller has to remember.
   :init-fn (fn [] {:db (arr/empty-db) :prev nil})
   :apply-fn
   (fn [state block]
     (let [ops (decode-block block)]
       (if-not fuel
         (update state :db #(reduce apply-op % ops))
         (let [height ((:height-fn fuel) block)
               r (fuel/apply-metered
                  {:state state :ops ops
                   :budget ((:budget-fn fuel) block)
                   :cost-fn (:cost-fn fuel)
                   :step (fn [s op] (update s :db apply-op op))})]
           ;; Into the DB, so `root-fn` covers it. See the ns docstring.
           (update (:state r) :db fuel-datoms height r)))))
   :root-fn
   (fn [state]
     ;; Content-addressed: the same db + prev + schema-version commits to the
     ;; same CID, which is what makes "every replica reached the same root" a
     ;; statement about the DATA rather than about the traversal order.
     (arr/commit! put! (:db state) (:prev state) schema-version
                  blind-fn encrypt-fn))
   :hydrate-fn
   (fn [root-cid decrypt-fn]
     (arr/restore get-fn root-cid decrypt-fn))})

(defn opaque-machine
  "Wrap a machine whose root is a digest. Legal, and honestly labelled: the
  only thing this changes is that `assert-hydratable!` will refuse it."
  [{:keys [init-fn apply-fn root-fn]}]
  {:root-kind :opaque :init-fn init-fn :apply-fn apply-fn :root-fn root-fn})

(defn hydratable?
  [machine] (= :cid (:root-kind machine)))

(defn assert-hydratable!
  "Gate for anything that points a kotobase ref at this machine's root.

  ADR-2608038000 D6: `inga.ref` is usable on its own, but wiring it to
  kotobase's datom plane needs F1, because a ref must resolve to something a
  reader can hydrate. Throwing here is the difference between that being a
  rule someone remembers and a rule that holds."
  [machine]
  (when-not (hydratable? machine)
    (throw (ex-info "inga.state: this machine's root is opaque — a kotobase ref cannot point at it"
                    {:type :inga.state/root-not-hydratable
                     :root-kind (:root-kind machine)})))
  machine)

(defn query
  "Datalog over a hydrated state. Thin on purpose — `arrangement.datalog/q`
  owns the engine; this exists so a caller does not have to know that the
  state a replica agreed on is an arrangement db."
  ([db q-map] (query db q-map (constantly true)))
  ([db q-map visible?] (adl/q db q-map visible?)))
