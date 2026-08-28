;; ONE replica, ONE process, real WebSockets to real hosts — not localhost.
;;
;; script/network.cljs proved consensus over real sockets, but all four
;; replicas live in one process on 127.0.0.1: that is a real transport with a
;; fake deployment topology, and "the sockets underneath are real and
;; sub-millisecond" is a namespace docstring's own admission that latency was
;; still simulated with a timer. This file is the same composition
;; (inga.replica + inga.net.ws + inga.net.server + inga.attest, nothing new)
;; run as an independent OS process so that ADR-2608281000's fleet-replica
;; verification is over an actual network of separate machines, not shared
;; memory.
;;
;; No byzantine validator, no forger — those are script/network.cljs's job,
;; already proven over sockets. This script exists to answer a narrower
;; question: do N independently-started processes, on N different hosts,
;; dialing each other over the tailnet, reach the SAME committed state root.
;;
;; Usage (one invocation per host):
;;
;;   WITNESS=w1 \
;;   WITNESSES=w1,w2,w3,w4 \
;;   PEERS='w1=0.0.0.0:19301,w2=100.x.x.x:19301,w3=100.y.y.y:19301,w4=100.z.z.z:19301' \
;;   PUBKEYS_FILE=/tmp/inga-pub.edn \
;;   PRIVKEY_B64=<this witness's base64 pkcs8 private key> \
;;   RUN_MS=20000 \
;;   OUT_FILE=/tmp/inga-w1-result.edn \
;;   nbb --classpath src script/network-node.cljs
;;
;; `PEERS` binds every witness (including self) to a host:port; this process
;; listens on the port named for its own witness (host part of its own entry
;; is ignored — it binds 0.0.0.0) and dials every other witness's host:port.
;; `PUBKEYS_FILE` is shared, public, identical on every host. `PRIVKEY_B64` is
;; local to this process only.
(ns network-node
  (:require ["ws" :as ws]
            ["node:crypto" :as nc]
            ["node:fs" :as fs]
            ["@noble/hashes/sha2.js" :refer [sha256]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [inga.attest :as att]
            [inga.consensus :as c]
            [inga.net.server :as srv]
            [inga.net.ws :as nws]
            [inga.replica :as r]
            [inga.wire :as wire]))

(defn env [k]
  (let [e (.-env js/process)]
    (when (exists? (aget e k)) (aget e k))))

(defn env-or [k d] (or (env k) d))

(def witness (or (env "WITNESS") (throw (ex-info "WITNESS is required" {}))))
(def witnesses (str/split (or (env "WITNESSES") (throw (ex-info "WITNESSES is required" {})))
                          #","))
(def chain-id (env-or "CHAIN_ID" "inga-fleet-verify-1"))
(def run-ms (js/parseInt (env-or "RUN_MS" "20000") 10))
(def tick-ms (js/parseInt (env-or "TICK_MS" "150") 10))
(def out-file (env "OUT_FILE"))

(def peer-map
  "witness -> {:host h :port p}, parsed from `w1=host:port,w2=host:port,...`"
  (into {}
        (for [pair (str/split (or (env "PEERS") (throw (ex-info "PEERS is required" {})))
                              #",")
              :let [[w hp] (str/split pair #"=")
                    [h p] (str/split hp #":")]]
          [w {:host h :port (js/parseInt p 10)}])))

(def my-port (:port (get peer-map witness)))

(def pubkeys
  "witness -> DER SPKI base64, shared and identical on every host."
  (:pub (edn/read-string (.toString (fs/readFileSync (env-or "PUBKEYS_FILE" "pubkeys.edn"))))))

(def pubkey-objs
  (into {} (for [[w b64] pubkeys]
             [w (nc/createPublicKey #js {:key (js/Buffer.from b64 "base64")
                                        :format "der" :type "spki"})])))

(def my-privkey
  (when-let [b64 (env "PRIVKEY_B64")]
    (nc/createPrivateKey #js {:key (js/Buffer.from b64 "base64")
                             :format "der" :type "pkcs8"})))

(defn sign-as-me [payload]
  (.toString (nc/sign nil (js/Buffer.from payload "utf8") my-privkey) "base64"))

(defn verify-fn [w payload sig]
  (if-let [pk (get pubkey-objs (wire/wire-id w))]
    (try (nc/verify nil (js/Buffer.from payload "utf8") pk
                    (js/Buffer.from sig "base64"))
         (catch :default _ false))
    false))

(defn- hex [^js bs]
  (apply str (map #(.padStart (.toString % 16) 2 "0") (array-seq bs))))

(defn hash-fn [b]
  (hex (sha256 (.encode (js/TextEncoder.) (c/canonical-block b)))))

(def machine
  "Same order-sensitive digest as script/network.cljs — deliberately no
  application semantics, so this proves ordering agreement and nothing about
  torihiki or engi."
  {:init-fn (fn [] {:height -1 :applied 0 :digest "genesis"})
   :apply-fn (fn [st b]
               {:height (:inga.block/height b)
                :applied (inc (:applied st))
                :digest (hex (sha256 (.encode (js/TextEncoder.)
                                              (str (:digest st) "|"
                                                   (:inga.block/height b) "|"
                                                   (c/canonical-block b)))))})
   :root-fn (fn [st] (str (:applied st) ":" (subs (:digest st) 0 16)))})

(defn vote-verifier [v]
  (verify-fn (:inga.vote/witness v)
             (att/vote-payload chain-id (:inga.vote/view v 0)
                               (:inga.vote/height v) (:inga.vote/block-hash v)
                               (:inga.vote/witness v))
             (:inga.vote/sig v)))

(def state (atom (r/replica {:witness witness
                             :witnesses witnesses
                             :quorum (c/quorum-size (count witnesses))
                             :hash-fn hash-fn
                             :chain-id chain-id
                             :verify-fn verify-fn
                             :machine machine})))
(def registry (atom {}))
(def sent (atom 0))
(def recv (atom 0))
(def out-node (atom nil))

(defn now [] (.getTime (js/Date.)))

(defn sign-out [outbox]
  ;; Same seam as script/network.cljs's `sign-out`: the replica emits an
  ;; unsigned vote, the transport signs it, the signed copy is folded back.
  (let [signed (mapv (fn [{:keys [msg] :as m}]
                       (if (= :vote (:type msg))
                         (assoc m :msg
                                (assoc msg :sig
                                       (sign-as-me
                                        (att/vote-payload
                                         chain-id (:view msg) (:height msg)
                                         (:block-hash msg) (wire/wire-id witness)))))
                         m))
                     outbox)]
    (doseq [{:keys [msg]} signed
            :when (and (:sig msg) (= :vote (:type msg)))]
      (let [[s' _] (r/on-message @state msg (now))]
        (reset! state s')))
    signed))

(declare ship-now!)
(defn ship! [outbox0]
  (let [batch (sign-out outbox0)]
    (ship-now! batch)))
(defn ship-now! [outbox]
  (doseq [{:keys [msg]} outbox]
    (swap! sent inc)
    (when-let [n @out-node] ((:broadcast! n) msg))
    (doseq [[_ s] @registry] (when (:send! s) ((:send! s) msg)))))

(defn feed! [msg]
  (swap! recv inc)
  (let [[s' out] (r/on-message @state msg (now))]
    (reset! state s')
    (ship! out)))

(def wss (ws/WebSocketServer. #js {:host "0.0.0.0" :port my-port}))
(def conn-counter (atom 0))
(.on wss "connection"
     (fn [sock]
       (let [peer (str "in-" (swap! conn-counter inc))
             handle (srv/attach! registry peer sock
                                 {:add-listener (fn [s ev f] (.on s ev f))
                                  :on-message (fn [_ m] (feed! m))})]
         (swap! registry update peer merge handle))))
(println (str "[" witness "] listening on 0.0.0.0:" my-port))

(defn dial! []
  (let [others (remove #{witness} witnesses)]
    (reset! out-node
            (nws/make-node
             {:peers (vec others)
              ;; The platform-global `js/WebSocket` differs by Node version —
              ;; measured across this fleet, a mixed Node 22/26 run crashed
              ;; one replica's client with a recursive internal error inside
              ;; undici's implementation on connect failure. The server side
              ;; here already uses the `ws` package; using its client too
              ;; keeps both directions on the one implementation this repo
              ;; ships and tests against, rather than trusting every runtime's
              ;; still-young built-in to agree.
              :ctor (.-WebSocket ws)
              :url-of (fn [p] (let [{:keys [host port]} (get peer-map p)]
                                (str "ws://" host ":" port)))
              :on-message (fn [_ m] (feed! m))}))
    ((:tick! @out-node))))

(defn tick! []
  (when-let [n @out-node] ((:tick! n)))
  (let [[s' out] (r/on-tick @state (now))]
    (reset! state s')
    (ship! out)))

(defn start! []
  (let [[s' out] (r/start @state (now))]
    (reset! state s')
    (ship! out)))

(defn report []
  (let [s @state
        committed-hashes (mapv hash-fn (:committed s))]
    {:witness witness
     :height (r/height s)
     :committed-height (r/committed-height s)
     :committed-count (count (:committed s))
     :committed-hashes committed-hashes
     :state-root (r/state-root s)
     :recv @recv
     :sent @sent
     :peers-in (count @registry)
     :peers-out (count ((:live @out-node)))
     :dropped-votes (:dropped-votes s)}))

(defn -main []
  (dial!)
  (js/setTimeout
   (fn []
     (start!)
     (let [iv (js/setInterval tick! tick-ms)]
       (js/setTimeout
        (fn []
          (js/clearInterval iv)
          (let [r (report)]
            (println (str "[" witness "] " (pr-str r)))
            (when out-file (fs/writeFileSync out-file (pr-str r)))
            (doseq [[_ s] @registry] (when (:close! s) ((:close! s))))
            (when-let [n @out-node] ((:close-all! n)))
            (.close wss)
            (js/setTimeout #(js/process.exit (if (pos? (:committed-height r)) 0 1)) 300)))
        run-ms)))
   1200))

(-main)
