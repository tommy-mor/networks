(ns networks.core
  (:require
   [clojure.tools.cli :refer [parse-opts]]
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
       string)}))

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

(defn set-covers [acks] (into #{} (range (apply min acks) (inc (apply max acks)))))

(defn average [coll default]
  (if (not-empty coll)
    (/ (apply + coll)
       (count coll))
    default))

;; any packet that is is not ackd, but is less than latest ackd one is lost, resubmit
(defn send3700 [recv_host recv_port]
  (reset! socket {:socket (new DatagramSocket 0 (InetAddress/getByName recv_host))
                  :info {:port (Integer/parseInt recv_port)
                         :host recv_host}}))

(defn crawl [{:keys [arguments] {:keys [port server]} :options}]
  (let [[username password] (filter (complement empty?) arguments)]
    (prn port)
    (prn server)
    (prn username)
    (prn password)))


(def cli-options
  [["-p" "--port PORT" "port number"
    :default 443
    :parse-fn #(Integer/parseInt %)]
   ["-s" "--server SERVER" "server"
    :default "proj5.3700.network"]])


(defn -main [& args]
  (crawl (parse-opts args cli-options)))
