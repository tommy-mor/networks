(ns networks.core
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [clojure.data.json :as json]
            [gloss.core :as gloss]
            [gloss.io :as io]
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

(defn wordle [{:keys [hostname username encrypted port]}] 
  (def words (clojure.string/split (slurp (clojure.java.io/resource "project1-words.txt")) #"\r\n"))
  (def protocol (gloss/compile-frame (gloss/string :utf-8 :delimiters ["\n"])))
  (def c @(tcp/client (cond-> {:host hostname :port port}
                        encrypted (assoc :ssl? true :insecure? true))))
  (def read-stream (io/decode-stream c protocol))
  
  (defn request [body]
    @(s/put! c (str (json/write-str body) "\n"))
    (let [x @(s/take! read-stream)
          r (json/read-str x :key-fn keyword)]
      r))

  (def session-id (:id (request {:type "hello" :northeastern_username username})))

  (def starting-word (rand-nth words))

  (defn guess [g]
    (into {}
          (map (fn [idx c score] (case score
                                   0 nil
                                   1 nil
                                   2 [idx c])) (range) (:word g) (:marks g))))
  (defn calc-response [guesses]
    (let [positions (apply merge (map guess guesses))
          regex (re-pattern (apply str (for [i (range 5)]
                                         (get positions i "."))))]
      (rand-nth (filter #(re-find regex %) words))))
  

  (def final (loop [resp (request {:type "guess" :id session-id :word starting-word})]
               (if (= "retry" (:type resp))
                 (recur
                  (request {:type "guess" :id session-id :word (calc-response (:guesses resp))}))
                 resp)))
  
  (println (:flag final)))

(def cli-options
  [["-p" "--port PORT" "port number" :default :unspecified :parse-fn #(Integer/parseInt %) :id :port]
   ["-s" nil "encrypted" :default false :id :encrypted]])

(defn -main [& args]
  (def opts (parse-opts args cli-options))
  (wordle {:hostname (-> opts :arguments first)
           :username (-> opts :arguments second)
           :encrypted (-> opts :options :encrypted)
           :port (let [port (-> opts :options :port)
                       encrypted (-> opts :options :encrypted)]
                   (cond
                     (and encrypted (= port :unspecified)) 27994
                     (= port :unspecified) 27993
                     :else port))}))
