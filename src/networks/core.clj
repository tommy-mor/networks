(ns networks.core
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [clojure.data.json :as json]
            [gloss.core :as gloss]
            [gloss.io :as io]
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

(def inputs
  (atom (list {:port 7833, :ip "1.2.3.2", :purpose "cust"}
              {:port 2374, :ip "192.168.0.2", :purpose "peer"}
              {:port 1293, :ip "67.32.9.2", :purpose "prov"})))

(def asn (atom 7))

;; TODO some requests have two responses
(defn -main [asn & relationships]
  (reset! asn (Integer. asn))
  (reset! inputs (for [rel relationships
                       :when (> (count rel) 0)]
                   (let [[port ip purpose]
                         (clojure.string/split rel #"-")]
                     {:port (Integer. port) :ip ip :purpose purpose})))
  (println "i am running :3"))
