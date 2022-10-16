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

;; neighber -> "where that neighbor can send things to.."
(def routing-table (atom {}))
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

(comment
  (process-message update-message))
