(ns inga.chain-test
  "ADR-2608038000 H1's claim, made true: what inga orders is the advance of
  agent chain heads.

  The ADR's own correction (2026-08-05) said this was design intent and not
  wiring — `:inga.block/proposals` was a vector of opaque ids and nothing
  turned a committed one into state. These tests are about the properties
  that make the bridge worth having rather than about the bridge existing: an
  agent cannot advance someone else's chain, cannot skip a link, cannot fork,
  and cannot be silently skipped when a replica lacks the body."
  (:require [clojure.test :refer [deftest is testing]]
            [ipld.core :as ipld]
            [inga.chain :as chain]
            [inga.state :as state]))

(defn- store [] (atom {}))
(def blind #?(:clj pr-str :cljs (fn [x] (js/Promise.resolve (pr-str x)))))
(def crypt #?(:clj identity :cljs (fn [b] (js/Promise.resolve b))))

(defn- entry-cid [n] (ipld/cid (ipld/encode {"entry" n})))

(defn- chain-machine
  "A machine whose blocks carry chain advances, resolved through `entries`."
  [blocks entries]
  (state/machine
   {:decode-block (chain/decode-block (fn [id] (get @entries id)))
    :put! (fn [cid bytes] (swap! blocks assoc cid bytes))
    :get-fn (fn [cid] (get @blocks cid))
    :blind-fn blind
    :encrypt-fn crypt
    :authority {}
    :height-fn :height}))

(defn- block [height & ids] {:height height :inga.block/proposals (vec ids)})

(defn- refusals-of [st height reason]
  (state/query st {:find '[?v]
                   :where [[(str "inga.refusal/block/" height)
                            (str "inga.refusal/" (name reason)) '?v]]}))

;; ── the chain moves forward ─────────────────────────────────────────────────

(deftest an-agent-brings-its-own-chain-into-existence-and-advances-it
  (let [entries (atom {"p0" {:author "alice" :seq 0 :prev nil :entry (entry-cid 0)}
                       "p1" {:author "alice" :seq 1 :prev (entry-cid 0) :entry (entry-cid 1)}})
        m (chain-machine (store) entries)
        st (reduce (:apply-fn m) ((:init-fn m)) [(block 1 "p0") (block 2 "p1")])]
    (is (= {:entry (entry-cid 1) :seq 1} (chain/head (state/actors st) "alice"))
        "the committed head is the actor record: :state is the entry, :nonce its sequence")
    (is (empty? (refusals-of st 1 :out-of-order)))))

(deftest the-head-is-read-from-committed-state-and-nowhere-else
  (testing "a caller building the next advance needs exactly this pair"
    (is (nil? (chain/head {} "alice")))
    (is (nil? (chain/head {"alice" {:nonce 0 :balance 5}} "alice"))
        "an actor with no chain has no head, rather than a head of nil")))

;; ── what the bridge refuses ────────────────────────────────────────────────

(deftest an-agent-cannot-advance-another-agents-chain
  (testing "self-write is not re-implemented here — advance-op puts the author
            in the position :authority checks, so the existing rule catches it"
    (testing "advance-op cannot express it: author fills both positions"
      (let [op (chain/advance-op {:author "alice" :seq 0 :prev nil :entry (entry-cid 0)})]
        (is (= (:address op) (:caller op) "alice"))))
    ;; What a malicious decode-block WOULD produce, applied directly.
    (let [blocks (store)
          m (state/machine {:decode-block :ops
                            :put! (fn [cid bytes] (swap! blocks assoc cid bytes))
                            :get-fn (fn [cid] (get @blocks cid))
                            :blind-fn blind :encrypt-fn crypt
                            :authority {} :height-fn :height})
          st ((:apply-fn m) ((:init-fn m))
              {:height 1 :ops [{:op :actor-advance :address "bob" :caller "alice"
                                :seq 0 :prev-entry nil :entry (entry-cid 0)}]})]
      (is (empty? (state/actors st)))
      (is (= #{[1]} (refusals-of st 1 :not-self))))))

(deftest a-fork-cannot-land
  (testing "two futures from one head: the second is refused and counted"
    (let [entries (atom {"p0" {:author "alice" :seq 0 :prev nil :entry (entry-cid 0)}
                         "p1" {:author "alice" :seq 1 :prev (entry-cid 0) :entry (entry-cid 1)}
                         "p1-fork" {:author "alice" :seq 1 :prev (entry-cid 0) :entry (entry-cid 9)}})
          m (chain-machine (store) entries)
          st (reduce (:apply-fn m) ((:init-fn m))
                     [(block 1 "p0") (block 2 "p1" "p1-fork")])]
      (is (= (entry-cid 1) (:entry (chain/head (state/actors st) "alice")))
          "the first advance in the committed order wins")
      (is (= #{[1]} (refusals-of st 2 :forked))
          "and the fork is counted, which is what a warrant would be built from"))))

(deftest a-skipped-link-is-refused
  (let [entries (atom {"p0" {:author "alice" :seq 0 :prev nil :entry (entry-cid 0)}
                       "p2" {:author "alice" :seq 2 :prev (entry-cid 0) :entry (entry-cid 2)}})
        m (chain-machine (store) entries)
        st (reduce (:apply-fn m) ((:init-fn m)) [(block 1 "p0") (block 2 "p2")])]
    (is (= 0 (:seq (chain/head (state/actors st) "alice"))))
    (is (= #{[1]} (refusals-of st 2 :out-of-order)))))

(deftest a-chain-cannot-start-anywhere-but-genesis
  (let [entries (atom {"p5" {:author "alice" :seq 5 :prev (entry-cid 4) :entry (entry-cid 5)}})
        m (chain-machine (store) entries)
        st ((:apply-fn m) ((:init-fn m)) (block 1 "p5"))]
    (is (empty? (state/actors st)))
    (is (= #{[1]} (refusals-of st 1 :out-of-order)))))

(deftest an-entry-that-is-not-a-cid-is-refused
  (testing "the actor tree's :state has to stay walkable"
    (let [entries (atom {"p0" {:author "alice" :seq 0 :prev nil :entry "not-a-cid"}})
          m (chain-machine (store) entries)
          st ((:apply-fn m) ((:init-fn m)) (block 1 "p0"))]
      (is (empty? (state/actors st)))
      (is (= #{[1]} (refusals-of st 1 :invalid-result))))))

;; ── the halt that is correct ───────────────────────────────────────────────

(deftest an-unresolvable-proposal-stops-the-replica
  (testing "applying nothing would produce a root that disagrees with every
            replica that COULD see the proposal, silently"
    (let [m (chain-machine (store) (atom {}))
          ;; The TYPE, not merely that something threw. Checking `thrown?`
          ;; alone passed while the resolver check was disabled, because a
          ;; nil advance reaches `inga.state`'s missing-address throw one
          ;; call later — a green test for the wrong reason, found by
          ;; mutating the thing it was supposed to be about.
          thrown (try ((:apply-fn m) ((:init-fn m)) (block 1 "nobody-has-this"))
                      nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                        (ex-data e)))]
      (is (= :inga.chain/unresolved-proposal (:type thrown)))
      (is (= "nobody-has-this" (:proposal thrown))
          "and it names WHICH proposal, so the replica knows what to sync"))))

(deftest one-predicate-guards-the-vote-path-and-the-apply-path
  (testing "so a proposal that would halt apply is one a correct replica never voted for"
    (is (chain/valid-advance? {:author "a" :seq 0 :prev nil :entry "bafy…"}))
    (is (chain/valid-advance? {:author "a" :seq 1 :prev "bafy…" :entry "bafy…"}))
    (is (not (chain/valid-advance? nil)))
    (is (not (chain/valid-advance? {:author "" :seq 0 :prev nil :entry "x"})))
    (is (not (chain/valid-advance? {:author "a" :seq -1 :prev nil :entry "x"})))
    (is (not (chain/valid-advance? {:author "a" :seq 0 :prev "parent" :entry "x"}))
        "genesis has no parent")
    (is (not (chain/valid-advance? {:author "a" :seq 1 :prev nil :entry "x"}))
        "and everything else has one")))

(deftest the-bridge-refuses-to-be-built-without-a-resolver
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
               (chain/decode-block nil))))

;; ── ordering is the whole product ──────────────────────────────────────────

(deftest two-agents-advance-independently-in-one-block
  (testing "the order is total, but the chains are per-agent — which is the
            point of ordering heads rather than transactions"
    (let [entries (atom {"a0" {:author "alice" :seq 0 :prev nil :entry (entry-cid 0)}
                         "b0" {:author "bob" :seq 0 :prev nil :entry (entry-cid 7)}})
          m (chain-machine (store) entries)
          st ((:apply-fn m) ((:init-fn m)) (block 1 "a0" "b0"))]
      (is (= (entry-cid 0) (:entry (chain/head (state/actors st) "alice"))))
      (is (= (entry-cid 7) (:entry (chain/head (state/actors st) "bob")))))))
