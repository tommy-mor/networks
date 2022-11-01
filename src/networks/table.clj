(ns networks.table
  (:require [manifold.stream :as s]
            [clojure.data.json :as json]
            [clojure.core.match :refer [match]]))

(defn log [thing]
  (spit "log.edn"
        (str (pr-str thing)
             "\n\n") :append true))




