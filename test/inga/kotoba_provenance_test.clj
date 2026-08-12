(ns inga.kotoba-provenance-test
  "The provenance records next to the checked-in Wasm say what produced it.
  Nothing read them.

  `kotoba/*.wasm.provenance.edn` is emitted by the compiler and carries the
  digest of the source it compiled and of the bytes it produced. Both were
  accurate when this namespace was written -- and would have stayed accurate
  looking until the day someone edited a `.kotoba` without regenerating, at
  which point the record would describe a compilation that no longer matches
  anything in the tree.

  ## Why the existing suites do not cover this

  `inga.quorum-kotoba-test` and `inga.fuel-kotoba-test` instantiate the checked-in
  `.wasm` and compare it against the `.cljc` over a matrix, which is the parity
  that matters and is worth having. But they never read the `.kotoba`. Editing
  the source and shipping the old binary leaves them green: the binary they test
  still agrees with the cljc, and the source that is supposed to be the reference
  quietly is not.

  That is the same shape as `kotoba-lang/provider`, whose registry names a
  builder for 112 packages and cannot re-run it. inga is better off because the
  compiler already wrote the digests down; this only makes the writing count.

  ## What this does not claim

  Not reproducibility. A provenance record proves the shipped bytes are the ones
  whose digest was recorded beside the source whose digest was recorded -- it
  does not prove that recompiling that source today produces those bytes, which
  needs the compiler and the commit that built it. `:compiler` here is
  \"kotoba-compiler/1\", a tool name, not a commit."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.security MessageDigest)))

(def ^:private kotoba-dir (io/file "kotoba"))

(defn- bytes-of [^java.io.File f]
  (with-open [in (io/input-stream f)] (.readAllBytes in)))

(defn- sha256 [^bytes b]
  (->> (.digest (MessageDigest/getInstance "SHA-256") b)
       (map #(format "%02x" %))
       (apply str)))

(defn- records []
  (->> (.listFiles kotoba-dir)
       (filter #(str/ends-with? (.getName %) ".wasm.provenance.edn"))
       (sort-by #(.getName %))
       (map (fn [f]
              (let [base (str/replace (.getName f) #"\.wasm\.provenance\.edn$" "")]
                {:base base
                 :record (edn/read-string (slurp f))
                 :wasm (io/file kotoba-dir (str base ".wasm"))
                 :source (io/file kotoba-dir (str base ".kotoba"))})))))

(deftest every-wasm-has-a-provenance-record
  ;; The direction that catches a binary added without one, rather than a record
  ;; left behind by a binary that was removed.
  (let [wasms (->> (.listFiles kotoba-dir)
                   (filter #(str/ends-with? (.getName %) ".wasm"))
                   (map #(.getName %)))]
    (is (<= 2 (count wasms)) "wasm count only grows")
    (doseq [w wasms]
      (is (.exists (io/file kotoba-dir (str w ".provenance.edn")))
          (str w " ships with no provenance record")))))

(deftest the-recorded-source-digest-is-this-source
  (doseq [{:keys [base record source]} (records)]
    (is (.exists source) (str base ".kotoba is missing"))
    (when (.exists source)
      (is (= (:source-sha256 record) (sha256 (bytes-of source)))
          (str base ".kotoba does not hash to the :source-sha256 its provenance"
               " records — the checked-in wasm was built from a different source."
               " Recompile: kotoba compile kotoba/" base ".kotoba --target wasm32")))))

(deftest the-recorded-output-digest-and-size-are-this-binary
  (doseq [{:keys [base record wasm]} (records)]
    (is (.exists wasm) (str base ".wasm is missing"))
    (when (.exists wasm)
      (let [b (bytes-of wasm)
            primary (get-in record [:outputs :primary])]
        (is (= :wasm (:format primary)) (str base " primary output is not wasm"))
        (is (= (:sha256 primary) (sha256 b))
            (str base ".wasm does not hash to the digest its provenance records"))
        (is (= (:size primary) (alength b))
            (str base ".wasm is " (alength b) " bytes, recorded as "
                 (:size primary)))))))

(deftest the-records-say-what-they-are
  ;; A record whose format, builder or target drifted is not comparable with the
  ;; others, and the checks above would be comparing fields that no longer mean
  ;; the same thing.
  (doseq [{:keys [base record]} (records)]
    (is (= :kotoba.provenance/v1 (:format record)) (str base " :format"))
    (is (= :kotoba-compiler/v1 (:builder record)) (str base " :builder"))
    (is (= :wasm32-kotoba-v1 (:target record)) (str base " :target"))
    (is (= :kotoba.language/safe-v1 (:language record)) (str base " :language"))
    (doseq [k [:kir-sha256 :hir-sha256 :sha256]]
      (is (re-matches #"[0-9a-f]{64}" (str (get record k)))
          (str base " " k " is not a sha256")))))
