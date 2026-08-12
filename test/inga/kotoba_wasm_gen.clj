(ns inga.kotoba-wasm-gen
  "Rebuild `kotoba/*.wasm` and their provenance records from `kotoba/*.kotoba`.

      clojure -M:test:gen

  Runs under :test because that is where the compiler lives and where it must
  stay — nothing under `src/` may load it.

  What it writes IS what `inga.kotoba-reproducibility-test` checks, so nothing
  here transforms the compiler's output: the bytes are written as emitted and
  the provenance record is the map the compiler returned. The two share
  `target` and `cores` from the test namespace for the same reason: if the
  generator and the gate disagreed about how to compile, the gate would be
  checking something other than what ships.

  Before this existed, regeneration was `kotoba compile kotoba/x.kotoba --target
  wasm32` from a CLI whose version was not written down anywhere in the repo —
  which is how a binary comes to be built by a compiler nobody can name. The
  pin in `deps.edn` is now that name."
  (:require [clojure.java.io :as io]
            [inga.kotoba-reproducibility-test :as gate])
  (:gen-class))

(defn regenerate!
  "Recompile `base` and write both artifacts. Returns the paths written."
  [base]
  (let [{:keys [bytes provenance]} (gate/compile-core base)
        wasm (io/file "kotoba" (str base ".wasm"))
        record (io/file "kotoba" (str base ".wasm.provenance.edn"))]
    (with-open [out (io/output-stream wasm)] (.write out ^bytes bytes))
    (spit record (pr-str provenance))
    [(.getPath wasm) (.getPath record)]))

(defn -main [& _]
  (run! println (mapcat regenerate! gate/cores))
  (shutdown-agents))
