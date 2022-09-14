(ns networks.core
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [clojure.data.json :as json]
            [gloss.core :as gloss]
            [gloss.io :as io]
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

(comment 
  (def words (clojure.string/split (slurp (clojure.java.io/resource "project1-words.txt")) #"\r\n"))
  (def protocol (gloss/compile-frame (gloss/string :utf-8 :delimiters ["\n"])))
  (def c @(tcp/client {:host "proj1.3700.network" :port 27994 :ssl? true :insecure? true}))
  (def read-stream (io/decode-stream c protocol))
  
  (defn request [body]
    @(s/put! c (str (json/write-str body) "\n"))
    (let [x @(s/take! read-stream)
          r (json/read-str x :key-fn keyword)]
      (println "received" r)
      r))

  (def session-id (:id (request {:type "hello" :northeastern_username "morriss.t"})))

  (def starting-word (rand-nth words))

  (defn guess-2 [g]
    (into {}
          (map (fn [idx c score] (case score
                                   0 nil
                                   1 nil
                                   2 [idx c])) (range) (:word g) (:marks g))))
  (defn guess-1 [g]
    (clojure.set/difference (into #{} (map (fn [c score] (case score
                                                           0 nil
                                                           1 c
                                                           2 nil)) (:word g) (:marks g)))
                            #{nil}))

  (defn calc-response [guesses]
    (let [somewhere (apply clojure.set/union (map guess-1 guesses))
          positions (apply merge (map guess-2 guesses))
          regex (re-pattern (apply str (for [i (range 5)]
                                         (get positions i "."))))]
      (rand-nth (filter #(re-find regex %) words))))
  

  (def final (loop [resp (request {:type "guess" :id session-id :word starting-word})]
               (if (= "retry" (:type resp))
                 (recur
                  (request {:type "guess" :id session-id :word (calc-response (:guesses resp))}))
                 resp)))
  
  
  
  
  )

(def cli-options
  [["-p" "--port PORT" "port number" :default :unspecified :parse-fn #(Integer/parseInt %) :id :port]
   ["-s" nil "encrypted" :default false :id :encrypted]])

(defn -main [& args]
  (def opts (parse-opts args cli-options))
  (assert (= 2 (count (:arguments opts))))
  
  (prn {:hostname (-> opts :arguments first)
        :username (-> opts :arguments second)
        :encrypted (-> opts :options :encrypted)
        :port (let [port (-> opts :options :port)
                    encrypted (-> opts :options :encrypted)]
                (cond
                  (and encrypted (= port :unspecified)) 27994
                  (= port :unspecified) 27993
                  :else port))}))
