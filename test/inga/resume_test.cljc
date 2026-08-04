(ns inga.resume-test
  "A replica that restarts must not vote twice at one height.

  These tests were written BEFORE `snapshot`/`resume` existed, because the
  failure they guard against is the one this system slashes for and it is
  invisible from the inside: a replica that votes a second time at a height it
  already voted at has equivocated, and nothing in its own state looks wrong
  afterwards. The only way to see it is to catch the second vote leaving.

  `replay` already restores `:voted` from the blocks it folds, which is why
  restarting from the WHOLE log is safe. `resume` exists so a replica does not
  have to fold the whole log — and the whole point of the bounded form is that
  it throws away the very thing `replay` was reconstructing. So the bound has
  to be replaced by something that refuses at least as much."
  (:require [clojure.test :refer [deftest is testing]]
            [inga.replica :as r]
            [inga.consensus :as c]))

(def witnesses [:w1 :w2 :w3 :w4])

(defn- hash-fn [b] (str "h:" (c/canonical-block b)))

(defn- opts [w]
  {:witness w :witnesses witnesses :quorum (c/quorum-size 4) :hash-fn hash-fn})

(defn- net []
  (into {} (for [w witnesses] [w (r/replica (opts w))])))

(defn- deliver-all [replicas outbox now max-steps]
  (loop [rs replicas ob outbox t now steps 0]
    (if (or (empty? ob) (>= steps max-steps))
      rs
      (let [[{:keys [from to msg]} & more] ob
            targets (if (= :all to) (sort (keys rs)) [to])
            [rs' produced]
            (reduce (fn [[rs acc] w]
                      (if (= w from)
                        [rs acc]
                        (let [[s' out] (r/on-message (get rs w) msg t)]
                          [(assoc rs w s') (into acc (map #(assoc % :from w) out))])))
                    [rs []]
                    targets)]
        (recur rs' (vec (concat more produced)) (+ t 1) (inc steps))))))

(defn- run
  "Start the network and let it settle, so there is a chain with real history
  rather than a genesis block and a hope."
  []
  (let [rs (net)
        leader (c/leader-for witnesses 1)
        [s0 out] (r/start (get rs leader) 1000)
        rs (assoc rs leader s0)
        rs (deliver-all rs (mapv #(assoc % :from leader) out) 1000 4000)]
    (reduce (fn [rs t]
              (let [{rs' :rs ob :ob}
                    (reduce (fn [acc w]
                              (let [[s' out] (r/on-tick (get rs w) t)]
                                (-> acc
                                    (update :rs assoc w s')
                                    (update :ob into (map #(assoc % :from w) out)))))
                            {:rs rs :ob []}
                            (sort (keys rs)))]
                (deliver-all rs' (vec ob) t 4000)))
            rs
            (range 2000 2800 100))))

(defn- votes-out
  "Vote messages in an outbox, as `[height block-hash]` pairs."
  [out]
  (for [{:keys [msg]} out
        :when (= :vote (:type msg))]
    [(:height msg) (:block-hash msg)]))

;; ── the bound has to refuse at least as much ────────────────────────────────

(deftest a-resumed-replica-does-not-vote-again-below-its-tip
  ;; The whole reason `resume` is dangerous. Re-deliver a proposal the replica
  ;; already voted on and watch what leaves.
  (let [rs (run)
        w :w2
        s (get rs w)
        h (r/height s)]
    (is (pos? h) "the fixture produced no chain, so this test proves nothing")
    (let [snap (r/snapshot s)
          s' (r/resume (opts w) snap)
          ;; every block this replica holds, offered back to it one at a time
          blocks (:chain (r/snapshot s))
          seconds (for [b blocks
                        :let [[_ out] (r/on-message s' {:type :proposal :block b} 9999)]
                        v (votes-out out)]
                    v)]
      (testing "no vote for a DIFFERENT block at a height already decided"
        (doseq [[vh vhash] seconds]
          (let [original (some (fn [b] (when (= vh (:inga.block/height b)) (hash-fn b)))
                               blocks)]
            (is (= original vhash)
                (str "resumed replica voted at height " vh
                     " for a block it had not adopted — that is equivocation"))))))))

(deftest resume-refuses-at-least-as-much-as-replay
  ;; `replay` is the safe baseline: it reconstructs `:voted` from the blocks.
  ;; `resume` may refuse MORE (fail-closed) but never less.
  ;;
  ;; **The baseline replays the WHOLE chain, not the snapshot's tail.** Handing
  ;; `replay` a tail is not a smaller version of the same thing: the first
  ;; block does not extend genesis, so `extend-chain` adopts none of them and
  ;; the replica sits at genesis with nothing voted. Comparing against that
  ;; measures nothing, and the first version of this test did exactly that and
  ;; reported the difference as a resume bug.
  (let [rs (run)
        w :w3
        s (get rs w)
        full (vec (:chain s))
        tail (:chain (r/snapshot s))
        resumed (r/resume (opts w) (r/snapshot s))
        replayed (r/replay (r/replica (opts w)) full)]
    (is (= (r/height replayed) (r/height s))
        "the baseline did not reconstruct the chain, so it is not a baseline")
    (doseq [b tail]
      (let [h (:inga.block/height b)
            [_ o1] (r/on-message replayed {:type :proposal :block b} 9999)
            [_ o2] (r/on-message resumed {:type :proposal :block b} 9999)]
        (is (<= (count (votes-out o2)) (count (votes-out o1)))
            (str "at height " h " resume emitted more votes than replay"))
        (testing "and any vote it does emit names the block it holds"
          (doseq [[vh vhash] (votes-out o2)]
            (is (= vh h))
            (is (= vhash (hash-fn b))
                "resume voted for a block other than the one proposed")))))))

;; ── and it still has to work ────────────────────────────────────────────────

(deftest a-resumed-replica-keeps-its-place-in-the-chain
  (let [rs (run)
        w :w4
        s (get rs w)
        s' (r/resume (opts w) (r/snapshot s))]
    (is (= (r/height s) (r/height s')))
    (is (= (hash-fn (r/tip s)) (hash-fn (r/tip s'))))
    (is (= (r/committed-height s) (r/committed-height s'))
        "a resumed replica that forgot what it committed would re-apply blocks")))

(deftest a-resumed-replica-still-commits-new-blocks
  ;; The trap in truncating `:chain`: `commits` used to count how many entries
  ;; of `three-chain-commits` were already in `:committed`, and over a
  ;; truncated chain that count is larger than the list — so nothing new is
  ;; ever committed and the replica stalls while looking healthy.
  (let [rs (run)
        rs' (into {} (for [[w s] rs] [w (r/resume (opts w) (r/snapshot s))]))
        before (into {} (for [[w s] rs'] [w (r/committed-height s)]))
        after-net (reduce (fn [rs t]
                            (let [{rs2 :rs ob :ob}
                                  (reduce (fn [acc w]
                                            (let [[s' out] (r/on-tick (get rs w) t)]
                                              (-> acc
                                                  (update :rs assoc w s')
                                                  (update :ob into (map #(assoc % :from w) out)))))
                                          {:rs rs :ob []}
                                          (sort (keys rs)))]
                              (deliver-all rs2 (vec ob) t 4000)))
                          rs'
                          (range 5000 6000 100))]
    (is (some (fn [[w s]] (> (r/committed-height s) (get before w)))
              after-net)
        "no resumed replica committed anything new — the chain stalled after resume")))

;; ── bounded, which is the entire point ──────────────────────────────────────

(deftest a-snapshot-does-not-grow-with-the-chain
  (let [rs (run)
        s (get rs :w1)
        snap (r/snapshot s)]
    (is (<= (count (:chain snap)) r/resume-tail)
        "the snapshot carried more blocks than the tail bound")
    (is (<= (count (:by-hash snap)) (inc r/resume-tail))
        "by-hash grew past the blocks the snapshot retains")
    (testing "and it is plain data — it has to survive storage"
      (is (nil? (:hash-fn snap)) "an injected fn cannot be serialised")
      (is (nil? (:machine snap)))
      (is (nil? (:sign-fn snap))))))

(deftest resume-rejects-a-snapshot-it-does-not-understand
  (let [s (get (run) :w1)]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (r/resume (opts :w1) (assoc (r/snapshot s) :inga.snapshot/version 999))))))
