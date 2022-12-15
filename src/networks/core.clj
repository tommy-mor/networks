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

(def data (atom {:last-applied -1
                 :store {}
                 :tape []}))

(def commit-index (atom -1))

(def leader-state (atom nil))

(def term (atom 0))
(def voted-for (atom nil))
(def voted-for-term (atom nil))

(def last-heartbeat (atom (now)))
(def timeout-ms (rand-nth (range 150 300)))

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
                                @port)]
    (.send @socket packet)))


(defn reply [req data]
  (let [mid (:rpc/mid req) 
        data (assoc data :rpc/mid mid :type :rpc/response
                    :src @myid :dst (:src req))]
    (send data)))

(def rpc-response (atom {}))
(def rpc-timeout 10)
(def crashed (atom #{}))
(defn send-rpc
  ([method data] (send-rpc method data rpc-timeout #{}))
  ([method data timeout valid-responses]
   (reset! last-heartbeat (now))
   (if (or (get @crashed (:dst data))
           (> timeout (* rpc-timeout (Math/pow 2 4))))
     (do
       (println "giving up on rpc")
       (swap! crashed conj (:dst data))
       :timed-out)
     (let [mid (str (java.util.UUID/randomUUID))
           valid-responses (clojure.set/union valid-responses #{mid})
           data (assoc data :rpc/mid mid :rpc/method method :src @myid :type :rpc/request)
           sent-at (now)]
       (send data)

       (loop []
         (let [responses @rpc-response
               k (first (clojure.set/intersection valid-responses (set (keys responses))))
               resp (get responses k)]
           (if resp
             (do
               (swap! rpc-response dissoc mid)
               resp)
             (if (> (- (now) sent-at) timeout)
               (do
                 (println "retrying! timeout " timeout (:dst data))
                 (send-rpc method data (* 2 timeout) valid-responses))
               (do (Thread/sleep 1)
                   (recur))))))))))
      

(defmulti respond (fn [data] (keyword (:type data))))


(defn putget [{:keys [src dst MID] :as req} v]
  (log req)
  (cond (nil? @leader)
        (do
          (println "fail!!!")
          (send {:MID MID :type "fail" :src dst :dst src}))
        
        (not= @mystate :leader)
        (send {:MID MID :type "redirect" :src dst :dst src})
        
        :else
        (send (v))))

(add-watch leader-state :leader->commited
           (fn [_ _ _ new]
             "
      If there exists an N such that N > commitIndex, a majority
      of matchIndex[i] ≥ N, and log[N].term == currentTerm:
      set commitIndex = N 
      "
             (swap! commit-index
                    (fn [current-index]
                      (loop [potential-index (inc current-index)]
                        (if (and (>= (count (filter (fn [[k v]] (>= v potential-index))
                                                    (:match-index new)))
                                     @majority)
                                 (= @term (:term (get-in @data [:tape potential-index]))))
                          (recur (inc potential-index))
                          (dec potential-index)))))))
(defn apply-log-entry [m entry]
  (case (:type entry)
    "put" (assoc m (:key entry) (:value entry))))

(add-watch commit-index :apply-committed-log
           (fn [_ _ old new]
             
             (when (and (not= old new)
                        (> new old)) 
               (swap! data
                      (fn [m]
                        #_(println "comttiing " old "->" new "m")
                        {:store (reduce apply-log-entry
                                        (:store m)
                                        (subvec (:tape m) (inc old) (inc new)))
                         :last-applied new
                         :tape (:tape m)})))))

(defn send-log-entries [dst]
  "takes info from @leader-state, then sends the correct log entry for that."
  (let [tape (:tape @data)
        last-log-index (dec (count tape))
        next-index-follower (get-in @leader-state [:next-index dst])
        entries-to-send (subvec tape next-index-follower)]
    (comment (prn [dst
                   "my last log index " last-log-index
                   "follower next index" next-index-follower
                   "entries to send" entries-to-send]))
    (if (>= last-log-index next-index-follower) ;; TODO I think I can get rid of this, and use this fn to send heartbeats as well...
      
      (let [resp (send-rpc :rpc/append-entries
                           {:dst dst
                            :entries entries-to-send
                            :term @term
                            :leader @myid
                            :prev-log-index (dec next-index-follower)
                            :prev-log-term (get-in tape [(dec next-index-follower) :term])
                            :leader-commit @commit-index})]
        (cond (:success resp)
              (swap! leader-state
                     (fn [st]
                       (-> st
                           (assoc-in [:next-index dst] (inc last-log-index))
                           (assoc-in [:match-index dst] last-log-index))))

              (= :timed-out resp)
              (println "timed out")

              :else
              
              (while true (println "FAILED APPENDENTRIES, dec next-index and retry")
                     (Thread/sleep 100)))))))

(defn put [{:keys [src dst MID key value] :as req}]
  (let [log-entry {:type "put" :key key :value value :term @term}
        new-tape (:tape (swap! data update-in [:tape] conj log-entry))
        this-put-idx (dec (count new-tape))]
    (try
      
      (prn ["new log entry" log-entry])

      (doseq [dst @other-replicas]
        (go (send-log-entries dst)))

      (logf "before" MID)

      ;; waits until has been committed and applied
      (while (not (>= (:last-applied @data) this-put-idx))
        (Thread/sleep 0))
      
      (logf "after" MID)
      
      
      #_(println "getting data!!" (get (:store @data) key) @data)
      
      {:type "ok" :src dst :dst src :MID MID}
      (catch Exception e
        (println "put failed???" e)))))

(defmethod respond :get [{:keys [src dst key MID] :as req}]
  (log [@data key])
  (putget req
          (constantly
           {:MID MID :type "ok" :src dst :dst src :key key :value (or (get (:store @data) key)
                                                                      (do
                                                                        (println "missing key" key)
                                                                        ""))})))

(defmethod respond :put [{:keys [src dst MID key value] :as req} ]
  (putget req
          (fn [] (put req))))

(defmulti respond-rpc (fn [data] (keyword (:rpc/method data))))

(defmethod respond-rpc :rpc/request-vote [{:keys [src dst MID candidate last-log-index last-log-term] :as req}]
  (log req)
  "TODO additional constraint "

  (cond (> @term (:term req))
        (do (println "term too low")
            (reply req {:term @term :vote-granted false}))

        (or (nil? @voted-for)
            (and (= @voted-for candidate)
                 (= @voted-for-term term)))
        (do
          (println "accepted candidate in term" candidate "in term " @term)
          (reset! voted-for candidate)
          (reset! voted-for-term term)
          (reply req {:term @term :vote-granted true}))

        :else
        (do
          (println "rejected candidate" candidate)
          (reply req {:term @term :vote-granted false}))))

(defmethod respond-rpc :rpc/append-entries [{:keys [src dst prev-log-index
                                                    prev-log-term
                                                    entries leader-commit] :as req}]
  (if-not (= @mystate :follower) (while true (println "should not be possible!!")
                                        (System/exit 1)))

  (logf (str "appends" @myid) (select-keys req [:prev-log-index :prev-log-term :leader-commit :entries]))

  "TODO 3. If an existing entry conflicts with a new one (same index
but different terms), delete the existing entry and all that
follow it (§5.3)"

  (reset! last-heartbeat (now))
  (let [tape (:tape @data)]
    (cond (> @term (:term req))
          (do
            (println "fail: got append entries from leader with lower term")
            (reply req {:term @term :success false}))
          
          (and
           (not= prev-log-index -1)
           (not= (get-in tape [prev-log-index :term])
                 prev-log-term))
          (do
            (println "fail: got append entries from leader with wrong prev-log-term"
                     tape prev-log-index)
            (reply req {:term @term :success false}))

          :else
          (do

            
            
            #_(println "leader commit" leader-commit)
            (let [new-tape (vec (concat tape entries))]
              (swap! data assoc :tape new-tape)
              (reset! commit-index (min leader-commit (dec (count new-tape)))))
            
            ;; watcher should update state machine here..
            
            (reset! leader (:leader req))
            ;; TODO maybe only reset this if we check the termid..
            (reset! term (:term req))

            ;; TODO check things correctly here..



            (reply req {:term @term :success true})))))

(defn send-heartbeat []
  (println "sending heartbeat")
  (reset! last-heartbeat (now))
  (send-rpc :rpc/append-entries
            {:dst "FFFF"
             :term @term :leader @myid
             
             :prev-log-index (:last-applied @data)
             :prev-log-term @term  ;; TODO make sure this is right..
             :entries []
             :leader-commit @commit-index}))


(defn start-election []
  (assert (= @mystate :follower))
  (swap! term inc)
  (when (not= @voted-for-term @term)
    (reset! mystate :candidate)
    (reset! voted-for @myid)
    (reset! voted-for-term @term)

    (println "started election in term" @term)
    ;; TODO pmap never finishes, so we cant win
    (let [votes (vec (pmap (fn [dst]
                             (send-rpc :rpc/request-vote
                                       {:dst dst
                                        :term @term
                                        :candidate @myid
                                        :last-log-index (:last-applied @data)
                                        :last-log-term @term}))
                           (vec @other-replicas)))]
      (if (and (>= (count (filter (fn [v] (and (:vote-granted v)
                                               (= @term (:term v)))) votes))
                   @majority)
               (not= @mystate :follower) )
        ;; TODO make sure that I can cancel candidacy if i get a hearteat)
        ;; TODO add other ways that candidacy can go..
        (do
          (println "i am elected leader :) " @myid)
          (reset! leader-state {:next-index (into {} (map (fn [r] [r (count (:tape @data))])
                                                          @other-replicas))
                                :match-index  (into {} (map (fn [r] [r 0])
                                                            @other-replicas))})
          (reset! mystate :leader)
          (reset! leader @myid)
          (send-heartbeat))
        (println "election failed")))))


(def external-requests (atom (list)))
(def rpc-requests (atom (list)))

(add-watch external-requests :external-requests
           (fn [k r o n]
             (logf "reqs" (count n))
             n))

(add-watch mystate :my-state
           (fn [k r o n]
             (println "my state changed to " n)
             n))

(add-watch rpc-requests :rpc-requests
           (fn [k r o n]
             (logf (str "rpc" @myid) (count n))
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
              (swap! external-requests conj data)))
          
          (recur))))))

(defn -main [myport myidd & replicaids]
  (reset! socket (DatagramSocket. 0))
  (.setSoTimeout @socket 1)
  (reset! port (Integer/parseInt myport))
  (reset! myid myidd)
  (let [replicas (filter (complement empty?) replicaids)]
    (reset! majority (quot (inc (count replicas)) 2))
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
              (println "starting election")
              (start-election))
            
            (when (and (= @mystate :leader)
                       (> (- (now) @last-heartbeat) (/ timeout-ms 2)))
              (send-heartbeat))
            
            (Thread/sleep 10)
            (recur)))
  
  (future (while true
            (try
              
              (when (not-empty @rpc-requests)
                (let [[[req & _ ] _] (swap-vals! rpc-requests rest )]
                  (when (and (:term req)
                             (> (:term req) @term))
                    (reset! term (:term req))
                    (println "i am a follower now, saw term" @term)
                    (reset! mystate :follower)
                    (reset! voted-for nil))
                  
                  (reset! last-heartbeat (now))
                  (respond-rpc req)))
              (catch Exception e
                (log e)
                (println "erhm" e)))
            (Thread/sleep 0)))
  
  (while true
    (when (not-empty @external-requests)
      (let [[[req & _] _] (swap-vals! external-requests rest)]
        (try 
          (respond req)
          (catch Exception e
            (while true
              (println "exception!!!"))
            (println "erhmreq" e)))))
    (Thread/sleep 0)))

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

