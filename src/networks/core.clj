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
  (str "https://" server ":" port "/fakebook/"))

(defn login-page [{:keys [port server]}]
  (str "https://" server ":" port "/accounts/login/?next=/fakebook/"))

(def socket (atom nil))

(defn connect [{:keys [port server] :as opts}]
  (when-not @socket
    (let [factory (SSLSocketFactory/getDefault)
          s (.createSocket factory ^String server ^int port)]
      (println "new socket")
      (.startHandshake ^SSLSocket s)
      (println "handshake")
      (reset! socket {:socket s
                      :in (BufferedReader. (InputStreamReader. (.getInputStream s)))
                      :out (PrintWriter. (.getOutputStream s))}))))

(defn REQ [opts {:keys [method url headers body]}]
  (connect opts)
  (try
    (let [{:keys [socket in out]} @socket
          {:keys [port server]} opts
          start (now)]
      (println (blue (str method url)))
      (.println out (str method " " url " HTTP/1.1"))
      (.println out (str "Host: " server))
      (.println out "Connection: close")
      (.println out "")
      (.flush out)

      (let [status (-> in .readLine)
            headers (->> (repeatedly #(-> in .readLine))
                         (take-while #(not (empty? %)))
                         (map #(clojure.string/split % #": *" 2))
                         (into {}))
            body (slurp in)]
        {:status status
         :headers headers
         :body body
         :time (- (now) start)}))
    
    (finally
      (when @socket
        (.close (:socket @socket))
        (reset! socket nil)))))


(defn crawl [{:keys [arguments] {:keys [port server] :as opts} :options}]
  (let [[username password] (filter (complement empty?) arguments)]
    (prn (REQ opts {:method "GET"
                    :url (login-page opts)
                    :headers {"Host" server
                              "Connection" "close"}}))))

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

