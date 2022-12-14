(ns networks.core
  (:require
   [clojure.tools.cli :refer [parse-opts]]
   [clojure.term.colors :refer [blue on-red]]
   [clojure.set]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.core.async :refer [go] :as async])
  (:import (java.net DatagramSocket DatagramPacket SocketTimeoutException)
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

(defn logf [f thing]
  (spit f
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

(def myid (atom nil))
(def mystate (atom :follower))
(def leader (atom nil))
(def data (atom {}))
(def last-heartbeat (atom (now)))
(def term (atom 0))
(def voted-for (atom nil))
(def voted-for-term (atom nil))
(def timeout-ms (rand-nth (range 1000 3000)))
(println "timeout-ms" timeout-ms)
(def majority (atom nil))

;; (String. (to-byte-arrays (json/write-str {:type :connect :port 3000})))

(defn receive []
  (let [buf (byte-array 1024)
        packet (DatagramPacket. buf (count buf))]
    
    (try (do
           (.receive @socket packet)
           (let [data (.getData packet)
                 length (.getLength packet)]
             (->> (byte-array data)
                  (#(String.  %))
                  (clojure.string/trim))))
         
         (catch SocketTimeoutException e
           :timeout))))

(defn send [data]
  (let [data (assoc data :leader (or @leader "FFFF"))
        _ (println "SENDING" data)
        bytes (-> data
                  json/generate-string
                  (.getBytes))
        packet (DatagramPacket. bytes
                                (count bytes)
                                (java.net.InetAddress/getByName "localhost")
                                @port
                                )]
    (.send @socket packet)))

(defmulti respond (fn [data] (keyword (:type data))))


(defn putget [{:keys [src dst MID] :as req} v]
  (log req)
  (cond (nil? @leader)
        (send {:MID MID :type "fail" :src dst :dst src})
        
        (not= @mystate :leader)
        (send {:MID MID :type "redirect" :src dst :dst src})
        
        :else
        (send (v))))

(defmethod respond :get [{:keys [src dst key MID] :as req}]
  (log [@data key])
  (putget req (constantly {:MID MID :type "ok" :src dst :dst src :key key :value (or (get @data key) "")})))

(defmethod respond :put [{:keys [src dst MID key value] :as req} ]
  (putget req
   (fn []
     (swap! data assoc key value)
     {:type "ok" :src dst :dst src :MID MID})))

(defmethod respond :rpc/request-vote [{:keys [src dst MID term candidate last-log-index last-log-term] :as req}]
  (log req)
  "TODO additional constraint "
  (if (or (nil? @voted-for)
          (and (= @voted-for candidate)
               (= @voted-for-term term)))
    (do
      (reset! voted-for candidate)
      (reset! voted-for-term term)
      (send {:type :rpc/request-vote-response :src dst :dst candidate :MID MID :term term :vote-granted true}))
    (send {:type :rpc/request-vote-response :src dst :dst candidate :MID MID :term term :vote-granted false})))

(defmethod respond :rpc/request-vote-response [req]
  (assert (= @mystate :leader) "this should only be possible for leaders"))

(defmethod respond :rpc/append-entries [{:keys [src dst MID prev-log-index prev-log-term entries leader-commit] :as req}]
  (assert (= @mystate :follower) "this should only be possible for followers")
  
  (log req)
  (reset! last-heartbeat (now))
  (reset! leader (:leader req))
  (reset! term (:term req))
  (send {:type :rpc/append-entries-response :src @myid :dst @leader :MID MID :term @term :success true}))

(defmethod respond :rpc/append-entries-response [{:keys [src dst MID prev-log-index prev-log-term entries leader-commit] :as req}]
  (assert (= @mystate :leader) "this should only be possible for leaders")
  
  (log req)
  (reset! last-heartbeat (now))
  (reset! term (:term req)))

(defn send-heartbeat []
  (send {:type :rpc/append-entries
         :src @myid :dst "FFFF"
         :term @term :leader @myid
         
         :prev-log-index (count @data)
         :prev-log-term @term
         :entries []
         :leader-commit (count @data)}))

(defn start-election []
  (println "starting election")
  (swap! term inc)
  (reset! mystate :candidate)
  (reset! voted-for @myid)
  (reset! voted-for-term @term)
  
  (send {:type :rpc/request-vote
         
         :src @myid :dst "FFFF"
         
         :term @term :candidate @myid
         :last-log-index (count @data)
         :last-log-term @term})
  
  (loop [votes 0]
    (if (= votes @majority)
      (do
        (println "i am elected leader :) " @myid)
        (reset! mystate :leader)
        (reset! leader @myid)
        (send-heartbeat))
      
      (let [data (receive)]
        (if (= :timeout data)
          (recur votes)
          (let [data (json/parse-string data keyword)]
            (log data)
            (case (keyword (:type data))
              :rpc/request-vote-response
              (do
                (if (and (= (:term data) @term)
                         (:vote-granted data))
                  (recur (inc votes))
                  (recur votes)))

              :rpc/append-entries
              (if (>= (:term data) @term)
                (do
                  (reset! term (:term data))
                  (reset! mystate :follower)
                  (reset! leader (:leader data))
                  (reset! last-heartbeat (now)))
                (do
                  (send {:type :rpc/append-entries-response :src @myid :dst (:src data)
                         :term @term :success false}) ;; TODO check if this is right response
                  (recur votes)))
              
              
              (do
                (respond data)
                (recur votes)))))))))


(defn -main [myport myidd & replicaids]
  (reset! socket (DatagramSocket. 0))
  (.setSoTimeout @socket 10)
  (reset! port (Integer/parseInt myport))
  (reset! myid myidd)
  (reset! majority (inc (quot (inc (count replicaids)) 2)))
  (send {:src @myid :dst "FFFF" :type "hello"})
  (while true
    (when (and (not= @mystate :leader)
               (> (- (now) @last-heartbeat) timeout-ms))
      (start-election))
    (let [data (receive)]
      (if (not= data :timeout)
        (respond (json/parse-string data keyword))))))

(comment
  (main-default))

