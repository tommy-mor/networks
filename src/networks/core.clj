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


;; https://github.com/babashka/babashka/blob/3dfc15f5a40efaec07cba991892c1207a352fab4/test-resources/babashka/statsd.clj
(def ^:dynamic *window* 4)

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

(defn send-msg [data]
  (let [{:keys [socket info]} @socket]
    (send-data socket (:ip info) (:port info) (pr-str (assoc data :hash (hash data))))))

(defn read-msg [{:keys [socket info]}]
  (:message (receive socket)))

(defn pending-packet [num->packet allpackets sent ackd]
  (num->packet (first (sort (clojure.set/difference allpackets sent ackd)))))

;; TODO group acks together, have have send but unknown constantly transmitted
;; in every batch
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
         window-size 2]
    
    (def ack (receive-timeout (:socket @socket) 100))

    (loge [window-size recentrtt sent ackd #_packetnum->sendtime])
    
    (cond
      (= (:message ack) :corrupted)
      (recur sent ackd packetnum->sendtime recentrtt window-size)
      
      (not= ack :timeout)
      (do
        (let [acks (:message ack)]
          (loge ["ack" (:ack acks)])
          (recur (do
                   (loge ["merging sent and" acks])
                   (clojure.set/union sent (set (:ack acks))))
                 
                 (reduce conj ackd (:ack acks))
                 
                 packetnum->sendtime
                 
                 (let [rtts (doall (for [ack (:ack acks)]
                                    (- (now) (packetnum->sendtime ack))))]
                   (take 4 (concat recentrtt rtts)))
                 
                 (do
                   (loge ["doubling window size to" (* 2 window-size)])
                   (* 2 window-size)))))
      
      (= allpackets ackd)
      (loge "done transmitting")
      
      (and (> window-size (count (clojure.set/difference sent ackd)))
           (pending-packet packets allpackets sent ackd))
      
      (let [packet (pending-packet packets allpackets sent ackd)
            packetnum (:num packet)]
        
        (loge ["sending" packetnum])
        
        (send-msg (assoc packet :window-size window-size))
        (recur (conj sent packetnum)
               ackd
               (assoc packetnum->sendtime packetnum (now))
               recentrtt
               window-size))

      true
      (do
        (let [now (now)
              timeout (if (not-empty recentrtt)
                        (* 2 (/ (apply + recentrtt)
                                (count recentrtt)))
                        2000)
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
          
          (when (not-empty outstanding-packets)
            (loge [(on-red (blue "outstanding packets"))
                   outstanding-packets]))
          #_(loge-slow [(first recentrtt) "window size: " window-size])
          
          (recur sent'
                 ackd
                 packetnum->sendtime
                 recentrtt
                 (if (and (not-empty outstanding-packets-unpunished)
                          (> window-size 1)) 
                   (do
                     (loge ["cutting window size" (count outstanding-packets-unpunished)])
                     (or
                      (- window-size (count outstanding-packets-unpunished))
                      (max 1 (/ window-size
                                (int
                                 (Math/pow 2 (count outstanding-packets-unpunished)))))))
                   window-size)))))))


(defn recv3700 []
  (reset! socket {:socket (new DatagramSocket)})
  (loge (str "Bound to port " (.getLocalPort ^DatagramSocket (:socket @socket))))
  (def total-packets (atom -1))

  ;; IDEA, count time. allow 80% of ack if its been a while
  

  (loop [recvd-packets {}
         printed false
         pending-acks #{}
         window-size 1]

    (log ["urhm" recvd-packets])
    (cond
      (and (not printed)
           (= @total-packets
              (count (map :num recvd-packets))))
      (do
        (loge "done:")
        (log recvd-packets)
        (doseq [p (sort-by :num (vals recvd-packets))]
          (print (:data p)))
        (flush)
        ;; this needs to still run, because if acks are dropped, we need to resend them so send
        ;; can finish
        (recur recvd-packets true pending-acks 0))
      
      (and (not-empty pending-acks)
           (or
            (>= (count pending-acks) window-size)
            
            (not= (count pending-acks)
                  (inc (- (apply max pending-acks)
                          (apply min pending-acks))))))
      (do
        (loge ["sending ack for" pending-acks])
        (send-msg {:ack (sort pending-acks)})
        (recur recvd-packets printed #{} window-size))
      
      true
      (do
        
        (def msg (receive (:socket @socket)))
        
        (when-not (:info @socket)
          (swap! socket assoc :info (select-keys msg [:host :port])))
        
        (def recvd (:message msg))

        #_(log [recvd msg])
        (loge ["received" (:num recvd) :window-size (:window-size recvd)])
        (when (= (:num recvd) -1)
          (loge (str "num packets " (:packets recvd)))
          (reset! total-packets (:packets recvd)))
        
        (case recvd
          :corrupted
          (do
            (loge "received corrupted packet")
            (recur recvd-packets printed pending-acks window-size))
          (do
            
            (loge ["received packet" (:num recvd)])
            (recur (case (int (:num recvd))
                     -1 recvd-packets
                     (assoc recvd-packets (:num recvd) recvd))
                   printed
                   (conj pending-acks (:num recvd))
                   (:window-size recvd))))))))

(defn -main [recvorsend & relationships]
  (case recvorsend
    "send" (apply send3700 (filter (complement empty?) relationships))
    "recv" (apply recv3700 (filter (complement empty?) relationships))))
