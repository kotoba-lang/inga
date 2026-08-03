(ns inga.ref
  "A `kotobase.storage.core/IRefStore` whose compare-and-set is decided by a
  QUORUM instead of by the host.

  ## The one idea

  **A 2f+1 quorum certificate IS a conditional write.** Two writers starting
  from the same observed head both ask the quorum to certify sequence n+1;
  the quorum certifies at most one, so at most one publishes. No
  `UPDATE … WHERE sequence = ?`, no `onlyIf.etagMatches`, no `If-Match`.

  This matters because the host primitive is exactly what was not portable.
  Backblaze B2 has no conditional put on either API, IPNS publishes
  unconditionally, and a content-addressed or erasure-coded network has
  nothing to be conditional about at all. `kotobase.storage.signed-head`
  documented that honestly and declared `:single-writer-ref`, ending with:

      a correct deployment still puts one writer in front of it — a Durable
      Object, an actor, a lease.

  inga is that one writer, replaced by a quorum. Same record shape (see
  `inga.head`), profile raised to `:linearizable-ref`, and the object store
  underneath demoted to what it is good at: `#{:immutable-blocks
  :cid-addressed-read}`. Superproject ADR-2608038000 D6 / ADR-2608039000.

  ## What this namespace does NOT establish

  This is the ADAPTER. It contains no consensus. Safety — that two
  conflicting certificates at the same height can never both form — is a
  property of the quorum behind `propose!`, proved in that layer's own
  equivocation tests, not here. Running
  `kotobase.storage.contract/verify` against this store with a reference
  quorum checks that the ADAPTER does not silently succeed; it is not
  evidence about any particular quorum's agreement. Passing a conformance
  suite with a cooperative oracle is the easiest way to believe something
  false, so the claim is stated narrowly on purpose.

  Deployment labelling is unchanged by anything here: while every witness is
  under one operator this is crash fault tolerance, and ADR-2607110300's
  rule says to call it that.

  ## Seams

  All injected, no I/O and no crypto in this namespace:

    read-head!   (fn [ref-name] -> head-map-or-nil)   dumb read
    write-head!  (fn [ref-name head] -> ignored)      dumb UNCONDITIONAL write
    propose!     (fn [record] -> {:certified? bool :cert c :current cid})
    verify-fn    (fn [bytes sig witness] -> boolean)
    quorum       positive int — the threshold this validator set requires
    height-fn    (fn [] -> int-or-nil)  consensus height the proposal rides"
  (:require [inga.head :as head]
            [kotobase.storage.core :as storage]))

(defn- current-head [{:keys [read-head! quorum verify-fn]} ref-name]
  (head/verify-head (read-head! ref-name) quorum verify-fn))

(defrecord QuorumRefStore [read-head! write-head! propose! verify-fn quorum height-fn]
  storage/IRefStore
  (-read-ref [this ref-name]
    (when-let [h (current-head this ref-name)]
      {:cid (get h "cid")
       :version (get h "seq")}))

  (-compare-and-set-ref! [this ref-name expected next-cid]
    (let [current (current-head this ref-name)
          current-cid (get current "cid")]
      (if (not= expected current-cid)
        ;; Lost before we even asked. Reporting the observed head here (rather
        ;; than only `published? false`) is what lets a caller retry against
        ;; the right base instead of spinning on a stale one.
        {:published? false :current current-cid :version (get current "seq")}
        (let [record (head/next-head ref-name current next-cid
                                     (when height-fn (height-fn)))
              seq' (get record "seq")
              outcome (propose! record)]
          (if-not (:certified? outcome)
            ;; The quorum refused. That is the compare-and-set failing, and
            ;; it is the ONLY place this store rejects a concurrent writer --
            ;; the object store below never had an opinion.
            {:published? false
             :current (or (:current outcome) current-cid)
             :version (get current "seq")}
            (do
              (write-head! ref-name (assoc record "cert" (:cert outcome)))
              ;; The write was unconditional, so winning is not ASSUMED even
              ;; though the certificate already decided: a dumb store can drop
              ;; or reorder. Same read-back discipline as
              ;; `kotobase.storage.signed-head`, and the same conservative
              ;; bias -- an exact-match check can only produce a FALSE
              ;; NEGATIVE (we won but the world already moved on), and a
              ;; caller that retries on a false negative simply discovers the
              ;; newer head. Claiming a publish that is not readable is the
              ;; failure that has no safe recovery.
              (let [after (current-head this ref-name)]
                (if (and after
                         (= seq' (get after "seq"))
                         (= next-cid (get after "cid")))
                  {:published? true :current next-cid :version seq'}
                  {:published? false
                   :current (get after "cid")
                   :version (get after "seq")}))))))))

  storage/IBackendCapabilities
  (-capabilities [_]
    ;; `:linearizable-ref` is claimed because the precondition is EVALUATED
    ;; (by the quorum) rather than ignored -- which is the distinction
    ;; `kotobase.storage.core/ref-profiles` exists to force a backend to
    ;; state. Deliberately no block capabilities: this serves refs only and is
    ;; meant to be `storage/compose`d with an object store, so that the ref
    ;; profile of the composition comes from here and the blocks come from a
    ;; host that needs no conditional write.
    #{:conditional-ref :linearizable-ref}))

(defn ref-store
  "Build the ref store. Every seam is required except `height-fn`, which is
  nil for deployments whose quorum has no chain height to report."
  [{:keys [read-head! write-head! propose! verify-fn quorum height-fn]}]
  (doseq [[k v] {:read-head! read-head! :write-head! write-head!
                 :propose! propose! :verify-fn verify-fn}]
    (when-not (fn? v)
      (throw (ex-info "inga.ref: missing or non-function seam"
                      {:type :inga.ref/invalid-seam :seam k}))))
  (when-not (pos-int? quorum)
    (throw (ex-info "inga.ref: quorum must be a positive integer"
                    {:type :inga.ref/invalid-quorum :quorum quorum})))
  (->QuorumRefStore read-head! write-head! propose! verify-fn quorum height-fn))
