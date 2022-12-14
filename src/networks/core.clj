(ns networks.core
  (:require
   [clojure.tools.cli :refer [parse-opts]]
   [clj-commons.byte-streams :refer [to-byte-arrays convert]]
   [clojure.term.colors :refer [blue on-red]]
   [clojure.set]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.core.async :refer [go] :as async])
  (:import (java.net DatagramSocket DatagramPacket)
           (java.io PrintWriter InputStreamReader))
  (:gen-class))

(set! *warn-on-reflection* false)

(defn now []
  (inst-ms (java.time.Instant/now)))

(defn log [thing]
  (spit "log.edn"
        (str (pr-str thing)
             "\n\n") :append true))

(defn log-body [body]
  (spit "body.txt"
        (str body
             "\n\n") :append true))

(defn loge [& e]
  (binding [*out* *err*]
    (apply println e)))

(def socket (atom nil))
(def port (atom nil))

;; (String. (to-byte-arrays (json/write-str {:type :connect :port 3000})))

(defn receive []
  (let [buf (byte-array 1024)
        packet (DatagramPacket. buf (count buf))]
    (.receive @socket packet)
    (let [data (.getData packet)
          length (.getLength packet)]
      (->> (byte-array data)
           (#(String.  %))
           (clojure.string/trim)))))

(defn send [data]
  (println "sending" data)
  (let [bytes (-> data
                  json/write-str
                  to-byte-arrays)
        packet (DatagramPacket. bytes
                                (count bytes)
                                (java.net.InetAddress/getByName "localhost")
                                @port
                                )]
    (.send @socket packet)))


(defn -main [myport myid & replicaids]
  (println "Starting up")
  (println "myport" (Integer/parseInt myport))
  (println "myid" myid)
  (println "replicaids" replicaids)
  (reset! socket (DatagramSocket. 0))
  (reset! port (Integer/parseInt myport))
  (send {:src myid :dst "FFFF" :leader "FFFF" :type "hello"})
  (def p (receive))
  (println "Received" p) )

(comment
  (main-default))

