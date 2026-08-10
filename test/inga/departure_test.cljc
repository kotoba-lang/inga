(ns inga.departure-test
  "What happens when a witness LEAVES.

  `replica-test` and the socket harness both test a replica that loses its
  memory — `evict!` rebuilds one in place and leaves it connected. Neither
  tests one that stops answering, and those are different failures: an evicted
  replica still takes its turn, a departed one does not.

  `my-turn?` says what happens, in its own words: *keyed by height, a dead
  leader holds its turn forever ... a protocol that tolerates one failure in
  four, not tolerating one failure in four.* This namespace turns that
  paragraph into something that runs.

  ## These tests pin the CURRENT behaviour, which is the wrong behaviour

  `stalls-when-the-height-leader-departs` asserts a STALL. It is a
  characterisation test, not a specification: the day someone lands view-keyed
  leadership with view synchronisation that converges, it will fail, and that
  failure is the point — it is the notification that the gap closed, and
  whoever closes it should delete this test rather than adjust it.

  The other two are real specifications and must keep passing either way: a
  departure that is NOT the next height's leader must not stop the chain, and
  a stall must never cost safety."
  (:require [clojure.test :refer [deftest is testing]]
            [inga.replica :as r]
            [inga.consensus :as c]))

(def witnesses [:w1 :w2 :w3 :w4 :w5 :w6 :w7])

(defn- hash-fn [b] (str "h:" (c/canonical-block b)))

(defn- net []
  (into {} (for [w witnesses]
             [w (r/replica {:witness w :witnesses witnesses
                            :quorum (c/quorum-size (count witnesses))
                            :hash-fn hash-fn})])))

(defn- deliver-all
  "Run until quiet or `max-steps`. `gone` never receives and never sends —
  which is the whole difference from `evict!`, where the replica is rebuilt
  but still participates."
  [replicas outbox now max-steps gone]
  (loop [rs replicas ob outbox t now steps 0]
    (if (or (empty? ob) (>= steps max-steps))
      [rs steps]
      (let [[{:keys [from to msg]} & more] ob
            targets (if (= :all to) (sort (keys rs)) [to])
            [rs' produced]
            (reduce (fn [[rs acc] w]
                      (if (or (= w from) (contains? gone w))
                        [rs acc]
                        (let [[s' out] (r/on-message (get rs w) msg t)]
                          [(assoc rs w s') (into acc (map #(assoc % :from w) out))])))
                    [rs []] targets)]
        (recur rs' (vec (concat more produced)) (inc t) (inc steps) )))))

(defn- tick-all [rs t gone max-steps]
  (let [{:keys [rs ob]}
        (reduce (fn [acc w]
                  (if (contains? gone w)
                    acc
                    (let [[s' out] (r/on-tick (get (:rs acc) w) t)]
                      (-> acc (update :rs assoc w s')
                          (update :ob into (map #(assoc % :from w) out))))))
                {:rs rs :ob []} (sort (keys rs)))
        [rs' _] (deliver-all rs ob t max-steps gone)]
    rs'))

(defn- boot
  "Start the chain and let it settle with everyone present."
  []
  (let [rs (net)
        leader (c/leader-for witnesses 1)
        [s0 out] (r/start (get rs leader) 1000)
        rs (assoc rs leader s0)
        [rs _] (deliver-all rs (mapv #(assoc % :from leader) out) 1000 4000 #{})]
    (reduce #(tick-all %1 %2 #{} 4000) rs (range 1100 1140))))

(defn- heights [rs gone]
  (->> (sort (keys rs)) (remove gone) (map #(r/height (get rs %))) vec))

(deftest a-departure-is-not-an-eviction
  (testing "the harnesses that exist rebuild a replica; this one removes it"
    (let [rs (boot)
          hs (heights rs #{})]
      (is (every? pos? hs) (str "the chain must be running before anything is removed: " hs))
      (is (= 1 (count (set hs))) (str "and everyone must agree: " hs)))))

(deftest stalls-when-the-height-leader-departs
  (testing "CHARACTERISATION — pins the documented gap, and must fail when it closes"
    (let [rs (boot)
          h (apply max (heights rs #{}))
          ;; the witness whose turn is the NEXT height, by the key `my-turn?`
          ;; actually uses
          victim (c/leader-for witnesses (inc h))
          gone #{victim}
          rs' (reduce #(tick-all %1 %2 gone 4000) rs (range 1200 1320))
          hs (heights rs' gone)]
      (is (= [h] (distinct hs))
          (str "height " h " is led by " victim ", which is gone — with leadership "
               "keyed by height the turn never moves, so the chain cannot pass it. "
               "If this FAILS, view-keyed leadership landed: delete this test. Got " hs))
      (is (<= 5 (count (remove gone witnesses)))
          "and it stalls with a quorum still present, which is what makes it a defect"))))

(deftest survives-a-departure-that-is-not-the-next-leader
  (testing "SPECIFICATION — losing a non-leading witness must not stop the chain"
    (let [rs (boot)
          h (apply max (heights rs #{}))
          next-leader (c/leader-for witnesses (inc h))
          ;; someone who is not due to lead the next height
          victim (first (remove #{next-leader} witnesses))
          gone #{victim}
          rs' (reduce #(tick-all %1 %2 gone 4000) rs (range 1200 1260))
          hs (heights rs' gone)]
      (is (> (apply max hs) h)
          (str "the chain must advance past " h " with " victim " gone "
               "(quorum " (c/quorum-size (count witnesses)) " of "
               (count witnesses) ", " (dec (count witnesses)) " present). Got " hs)))))

(deftest a-stall-never-costs-safety
  (testing "SPECIFICATION — a chain that cannot advance must still agree"
    (let [rs (boot)
          h (apply max (heights rs #{}))
          gone #{(c/leader-for witnesses (inc h))}
          rs' (reduce #(tick-all %1 %2 gone 4000) rs (range 1200 1320))
          committed (->> (sort (keys rs')) (remove gone)
                         (map #(mapv (fn [b] ((:hash-fn (get rs' %)) b))
                                     (:committed (get rs' %)))))]
      (is (= 1 (count (set committed)))
          "every surviving replica must hold the same committed prefix")
      (is (every? #(= (first committed) %) committed)
          "liveness may fail; agreement may not"))))
