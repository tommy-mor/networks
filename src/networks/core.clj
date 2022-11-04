(ns networks.core
  (:require
   [clj-commons.byte-streams :refer [to-byte-arrays convert]]
   [clojure.term.colors :refer [blue on-red]])
  (:import (java.net InetAddress DatagramPacket DatagramSocket))
  (:gen-class))

(set! *warn-on-reflection* true)

(defn now []
  (inst-ms (java.time.Instant/now)))

(def socket (atom nil))


(defn loge [& e]
  (binding [*out* *err*]
    (apply println e)))

(def lastcall (atom (now)))

(defn loge-slow [& e]
  (when (< 100 (- (now) @lastcall))
    (apply loge e)
    (reset! lastcall (now))))


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
     (let [string (String. (.getData packet)
                           0 (.getLength packet))]
       (try
         (let [parsed (clojure.edn/read-string string)]
           (if (= (:hash parsed)
                  (hash (dissoc parsed :hash)))
             (dissoc parsed :hash)
             (do
               (loge ["corrupted hash!!"])
               :corrupted)))
         (catch java.lang.RuntimeException e
           
           (loge ["corrupted parse!!" string])
           :corrupted)))}))

(defn receive-timeout [^DatagramSocket socket timeout]
  (.setSoTimeout socket timeout)
  (try
    (receive socket)
    (catch java.net.SocketTimeoutException e
      (.setSoTimeout socket 0)
      :timeout)))




(defn log [thing]
  (spit "log.edn"
        (str (pr-str thing)
             "\n\n") :append true))

(defn send-msg [{:keys [socket info]} data]
  ;; @(s/put! socket (merge info {:message (pr-str data)}))
  (send-data socket (:ip info) (:port info) (pr-str (assoc data :hash (hash data)))))

(defn read-msg [{:keys [socket info]}]
  ;; (clojure.edn/read-string (String. (:message @(s/take! socket))))
  (:message (receive socket)))

(defn pending-packet [num->packet allpackets sent ackd]
  (num->packet (first (sort (clojure.set/difference allpackets sent ackd)))))

;; TODO group acks together.
(defn send3700 [recv_host recv_port]
  (reset! socket {:socket (new DatagramSocket 0 (InetAddress/getByName recv_host))
                       :info {:port (Integer/parseInt recv_port)
                              :host recv_host}})
  (def packets (let [packets (into 
                              {}
                              (map (fn [^"[B" a b] [b (assoc {}
                                                             :data (String. a)
                                                             :num b)])
                                   (to-byte-arrays (slurp *in*) {:chunk-size 1375})
                                   (range)))]
                 (assoc packets -1 {:packets (count packets) :num -1})))

  (def allpackets (into #{} (keys packets)))

  (loop [sent #{}
         ackd #{}
         packetnum->sendtime {}
         recentrtt (list)
         window-size 1]
    
    (def ack (receive-timeout (:socket @socket) 1))

    (comment (loge [window-size recentrtt sent ackd #_packetnum->sendtime]))
    #_(loge [recentrtt])
    
    (cond
      (= (:message ack) :corrupted)
      (recur sent ackd packetnum->sendtime recentrtt window-size)
      
      (not= ack :timeout)
      (do
        (let [ack (:message ack)]
          (loge ["ack" (:ack ack)])
          (recur sent
                 
                 (conj ackd (:ack ack))
                 
                 packetnum->sendtime
                 
                 (let [rtt (- (now) (packetnum->sendtime (:ack ack)))]
                   (case (count recentrtt)
                     4 (conj (butlast recentrtt) rtt)
                     (conj recentrtt rtt)))
                 
                 (* 2 window-size))))
      
      (= allpackets ackd)
      (loge "done transmitting")
      
      (and (> window-size (count (clojure.set/difference sent ackd)))
           (pending-packet packets allpackets sent ackd))
      
      (let [packet (pending-packet packets allpackets sent ackd)
            packetnum (:num packet)]
        (loge ["sending" packetnum])
        (send-msg @socket packet)
        (recur (conj sent packetnum)
               ackd
               (assoc packetnum->sendtime packetnum (now))
               recentrtt
               window-size))

      true
      (do
        (let [now (now)
              timeout (if (not-empty recentrtt)
                        (* 1 (/ (apply + recentrtt)
                                (count recentrtt)))
                        1000)
              outstanding-packets
              (->> packetnum->sendtime
                   (filter
                    (fn [[packetnum time]] (> (- now time) timeout)))
                   (map first)
                   set)
              
              outstanding-packets
              (clojure.set/difference outstanding-packets ackd)
              
              sent'
              (clojure.set/difference sent outstanding-packets)

              outstanding-packets-unpunished
              (clojure.set/intersection outstanding-packets sent)]
          
          (comment (when (not-empty outstanding-packets)
                     (loge [(on-red (blue "outstanding packets"))
                            (count outstanding-packets)])))
          (loge [(first recentrtt) "window size: " window-size])
          
          (recur sent'
                 ackd
                 packetnum->sendtime
                 recentrtt
                 (if (and (not-empty outstanding-packets-unpunished)
                          (> window-size 1)) 
                   (do
                     (loge ["cutting window size" (count outstanding-packets-unpunished)])
                     (max 1 (/ window-size (int
                                            (Math/pow 2 (count outstanding-packets-unpunished))))))
                   window-size)))))))


(defn recv3700 []
  (reset! socket {:socket (new DatagramSocket)})
  (loge (str "Bound to port " (.getLocalPort ^DatagramSocket (:socket @socket))))

  (loop [numpackets -1
         recvd-packets #{}
         printed false]
    (if (and (not printed)
             (= numpackets (count (map :num recvd-packets))))
      (do
        (loge "done:")
        (doseq [p (sort-by :num recvd-packets)]
          (print (:data p)))
        (flush)
        ;; this needs to still run, because if acks are dropped, we need to resend them so send
        ;; can finish
        (recur numpackets recvd-packets true))
      
      (do
        
        (def msg (receive (:socket @socket)))
        
        (when-not (:info @socket)
          (swap! socket assoc :info (select-keys msg [:host :port])))
        
        (def recvd (:message msg))

        #_(log [recvd msg])
        (loge ["received" (:num recvd)])
        
        (if (= (:num recvd) -1)
          (do
            (loge (str "num packets " (:packets recvd)))
            (send-msg @socket {:ack -1})
            (recur (:packets recvd) recvd-packets printed))
          (do
            
            
            (case recvd
              :corrupted
              (do
                (loge "received corrupted packet")
                (recur numpackets recvd-packets printed))
              (do
                
                (loge ["sending ack" (:num recvd)])
                (send-msg @socket {:ack (:num recvd)})
                (recur numpackets (conj recvd-packets recvd) printed)))))))))

(defn -main [recvorsend & relationships]
  (case recvorsend
    "send" (apply send3700 (filter (complement empty?) relationships))
    "recv" (apply recv3700 (filter (complement empty?) relationships))))
