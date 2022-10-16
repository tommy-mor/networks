(ns networks.table
  (:require [manifold.stream :as s]
            [clojure.data.json :as json]))

(defn oneify-ip [ip]
  (clojure.string/join "." (let [d (clojure.string/split ip #"\.")]
                             (conj (vec (butlast d)) (str (dec (Integer. (last d))))))))

(assert (= "192.168.0.1" (oneify-ip "192.168.0.2")))

(defn send-message [{:keys [socket ip port]} msg]
  @(s/put! socket
           {:host "localhost"
            :port port
            :message (json/write-str msg)}))

(def asn (atom 7))
(def neighbors  
  (atom (list {:port 59484, :ip "192.168.0.2", :purpose "cust"}
              {:port 51646, :ip "172.168.0.2", :purpose "cust"})))

(defn ip->neighbor [ip]
  (first (filter (comp #{ip} :ip) @neighbors)))

(def routing-table (atom []))
(def all-messages (atom []))

(def msg {:type "update",
          :src "192.168.0.2", ;; my neighbor
          :dst "192.168.0.1", ;; me
          :msg {:network "192.168.0.0",  ;the neighbor knows abou tthis network
                :netmask "255.255.255.0", ;; under this mask
                :localpref 100,
                :ASPath [1],
                :origin "EGP",
                :selfOrigin true}})

(defn other-neighbors [src]
  (filter #(not= (:ip %) src) @neighbors))

(defn prepend [coll a]
  (into [a] coll))

(defmulti process-message (comp keyword :type))
(defmethod process-message :update [msg]
  "save a copy"
  "add entry to forwarding table"
  "potentially send to neighboring routers"

  (swap! routing-table conj [(:src msg) (:msg msg)])

  (doall (for [neighbor (other-neighbors (:src msg))]
           (send-message
            neighbor
            {:type :update
             :src (oneify-ip (:ip neighbor))
             :dst (:ip neighbor)
             :msg (-> (:msg msg)
                      (update-in [:ASPath] prepend @asn)
                      (dissoc :localpref
                              :origin ;; TODO there is more logic to be done here
                              :selfOrigin))}))))


(def data1 {:src "172.168.0.25", :dst "192.168.0.25", :type "data", :msg {:ignore "this"}})
(def data2 {:src "192.168.0.25", :dst "172.168.0.25", :type "data", :msg {:ignore "this"}})

(defn ip->int [ip]
  (apply + (map (fn [base coef] (bit-shift-left base coef))
                (map #(Integer/parseInt %) (clojure.string/split ip #"\."))
                [24 16 8 0])))

(assert (= (ip->int "255.255.255.255") 2r11111111111111111111111111111111))
(assert (= (ip->int "255.255.255.0") 2r11111111111111111111111100000000))
(assert (= (ip->int "255.255.0.0") 2r11111111111111110000000000000000))

(defn entry-applies? [ip entry]
  (= (bit-and (ip->int (:network entry))
              (ip->int (:netmask entry)))
     (bit-and (ip->int ip)
              (ip->int (:netmask entry)))))

(assert (entry-applies? "192.168.0.25" {:network "192.168.0.0" :netmask "255.255.255.0"}))
(assert (not (entry-applies? "192.168.2.25" {:network "192.168.0.0" :netmask "255.255.255.0"})))
(assert (entry-applies? "192.168.2.25" {:network "192.168.0.0" :netmask "255.255.0.0"}))

(defn matches [ip]
  "gets the routes in the table that are apply to this ip"

  (filter (comp (partial entry-applies? ip) second) @routing-table))

(def msg data1)



(defmethod process-message :data [msg]
  (def potential-routes (matches (:dst msg)))

  (cond
    (= 1 (count potential-routes))
    (let [[ip _] (first potential-routes)] (send-message (ip->neighbor ip)
                                                         msg)))
  "scenario 1: does not have route, gives no route message"
  "scenario 2: exactly one possible route, forward properly"
  "scenario 3: multiple routes, do longest prefix match"
  "check for legality: if source|dest is customer: send data. if source is peer|providor AND dest peer|providor, then drop message . send no route")

(comment
  (process-message update-message))
