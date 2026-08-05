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

  The split is not only in the return value: on cljs, arrangement also expects
  `blind-fn` / `encrypt-fn` / `decrypt-fn` to RETURN Promises. Passing the
  JVM-shaped synchronous seams fails inside arrangement with `.then is not a
  function`, and only the cljs build says so.

  `inga.head`, `inga.ref`, `inga.fuel` and `inga.power` are pure and
  synchronous on both runtimes — `inga.parity` runs them on JVM and nbb and
  checks one digest. `inga.state` is the namespace where the split lives, and
  it is covered by the shadow-cljs suite instead (`npm run test:cljs`), which
  runs the same 247 tests the JVM does. nbb cannot host it: SCI raises
  `Protocol not found: IEquiv` inside a transitive dependency.

  Where the split lands in production: `inga.replica/state-root` is REPORTING
  — no adopt or commit path reads it — so a Promise there is a caller's
  `await`, not a protocol break.

  ## Fuel

  When `:fuel` is supplied, ops are metered by `inga.fuel` and the outcome is
  written into the db AS DATOMS, not hung off the state map. This is not a
  stylistic choice: `root-fn` commits `(:db state)`, so a fuel ledger kept
  anywhere else would not be covered by the root, and two replicas that
  stopped at different ops could still produce identical CIDs. Recording it
  as datoms makes exhaustion change the root, which is the only version of
  this that a peer can check.

  ## Two roots (superproject ADR-2608059000)

  The state root is now a node with TWO children, not one:

      StateRoot = {schema-version, actors, datoms, prev}
                                   |       |
                     actor tree ---+       +--- arrangement commit
                     address -> {code,          {schema-version,
                     state, nonce, balance}      index-roots{spo pso pos ocp},
                                                 prev}

  and the direction between them is the decision, not the shape: **the actor
  tree is the source, the datom indices are a committed projection of it.**

  Why they are SIBLINGS rather than one inside the other. An actor tree
  partitions state by actor. Put the datoms inside an actor's subtree and
  `pos` (\"which entity has attribute A = value V\") and `ocp` (reverse
  references) stop being global — answering either would mean scanning every
  actor, which is superproject ADR-260726's one-ref rule broken, and the same
  failure the sekaiju shard split produced. Keeping the indices at the same
  height as the actor tree is the entire condition; meet it and the actor
  layer costs the query plane nothing.

  What the actor tree buys that datoms alone did not: a place to enforce
  \"only this actor may write here\" structurally, `code` as the CID of a
  checked definition (`codebase/typed-code`), and somewhere for `nonce` and
  `balance` to live.

  `\"datoms\"` links arrangement's COMMIT, not its four index roots directly.
  ADR-2608059000's figure draws that child as `index-roots`; linking the
  commit is the faithful implementation of it, because the commit is what
  `arrangement/restore` takes, and reimplementing its envelope here would be
  two encodings of one thing.

  ## What two roots does NOT close yet

  - **Emission is opt-in, not enforced.** `:emit-fn` derives datoms from an
    actor op in the same step. Supply `default-emit` (or your own) and the
    projection tracks the source; supply nothing and the two roots are
    independent trees — honest, but not yet the invariant the ADR states.
    Closing that is ADR-2608059000's step 3, where emission becomes a
    `.kotoba` pure function.
  - **Emission is not separately metered.** `cost-fn` sees the actor op, not
    the datoms it expands into, so a caller running with `:fuel` must price
    actor ops with their emission in mind. Pretending otherwise would put a
    number in the fuel ledger that does not match the work done.
  - **`:prev` advances nowhere.** `init-fn` seeds it nil and no path writes
    it, so every StateRoot so far carries a null `prev`. That was already
    true before this change (it was passed to `arrangement/commit!` and was
    always nil); what moved is only which node the chain belongs to.
  "
  (:require [arrangement.core :as arr]
            [arrangement.datalog :as adl]
            [ipld.core :as ipld]
            [prolly-tree.core :as pt]
            [inga.fuel :as fuel]))

(def root-kinds #{:cid :opaque})

(def schema-version arr/current-schema-version)

(def state-root-schema-version
  "The StateRoot envelope's own version, independent of arrangement's
  `schema-version` for the datom commit it links. Two nodes, two versions —
  the datom shape and the actor-tree shape can move separately."
  1)

;; ── the actor record ────────────────────────────────────────────────────────

(def actor-fields
  "The fields `default-emit` projects into datoms, in a fixed order so the
  emission is deterministic. `state` is deliberately absent: it links the
  actor's OWN graph, and copying it into the index would make every internal
  write an actor does touch the global indices."
  [["inga.actor/nonce" :nonce]
   ["inga.actor/balance" :balance]
   ["inga.actor/code" :code]])

(defn- assert-cid-shaped!
  "`ipld/link` accepts any string and only fails much later, inside the codec,
  with a NullPointerException from the base32 decoder — so a caller who passes
  a name or a digest where a CID belongs learns about it from a stack trace in
  a dependency. This is a SHAPE check (CIDv1 base32 begins `b`), not a
  validity check; it is here to turn that NPE into a sentence."
  [field v]
  (when (and (some? v)
             (not (and (string? v) (>= (count v) 20) (= \b (first v)))))
    (throw (ex-info "inga.state: an actor's code/state must be a CIDv1 string"
                    {:type :inga.state/not-a-cid :field field :value v})))
  v)

(defn actor
  "Normalize an actor record. `code`/`state` are CID strings or nil; `nonce`
  and `balance` default to 0, so an actor put with neither is a complete
  record rather than one carrying nils into the index."
  [{:keys [code state nonce balance]}]
  {:code (assert-cid-shaped! :code code)
   :state (assert-cid-shaped! :state state)
   :nonce (or nonce 0)
   :balance (or balance 0)})

(defn- encode-actor
  "Actor record -> the dag-cbor map stored in the tree. `code`/`state` become
  REAL tag-42 links, so a generic IPLD walker reaches the definition and the
  actor's own graph from the state root without knowing anything about inga."
  [{:keys [code state nonce balance]}]
  {"code" (some-> code ipld/link)
   "state" (some-> state ipld/link)
   "nonce" nonce
   "balance" balance})

(defn- decode-actor
  "The inverse. Links come back as `ipld.core.Link`, and leaving them in the
  returned map would make equality against a record the caller built fail for
  a reason nothing in the API explains."
  [m]
  {:code (some-> (get m "code") ipld/link-cid)
   :state (some-> (get m "state") ipld/link-cid)
   :nonce (get m "nonce")
   :balance (get m "balance")})

(defn default-emit
  "A projection policy: nonce, balance and code as datoms on the actor's
  address. Opt in by passing it as `:emit-fn`.

  It RETRACTS the previous values before asserting the new ones. arrangement
  is a quad store, not a map — asserting `balance 90` over `balance 100`
  leaves both, and `pos` would then answer that this actor has two balances.
  A projection that accumulates history is not a projection of the current
  state, so the retraction is the load-bearing half."
  [{:keys [address]} prev next]
  (vec (concat
        (for [[p k] actor-fields :when (some? (get prev k))]
          {:op :retract :s address :p p :o (get prev k)})
        (for [[p k] actor-fields :when (some? (get next k))]
          {:op :assert :s address :p p :o (get next k)}))))

(defn- apply-datom-op [db {:keys [op s p o]}]
  (case op
    :assert (arr/assert-quad db {:s s :p p :o o})
    :retract (arr/retract-quad db {:s s :p p :o o})
    (throw (ex-info "inga.state: unknown op"
                    {:type :inga.state/unknown-op :op op}))))

(def ^:private actor-ops #{:actor-put :actor-delete})

(defn- apply-op
  "One op against the whole state. Datom ops touch `:db`; actor ops touch
  `:actors` and, when `emit-fn` is supplied, `:db` as well — in THIS call, so
  that a source mutation and its projection cannot land in different blocks."
  [emit-fn state {:keys [op address] :as o}]
  (if-not (contains? actor-ops op)
    (update state :db apply-datom-op o)
    (let [_ (when-not (string? address)
              (throw (ex-info "inga.state: an actor op needs an :address"
                              {:type :inga.state/missing-address :op op})))
          prev (get-in state [:actors address])
          next (when (= :actor-put op) (actor (:actor o)))
          state' (if next
                   (assoc-in state [:actors address] next)
                   (update state :actors dissoc address))]
      (if-not emit-fn
        state'
        (update state' :db #(reduce apply-datom-op % (emit-fn o prev next)))))))

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
    (reduce apply-datom-op db
            (cond-> base
              exhausted-at
              (conj {:op :assert :s s :p "inga.fuel/exhausted-at" :o exhausted-at}
                    {:op :assert :s s :p "inga.fuel/dropped" :o dropped})))))

;; ── the two roots ───────────────────────────────────────────────────────────

(defn- actors-root
  "Build the actor tree and return its root CID, or nil for no actors.

  Built whole at commit time from the in-memory map rather than maintained
  incrementally, which is what `arrangement/index-root` does with the four
  datom indices — same reason: `prolly-tree/build-tree` is synchronous on
  both runtimes, while the incremental writers split into a Promise-returning
  `insert-many-async` on cljs. An empty tree is a nil root and encodes as a
  null link, again matching an empty index."
  [put! actors]
  (when (seq actors)
    (pt/build-tree put! (->> actors
                             (map (fn [[address rec]] [address (encode-actor rec)]))
                             (sort-by first)
                             vec))))

(defn- state-root-node [actors-cid datoms-cid prev]
  {"schema-version" state-root-schema-version
   "actors" (some-> actors-cid ipld/link)
   "datoms" (some-> datoms-cid ipld/link)
   "prev" (some-> prev ipld/link)})

(defn- read-actors
  "Every actor under `actors-cid`, as `{address record}`. A full ordered scan,
  which is what hydrate wants — the point read is `actor-at`."
  [get-fn actors-cid]
  (if-not actors-cid
    {}
    (into {} (map (fn [[address m]] [address (decode-actor m)]))
          (pt/scan-prefix get-fn actors-cid ""))))

(defn actor-at
  "One actor under a hydrated state's actors root, without hydrating the rest.

  Takes the ROOT CID rather than a hydrated state on purpose: a caller holding
  only the state root can read one actor from the block store, which is the
  whole point of the tree being content-addressed. `prolly-tree/inclusion-proof`
  works on this same root when the caller needs to PROVE the record rather
  than read it — that surface belongs to prolly-tree, not here."
  [get-fn actors-cid address]
  (when actors-cid
    (some-> (pt/lookup get-fn actors-cid address) decode-actor)))

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

  `:emit-fn` is optional `(fn [actor-op prev-actor next-actor] -> [datom-op …])`
  — the projection from the actor tree (source) to the datom indices. It runs
  inside the same `apply-op` call as the actor mutation, so a source change and
  its projection cannot land in different blocks. `default-emit` is a ready
  policy; omitting it leaves the two roots independent, which is legal and is
  what ADR-2608059000's step 3 closes.

  Ops are `{:op :assert|:retract :s _ :p _ :o _}` for datoms and
  `{:op :actor-put :address _ :actor {…}}` / `{:op :actor-delete :address _}`
  for actors. Actor ops are DATA, not functions: a block has to decode to the
  same ops on every replica, and a closure does not travel.

  Returns `{:init-fn :apply-fn :root-fn :root-kind :hydrate-fn}`. The first
  three are the shape the replica already takes."
  [{:keys [decode-block put! get-fn blind-fn encrypt-fn fuel emit-fn]}]
  (doseq [[k v] {:decode-block decode-block :put! put! :get-fn get-fn
                 :blind-fn blind-fn :encrypt-fn encrypt-fn}]
    (when-not (ifn? v)
      (throw (ex-info "inga.state/machine: missing or non-callable seam"
                      {:type :inga.state/invalid-seam :seam k}))))
  (when (and (some? emit-fn) (not (ifn? emit-fn)))
    (throw (ex-info "inga.state/machine: :emit-fn is not callable"
                    {:type :inga.state/invalid-seam :seam :emit-fn})))
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
   :init-fn (fn [] {:db (arr/empty-db) :actors {} :prev nil})
   :apply-fn
   (fn [state block]
     (let [ops (decode-block block)
           step (fn [s op] (apply-op emit-fn s op))]
       (if-not fuel
         (reduce step state ops)
         (let [height ((:height-fn fuel) block)
               r (fuel/apply-metered
                  {:state state :ops ops
                   :budget ((:budget-fn fuel) block)
                   :cost-fn (:cost-fn fuel)
                   :step step})]
           ;; Into the DB, so `root-fn` covers it. See the ns docstring.
           (update (:state r) :db fuel-datoms height r)))))
   :root-fn
   (fn [state]
     ;; Content-addressed: the same actors + db + prev commit to the same CID,
     ;; which is what makes "every replica reached the same root" a statement
     ;; about the DATA rather than about the traversal order.
     ;;
     ;; `prev` for the INNER arrangement commit is nil: the chain now belongs
     ;; to the StateRoot, and carrying it in both places would be two names
     ;; for one edge that nothing keeps in agreement. It was nil in practice
     ;; before this change too (see the ns docstring).
     #?(:clj
        (let [datoms (arr/commit! put! (:db state) nil schema-version
                                  blind-fn encrypt-fn)]
          (ipld/put-node! put! (state-root-node (actors-root put! (:actors state))
                                                datoms (:prev state))))
        :cljs
        (-> (arr/commit! put! (:db state) nil schema-version blind-fn encrypt-fn)
            (.then (fn [datoms]
                     (ipld/put-node!
                      put! (state-root-node (actors-root put! (:actors state))
                                            datoms (:prev state))))))))
   :hydrate-fn
   ;; Returns the STATE, not the db. `root-fn` takes a state and gives a CID,
   ;; so its inverse gives a state back -- and with two roots, returning only
   ;; the db would silently drop every actor.
   (fn [root-cid decrypt-fn]
     (let [node (ipld/get-node get-fn root-cid)
           _ (when-not node
               (throw (ex-info "inga.state: no block at this state root"
                               {:type :inga.state/missing-root :cid root-cid})))
           datoms-cid (some-> (get node "datoms") ipld/link-cid)
           actors (read-actors get-fn (some-> (get node "actors") ipld/link-cid))
           prev (some-> (get node "prev") ipld/link-cid)]
       #?(:clj {:db (arr/restore get-fn datoms-cid decrypt-fn)
                :actors actors :prev prev}
          :cljs (-> (arr/restore get-fn datoms-cid decrypt-fn)
                    (.then (fn [db] {:db db :actors actors :prev prev}))))))})

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
  "Datalog over a hydrated state — the map `hydrate-fn` returns, not a bare
  db. Thin on purpose: `arrangement.datalog/q` owns the engine; this exists so
  a caller does not have to know that the datom half of an agreed state is an
  arrangement db.

  Handed a bare db it throws rather than answering. Passing one used to be
  correct and silently is not any more, and `adl/q` over a state map would
  return an empty result set — a wrong answer that looks like a right one is
  the failure mode worth spending an exception on."
  ([state q-map] (query state q-map (constantly true)))
  ([state q-map visible?]
   (when-not (and (map? state) (contains? state :db))
     (throw (ex-info "inga.state/query takes the hydrated state, not a bare db"
                     {:type :inga.state/not-a-hydrated-state
                      :keys (when (map? state) (vec (keys state)))})))
   (adl/q (:db state) q-map visible?)))

(defn actors
  "The actor map of a hydrated state, `{address record}`."
  [state] (:actors state))
