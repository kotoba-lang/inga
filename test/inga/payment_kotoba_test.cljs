(ns inga.payment-kotoba-test
  (:require [cljs.test :refer [async deftest is]]
            [goog.object :as gobj]
            [inga.payment :as payment]))

(def ^:private fs (js/require "node:fs"))
(def ^:private wasm-bytes (delay (.readFileSync fs "kotoba/payment.wasm")))

(defn- instantiate []
  (-> (js/WebAssembly.instantiate @wasm-bytes #js {})
      (.then #(.-exports (.-instance %)))))

(deftest kotoba-and-cljc-agree-on-payment-reservations
  (async done
    (let [cases [[10000 0 10000]
                 [10000 1 10000]
                 [100000 90000 10000]
                 [100000 90000 10001]
                 [0 0 1]
                 [10 11 1]
                 [-1 0 1]
                 [10 -1 1]
                 [10 0 0]]]
      (-> (instantiate)
          (.then
           (fn [e]
             (doseq [[paid consumed price] cases]
               (let [verdict (payment/reserve-verdict
                              {:paid-micros paid :consumed-micros consumed
                               :price-micros price})
                     allowed (js/Number (.allowed e (js/BigInt paid)
                                                  (js/BigInt consumed)
                                                  (js/BigInt price)))
                     next-consumed (js/Number
                                    (.call (gobj/get e "next-consumed") e
                                           (js/BigInt paid)
                                           (js/BigInt consumed)
                                           (js/BigInt price)))]
                 (is (= (if (:allow? verdict) 1 0) allowed)
                     (str "allowed " [paid consumed price]))
                 (is (= (if (:allow? verdict) (:consumed-micros verdict) consumed)
                        next-consumed)
                     (str "next-consumed " [paid consumed price]))))
             (done)))
          (.catch (fn [e] (is false (str e)) (done)))))))
