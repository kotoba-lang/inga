(ns inga.kotoba-reproducibility-test
  "Compiling `kotoba/*.kotoba` today produces `kotoba/*.wasm` exactly — and the
  decision cores are NOT what runs, which is a measured choice this namespace
  also keeps from being reversed by accident.

  ## What this adds over `inga.kotoba-provenance-test`

  That namespace checks the compiler's records against the files beside them:
  the source hashes to `:source-sha256`, the binary hashes to the recorded
  output digest. It is worth having and it catches the ordinary accident
  (editing a `.kotoba` and shipping the old binary). But it says so itself:

      Not reproducibility. A provenance record proves the shipped bytes are the
      ones whose digest was recorded beside the source whose digest was
      recorded -- it does not prove that recompiling that source today produces
      those bytes, which needs the compiler and the commit that built it.

  Both halves of that are now here. The compiler is a test-only dependency
  pinned in `deps.edn`, and this namespace recompiles the sources and requires
  the bytes to be equal — so the `.kotoba` is the authority for the binary in
  the strong sense, checkable rather than recorded.

  Measured 2026-08-12 before writing it: at that pin both binaries reproduce
  byte-for-byte, and two compilations of the same source produce identical
  bytes. Neither was assumed.

  ## Why this is not the recompile gate ADR-2608120200 declined to write

  That ADR looked at `kotoba-lang/provider`, found 29 of 71 shipped modules no
  longer reproducing, and said writing a recompile gate there would 'go red
  every time the compiler pin moves' — theatre. The difference is who owns the
  pin. provider records `:builder :kotoba-compiler/v1`, a TOOL NAME with no
  version, and tracks whatever the fleet has. inga pins the compiler in its own
  `deps.edn`, so the gate only moves when inga moves it, and red means what it
  should: *the recorded builder is no longer the one that made these bytes.*

  That pin is the 'builder commit' the same ADR named as the missing half. One
  pin for the repo rather than a per-artifact annotation, because there are two
  artifacts and one compiler — a second place to write it down is a second
  place for it to be wrong.

  ## And the part that is a decision rather than a check

  `kotoba/fuel.kotoba` and `kotoba/quorum.kotoba` are the reference for the
  arithmetic and are NOT executed in production; the `.cljc` decides. That is
  unusual — the workspace pattern (ADR-2608112100) is to move the authority to
  the shipped artifact and leave the host the parts that are not decisions —
  and it was chosen against measurements rather than by default. README's
  'What the Kotoba cores actually run' has them.

  The two tests at the bottom keep the choice from being reversed silently. An
  interpreter arriving in the runtime dependency set, or a `src/` namespace
  requiring one, would make replicas' answers depend on a pin — and this is a
  consensus library, so that is the one class of dependency it cannot take."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as kir]))

(def target
  "The target the checked-in binaries are built for. Named once, here, and used
  by both this namespace and `inga.kotoba-wasm-gen` — if the gate and the
  generator disagreed about how to compile, the gate would be checking
  something other than what ships."
  :wasm32-kotoba-v1)

(def cores
  "The `.kotoba` under `kotoba/`, discovered rather than listed, so a core added
  without a binary fails here instead of being quietly untested."
  (->> (.listFiles (io/file "kotoba"))
       (filter #(str/ends-with? (.getName %) ".kotoba"))
       (map #(str/replace (.getName %) #"\.kotoba$" ""))
       sort
       vec))

(defn compile-core
  "Compile `kotoba/<base>.kotoba` with the pinned compiler."
  [base]
  (compiler/compile-source (slurp (io/file "kotoba" (str base ".kotoba"))) target {}))

(defn- shipped-bytes [base]
  (with-open [in (io/input-stream (io/file "kotoba" (str base ".wasm")))]
    (.readAllBytes in)))

(deftest there-is-at-least-one-core-to-check
  ;; `cores` is discovered; a glob that silently found nothing would make every
  ;; doseq below vacuous, which is the failure mode of a discovered fixture.
  (is (<= 2 (count cores)) (str "found cores: " (pr-str cores))))

(deftest compiling-the-source-today-produces-the-shipped-binary
  (doseq [base cores]
    (testing base
      (let [fresh (:bytes (compile-core base))]
        (is (some? fresh) (str base " compiled to no bytes"))
        (is (java.util.Arrays/equals ^bytes fresh ^bytes (shipped-bytes base))
            (str "kotoba/" base ".wasm is not what kotoba/" base ".kotoba compiles"
                 " to under the pinned compiler. Either the source changed without"
                 " regeneration (run `clojure -M:test:gen`), or the compiler pin in"
                 " deps.edn moved and the binaries were not rebuilt with it."))))))

(deftest the-compiler-emits-the-provenance-record-that-ships
  ;; The provenance files are compiler output, not prose, and nothing else
  ;; checked that. A hand-edited record would satisfy every digest test in
  ;; `inga.kotoba-provenance-test` — those hash the files the record points at,
  ;; and a forger editing the record would edit the digests too.
  (doseq [base cores]
    (testing base
      (let [on-disk (edn/read-string (slurp (io/file "kotoba" (str base ".wasm.provenance.edn"))))]
        (is (= on-disk (:provenance (compile-core base)))
            (str "kotoba/" base ".wasm.provenance.edn is not what the compiler"
                 " emits for this source — it was edited by hand or built by a"
                 " different compiler. Run `clojure -M:test:gen`."))))))

(deftest the-builder-commit-is-recorded-precisely
  ;; ADR-2608120200's missing half. A tag, a branch or an abbreviated sha names
  ;; something that can come to mean different bytes later, which is exactly
  ;; what a builder record exists to prevent.
  (let [sha (get-in (edn/read-string (slurp "deps.edn"))
                    [:aliases :test :extra-deps 'io.github.kotoba-lang/compiler :git/sha])]
    (is (string? sha) "deps.edn :test does not pin a compiler at all")
    (is (re-matches #"[0-9a-f]{40}" (str sha))
        (str "the compiler pin must be a full 40-character commit sha, got " (pr-str sha)))))

(deftest the-cores-stay-inside-the-native-word-typed-slice
  ;; README claims both modules are `i64 -> i64` over `+ - * quot` and
  ;; comparison, which is what `only-native-word-typed-features?` admits, and
  ;; therefore that they compile for the native AOT backends and not only
  ;; wasm32. Nothing checked the claim. An edit that leaves the slice is a
  ;; legitimate change to make deliberately and a bad one to make by accident.
  (doseq [base cores]
    (testing base
      (is (true? (kir/only-native-word-typed-features? (:hir (compile-core base))))
          (str base " left the native word-typed slice")))))

;; ── the decision, as something that fails rather than as prose ──────────────

(def runtime-deps
  "What may be on the runtime classpath of a consensus library.

  Not 'the dependencies we happen to have'. The rule is that nothing on which
  two replicas could disagree may be here, and both of these pass it: they are
  content-addressed data structures, so a replica on a different pin produces a
  visibly different CID rather than a quietly different answer."
  '#{io.github.kotoba-lang/kotobase-storage
     io.github.kotoba-lang/arrangement})

(deftest an-interpreter-does-not-arrive-in-the-runtime-dependency-set
  (testing "a KIR interpreter or a wasm host here would make a replica's answer
            a function of which pin it has — see README, 'What the Kotoba cores
            actually run'. Adding one is allowed; doing it without revisiting
            that argument is what this stops"
    (is (= runtime-deps (set (keys (:deps (edn/read-string (slurp "deps.edn"))))))
        "deps.edn's runtime :deps changed. If this is deliberate, change
         `runtime-deps` here in the same commit and say in the message what a
         replica now depends on.")))

(deftest nothing-under-src-loads-the-compiler-or-the-interpreter
  ;; The producer must not reach a consumer, and the interpreter must not become
  ;; one. Checked over the text of `src/` because that is the thing a reviewer
  ;; would otherwise have to check by reading — a require added in a namespace
  ;; nobody re-reads is precisely how a test-only dependency stops being one.
  (doseq [f (->> (file-seq (io/file "src"))
                 (filter #(.isFile ^java.io.File %))
                 (filter #(re-find #"\.clj[cs]?$" (.getName ^java.io.File %))))]
    (let [text (slurp f)]
      (doseq [forbidden ["kotoba.compiler" "kotoba.kir" "kototama."]]
        (is (not (str/includes? text forbidden))
            (str (.getPath ^java.io.File f) " references " forbidden
                 " — that moves a decision onto a pin every replica must share."))))))
