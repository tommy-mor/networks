(ns networks.core
  (:require
   [clojure.tools.cli :refer [parse-opts]]
   [clojure.term.colors :refer [blue on-red]]
   [clojure.set]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.core.async :refer [go] :as async])
  (:import (java.net DatagramSocket DatagramPacket)
           (java.io PrintWriter InputStreamReader))
  (:gen-class))

(set! *warn-on-reflection* false)

(spit "log.edn" "")

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
(def leader (atom nil))
(def data (atom {}))

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
                  json/generate-string
                  (.getBytes))
        packet (DatagramPacket. bytes
                                (count bytes)
                                (java.net.InetAddress/getByName "localhost")
                                @port
                                )]
    (.send @socket packet)))

(defmulti respond (fn [data] (keyword (:type data))))

(defmethod respond :get [{:keys [src dst leader key MID] :as req}]
  (log req)
  (log [@data key])
  (send {:MID MID :type "ok" :src dst :dst src :leader leader :key key :value (get @data key)}))

(defmethod respond :put [{:keys [src dst leader MID key value] :as req} ]
  (log req)
  (swap! data assoc key value)
  (send {:type "ok" :src dst :dst src :leader leader :MID MID}))



(defn -main [myport myid & replicaids]
  (println "Starting up")
  (println "myport" (Integer/parseInt myport))
  (println "myid" myid)
  (println "replicaids" replicaids)
  (reset! socket (DatagramSocket. 0))
  (reset! port (Integer/parseInt myport))
  (send {:src myid :dst "FFFF" :leader "FFFF" :type "hello"})
  (while true
    (let [data (receive)]
      (respond (json/parse-string data keyword)))))

(comment
  (main-default))

