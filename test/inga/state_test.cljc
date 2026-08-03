(ns inga.state-test
  "F1's acceptance criterion, stated by ADR-2608038000 as: four replicas
  reach the same CID, AND the state hydrated from that CID is queryable with
  Datalog. The second half is the whole point — same-digest was already
  achievable and was not enough."
  (:require [clojure.test :refer [deftest is testing]]
            [inga.fuel :as fuel]
            [inga.state :as state]))

;; A shared content-addressed block store. Replicas write into the SAME store
;; because that is the real topology: blocks are immutable and CID-addressed,
;; so two replicas producing the same block produce the same bytes at the same
;; address, and the store cannot tell (or care) which of them wrote it.
(defn- store [] (atom {}))

(defn- machine-on [blocks]
  (state/machine
   {:decode-block identity            ; a block IS its op list in these tests
    :put! (fn [cid bytes] (swap! blocks assoc cid bytes))
    :get-fn (fn [cid] (get @blocks cid))
    ;; blind-fn/encrypt-fn are arrangement's required keyed-MAC and AEAD
    ;; seams. Identity-shaped stand-ins here: what F1 is about is the ROOT
    ;; being content-addressed and restorable, and a real MAC would not make
    ;; that sharper. arrangement requires them precisely so no caller gets an
    ;; unblinded index by forgetting an argument, which is why they are passed
    ;; explicitly rather than defaulted.
    :blind-fn pr-str
    :encrypt-fn identity}))

(def ops-a [{:op :assert :s "alice" :p "role" :o "witness"}
            {:op :assert :s "alice" :p "stake" :o 100}
            {:op :assert :s "bob" :p "role" :o "witness"}])
(def ops-b [{:op :assert :s "bob" :p "stake" :o 50}
            {:op :retract :s "alice" :p "stake" :o 100}
            {:op :assert :s "alice" :p "stake" :o 120}])

(defn- run [machine blocks-seq]
  (reduce (:apply-fn machine) ((:init-fn machine)) blocks-seq))

(deftest four-replicas-reach-the-same-cid
  (let [blocks (store)
        roots (mapv (fn [_]
                      (let [m (machine-on blocks)]
                        ((:root-fn m) (run m [ops-a ops-b]))))
                    (range 4))]
    (is (= 1 (count (set roots)))
        "four independent runs of the same committed prefix produce one root")
    (is (string? (first roots)))
    (testing "and it is a CID, not a digest of our own invention"
      (is (re-find #"^b" (first roots))
          "arrangement returns a CIDv1 base32 string"))))

(deftest the-root-is-a-function-of-the-data-not-the-traversal
  (testing "the same facts asserted in a different order commit to one root"
    (let [blocks (store)
          m1 (machine-on blocks)
          m2 (machine-on blocks)
          forward ((:root-fn m1) (run m1 [ops-a]))
          shuffled ((:root-fn m2) (run m2 [(vec (reverse ops-a))]))]
      (is (= forward shuffled)
          "content-addressed means addressed by content"))))

(deftest a-different-prefix-produces-a-different-root
  (let [blocks (store)
        m1 (machine-on blocks)
        m2 (machine-on blocks)]
    (is (not= ((:root-fn m1) (run m1 [ops-a]))
              ((:root-fn m2) (run m2 [ops-a ops-b])))
        "otherwise the root would not be checking anything")))

(deftest the-agreed-root-hydrates-and-answers-datalog
  (testing "this is the half an opaque digest could never do"
    (let [blocks (store)
          m (machine-on blocks)
          root ((:root-fn m) (run m [ops-a ops-b]))
          ;; A cold reader: it has the CID and the block store, and nothing
          ;; else -- no in-memory state carried over from the run.
          restored ((:hydrate-fn m) root identity)]
      (is (= #{["alice"] ["bob"]}
             (state/query restored {:find '[?s] :where '[[?s "role" "witness"]]}))
          "the state four replicas agreed on is queryable by a party that only had the CID")
      (is (= #{[120]}
             (state/query restored {:find '[?v] :where '[["alice" "stake" ?v]]}))
          "and the retraction in the second block is reflected"))))

(deftest opaque-roots-stay-legal-but-cannot-back-a-kotobase-ref
  (let [opaque (state/opaque-machine
                {:init-fn (constantly 0)
                 :apply-fn (fn [s _] (inc s))
                 :root-fn (fn [s] (str "digest-" s))})]
    (testing "an opaque machine is a normal machine"
      (is (= 2 (reduce (:apply-fn opaque) ((:init-fn opaque)) [:a :b])))
      (is (false? (state/hydratable? opaque))))
    (testing "but the gate refuses it, so ADR-2608038000 D6's ordering is enforced by code"
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (state/assert-hydratable! opaque))))
    (testing "and passes a cid machine"
      (let [m (machine-on (store))]
        (is (state/hydratable? m))
        (is (identical? m (state/assert-hydratable! m)))))))

(deftest a-machine-cannot-be-built-with-a-missing-seam
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (state/machine {:decode-block identity :put! (fn [_ _])}))))

(deftest init-is-a-thunk-so-replicas-never-share-state
  (testing "engi ADR-2608022600: a shared ready-made machine value made four
            replicas agree on blocks and differ by 200 resting orders"
    (let [m (machine-on (store))]
      (is (not (identical? ((:init-fn m)) ((:init-fn m))))
          "each replica gets its own initial state"))))

;; ── F2 wired into F1: exhaustion must change the root ────────────────────────

(defn- metered-machine [blocks budget]
  (state/machine
   {:decode-block :ops
    :put! (fn [cid bytes] (swap! blocks assoc cid bytes))
    :get-fn (fn [cid] (get @blocks cid))
    :blind-fn pr-str
    :encrypt-fn identity
    :fuel {:budget-fn (constantly budget)
           :cost-fn (fuel/fixed-cost 1)
           :height-fn :height}}))

(def big-block {:height 1 :ops ops-a})

(deftest fuel-exhaustion-changes-the-root
  (testing "the property the whole wiring exists for -- a fuel ledger kept
            outside the db would leave two replicas that stopped at different
            ops producing identical CIDs, which would look like agreement"
    (let [blocks (store)
          generous ((:root-fn (metered-machine blocks 100))
                    ((:apply-fn (metered-machine blocks 100))
                     ((:init-fn (metered-machine blocks 100))) big-block))
          starved ((:root-fn (metered-machine blocks 1))
                   ((:apply-fn (metered-machine blocks 1))
                    ((:init-fn (metered-machine blocks 1))) big-block))]
      (is (not= generous starved)
          "a replica that ran out is visibly a different state, not a silent one"))))

(deftest the-same-budget-still-agrees
  (let [blocks (store)
        run #(let [m (metered-machine blocks 2)]
               ((:root-fn m) ((:apply-fn m) ((:init-fn m)) big-block)))]
    (is (= 1 (count (set (repeatedly 4 run))))
        "metering does not cost determinism")))

(deftest the-fuel-record-is-queryable-from-the-cid
  (let [blocks (store)
        m (metered-machine blocks 2)
        root ((:root-fn m) ((:apply-fn m) ((:init-fn m)) big-block))
        restored ((:hydrate-fn m) root identity)]
    (is (= #{[2]} (state/query restored
                               {:find '[?i]
                                :where '[["inga.fuel/block/1" "inga.fuel/exhausted-at" ?i]]}))
        "a reader holding only the CID can see where this block ran out")
    (is (= #{[1]} (state/query restored
                               {:find '[?n]
                                :where '[["inga.fuel/block/1" "inga.fuel/dropped" ?n]]})))))

(deftest a-block-that-finished-writes-no-exhaustion-datom
  (testing "an explicit exhausted-at = -1 per block would put a datom into a
            content-addressed index forever to record that nothing happened"
    (let [blocks (store)
          m (metered-machine blocks 100)
          root ((:root-fn m) ((:apply-fn m) ((:init-fn m)) big-block))
          restored ((:hydrate-fn m) root identity)]
      (is (= #{} (state/query restored
                              {:find '[?i]
                               :where '[["inga.fuel/block/1" "inga.fuel/exhausted-at" ?i]]})))
      (is (= #{[3]} (state/query restored
                                 {:find '[?n]
                                  :where '[["inga.fuel/block/1" "inga.fuel/applied" ?n]]}))))))

(deftest fuel-config-must-be-complete
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (state/machine {:decode-block identity :put! (fn [_ _]) :get-fn identity
                               :blind-fn pr-str :encrypt-fn identity
                               :fuel {:budget-fn (constantly 1)}}))))
