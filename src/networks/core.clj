(ns networks.core
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [clojure.data.json :as json]
            [gloss.core :as gloss]
            [gloss.io :as io]
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

(defn trim-ftp [s]
  (assert (clojure.string/starts-with? s "ftp://"))
  (-> s
      (clojure.string/replace-first #"ftp://" "")
      (clojure.string/replace #"/$" "")))

(def url (trim-ftp "ftp://ftp.3700.network/"))

(def login-data {:username "morrisst"
                 :password "3sNurnEDZX7Q6aHWvdO1"})


(def control (atom nil))
(def data (atom nil))
(def logged-in (atom false))

(defn reset-connection []
  (when-not (nil? @data)
    (s/close! @data)
    (reset! data nil))
  
  (if (nil? @control)
    (do
      (reset! control @(tcp/client {:host url
                                    :port 21}))
      (reset! logged-in false)
      (slurp @(s/take! @control))
      #_(reset! read-control (io/decode-stream @control protocol)))
    (do
      (s/close! @control)
      (when @data (s/close! @data))
      (reset! control nil)
      (reset! data nil)
      (reset-connection))))

(comment (reset-connection))

(defn take-stream-one []
  @(s/try-take! @control :drained 100 :empty))

(defn take-stream []
  (let [msgs
        (take-while (partial not= :empty)
                    (repeatedly take-stream-one))
        msgs (map slurp msgs)]
    (println "received " (count msgs))
    (doseq [m msgs]
      (println "RECIEVED" m))
    (last msgs)))

;; TODO some requests have two responses
(defn request [cmd & args]
  (assert (#{"USER" "PASS" "TYPE" "MODE" "STRU" "LIST"
             "PASV" "STOR" "RETR" "DELE" "RMD" "MKD"} cmd))
  (let [tosend (str (clojure.string/join " " (into [cmd] args)) "\r\n")]
    (println "SENDING " tosend)
    
    @(s/put! @control tosend))
  (clojure.string/split (take-stream) #" " 2))

(defn login []
  (when-not @logged-in
    (def req (request "USER" (:username login-data)))
    (assert (= "331" (first req)))
    (def pwreq (request "PASS" (:password login-data)))
    (assert (= "230" (first pwreq)))
    
    (assert (= "200" (first (request "TYPE" "I"))))
    (assert (= "200" (first (request "MODE" "S"))))
    (assert (= "200" (first (request "STRU" "F"))))
    (reset! logged-in true)))

(defn shift-add [top bottom]
  (+ (bit-shift-left top 8)
     bottom))

(defn parse-pasv-ip [s]
  (def s s)
  (let [numbers  (as->
                     (re-find #"\(.*\)" s) $
                   (drop 1 $)
                   (butlast $)
                   (apply str $)
                   (clojure.string/split $ #","))
        ipnums (take 4 numbers)
        portnums (map #(Integer/parseInt %) (drop 4 numbers))
        topbits (first portnums)
        lowerbits (second portnums)
        port (shift-add topbits lowerbits)]
    (println port)
    {:host (clojure.string/join "." ipnums)
     :port port}))

;; (defnDEBUG respond-227 [req]
;;   (def req req)
;;   (println "227req" (parse-pasv-ip (second req)))
;;   (reset! data @(tcp/client (parse-pasv-ip (second req))))
;;   (println "closed?" (s/closed? @data))
;;   true)

(defn open-data-channel []
  (def pasvreq (request "PASV"))
  (if (= "227" (first pasvreq))
    (respond-227 pasvreq)
    (throw (ex-info {:req pasvreq}))))

(defn ls [dirr]
  (login)
  
  (open-data-channel)
  
  (request "LIST" dirr)

  (def ls-result @(s/try-take! @data :drained 100 :timeout))
  (if (= ls-result :drained)
    nil
    (clojure.string/split (slurp ls-result) #"\r\n")))

(comment (ls "/my_stuff")
         (ls "/"))

(defn retr [dirr]
  (login)
  
  (open-data-channel)
  
  (def retr-req (first (request "RETR" dirr)))
  (cond
    (#{"550"} retr-req)
    "failed to open file"
    
    (#{"150" "226"} retr-req)
    (do
      (def retr-result @(s/try-take! @data :drained 100000 :timeout))
      (if (= retr-result :drained)
        nil
        (slurp retr-result)))))

(defn stor [dirr file-to-upload]
  (login)
  
  (open-data-channel)
  (request "STOR" dirr)

  @(s/put! @data file-to-upload)
  (s/close! @data))

(defn mkd [dirr] (login) (request "MKD" dirr))
(defn rmd [dirr] (login) (request "RMD" dirr))

(defn dele [dirr] (login) (request "DELE" dirr))

(comment
  (stor "/my_stuff/epic.txt" "epicn win file")
  (ls "/")
  (ls "/my_stuff")
  (retr "/hello.txt")
  (retr "/epic.txt")
  (dele "/epic.txt")
  
  (mkd "/my_folder/")
  (stor "/my_folder/epic.txt" "epicn win filesss")
  (retr "/my_folder/epic.txt")
  (ls "/my_folder")
  (dele "/my_folder/epic.txt")
  (rmd "/my_folder/")
  
  )

(defmulti ftp (fn [fst & r] (keyword fst)))
(defmethod ftp :ls [_ url]
  (println url))

(defn -main [& args]
  (def opts (parse-opts args cli-options))
  (prn opts))
