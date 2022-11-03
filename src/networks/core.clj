(ns networks.core
  (:require
   [clj-commons.byte-streams :refer [to-byte-arrays convert]])
  (:import (java.net InetAddress DatagramPacket DatagramSocket))
  (:gen-class))

(set! *warn-on-reflection* true)


(defn loge [& e]
  (binding [*out* *err*]
    (apply println e)))


;; https://github.com/babashka/babashka/blob/3dfc15f5a40efaec07cba991892c1207a352fab4/test-resources/babashka/statsd.clj

(defn make-socket
  [port] (new DatagramSocket ^int port))

(defn send-data [^DatagramSocket socket ip port ^String data]
  (let [ipaddr (InetAddress/getByName ip)
        send-packet (DatagramPacket. (.getBytes data) (.length data) ipaddr ^int port)]
    (.send socket send-packet)))


(defn receive
  "Block until a UDP message is received on the given DatagramSocket, and
  return the payload message as a string."
  [^DatagramSocket socket]
  (let [size (.getReceiveBufferSize socket)
        buffer (byte-array size)
        packet (DatagramPacket. buffer size)]
    
    (.receive socket packet)
    
    {:port (.getPort ^java.net.InetSocketAddress (.getSocketAddress packet))
     :host (.getHostName ^java.net.InetSocketAddress (.getSocketAddress packet))
     :message
     (clojure.edn/read-string (String. (.getData packet)
                                       0 (.getLength packet)))}))

(defn receive-timeout [^DatagramSocket socket timeout]
  (.setSoTimeout socket 100)
  (try
    (receive socket)
    (catch java.net.SocketTimeoutException e
      (.setSoTimeout socket 0)
      :timeout)))




;; TODO, make last packet have a last:true flag
(def send-socket (atom nil))
(comment
  (send3700 "127.0.0.1" "56655"))

(defn log [thing]
  (spit "log.edn"
        (str (pr-str thing)
             "\n\n") :append true))

(defn send-msg [{:keys [socket info]} data]
  ;; @(s/put! socket (merge info {:message (pr-str data)}))
  (send-data socket (:ip info) (:port info) (pr-str data)))

(defn read-msg [{:keys [socket info]}]
  ;; (clojure.edn/read-string (String. (:message @(s/take! socket))))
  (:message (receive socket)))

(def sent (atom #{}))
(def ackd (atom #{}))

(def packets (atom nil))

(def ^:dynamic *window* 4)

;; TODO problem: the {:packets 12} packet is duplicated... which is messing stuff up.

(defn send3700 [recv_host recv_port]
  (reset! send-socket {:socket (new DatagramSocket 0 (InetAddress/getByName recv_host))
                       :info {:port (Integer/parseInt recv_port)
                              :host recv_host}})
  (def packets (reduce conj
                       (clojure.lang.PersistentQueue/EMPTY)
                       (map (fn [^"[B" a b] (assoc {}
                                                   :data (String. a)
                                                   :num b))
                            (to-byte-arrays (slurp *in*) {:chunk-size 1375})
                            (range))))
  
  (def allpackets (into #{} (map :num packets)))

  (send-msg @send-socket {:packets (count allpackets)
                          :num -1})
  
  
  (loop [packets packets
         sent #{}
         ackd #{}]
    
    (def ack (receive-timeout (:socket @send-socket) 10))

    (loge [sent ackd ack])
    
    (cond
      (not= ack :timeout)
      (do
        (let [ack (:message ack)]
          (loge (str "recvd: " ack))
          (recur packets sent (conj ackd (:ack ack)))))
      
      (= allpackets ackd)
      (loge "done transmitting")
      
      (and (> *window* (- (count sent) (count ackd))) (peek packets))
      (do
        (loge ["sending" (:num (peek packets))])
        (send-msg @send-socket (peek packets))
        (recur (pop packets) (conj sent (:num (peek packets))) ackd))

      true
      (recur packets sent ackd))))


(def recv-socket (atom nil))
(defn recv3700 []
  (reset! recv-socket {:socket (new DatagramSocket)})
  (loge (str "Bound to port " (.getLocalPort ^DatagramSocket (:socket @recv-socket))))
  (def numpackets (:packets (read-msg @recv-socket)))
  
  (loge (str "num packets " numpackets))
  
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
        
        (def msg (receive (:socket @recv-socket)))

        ;; skip the first package if it is a dup
        (if (= (:num (:message msg)) -1)
          (recur recvd-packets)
          (do
            
            
            (when-not (:info @recv-socket)
              (swap! recv-socket assoc :info (select-keys msg [:host :port])))
            
            (def recvd (:message msg))

            (log recvd)
            (loge ["received" (:num recvd)])
            
            (do
              (send-msg @recv-socket {:ack (:num recvd)})
              (recur (conj recvd-packets recvd)))))))))

(defn -main [recvorsend & relationships]
  (case recvorsend
    "send" (apply send3700 (filter (complement empty?) relationships))
    "recv" (apply recv3700 (filter (complement empty?) relationships))))
