(ns inga.power-test
  "F3: the power table is a function of the committed prefix, so peers cannot
  hold different validator sets while agreeing on blocks."
  (:require [clojure.test :refer [deftest is testing]]
            [inga.power :as power]
            [inga.stake :as stake]))

(defn- table-after [events]
  (power/apply-events power/empty-table 1 events))

(def bonded
  (table-after [{:event :bond :witness "w1" :amount 100 :roles [:ordering]}
                {:event :bond :witness "w2" :amount 100 :roles [:ordering]}
                {:event :bond :witness "w3" :amount 100 :roles [:ordering]}
                {:event :bond :witness "w4" :amount 100 :roles [:ordering :storage]}]))

(deftest the-table-is-a-function-of-the-committed-prefix
  (testing "the same events in the same order give the same table -- which is
            what moving it into committed state buys"
    (let [evs [{:event :bond :witness "w1" :amount 50 :roles [:ordering]}
               {:event :bond :witness "w2" :amount 25 :roles [:storage]}
               {:event :slash :witness "w1" :terms {}}]]
      (is (= (table-after evs) (table-after evs)))
      (is (= 25 (get-in (table-after evs) [:bonds "w2" :amount])))
      (is (nil? (get-in (table-after evs) [:bonds "w1"]))
          "inga.stake/slash removes the entire record, and this must not write it back")))
  (testing "and the height it is a function OF is recorded"
    (is (= 1 (:height bonded)))))

(deftest storage-is-a-role-on-the-existing-market-not-a-second-economy
  (is (= #{:ordering :recompute :storage} power/roles))
  (is (= #{"w4"} (stake/eligible-witnesses (power/bonds bonded) 1 :storage)))
  (is (= #{"w1" "w2" "w3" "w4"} (stake/eligible-witnesses (power/bonds bonded) 1 :ordering)))
  (testing "one bond, self-selected roles"
    (is (= 100 (get-in bonded [:bonds "w4" :amount]))
        "w4 does not post separate collateral per role")))

(deftest an-unknown-role-is-refused-rather-than-ignored
  (testing "silently ignoring is either a typo that counts toward nothing or a
            privilege escalation that counts toward everything"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (table-after [{:event :bond :witness "w" :amount 1 :roles [:ordring]}])))))

(deftest an-unknown-event-is-refused-rather-than-ignored
  (testing "a no-op on an unknown type diverges a replica that knows it from
            one that does not -- the exact failure the table moved to fix"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (table-after [{:event :airdrop :witness "w1"}])))))

(deftest the-bonds-seam-is-what-stake-consumes
  (testing "the whole point of F3: inga.stake is handed a bonds map, and this
            makes that map a function of the committed prefix"
    (is (= 400 (stake/total-stake (power/bonds bonded)
                                  (stake/eligible-witnesses (power/bonds bonded) 1 :ordering))))
    (is (= #{} (stake/eligible-witnesses (power/bonds bonded) 101 :ordering))
        "the floor is stake's policy, not this namespace's")))

(deftest unbonding-leaves-the-active-set-before-the-collateral-leaves
  (let [t (power/apply-events bonded 2 [{:event :unbond-request :witness "w4" :available-at 10}])]
    (testing "a witness in its notice period is not in the map stake reads --
              voting through the notice period would let it equivocate and
              then walk the bond out"
      (is (nil? (get (power/bonds t) "w4")))
      (is (= #{"w1" "w2" "w3"} (stake/eligible-witnesses (power/bonds t) 1 :ordering))))
    (testing "but the collateral is still in the table -- leaving immediately
              would mean equivocating with nothing at risk"
      (is (= 100 (get-in t [:bonds "w4" :amount]))))
    (testing "and completing early is refused"
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (power/apply-events t 3 [{:event :unbond-complete :witness "w4" :height 3}])))
      (is (nil? (get-in (power/apply-events t 10 [{:event :unbond-complete :witness "w4" :height 10}])
                        [:bonds "w4"]))))))

(deftest slashing-lands-at-a-decided-height
  (let [t (power/apply-events bonded 5 [{:event :slash :witness "w1" :terms {}}])]
    (is (nil? (get-in t [:bonds "w1"])) "the whole record goes -- no ghost entry")
    (is (= 5 (:height t)) "and the table records which prefix it is a function of")
    (is (nil? (get (power/bonds t) "w1"))
        "so stake never counts it again from this height on")
    (testing "the magnitude is committed too, not just the fact"
      (is (= [{:witness "w1" :burned 95.0 :rewarded 0}] (:slashes t))))))

(deftest events-for-an-unbonded-witness-are-refused
  (doseq [e [{:event :slash :witness "ghost" :terms {}}
             {:event :set-roles :witness "ghost" :roles [:ordering]}
             {:event :unbond-request :witness "ghost" :available-at 1}]]
    (is (thrown? #?(:clj Exception :cljs js/Error) (table-after [e])) (pr-str e))))
