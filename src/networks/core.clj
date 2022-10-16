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

(defn close []
  (doseq [{:keys [socket]} @inputs]
    (when socket
      (s/close! socket))))

(assert (= "1.2.3.1" (oneify-ip "1.2.3.2")))
(assert (= "192.168.0.1" (oneify-ip "192.168.0.2")))

(def message-log (atom []))

(defn process-message [idx msg]
  (def msg msg)
  (let [recv (json/read-str (String. (:message msg))
                            :key-fn keyword)]
    (println idx "received-message" recv )
    (swap! message-log conj recv)
    (comment "TODO MAKE HANDLE FUNCTION HERE")))

(comment
  (close)
  (handshake))

(comment @(s/put! (-> @inputs first :socket)
                  {:host "localhost"
                   :port (-> @inputs second :port)
                   :message (json/write-str {:test 3})})
         @(s/put! (-> @inputs second :socket)
                  {:host "localhost"
                   :port (-> @inputs first :port)
                   :message (json/write-str {:test 3})})

         (String. (:message @(s/take! (-> @inputs second :socket)))))


(def asn (atom 7))

(defn handshake []

  (reset! inputs (for [{:keys [port] :as input}  @inputs]
                   (assoc input :socket @(udp/socket {:port port}))))
  (doall (map (fn [idx {:keys [socket]}]
                (s/consume (partial process-message idx)
                           socket))
              (range)
              @inputs))
  
  (prn @inputs))

(defn send-handshakes []
  (doall (for [{:keys [port ip socket]} @inputs]
           @(s/put! socket {:host "localhost"
                            :port port
                            :message (json/write-str {:src (oneify-ip ip)
                                                      :dst ip
                                                      :type "handshake"
                                                      :msg {}})}))))
(comment
  (send-handshakes))

;; TODO some requests have two responses
(defn -main [asn-str & relationships]
  (reset! asn (Integer. asn-str))
  (reset! inputs (for [rel relationships
                       :when (> (count rel) 0)]
                   (let [[port ip purpose]
                         (clojure.string/split rel #"-")]
                     {:port (Integer. port) :ip ip :purpose purpose})))

  (prn @inputs)

  (handshake)
  (send-handshakes))
