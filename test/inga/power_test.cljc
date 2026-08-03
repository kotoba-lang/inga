(ns inga.power-test
  "F3: the power table is a function of the committed prefix, so peers cannot
  hold different validator sets while agreeing on blocks."
  (:require [clojure.test :refer [deftest is testing]]
            [inga.power :as power]))

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
               {:event :bond :witness "w1" :amount 25 :roles [:storage]}
               {:event :slash :witness "w1" :amount 30}]]
      (is (= (table-after evs) (table-after evs)))
      (is (= 45 (get-in (table-after evs) [:bonds "w1" :amount])))))
  (testing "and the height it is a function OF is recorded"
    (is (= 1 (:height bonded)))))

(deftest storage-is-a-role-on-the-existing-market-not-a-second-economy
  (is (= #{:ordering :recompute :storage} power/roles))
  (is (= #{"w4"} (power/eligible bonded :storage 1)))
  (is (= #{"w1" "w2" "w3" "w4"} (power/eligible bonded :ordering 1)))
  (testing "one bond, self-selected roles"
    (is (= 100 (get-in bonded [:bonds "w4" :amount]))
        "w4 does not post separate collateral per role")))

(deftest an-unknown-role-is-refused-rather-than-ignored
  (testing "silently ignoring is either a typo that counts toward nothing or a
            privilege escalation that counts toward everything"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (table-after [{:event :bond :witness "w" :amount 1 :roles [:ordring]}])))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (power/eligible bonded :nonsense 1)))))

(deftest an-unknown-event-is-refused-rather-than-ignored
  (testing "a no-op on an unknown type diverges a replica that knows it from
            one that does not -- the exact failure the table moved to fix"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (table-after [{:event :airdrop :witness "w1"}])))))

(deftest the-floor-is-a-parameter-because-it-is-policy
  (is (= #{} (power/eligible bonded :ordering 101)))
  (is (= 400 (power/stake-for bonded :ordering 1)))
  (is (= 0 (power/stake-for bonded :ordering 101))))

(deftest quorum-is-stake-weighted-so-splitting-a-bond-buys-nothing
  (testing "sybil: one witness with 300 vs three witnesses with 100 each"
    (let [whole (table-after [{:event :bond :witness "big" :amount 300 :roles [:ordering]}
                              {:event :bond :witness "other" :amount 300 :roles [:ordering]}])
          split (table-after [{:event :bond :witness "big-a" :amount 100 :roles [:ordering]}
                              {:event :bond :witness "big-b" :amount 100 :roles [:ordering]}
                              {:event :bond :witness "big-c" :amount 100 :roles [:ordering]}
                              {:event :bond :witness "other" :amount 300 :roles [:ordering]}])]
      (is (false? (power/quorum-met? whole :ordering 1 ["big"])))
      (is (false? (power/quorum-met? split :ordering 1 ["big-a" "big-b" "big-c"]))
          "three identities voting the same 300 is still 300"))))

(deftest exactly-two-thirds-is-not-a-quorum
  (testing "at exactly 2/3 two disjoint quorums can both form, and two quorums
            that do not intersect is what every safety argument rules out"
    (let [t (table-after [{:event :bond :witness "a" :amount 200 :roles [:ordering]}
                          {:event :bond :witness "b" :amount 100 :roles [:ordering]}])]
      (is (false? (power/quorum-met? t :ordering 1 ["a"])) "200 of 300 is exactly 2/3")
      (is (true? (power/quorum-met? t :ordering 1 ["a" "b"]))))))

(deftest votes-from-outside-the-eligible-set-do-not-count
  (is (false? (power/quorum-met? bonded :ordering 1 ["w1" "w2" "stranger"]))
      "200 of 400 even with a stranger's name on the list")
  (is (true? (power/quorum-met? bonded :ordering 1 ["w1" "w2" "w3"]))))

(deftest unbonding-leaves-the-active-set-before-the-collateral-leaves
  (let [t (power/apply-events bonded 2 [{:event :unbond-request :witness "w4" :available-at 10}])]
    (testing "roles drop immediately -- a witness voting through its notice
              period could equivocate and then walk the bond out"
      (is (= #{"w1" "w2" "w3"} (power/eligible t :ordering 1)))
      (is (= #{} (power/eligible t :storage 1))))
    (testing "but the collateral is still there -- leaving immediately would
              mean equivocating with nothing at risk"
      (is (= 100 (get-in t [:bonds "w4" :amount]))))
    (testing "and completing early is refused"
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (power/apply-events t 3 [{:event :unbond-complete :witness "w4" :height 3}])))
      (is (nil? (get-in (power/apply-events t 10 [{:event :unbond-complete :witness "w4" :height 10}])
                        [:bonds "w4"]))))))

(deftest slashing-lands-at-a-decided-height
  (let [t (power/apply-events bonded 5 [{:event :slash :witness "w1" :amount 60}])]
    (is (= 40 (get-in t [:bonds "w1" :amount])))
    (is (= #{} (get-in t [:bonds "w1" :roles])) "and it leaves the active set")
    (is (= 5 (:height t)))
    (testing "a slash cannot drive a bond negative"
      (is (= 0 (get-in (power/apply-events bonded 6 [{:event :slash :witness "w1" :amount 9999}])
                       [:bonds "w1" :amount]))))))

(deftest events-for-an-unbonded-witness-are-refused
  (doseq [e [{:event :slash :witness "ghost" :amount 1}
             {:event :set-roles :witness "ghost" :roles [:ordering]}
             {:event :unbond-request :witness "ghost" :available-at 1}]]
    (is (thrown? #?(:clj Exception :cljs js/Error) (table-after [e])) (pr-str e))))
