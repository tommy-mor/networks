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
(defn reset-connection []
  (if (nil? @control)
    (do
      (reset! control @(tcp/client {:host url
                                    :port 21}))
      (slurp @(s/take! @control))
      #_(reset! read-control (io/decode-stream @control protocol)))
    (do
      (s/close! @control)
      (reset! control nil)
      (reset-connection))))

(comment (reset-connection))

(defn take-stream []
  (let [r (slurp @(s/take! @control :empty))]
    (println "RECEIVED" r)
    r))

;; TODO some requests have two responses
(defn request [cmd & args]
  (assert (#{"USER" "PASS" "TYPE" "MODE" "STRU" "LIST" "PASV"} cmd))
  (let [tosend (str (clojure.string/join " " (into [cmd] args)) "\r\n")]
    (println "SENDING " tosend)
    
    @(s/put! @control tosend))
  (clojure.string/split (take-stream) #" " 2))

(defn login []
  (def req (request "USER" (:username login-data)))
  (assert (= "331" (first req)))
  (def pwreq (request "PASS" (:password login-data)))
  (assert (= "230" (first pwreq))))

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
        lowerbits (second portnums)]
    {:host (clojure.string/join "." ipnums)
     :port (shift-add topbits lowerbits)}))

(defn open-data-channel []
  (def pasvreq (request "PASV"))
  (tcp/client (parse-pasv-ip (second pasvreq))))

(defn ls []
  (login)
  (assert (= "200" (first (request "TYPE" "I"))))
  (assert (= "200" (first (request "MODE" "S"))))
  (assert (= "200" (first (request "STRU" "F"))))
  (def data @(open-data-channel))
  (assert (= "150" (first (request "LIST" "."))))
  (def ls-result (slurp @(s/take! data)))
  (clojure.string/split ls-result #"\r\n"))


(def cli-options [])

(defmacro defnDEBUG
  [name & args]
  (let [fdecl (if (string? (first args)) (next args) args)
        fdecl (if (map? (first fdecl)) (rest fdecl) fdecl)
        fdecl (first args)
        body (rest args)
        body (into body (map (fn [sym] `(def ~sym ~sym)) fdecl))]
    `(defn ~name ~fdecl ~@body)))


(defnDEBUG epic-fun [a c d]
  (* 40 a))

(defmulti ftp (fn [fst & r] (keyword fst)))
(defmethod ftp :ls [_ url]
  (println url))

(ftp "ls" url)

(defn -main [& args]
  (def opts (parse-opts args cli-options))
  (prn opts)
  )
