#!/usr/bin/env nbb
;; nbb port of gen-shadow-cljs-edn.bb (ADR-2607173000), from kotoba-lang/arrangement.
;;
;; Output is out/test.cjs, not .js: this package is "type": "module" (the nbb
;; scripts need ESM) and shadow-cljs emits CommonJS, so a .js extension makes
;; node treat the bundle as an ES module and die on __dirname.
(require '[clojure.string :as str])
(def fs (js/require "node:fs"))
(def cp (js/require "node:child_process"))
(defn sh [& args]
  (let [r (.spawnSync cp (first args) (to-array (rest args)) #js {:encoding "utf8"})]
    {:exit (or (.-status r) 1) :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))
(let [r (sh "clojure" "-Spath")
      cp-str (str/trim (or (:out r) ""))
      dirs (->> (str/split cp-str #":")
                (remove str/blank?)
                (filter (fn [p] (try (.isDirectory (.statSync fs p)) (catch :default _ false)))))]
  (.writeFileSync fs "shadow-cljs.edn"
    (str "{:source-paths " (pr-str (vec (concat ["test"] dirs))) "\n"
         " :builds\n"
         " {:test {:target :node-test\n"
         "         :output-to \"out/test.cjs\"\n"
         "         :ns-regexp \"-test$\"}}}\n"))
  (println "wrote shadow-cljs.edn with" (count dirs) "source dirs from clojure -Spath"))
