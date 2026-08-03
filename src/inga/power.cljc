(ns inga.power
  "F3 — the power table as COMMITTED STATE, and a `:storage` role.

  ## What changes, and what deliberately does not

  `inga.stake` (which arrived here from engi in the same extraction) already
  implements permissionless admission by external
  collateral, stake-weighted quorum, equivocation-only slashing, and a
  role-tagged single bond market (`:ordering` for block-consensus voting,
  `:recompute` for proof-of-compute sampling). None of that economics is
  reimplemented here, and this namespace must not grow a second copy of it —
  superproject ADR-2608031200 named that failure mode the same week.

  The one thing `inga.stake` cannot do by itself is the thing F3 is about:
  **it is HANDED the bond map from outside.** `{witness-did -> {:amount
  :roles}}` arrives already assembled, so who is a witness is decided
  somewhere the consensus does not order. Two replicas reading the escrow at
  slightly different moments have different validator sets, and a validator
  set is not a thing peers may disagree about — it is what quorum is counted
  against.

  Filecoin's Storage Power Consensus puts the power table IN chain state, so
  a change in power is ordered by the same consensus that orders everything
  else. This namespace is that: a table plus a transition function the
  machine applies, so the table at height h is a function of the committed
  prefix and nothing else.

  ## `:storage` — the new role

  SPC's deeper idea is that the Sybil-resistant resource should be the useful
  work the network exists to do (storage, proved). For this stack that work
  is retaining and serving datom blocks, so `:storage` joins the existing
  role set on the existing market — a role added to one rulebook, not a
  second economy.

  What this does NOT do: replace external collateral. `inga.stake`'s reason
  for bonding USDC rather than EN holds unchanged (EN nets to zero across all
  agents, so bonding it disincentivises nothing), and ADR-2608038000 F3 says
  so explicitly. And `:storage` power here is credited by attested retrieval
  sampling — the shape `:recompute` already uses — **not** by PoRep/PoSt.
  This library does not implement a storage proof and no deployment using it
  may claim Filecoin-equivalent storage guarantees."
  (:require [clojure.set :as set]
            [inga.stake :as stake]))

(def roles
  "Closed on purpose. An unknown role that silently counts toward nothing is
  indistinguishable from a typo, and an unknown role that silently counts
  toward everything is a privilege escalation."
  #{:ordering :recompute :storage})

(def empty-table
  {:bonds {} :height 0})

;; ── transitions ─────────────────────────────────────────────────────────────
;;
;; Every one is `(table, event) -> table`. Pure, total, and ORDER-DEPENDENT by
;; construction: that is the point — the committed sequence decides the table,
;; so peers cannot hold different validator sets while agreeing on blocks.

(defmulti apply-event
  "Apply one power event. Unknown event types throw rather than no-op: a
  silently ignored event is a state divergence between a replica that knows
  the type and one that does not, which is exactly the failure the table
  moved into committed state to avoid."
  (fn [_table event] (:event event)))

(defmethod apply-event :default
  [_table event]
  (throw (ex-info "inga.power: unknown power event"
                  {:type :inga.power/unknown-event :event (:event event)})))

(defmethod apply-event :bond
  [table {:keys [witness amount roles] :as e}]
  (when-not (and (string? witness) (nat-int? amount))
    (throw (ex-info "inga.power: :bond needs a witness and a non-negative amount"
                    {:type :inga.power/invalid-event :event e})))
  (let [rs (set roles)
        unknown (set/difference rs inga.power/roles)]
    (when (seq unknown)
      (throw (ex-info "inga.power: unknown role in :bond"
                      {:type :inga.power/unknown-role :roles unknown})))
    (update-in table [:bonds witness]
               (fn [b] (-> (or b {:amount 0 :roles #{}})
                           (update :amount + amount)
                           (update :roles into rs))))))

(defmethod apply-event :set-roles
  [table {:keys [witness roles] :as e}]
  (let [rs (set roles)
        unknown (set/difference rs inga.power/roles)]
    (when (seq unknown)
      (throw (ex-info "inga.power: unknown role in :set-roles"
                      {:type :inga.power/unknown-role :roles unknown})))
    (if-not (get-in table [:bonds witness])
      (throw (ex-info "inga.power: :set-roles for a witness with no bond"
                      {:type :inga.power/invalid-event :event e}))
      (assoc-in table [:bonds witness :roles] rs))))

(defmethod apply-event :unbond-request
  [table {:keys [witness available-at] :as e}]
  (if-not (get-in table [:bonds witness])
    (throw (ex-info "inga.power: :unbond-request for a witness with no bond"
                    {:type :inga.power/invalid-event :event e}))
    ;; Requesting takes the witness out of the active set IMMEDIATELY while
    ;; leaving the collateral in place until `available-at`. A witness that
    ;; kept voting through its notice period could equivocate and then walk
    ;; the bond out; a witness whose bond left immediately could equivocate
    ;; with nothing at risk. Both halves are needed and they are not
    ;; symmetric.
    (-> table
        (assoc-in [:bonds witness :unbonding-at] available-at)
        (assoc-in [:bonds witness :roles] #{}))))

(defmethod apply-event :unbond-complete
  [table {:keys [witness height] :as e}]
  (let [b (get-in table [:bonds witness])
        at (:unbonding-at b)]
    (cond
      (nil? b) (throw (ex-info "inga.power: :unbond-complete for a witness with no bond"
                               {:type :inga.power/invalid-event :event e}))
      (nil? at) (throw (ex-info "inga.power: :unbond-complete without a request"
                                {:type :inga.power/invalid-event :event e}))
      (< height at) (throw (ex-info "inga.power: :unbond-complete before the notice period elapsed"
                                    {:type :inga.power/too-early :height height :available-at at}))
      :else (update table :bonds dissoc witness))))

(defmethod apply-event :slash
  [table {:keys [witness terms] :as e}]
  (if-not (get-in table [:bonds witness])
    (throw (ex-info "inga.power: :slash for a witness with no bond"
                    {:type :inga.power/invalid-event :event e}))
    ;; The economics are `inga.stake/slash`'s (burn fraction, whistleblower
    ;; share, where the remainder is credited). The EVIDENCE is checked before
    ;; an event gets here — `inga.stake` owns equivocation detection and its
    ;; verification too. What this owns is that the consequence lands at a
    ;; DECIDED POINT in the sequence, so every replica's table changes at the
    ;; same height rather than whenever each one noticed.
    ;; `stake/slash` returns `{:bonds :burned :rewarded}` and removes the
    ;; offender's ENTIRE record. Writing the roles back afterwards -- which the
    ;; first version of this did -- resurrects a ghost `{:roles #{}}` entry
    ;; that `bonds` then hands to stake as a zero-stake witness. The test that
    ;; caught it is `slashing-lands-at-a-decided-height`.
    (let [{:keys [bonds burned rewarded]} (stake/slash (:bonds table) witness
                                                       (or terms {}))]
      (-> table
          (assoc :bonds bonds)
          ;; The numbers go into the table too: a slash whose consequence is
          ;; ordered but whose magnitude is not is only half committed.
          (update :slashes (fnil conj [])
                  {:witness witness :burned burned :rewarded rewarded})))))

(defn apply-events
  "Fold a block's power events at `height`. `:height` is recorded so a reader
  can say WHICH committed prefix a table is the function of — a power table
  without its height is a claim with no way to check it."
  [table height events]
  (assoc (reduce apply-event table events) :height height))

;; ── reading the table: DELEGATED ────────────────────────────────────────────
;;
;; `eligible` / `stake-for` / `quorum-met?` used to live here as simplified
;; reimplementations, written before `inga.stake` was in this repo. They are
;; gone. `inga.stake` already owns admission, stake weighting and the quorum
;; rule, and two implementations of a quorum rule is not a redundancy — it is
;; two answers to "did this block commit". ADR-2608031200 named this failure
;; mode the same week.
;;
;; What is left here is the part `inga.stake` genuinely cannot do: it is HANDED
;; a bonds map, so `bonds` below is the seam that makes that map a function of
;; the committed prefix instead of of whenever someone read the escrow.

(defn bonds
  "The `{witness -> {:amount :roles}}` map `inga.stake` consumes, as of this
  table's height. Witnesses in their unbonding notice period are excluded:
  their collateral is still at risk but their vote is not counted."
  [table]
  (into {} (remove (comp :unbonding-at val)) (:bonds table)))
