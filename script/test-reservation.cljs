(require '[clojure.test :as t] '[inga.reservation-test])
(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (when (pos? (+ (:fail m) (:error m))) (js/process.exit 1)))
(t/run-tests 'inga.reservation-test)
