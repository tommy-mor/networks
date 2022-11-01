(ns networks.core
  (:require [aleph.udp :as udp]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [clojure.data.json :as json]
            [networks.table]
            [clj-commons.byte-streams :refer [to-byte-arrays convert]]
            [clojure.data.json :as json])
  (:gen-class))

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

(defn send3700 [recv_host recv_port]
  (reset! send-socket {:socket @(udp/socket {:port 0
                                             :host recv_host})
                       :info {:port (Integer/parseInt recv_port)
                              :host recv_host}})
  
  (def inp (to-byte-arrays (slurp *in*) {:chunk-size 1375}))
  (doseq [section inp]
    (loge "sending a section")
    (send-msg @send-socket {:data (String. section)})
    (loge "waiting for ack")
    (loge (clojure.edn/read-string (String. (:message @(s/take! (:socket @send-socket)))))))
  
  (send-msg @send-socket {:done true})
  (loge "done sent")
  (loge (String. (:message @(s/take! (:socket @send-socket))))))


(def recv-socket (atom nil))
(defn recv3700 []
  (reset! recv-socket {:socket @(udp/socket {:host "0.0.0.0"})})
  (loge (str "Bound to port " (Integer/parseInt (last
                                                 (clojure.string/split
                                                  (-> (.description (:socket @recv-socket))
                                                      :sink
                                                      :connection
                                                      :local-address) #"\:")))))
  
  (loop []
    (loge "receiving")
    (def msg @(s/take! (:socket @recv-socket)))
    
    (when-not (:info @recv-socket)
      (swap! recv-socket assoc :info {:port (.getPort (:sender msg))
                                      :host (.getHostName (:sender msg))}))
    
    (def recvd (clojure.edn/read-string (String. (:message msg))))
    (if (:done recvd)
      (do
        (loge "done")
        (println "")
        (send-msg @recv-socket {:ack "exit"})
        (recur))
      (do
        (loge "received")
        (print (:data recvd))
        (loge "acking")
        (send-msg @recv-socket {:ack true})
        (recur)))))

(defn -main [recvorsend & relationships]
  (case recvorsend
    "send" (apply send3700 (filter (complement empty?) relationships))
    "recv" (apply recv3700 (filter (complement empty?) relationships))))
