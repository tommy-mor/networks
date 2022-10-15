(ns networks.core
  (:require [aleph.udp :as udp]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [clojure.data.json :as json])
  (:gen-class))

(def inputs
  (atom (list {:port 59484, :ip "192.168.0.2", :purpose "cust"}
              {:port 51646, :ip "172.168.0.2", :purpose "cust"})))

(defn oneify-ip [ip]
  (clojure.string/join "." (let [d (clojure.string/split ip #"\.")]
                             (conj (vec (butlast d)) (str (dec (Integer. (last d))))))))

(assert (= "1.2.3.1" (oneify-ip "1.2.3.2")))
(assert (= "192.168.0.1" (oneify-ip "192.168.0.2")))

(s/consume #(println "received!" (String. (:message %)))
           (-> @inputs second :socket))

(comment @(s/put! (-> @inputs first :socket)
                 {:host "localhost"
                  :port (-> @inputs second :port)
                  :message "hello"})

         (String. (:message @(s/take! (-> @inputs second :socket)))))


(def asn (atom 7))

(defn handshake []

  (reset! inputs (for [{:keys [port] :as input}  @inputs]
                   (assoc input :socket @(udp/socket {:port port}))))
  (prn @inputs))

;; TODO some requests have two responses
(defn -main [asn-str & relationships]
  (reset! asn (Integer. asn-str))
  (reset! inputs (for [rel relationships
                       :when (> (count rel) 0)]
                   (let [[port ip purpose]
                         (clojure.string/split rel #"-")]
                     {:port (Integer. port) :ip ip :purpose purpose})))

  (prn @inputs)

  (handshake))
