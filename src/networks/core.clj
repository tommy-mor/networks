(ns networks.core
  (:require [aleph.udp :as udp]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [clojure.data.json :as json]
            [networks.table])
  (:gen-class))

(def message-log (atom []))

(defn read-message [msg]
  (let [recv (json/read-str (String. (:message msg))
                            :key-fn keyword)]
    (println "received-message" (pr-str recv) )
    (swap! message-log conj recv)
    (networks.table/process-message recv)))

(comment
  (close)
  (handshake))


(defn handshake []

  (reset! networks.table/neighbors (doall (for [{:keys [port ip] :as input}  @networks.table/neighbors]
                                            (let [socket @(udp/socket {:epoll? true})]
                                              @(s/put! socket {:host "localhost"
                                                               :port port
                                                               :message (json/write-str {:src (networks.table/oneify-ip ip)
                                                                                         :dst ip
                                                                                         :type "handshake"
                                                                                         :msg {}})})
                                              (s/consume read-message
                                                         socket)
                                              (assoc input :socket socket)))))
  (println "done handshake"))

;; TODO some requests have two responses
(defn -main [asn-str & relationships]
  (reset! networks.table/asn (Integer. asn-str))
  (reset! networks.table/neighbors (for [rel relationships
                                         :when (> (count rel) 0)]
                                     (let [[port ip purpose]
                                           (clojure.string/split rel #"-")]
                                       {:port (Integer. port) :ip ip :purpose purpose})))
  (prn @networks.table/neighbors)

  (handshake)
  (while true
    (Thread/sleep 100)))
