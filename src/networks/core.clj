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

(def protocol (gloss/compile-frame (gloss/string :utf-8 :delimiters ["\r\n"])))

(def control (atom nil))
(def read-control (atom nil))

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
  (slurp @(s/take! @control :empty)))

;; TODO some requests have two responses
(defn request [cmd & args]
  (assert (#{"USER" "PASS"} cmd))
  (let [tosend (str (clojure.string/join " " (into [cmd] args)) "\r\n")]
    (println "SENDING " tosend)
    
    @(s/put! @control tosend))
  (clojure.string/split (take-stream) #" " 2))

(comment (def req (request "USER" "thmorriss"))
         (def pwreq (request "PASS" "rst")))


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
