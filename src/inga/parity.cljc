(ns inga.parity
  "One scenario over the pure namespaces, run on both runtimes, printing one
  digest.

      clojure -M:parity
      nbb --classpath src -e \"(require '[inga.parity :as p]) (p/report)\"

  Both must print the same line.

  ## Why this exists

  A JVM suite is not evidence about ClojureScript, and ClojureScript is the
  runtime that matters here — kotobase deploys on Cloudflare Workers. engi
  wrote the same namespace for the same reason and its docstring names the
  incident: a JVM-side optimisation broke torihiki's cljs path completely
  while every test stayed green.

  ## What it covers, and the one thing it cannot

  Covered: `inga.head` (canonical bytes, certificate counting and
  distinctness), `inga.fuel` (metering and the exhaustion point),
  `inga.power` (the table as a function of an event sequence), and
  `inga.ref`'s CAS decision over an in-memory head store. All pure, all
  synchronous on both runtimes.

  NOT covered: `inga.state`. `arrangement/commit!` returns a CID on the JVM
  and a `js/Promise` on cljs, so there is no single synchronous digest to
  compare — the platform split is real and is documented in
  `inga.state`'s docstring rather than papered over here. Verifying the cljs
  path of `inga.state` needs arrangement's own cljs deps and is a separate
  piece of work, named in the README as open."
  (:require [inga.fuel :as fuel]
            [inga.head :as head]
            [inga.power :as power]
            [inga.ref :as iref]
            [kotobase.storage.core :as storage]))

(defn- sig-for [w r] (str w "|" (head/canonical-bytes r)))
(defn- verify-fn [bytes sig w] (= sig (str w "|" bytes)))

(defn- head-digest []
  (let [r (head/head-record {:ref-name "main" :seq 3 :cid "cid-3" :prev "cid-2" :height 9})
        ok (head/verify-cert r {:sigs (mapv (fn [w] {:witness w :sig (sig-for w r)})
                                            ["w1" "w2" "w3"])} 3 verify-fn)
        dup (head/verify-cert r {:sigs (repeat 5 {:witness "w1" :sig (sig-for "w1" r)})}
                              3 verify-fn)
        cross (head/verify-cert r {:sigs (mapv (fn [w]
                                                 {:witness w
                                                  :sig (sig-for w (assoc r "ref" "other"))})
                                               ["w1" "w2" "w3"])} 3 verify-fn)]
    (str "head:" (count (head/canonical-bytes r))
         "/" (count (:verified-signers ok))
         "/" (some? dup) "/" (some? cross))))

(defn- fuel-digest []
  (let [ops (mapv (fn [i] {:op :assert :i i}) (range 10))
        step (fn [s op] (conj s (:i op)))
        run (fn [budget cost]
              (fuel/apply-metered {:state [] :ops ops :budget budget
                                   :cost-fn (fuel/fixed-cost cost) :step step}))
        a (run 100 1) b (run 7 2) c (run 2 3)]
    (str "fuel:" (:applied a) "," (:exhausted-at a)
         "/" (:applied b) "," (:exhausted-at b) "," (:spent b)
         "/" (:applied c) "," (:exhausted-at c))))

(defn- power-digest []
  (let [t (power/apply-events
           power/empty-table 4
           [{:event :bond :witness "w1" :amount 200 :roles [:ordering]}
            {:event :bond :witness "w2" :amount 100 :roles [:ordering :storage]}
            {:event :bond :witness "w3" :amount 100 :roles [:recompute]}
            {:event :slash :witness "w1" :amount 50}])]
    (str "power:" (power/stake-for t :ordering 1)
         "/" (count (power/eligible t :ordering 1))
         "/" (count (power/eligible t :storage 1))
         "/" (power/quorum-met? t :ordering 1 ["w2"])
         "/" (:height t))))

(defn- ref-digest []
  (let [heads (atom {}) decided (atom {})
        propose! (fn [record]
                   (let [k [(get record "ref") (get record "seq")]
                         winner (get (swap! decided #(if (contains? % k) % (assoc % k record))) k)]
                     (if (= winner record)
                       {:certified? true
                        :cert {:sigs (mapv (fn [w] {:witness w :sig (sig-for w record)})
                                           ["w1" "w2" "w3"])}}
                       {:certified? false :current (get winner "cid")})))
        refs (iref/ref-store {:read-head! (fn [n] (get @heads n))
                              :write-head! (fn [n h] (swap! heads assoc n h))
                              :propose! propose! :verify-fn verify-fn :quorum 3})
        a (storage/-compare-and-set-ref! refs "main" nil "cid-1")
        b (storage/-compare-and-set-ref! refs "main" nil "cid-other")
        c (storage/-compare-and-set-ref! refs "main" "cid-1" "cid-2")]
    (str "ref:" (:published? a) "/" (:published? b) "," (:current b)
         "/" (:published? c) "," (:version c)
         "/" (:cid (storage/-read-ref refs "main")))))

(defn digest []
  (str (head-digest) " " (fuel-digest) " " (power-digest) " " (ref-digest)))

(defn report []
  (println (digest))
  (digest))

(defn -main [& _] (report))
