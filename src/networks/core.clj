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
(def other-replicas (atom #{}))

(def myid (atom nil))
(def mystate (atom :follower))
(def leader (atom nil))

(def data (atom {}))
(def tape (atom []))
(def commit-index (atom 0))
(def last-applied (atom 0))

(def leader-state (atom nil))

(def term (atom 0))
(def voted-for (atom nil))
(def voted-for-term (atom nil))

(def last-heartbeat (atom (now)))
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
        bytes (-> data
                  json/generate-string
                  (.getBytes))
        packet (DatagramPacket. bytes
                                (count bytes)
                                (java.net.InetAddress/getByName "localhost")
                                @port
                                )]
    (.send @socket packet)))


(defn reply [req data]
  (let [mid (:rpc/mid req) 
        data (assoc data :rpc/mid mid :type :rpc/response
                    :src @myid :dst (:src req))]
    (println "responding to " "with " data)
    
    (send data)))

(def rpc-response (atom {}))
(defn send-rpc [method data]
  (let [mid (str (java.util.UUID/randomUUID))
        data (assoc data :rpc/mid mid :rpc/method method :src @myid :type :rpc/request)]
    (send data)
    
    (loop []
      (let [resp (get @rpc-response mid)]
        (if resp
          (do
            (swap! rpc-response dissoc mid)
            resp)
          (do
            (Thread/sleep 10)
            (recur)))))))
      

(defmulti respond (fn [data] (keyword (:type data))))


(defn putget [{:keys [src dst MID] :as req} v]
  (log req)
  (cond (nil? @leader)
        (send {:MID MID :type "fail" :src dst :dst src})
        
        (not= @mystate :leader)
        (send {:MID MID :type "redirect" :src dst :dst src})
        
        :else
        (send (v))))

(defn put [{:keys [src dst MID key value] :as req}]
  (let [log-entry {:type :put :key key :value value :term @term}]
    (swap! tape conj log-entry)

    "this can take potentially a long time, because it might trigger entire log refilling loop.
     BUT we only block here until we get majority votes.."
    (let [append-responses (async/chan 100)]
      (doseq [dst @other-replicas]
        (go
          (println "sending rpc to dst" dst)
          (async/>! append-responses
                    (send-rpc :rpc/append-entries
                              {:dst dst
                               :entries [{:type :put :key key :value value :term @term}]
                               :term @term
                               :leader @myid
                               :prev-log-index (dec (dec (count @tape)))
                               :prev-log-term (get-in @tape [(dec (dec (count @tape))) :term])
                               :leader-commit @commit-index}))))
      (loop [received 0]
        (if (= received @majority)
          (println "got majority")
          (let [resp (async/<!! append-responses)]
            ;; TODO need to update my current term if there is a new one.... maybe thats handled
            ;; in other loop
            (if (and (= (:term resp) @term)
                     (:success resp))
              (recur (inc received))
              (recur received))))))
    
    (reset! commit-index (dec (count @tape)))
    
  (swap! data assoc key value)
  {:type "ok" :src dst :dst src :MID MID}))

(defmethod respond :get [{:keys [src dst key MID] :as req}]
  (log [@data key])
  (putget req (constantly {:MID MID :type "ok" :src dst :dst src :key key :value (or (get @data key) "")})))

(defmethod respond :put [{:keys [src dst MID key value] :as req} ]
  (putget req
          (fn [] (put req))))

(defmulti respond-rpc (fn [data] (keyword (:rpc/method data))))

(defmethod respond-rpc :rpc/request-vote [{:keys [src dst MID term candidate last-log-index last-log-term] :as req}]
  (log req)
  "TODO additional constraint "
  (if (or (nil? @voted-for)
          (and (= @voted-for candidate)
               (= @voted-for-term term)))
    (do
      (reset! voted-for candidate)
      (reset! voted-for-term term)
      (reply req {:term term :vote-granted true}))
    (reply req {:term term :vote-granted false})))

(defn apply-log-entry [entry]
  (println "entry" (pr-str entry))
  (case (:type entry)
    "put" (swap! data assoc (:key entry) (:value entry))))
  
(defn update-state-machine []
  (let [entries (subvec @tape @last-applied (inc @commit-index))]
    (doseq [entry entries]
      (apply-log-entry entry)
      (swap! last-applied inc))))

(defmethod respond-rpc :rpc/append-entries [{:keys [src dst prev-log-index
                                                    prev-log-term
                                                    entries leader-commit] :as req}]
  (if-not (= @mystate :follower) (while true (println "should not be possible!!")
                                        (System/exit 1)))

  "TODO 3. If an existing entry conflicts with a new one (same index
but different terms), delete the existing entry and all that
follow it (§5.3)"

  (cond (> @term (:term req))
        (do
          (println "got append entries from leader with lower term")
          (reply req {:term @term :success false}))
        
        (not= (get-in @tape [prev-log-index :term])
              prev-log-term)
        (do
          (println "got append entries from leader with wrong prev-log-term")
          (reply req {:term @term :success false}))

        :else
        (do

          
          
          (reset! tape (vec (concat @tape entries)))
          (reset! commit-index (min leader-commit (dec (count @tape))))
          
          (logf (str "tape" @myid) [:before @tape entries])
          (update-state-machine)
          (logf (str "tape" @myid) [:after @tape entries])
          
          (reset! last-heartbeat (now))
          (reset! leader (:leader req))
          ;; TODO maybe only reset this if we check the termid..
          (reset! term (:term req))

          ;; TODO check things correctly here..



          (reply req {:term @term :success true}))))

(defn send-heartbeat []
  (send-rpc :rpc/append-entries
            {:dst "FFFF"
             :term @term :leader @myid
             
             :prev-log-index (count @data)
             :prev-log-term @term
             :entries []
             :leader-commit (count @data)})
  (reset! last-heartbeat (now)))


(defn start-election []
  (assert (= @mystate :follower) )
  (when (not= @voted-for-term @term)
    (swap! term inc)
    (reset! mystate :candidate)
    (reset! voted-for @myid)
    (reset! voted-for-term @term)
    (println "other replicas" @other-replicas)
    
    (let [votes (vec (pmap (fn [dst]
                             (send-rpc :rpc/request-vote
                                       {:dst dst
                                        :term @term
                                        :candidate @myid
                                        :last-log-index (count @data)
                                        :last-log-term @term}))
                           (vec @other-replicas)))]
      (if (and (>= (count (filter (fn [v] (and (:vote-granted v)
                                               (= @term (:term v)))) votes))
                   @majority)
               (not= @mystate :follower) )
        ;; TODO make sure that I can cancel candidacy if i get a hearteat)
        (do
          (println "i am elected leader :) " @myid)
          (reset! mystate :leader)
          (reset! leader @myid)
          (reset! leader-state {:next-index (into {} (map (fn [r] [r (inc (count @log))])) @other-replicas)
                                :match-index  (into {} (map (fn [r] [r 0])) @other-replicas)})
          (send-heartbeat))))))


(def external-requests (atom (list)))
(def rpc-requests (atom (list)))

(add-watch external-requests :external-requests
           (fn [k r o n]
             n))

(add-watch mystate :my-state
           (fn [k r o n]
             (println "my state changed to " n)
             n))

(add-watch rpc-requests :rpc-requests
           (fn [k r o n]
             n))

(defn read-loop []
  (loop []
    (let [data (receive)]
      (case data
        :timeout (recur)
        (let [data (json/parse-string data keyword)]
          
          (logf (str "received" @myid) data)
          (case (keyword (:type data))
            :rpc/request (swap! rpc-requests conj data)
            :rpc/response (swap! rpc-response assoc (:rpc/mid data) data)
            (do
              (println "external request")
              (swap! external-requests conj data)))
          
          (recur))))))

(defn -main [myport myidd & replicaids]
  (reset! socket (DatagramSocket. 0))
  (.setSoTimeout @socket 10)
  (reset! port (Integer/parseInt myport))
  (reset! myid myidd)
  (let [replicas (filter (complement empty?) replicaids)]
    (reset! majority (inc (quot (inc (count replicas)) 2)))
    (reset! other-replicas (set replicas)))

  (spit (str "received" @myid) "")
  
  
  (send {:src @myid :dst "FFFF" :type "hello"})
  
  
  (comment (future (while true
                     (println "my state is " @mystate)
                     (Thread/sleep 100))))
  (future (read-loop))
  (future (loop []
            (when (and (not= @mystate :leader)
                       (> (- (now) @last-heartbeat) timeout-ms))
              (start-election))
            
            (when (and (= @mystate :leader)
                       (> (- (now) @last-heartbeat) (/ timeout-ms 2)))
              (send-heartbeat))
            
            (Thread/sleep 10)
            (recur)))
  
  (future (while true
            (try
              
              (when (not-empty @rpc-requests)
                (let [req (first @rpc-requests)]
                  (swap! rpc-requests rest)
                  
                  (when (and (:term req)
                             (> (:term req) @term))
                    (reset! term (:term req))
                    (println "i am a follower now, saw term" @term)
                    (reset! mystate :follower)
                    (reset! leader (:src req)))
                  
                  (reset! last-heartbeat (now))
                  (println "responding to rpc request" req)
                  (respond-rpc req)))
              (catch Exception e
                (log e)
                (println "erhm" e)))
            (Thread/sleep 10)))
  
  (while true
    (when (not-empty @external-requests)
      (println "responding to external request")
      (respond (first @external-requests))
      (swap! external-requests rest))
    (Thread/sleep 10)))

"TODO
Current terms are exchanged
whenever servers communicate; if one server’s current
term is smaller than the other’s, then it updates its current
term to the larger value. If a candidate or leader discovers
that its term is out of date, it immediately reverts to follower state. If a server receives a request with a stale term
number, it rejects the request.
"

(comment
  (main-default))

