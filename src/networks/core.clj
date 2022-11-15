(ns networks.core
  (:require
   [clojure.tools.cli :refer [parse-opts]]
   [clj-commons.byte-streams :refer [to-byte-arrays convert]]
   [clojure.term.colors :refer [blue on-red]])
  (:import (javax.net.ssl SSLSocket SSLSocketFactory)
           (java.io PrintWriter InputStreamReader BufferedReader))
  (:gen-class))

(set! *warn-on-reflection* false)

(defn now []
  (inst-ms (java.time.Instant/now)))

(defn log [thing]
  (spit "log.edn"
        (str (pr-str thing)
             "\n\n") :append true))
(defn loge [& e]
  (binding [*out* *err*]
    (apply println e)))

(defn root-page [{:keys [port server]}]
  (str "https://" server ":" port "/"))

(def socket (atom nil))

(defn connect [{:keys [port server] :as opts}]
  (prn "Connecting to" opts)
  (let [factory (SSLSocketFactory/getDefault)
        s (.createSocket factory ^String server ^int port)]
    (prn "connected")
    (.startHandshake ^SSLSocket s)
    (prn "handshake done")
    (reset! socket {:socket s
                    :in (BufferedReader. (InputStreamReader. (.getInputStream s)))
                    :out (PrintWriter. (.getOutputStream s))})))

(defn http-request [requeststr]
  (when-not @socket (connect))
  
  (let [{:keys [in out]} @socket]
    (doto out
      (.println requeststr)
      (.println "")
      (.flush))
    (prn (slurp in))))


(defn crawl [{:keys [arguments] {:keys [port server] :as opts} :options}]
  (let [[username password] (filter (complement empty?) arguments)]
    ))

(def cli-options
  [["-p" "--port PORT" "port number"
    :default 443
    :parse-fn #(Integer/parseInt %)]
   ["-s" "--server SERVER" "server"
    :default "proj5.3700.network"]])

(defn -main [& args]
  (crawl (parse-opts args cli-options)))

(defn main-default []
  (crawl {:options {:port 443, :server "proj5.3700.network"}
          :arguments ["morriss.t" "001485200" "" "" "" "" "" ""]}))

(comment
  (main-default))

