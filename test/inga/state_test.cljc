(ns inga.state-test
  "F1's acceptance criterion, stated by ADR-2608038000 as: four replicas reach
  the same CID, AND the state hydrated from that CID is queryable with
  Datalog. The second half is the whole point — same-digest was already
  achievable and was not enough.

  ## Why half of this file is written twice

  `arrangement/commit!` returns a CID on the JVM and a `js/Promise` on
  ClojureScript, and `restore` likewise. Tests that assume the synchronous
  shape PASS on the JVM and are silent about the runtime kotobase actually
  deploys on — which is the exact failure `inga.parity` exists to prevent, so
  writing these once and only running them on the JVM would have been the same
  mistake in a different file.

  Every test that touches `root-fn` or `hydrate-fn` therefore appears in both
  arms: synchronous on `:clj`, `cljs.test/async` on `:cljs`. The assertions are
  the same ones; only the awaiting differs, exactly as it does for a caller.

  Where this lands in production: `inga.replica/state-root` is REPORTING, not
  a consensus decision — no adopt or commit path reads it — so a Promise there
  is a caller's `await`, not a protocol break. That is why this split is
  liveable rather than a defect."
  (:require [clojure.test :refer [deftest is testing]]
            #?(:cljs [clojure.test :refer [async]])
            [inga.fuel :as fuel]
            [inga.state :as state]))

;; A shared content-addressed block store. Replicas write into the SAME store
;; because that is the real topology: blocks are immutable and CID-addressed,
;; so two replicas producing the same block produce the same bytes at the same
;; address, and the store cannot tell (or care) which of them wrote it.
(defn- store [] (atom {}))

;; arrangement's platform split is not only in what it RETURNS: on cljs it also
;; expects `blind-fn`/`encrypt-fn`/`decrypt-fn` to return Promises. Discovered
;; the only way it can be -- by running the cljs build, which failed inside
;; arrangement with `.then is not a function` while the JVM suite was green.
(def blind #?(:clj pr-str :cljs (fn [x] (js/Promise.resolve (pr-str x)))))
(def crypt #?(:clj identity :cljs (fn [b] (js/Promise.resolve b))))
(def uncrypt crypt)

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
    :blind-fn blind
    :encrypt-fn crypt}))

(def ops-a [{:op :assert :s "alice" :p "role" :o "witness"}
            {:op :assert :s "alice" :p "stake" :o 100}
            {:op :assert :s "bob" :p "role" :o "witness"}])
(def ops-b [{:op :assert :s "bob" :p "stake" :o 50}
            {:op :retract :s "alice" :p "stake" :o 100}
            {:op :assert :s "alice" :p "stake" :o 120}])

(defn- run [machine blocks-seq]
  (reduce (:apply-fn machine) ((:init-fn machine)) blocks-seq))

;; `settle` is how a caller gets a CID out of arrangement on either runtime.
;; It is not a shim that hides the difference — the :cljs arm is a real await,
;; and the tests below still have to say `async done`.
#?(:cljs
   (defn- settle [xs f] (-> (js/Promise.all (clj->js xs)) (.then #(f (vec %))))))

(defn- roots-of [ms-and-blocks]
  (mapv (fn [[m bs]] ((:root-fn m) (run m bs))) ms-and-blocks))

;; ── the same CID from independent runs ───────────────────────────────────────

(defn- same-cid-assertions [roots]
  (is (= 1 (count (set roots)))
      "four independent runs of the same committed prefix produce one root")
  (is (string? (first roots)))
  (is (re-find #"^b" (first roots))
      "and it is a CIDv1 base32 string, not a digest of our own invention"))

(deftest four-replicas-reach-the-same-cid
  (let [blocks (store)
        rs (roots-of (repeatedly 4 (fn [] [(machine-on blocks) [ops-a ops-b]])))]
    #?(:clj (same-cid-assertions rs)
       :cljs (async done (settle rs (fn [cids] (same-cid-assertions cids) (done)))))))

(deftest the-root-is-a-function-of-the-data-not-the-traversal
  (testing "the same facts asserted in a different order commit to one root"
    (let [blocks (store)
          rs (roots-of [[(machine-on blocks) [ops-a]]
                        [(machine-on blocks) [(vec (reverse ops-a))]]])
          check (fn [[forward shuffled]]
                  (is (= forward shuffled) "content-addressed means addressed by content"))]
      #?(:clj (check rs)
         :cljs (async done (settle rs (fn [cids] (check cids) (done))))))))

(deftest a-different-prefix-produces-a-different-root
  (let [blocks (store)
        rs (roots-of [[(machine-on blocks) [ops-a]]
                      [(machine-on blocks) [ops-a ops-b]]])
        check (fn [[a b]]
                (is (not= a b) "otherwise the root would not be checking anything"))]
    #?(:clj (check rs)
       :cljs (async done (settle rs (fn [cids] (check cids) (done)))))))

;; ── the half an opaque digest could never do ─────────────────────────────────

(defn- query-assertions [restored]
  (is (= #{["alice"] ["bob"]}
         (state/query restored {:find '[?s] :where '[[?s "role" "witness"]]}))
      "the state four replicas agreed on is queryable by a party that only had the CID")
  (is (= #{[120]}
         (state/query restored {:find '[?v] :where '[["alice" "stake" ?v]]}))
      "and the retraction in the second block is reflected"))

(deftest the-agreed-root-hydrates-and-answers-datalog
  (let [blocks (store)
        m (machine-on blocks)
        root ((:root-fn m) (run m [ops-a ops-b]))]
    ;; A cold reader: it has the CID and the block store, and nothing else --
    ;; no in-memory state carried over from the run.
    #?(:clj (query-assertions ((:hydrate-fn m) root uncrypt))
       :cljs (async done
               (-> (js/Promise.resolve root)
                   (.then (fn [cid] ((:hydrate-fn m) cid uncrypt)))
                   (.then (fn [restored] (query-assertions restored) (done))))))))

;; ── F2 wired into F1: exhaustion must change the root ────────────────────────

(defn- metered-machine [blocks budget]
  (state/machine
   {:decode-block :ops
    :put! (fn [cid bytes] (swap! blocks assoc cid bytes))
    :get-fn (fn [cid] (get @blocks cid))
    :blind-fn blind
    :encrypt-fn crypt
    :fuel {:budget-fn (constantly budget)
           :cost-fn (fuel/fixed-cost 1)
           :height-fn :height}}))

(def big-block {:height 1 :ops ops-a})

(defn- metered-root [blocks budget]
  (let [m (metered-machine blocks budget)]
    ((:root-fn m) ((:apply-fn m) ((:init-fn m)) big-block))))

(deftest fuel-exhaustion-changes-the-root
  (testing "the property the whole wiring exists for -- a fuel ledger kept
            outside the db would leave two replicas that stopped at different
            ops producing identical CIDs, which would look like agreement"
    (let [blocks (store)
          rs [(metered-root blocks 100) (metered-root blocks 1)]
          check (fn [[generous starved]]
                  (is (not= generous starved)
                      "a replica that ran out is visibly a different state, not a silent one"))]
      #?(:clj (check rs)
         :cljs (async done (settle rs (fn [cids] (check cids) (done))))))))

(deftest the-same-budget-still-agrees
  (let [blocks (store)
        rs (vec (repeatedly 4 #(metered-root blocks 2)))
        check (fn [cids] (is (= 1 (count (set cids))) "metering does not cost determinism"))]
    #?(:clj (check rs)
       :cljs (async done (settle rs (fn [cids] (check cids) (done)))))))

(defn- exhaustion-assertions [restored]
  (is (= #{[2]} (state/query restored
                             {:find '[?i]
                              :where '[["inga.fuel/block/1" "inga.fuel/exhausted-at" ?i]]}))
      "a reader holding only the CID can see where this block ran out")
  (is (= #{[1]} (state/query restored
                             {:find '[?n]
                              :where '[["inga.fuel/block/1" "inga.fuel/dropped" ?n]]}))))

(deftest the-fuel-record-is-queryable-from-the-cid
  (let [blocks (store)
        m (metered-machine blocks 2)
        root ((:root-fn m) ((:apply-fn m) ((:init-fn m)) big-block))]
    #?(:clj (exhaustion-assertions ((:hydrate-fn m) root uncrypt))
       :cljs (async done
               (-> (js/Promise.resolve root)
                   (.then (fn [cid] ((:hydrate-fn m) cid uncrypt)))
                   (.then (fn [restored] (exhaustion-assertions restored) (done))))))))

(defn- clean-block-assertions [restored]
  (is (= #{} (state/query restored
                          {:find '[?i]
                           :where '[["inga.fuel/block/1" "inga.fuel/exhausted-at" ?i]]})))
  (is (= #{[3]} (state/query restored
                             {:find '[?n]
                              :where '[["inga.fuel/block/1" "inga.fuel/applied" ?n]]}))))

(deftest a-block-that-finished-writes-no-exhaustion-datom
  (testing "an explicit exhausted-at = -1 per block would put a datom into a
            content-addressed index forever to record that nothing happened"
    (let [blocks (store)
          m (metered-machine blocks 100)
          root ((:root-fn m) ((:apply-fn m) ((:init-fn m)) big-block))]
      #?(:clj (clean-block-assertions ((:hydrate-fn m) root uncrypt))
         :cljs (async done
                 (-> (js/Promise.resolve root)
                     (.then (fn [cid] ((:hydrate-fn m) cid uncrypt)))
                     (.then (fn [restored] (clean-block-assertions restored) (done)))))))))

;; ── structural, and identical on both runtimes ───────────────────────────────

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

(deftest fuel-config-must-be-complete
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (state/machine {:decode-block identity :put! (fn [_ _]) :get-fn identity
                               :blind-fn blind :encrypt-fn crypt
                               :fuel {:budget-fn (constantly 1)}}))))
