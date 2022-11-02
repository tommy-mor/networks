(ns networks.core
  (:require [aleph.udp :as udp]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [clj-commons.byte-streams :refer [to-byte-arrays convert]])
  (:gen-class))

;; TODO, make last packet have a last:true flag
(def send-socket (atom nil))
(comment
  (send3700 "127.0.0.1" "56655"))

(defn log [thing]
  (spit "log.edn"
        (str (pr-str thing)
             "\n\n") :append true))

(defn loge [& e]
  (binding [*out* *err*]
    (apply println e)))

(defn send-msg [{:keys [socket info]} data]
  @(s/put! socket (merge info {:message (pr-str data)})))

(defn read-msg [{:keys [socket info]}]
  (clojure.edn/read-string (String. (:message @(s/take! socket)))))

(def sent (atom #{}))
(def ackd (atom #{}))

(def packets (atom nil))

(defn send3700 [recv_host recv_port]
  (reset! send-socket {:socket @(udp/socket {:port 0
                                             :host recv_host})
                       :info {:port (Integer/parseInt recv_port)
                              :host recv_host}})
  
  (def packets (reduce conj
                       (clojure.lang.PersistentQueue/EMPTY)
                       (map #(assoc {}
                                    :data (String. %1)
                                    :num %2)
                            (to-byte-arrays (slurp *in*) {:chunk-size 1375})
                            (range))))
  
  (def allpackets (into #{} (map :num packets)))

  (send-msg @send-socket {:packets (count allpackets)})
  
  
  (loop [packets packets
         sent #{}
         ackd #{}]
    (def ack @(s/try-take! (:socket @send-socket) :default 10 :timeout))
    
    (cond
      (not= ack :timeout)
      (do
        (let [ack (clojure.edn/read-string (String. (:message ack)))]
          (loge (str "recvd: " ack))
          (recur packets sent (conj ackd (:ack ack)))))
      
      (= allpackets ackd)
      (loge "done transmitting")
      
      (> 2 (- (count sent) (count ackd)))
      (do
        (loge "sending")
        (send-msg @send-socket (peek packets))
        (recur (pop packets) (conj sent (:num (peek packets))) ackd))

      true
      (recur packets sent ackd))))


(def recv-socket (atom nil))
(defn recv3700 []
  (reset! recv-socket {:socket @(udp/socket {:host "0.0.0.0"})})
  (loge (str "Bound to port " (Integer/parseInt (last
                                                 (clojure.string/split
                                                  (-> (.description (:socket @recv-socket))
                                                      :sink
                                                      :connection
                                                      :local-address) #"\:")))))
  
  (def numpackets (:packets (read-msg @recv-socket)))
  (loge (str "num packets" numpackets))
  
  (loop [recvd-packets #{}]
    (if (= numpackets (count (map :num recvd-packets)))
      (do
        (loge "done:")
        (doseq [p (sort-by :num recvd-packets)]
          (print (:data p)))
        (flush)
        (while true
          (loge "waiting for exit")
          (Thread/sleep 1000)))
      
      (do
        
        (def msg @(s/take! (:socket @recv-socket)))
        
        (when-not (:info @recv-socket)
          (swap! recv-socket assoc :info {:port (.getPort (:sender msg))
                                          :host (.getHostName (:sender msg))}))
        
        (def recvd (clojure.edn/read-string (String. (:message msg))))
        (loge ["received" (:num recvd)])
        
        (do
          (send-msg @recv-socket {:ack (:num recvd)})
          (recur (conj recvd-packets recvd)))))))

(defn -main [recvorsend & relationships]
  (case recvorsend
    "send" (apply send3700 (filter (complement empty?) relationships))
    "recv" (apply recv3700 (filter (complement empty?) relationships))))
