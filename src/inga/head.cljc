(ns inga.head
  "The head record a quorum certifies — `kotobase.head/v1` with the issuer
  signature replaced by a quorum certificate.

  ## Why the record shape barely changes

  `kotobase.storage.signed-head` already made the head a SIGNED, SEQUENCED
  record stored as an ordinary immutable block, and its docstring is precise
  about the one thing that buys and the one thing it does not:

      What that buys: fork DETECTION, untrusted hosting, and the loser
      learning it lost.
      What it does NOT buy: AGREEMENT. A signature proves who issued a head,
      not that no one else issued another. So the declared profile is
      `:single-writer-ref`, and a correct deployment still puts one writer in
      front of it — a Durable Object, an actor, a lease.

  inga replaces that one writer with a quorum. Everything else about the
  record was already right, so `head-record` here is `kotobase.head/v1`'s
  field-for-field with `issuer`/`sig` widened to `cert`. A reader that walks
  `prev` and checks `seq` monotonicity does not have to learn a second
  format.

  ## What a certificate has to prove, and what it does not

  A certificate proves that `quorum` DISTINCT witnesses of a known validator
  set signed exactly this record at exactly this sequence. That is the whole
  claim. It does NOT prove the witnesses were honest, that the validator set
  is well chosen, or that the operator running them is plural — those are
  deployment properties, and ADR-2607110300's labelling rule applies: while
  every witness is under one operator this is crash fault tolerance, not
  Byzantine fault tolerance, and it is named that way.

  Verification here is deliberately dumb and total: shape, version, sequence,
  distinctness, threshold, then every signature. It rejects rather than
  throws, because an untrusted host is expected to be able to serve rubbish
  and the caller's job is to not believe it — the same stance
  `kotobase.storage.signed-head/verify-chain` takes.

  No I/O, no crypto, no clock. `verify-fn` is injected exactly the way
  `signed-head` injects it and `engi.consensus` injects `hash-fn`."
  (:require [clojure.string :as str]))

(def head-version "inga.head/v1")

(defn head-record
  "The canonical map the witnesses sign and a verifier checks. One function
  so the two sides cannot drift — the same reason `signed-head` keeps it in
  one place."
  [{:keys [ref-name seq cid prev height]}]
  {"v" head-version
   "ref" ref-name
   "seq" seq
   "cid" cid
   "prev" prev
   "height" height})

(defn canonical-bytes
  "Deterministic string serialization of a head record, for signing and
  verifying. Plain concatenation with no EDN/JSON printer in the path, so it
  is byte-identical across JVM and cljs — the same construction
  `engi.consensus/canonical-block` uses, and for the same reason.

  `nil` renders as the empty string, which is unambiguous here because every
  field is either a fixed-width integer or a CID string, and a CID is never
  empty. Genesis is `prev=`, not `prev=nil`."
  [record]
  (str "inga/head\n"
       "v=" (get record "v") "\n"
       "ref=" (get record "ref") "\n"
       "seq=" (get record "seq") "\n"
       "cid=" (get record "cid") "\n"
       "prev=" (or (get record "prev") "") "\n"
       "height=" (get record "height") "\n"))

(defn- distinct-signers
  "Signers that appear once each. A witness resubmitting (or being credited
  with) the same signature twice must not count twice toward the threshold —
  the concrete place equivocation gets neutralised, mirroring
  `engi.consensus/qc`'s distinct-BY-WITNESS counting."
  [sigs]
  (into {} (map (juxt :witness :sig)) sigs))

(defn verify-cert
  "Verified certificate, or nil.

  `verify-fn` is `(fn [bytes sig witness] -> boolean)`. `quorum` is the
  threshold this validator set requires; the caller derives it (for a BFT set
  that is `engi.consensus/quorum-size`, 2f+1 of n=3f+1) rather than this ns
  guessing, because a wrong threshold is a silent safety failure and the
  arithmetic belongs with the set that knows n."
  [record cert quorum verify-fn]
  (when (and (map? cert) (pos-int? quorum))
    (let [signers (distinct-signers (:sigs cert))]
      (when (>= (count signers) quorum)
        (let [bytes (canonical-bytes record)
              good (into {} (filter (fn [[witness sig]] (verify-fn bytes sig witness)))
                         signers)]
          (when (>= (count good) quorum)
            (assoc cert :verified-signers (set (keys good)))))))))

(defn verify-head
  "A head map from storage, verified, or nil.

  A head that fails verification is treated as ABSENT rather than as an
  error. That is not leniency: the block plane is content-addressed and safe
  on any host, so the only thing an untrusted or broken ref host can do is
  serve a record that does not verify, and the correct reading of that is
  `there is no head here` — which a caller already knows how to handle."
  [head quorum verify-fn]
  (when (and (map? head)
             (= head-version (get head "v"))
             (integer? (get head "seq"))
             (not (str/blank? (str (get head "ref"))))
             (string? (get head "cid")))
    (let [record (head-record {:ref-name (get head "ref")
                               :seq (get head "seq")
                               :cid (get head "cid")
                               :prev (get head "prev")
                               :height (get head "height")})]
      (when (verify-cert record (get head "cert") quorum verify-fn)
        head))))

(defn next-head
  "The record a writer asks the quorum to certify, given the verified current
  head (or nil at genesis). `seq` is derived, never passed — a caller that
  could choose its own sequence could rewind the chain."
  [ref-name current next-cid height]
  (head-record {:ref-name ref-name
                :seq (inc (or (get current "seq") -1))
                :cid next-cid
                :prev (get current "cid")
                :height height}))
