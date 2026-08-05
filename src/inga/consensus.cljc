(ns inga.consensus
  "ENGI/EN L1 — chained HotStuff-style BFT consensus over blocks of transfer
  proposals (ADR-2607993000). Pure, no I/O, no crypto, no wall-clock: like
  `engi.core`, signature verification is injected by the caller rather than
  performed here (the same seam `engi.core/fold-balance` already uses for
  its optional `:hash-fn` — this ns takes already-verified votes and a
  caller-supplied `hash-fn`). Runs identically under JVM `clojure -M:test`
  and cljs, and a whole n-witness validator set can be simulated as plain
  data with no real network, transport, or keys.

  What this ns OWNS: block/QC shape, quorum-size arithmetic (n=3f+1,
  quorum=2f+1), the chained 3-chain commit rule, round-robin leader
  rotation, and — the property this whole ADR is about — that two
  conflicting Quorum Certificates at the same height can never both form
  (see `consensus_test.cljc`'s Byzantine-equivocation scenario).

  What this ns does NOT own (deliberately, per ADR-2607993000 Decision #1):
  wire transport, vote/QC signing, or what a proposal MEANS — a block's
  `:inga.block/proposals` is a vector of opaque ids and this namespace never
  looks inside one.

  ## `engi.*` in these docstrings is another repo

  `engi.core` / `engi.crypto` / `engi.metrics` stayed in `kotoba-lang/engi`
  when the consensus was extracted. They are named here only as the precedent
  a seam follows; nothing in this repo requires them.

  ## A correction, because this docstring asserted three dependencies it
  ## never had

  It used to say transport was \"murakumo/overlay's QUIC, reused unchanged\",
  signing was \"kotoba-lang/witness-quorum's signer/attestation, reused
  unchanged\", and proposals were ENGI transfer CIDs from `engi.core`. All
  three were written while the design was a plan and none was corrected as it
  was built. In fact transport is `inga.net` over WebSockets, signing is
  `inga.attest` over WebCrypto, and `engi.core` does not exist in this repo.

  The witness-quorum claim in particular sent a reader looking for a
  duplication that is not there. `kotoba-lang/witness-quorum` solves a
  DIFFERENT problem — post-hoc cosigning of an already-written CID, a
  Certificate-Transparency shape, with a 3-layer validation membrane. This
  namespace signs votes and certificates BEFORE a commit, inside the protocol.
  The two overlap only at \"Ed25519\", and even there they differ on purpose:
  witness-quorum's cljs signer takes an npm dependency (`@noble/curves`),
  while `inga.attest.ed25519` uses WebCrypto and takes none, because this has
  to run in a Worker. Merging them would mean adding a dependency to the side
  that does not need one.")

;; ── quorum arithmetic ───────────────────────────────────────────────────────

(defn quorum-size
  "The smallest quorum that is SAFE for `n` witnesses: any two quorums
  intersect in more than f nodes, so at least one HONEST witness is in both.
  That intersection is the safety lemma chained HotStuff and PBFT rest on —
  it is what makes two conflicting certificates at one height impossible.

  f is derived from n, never passed separately, so a caller cannot supply an
  inconsistent (n, f) pair.

  ## Why this is not just 2f+1

  It was, and 2f+1 is right for the sizes this system was written against:
  on n = 3f+1 the two formulas are IDENTICAL, since
  ceil((3f+1 + f+1)/2) = ceil((4f+2)/2) = 2f+1. Every existing threshold is
  unchanged.

  But n is whatever the caller passes, and `qc` never required it to be
  3f+1. Off that grid 2f+1 quietly stops being safe while still looking like
  a supermajority:

      n=5  f=1   2f+1 = 3   {a b c} and {c d e} share ONE node
      n=6  f=1   2f+1 = 3   {a b c} and {d e f} share NONE

  Two disjoint quorums are two conflicting certificates at the same height —
  the exact outcome the docstring above claims cannot happen. So the rule is
  stated as the property it must have rather than as an arithmetic shortcut
  that happens to have it on a subset of inputs.

      n=5 -> 4    n=6 -> 4    n=4 -> 3    n=7 -> 5    n=10 -> 7"
  [n]
  (let [f (quot (dec n) 3)]
    (quot (+ n f 2) 2)))

(defn byzantine-tolerance
  "f for a given n (n=3f+1). Convenience inverse of quorum-size's derivation,
  for tests/callers that want to state \"how many faulty witnesses can this
  validator-set size tolerate\" directly."
  [n]
  (quot (dec n) 3))

;; ── block / vote / QC shape ──────────────────────────────────────────────────

(defn canonical-block
  "Deterministic string serialization of a block, for hashing/signing — same
  style as `engi.core/canonical-entry` (plain string concatenation, no
  JSON/EDN printer dependency, byte-identical across JVM and cljs)."
  [{:keys [inga.block/height inga.block/parent-hash inga.block/proposals
           inga.block/proposer inga.block/ts]}]
  (str "engi/block\n"
       "height=" height "\n"
       "parent-hash=" parent-hash "\n"
       "proposals=" (apply str (interpose "," proposals)) "\n"
       "proposer=" proposer "\n"
       "ts=" ts "\n"))

(defn make-block
  "Build a block. `justify` is the QC (see `qc`) certifying this block's
  immediate parent — nil only for the genesis block. `proposals` is a vector
  of TransferBody CIDs (engi.core/ADR-2607101100's existing proposal shape,
  unchanged here)."
  [{:keys [height parent-hash proposals proposer ts justify]}]
  {:inga.block/height height
   :inga.block/parent-hash (or parent-hash "genesis")
   :inga.block/proposals (vec proposals)
   :inga.block/proposer proposer
   :inga.block/ts ts
   :inga.block/justify justify})

(defn make-vote
  "A witness's vote for a specific block. Unsigned here — signing is
  `inga.attest`'s job (this docstring used to name `witness-quorum`, which
  this repo has never depended on; see the ns docstring); a
  real caller attaches `:inga.vote/sig` after this and `qc` never inspects
  signatures itself (verification already happened before votes reach this
  ns, same division of labor as `fold-balance`'s injected `:hash-fn`)."
  [witness block-hash height]
  {:inga.vote/witness witness
   :inga.vote/block-hash block-hash
   :inga.vote/height height})

(defn qc
  "Given `votes` (already signature-verified by the caller) all claiming the
  SAME block-hash/height, and the validator-set size `n`, return a Quorum
  Certificate if the number of DISTINCT witnesses reaches `quorum-size`,
  else nil. Distinct-BY-WITNESS is what matters — a Byzantine witness
  resubmitting (or being credited with) the same vote twice must not count
  twice toward quorum; this is the concrete place equivocation gets
  neutralized. Throws if the votes don't actually agree on block-hash/height
  (a caller bug, not a Byzantine-tolerance case — routing votes for
  different blocks into one `qc` call is a programming error)."
  ([votes n] (qc votes n nil))
  ([votes n view]
   (when (seq votes)
    (let [{:keys [inga.vote/block-hash inga.vote/height]} (first votes)]
      (when-not (every? #(and (= block-hash (:inga.vote/block-hash %))
                               (= height (:inga.vote/height %)))
                         votes)
        (throw (ex-info "qc: all votes must target the same block-hash/height"
                         {:votes votes})))
      (let [distinct-witnesses (set (map :inga.vote/witness votes))]
        (when (>= (count distinct-witnesses) (quorum-size n))
          (cond-> {:inga.qc/block-hash block-hash
                   :inga.qc/height height
                   :inga.qc/witnesses distinct-witnesses
                   :inga.qc/vote-count (count distinct-witnesses)}
            ;; The view a certificate was formed in. `inga.pacemaker` orders
            ;; QCs by it and locks on the later one, so a certificate without
            ;; it can never become a lock — which is exactly what happened
            ;; while this arity did not exist: every hand-built QC in the
            ;; pacemaker tests carried a view, every QC this constructor
            ;; produced did not, and the lock silently never engaged.
            view (assoc :inga.qc/view view))))))))

;; ── chained 3-chain commit rule ───────────────────────────────────────────────

(defn direct-extends?
  "`child` directly extends `parent`: child's :inga.block/parent-hash AND its
  :inga.block/justify QC both point at parent (hash AND height agree). A
  block that merely NAMES a parent hash without a QC actually certifying
  that parent is not a safe direct extension — this double-check (link +
  justify) is what stops a Byzantine proposer from splicing an uncertified
  block into the chain."
  [hash-fn parent child]
  (let [parent-hash (hash-fn parent)
        justify (:inga.block/justify child)]
    (boolean
     (and (= parent-hash (:inga.block/parent-hash child))
          justify
          (= parent-hash (:inga.qc/block-hash justify))
          (= (:inga.block/height parent) (:inga.qc/height justify))))))

(defn- first-index-above
  "Index of the first block in `chain` above `h`. Binary search, because the
  point of the caller is to stop touching the whole chain."
  [chain h]
  (loop [lo 0 hi (count chain)]
    (if (< lo hi)
      (let [mid (quot (+ lo hi) 2)]
        (if (<= (:inga.block/height (nth chain mid)) h)
          (recur (inc mid) hi)
          (recur lo mid)))
      lo)))

(defn three-chain-commits
  "Given `chain` (a vector of blocks in strictly increasing height order,
  each carrying a :inga.block/justify QC for its immediate predecessor —
  the genesis block is the only one that may have justify=nil), return the
  vector of blocks SAFELY COMMITTED under chained HotStuff's 3-chain rule:
  block B commits once B <- B' <- B'' are three CONSECUTIVE direct
  extensions. This is what turns a bare sequence of proposed blocks into
  finalized ones — everything before the last-committed block is authoritative
  for `en.core/finalized-balance` (ADR-2607993000 Decision #4); anything at
  or after the tip is still tentative.

  ## `above-height` is not an optimisation of the answer, it is one of the work

  A caller that already committed up to height `h` throws away every window
  below it. Computing them anyway costs two `hash-fn` calls each, and
  `hash-fn` is the expensive thing here — so a replica adopting its Nth block
  hashes the whole chain again, and adopting N blocks costs N² hashes.

  That is not a slow path, it is a wall. Measured on a deployed validator
  catching up over 1067 blocks: it never finished. The Durable Object hit its
  CPU limit mid-replay, was reset, started the replay from the beginning, and
  did that indefinitely — and because that validator was the leader, the chain
  it was trying to rejoin could not advance without it. **The replica that
  most needs to catch up is the one least able to.**

  Passing the committed height skips exactly the windows the caller would
  discard, so the answer is unchanged by construction; only the work is."
  ([hash-fn chain] (three-chain-commits hash-fn chain nil))
  ([hash-fn chain above-height]
   (let [n (count chain)
         start (if (nil? above-height) 0 (first-index-above chain above-height))]
     (vec
      (keep (fn [i]
              (let [b0 (nth chain i) b1 (nth chain (inc i)) b2 (nth chain (+ i 2))]
                (when (and (direct-extends? hash-fn b0 b1)
                           (direct-extends? hash-fn b1 b2))
                  b0)))
            (range start (max 0 (- n 2))))))))

;; ── leader rotation ──────────────────────────────────────────────────────────

(defn leader-for
  "v1 leader election: plain round-robin over `witnesses` (a vector of
  witness ids) keyed by height. Deliberately simple and PREDICTABLE — an
  adaptive adversary that knows the schedule could target the upcoming
  leader. ADR-2607993000 names this an intentional v1 simplification;
  hardening to VRF-based unpredictable election is deferred until a real
  adversarial (non-single-operator) validator set exists."
  [witnesses height]
  (nth witnesses (mod height (count witnesses))))
