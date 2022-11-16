(ns networks.core
  (:require
   [clojure.tools.cli :refer [parse-opts]]
   [clj-commons.byte-streams :refer [to-byte-arrays convert]]
   [clojure.term.colors :refer [blue on-red]]
   [clojure.set]
   [clojure.java.io :as io]
   [clojure.core.async :refer [go] :as async])
  (:import (javax.net.ssl SSLSocket SSLSocketFactory)
           (java.io PrintWriter InputStreamReader)
           (java.util.zip GZIPInputStream))
  (:gen-class))

(set! *warn-on-reflection* false)

(defn now []
  (inst-ms (java.time.Instant/now)))

(defn log [thing]
  (spit "log.edn"
        (str (pr-str thing)
             "\n\n") :append true))

(defn log-body [body]
  (spit "body.txt"
        (str body
             "\n\n") :append true))

(defn loge [& e]
  (binding [*out* *err*]
    (apply println e)))

(defn root-page [{:keys [port server]}]
  (str "https://" server ":" port "/fakebook/"))

(defn login-page [{:keys [port server]}]
  (str "https://" server ":" port "/accounts/login/"))

(defn connect [{:keys [port server] :as opts}]
  (let [factory (SSLSocketFactory/getDefault)
        s (.createSocket factory ^String server ^int port)]
    (.startHandshake ^SSLSocket s)
    (println "completed handshake")
    {:socket s
     :in (.getInputStream s)
     :stringreader ()
     :out (PrintWriter. (.getOutputStream s))}))

(def cookies (atom {}))

(defn read-cookies [headers]
  (swap! cookies merge (->> (get headers "Set-Cookie")
                            (map #(clojure.string/split % #"[=;] *" 3))
                            (map #(take 2 %))
                            (map vec)
                            (into {}))))

(defn char-seq 
  [rdr]
  (let [chr (.read rdr)]
    (if (>= chr 0)
      (cons chr (lazy-seq (char-seq rdr))))))

(defn REQ [socket opts {:keys [method url headers body]}]
  (let [{:keys [socket in out]} socket
        {:keys [port server]} opts
        start (now)]
    (println (blue (str method " " url)))
    (.println out (str method " " url " HTTP/1.1"))
    (.println out (str "Host: " server))
    (.println out "Connection: Keep-Alive")
    (.println out "Accept-Encoding: gzip")
    
    (when (not-empty body)
      (.println out (str "Content-Length: " (count body))))
    
    (when headers
      (doseq [[k v] headers]
        (.println out (str k ": " v))))
    
    (.println out (str "cookie: "
                       (clojure.string/join "; "
                                            (map #(clojure.string/join "=" %)
                                                 @cookies))))
    (.println out "")

    (when (not-empty body)
      (println "body:" body)
      (.println out body))
    
    (.println out "")
    
    (.flush out)
    
    (let [readline (fn [in] (->> (repeatedly (fn []
                                               (.read in)))
                                 (filter #(not= % -1))
                                 (take-while #(not= (char %) \newline))
                                 (map char)
                                 (drop-last)
                                 clojure.string/join) )
          reader in
          status (-> reader readline)
          remember (fn [coll]
                     (def coll coll)
                     coll)
          headers (->> (repeatedly #(-> reader readline))
                       (take-while #(not (empty? %)))
                       remember
                       (map #(clojure.string/split % #": *" 2))
                       (group-by first)
                       (map (fn [[k v]] [k (map second v)]))
                       (into {}))
          
          content-length (Integer/parseInt (first (get headers "Content-Length" "0")))
          
          encoding (first (get headers "Content-Encoding"))
          body (if (not (zero? content-length))
                 (let [byts (take content-length (repeatedly #(.read in)))]
                   (slurp (GZIPInputStream. (io/input-stream (byte-array byts))))) 
                 "")]
      (read-cookies headers)
      {:url url
       :status status
       :headers headers
       :body body
       :time (- (now) start)}))) 

(def horizon (atom #{}))
(def visited (atom #{}))
(def active (atom #{}))
(def flags (atom #{}))

(defn REQ-follow [socket opts args]
  (let [resp (REQ socket opts args)]
    (cond
      (clojure.string/includes? (:status resp) "302")
      (let [location (first (get-in resp [:headers "Location"]))]
        (println (blue "302") location)
        (REQ socket opts {:method "GET"
                          :url location}))
      
      (clojure.string/includes? (:status resp) "403")
      (println (on-red "403 forbidden" (:url resp)))
      
      (clojure.string/includes? (:status resp) "503")
      (REQ-follow socket opts args)
      
      
      (clojure.string/includes? (:status resp) "200")
      resp
      
      (clojure.string/includes? (:status resp) "404")
      (println (on-red "404 not found" (:url resp)))

      true
      (throw (ex-info "unknown status" {:resp resp})))))


(defn extract-csrf [body]
  (->> (re-seq #"name=\"csrfmiddlewaretoken\" value=\"([^\"]+)\"" body)
       (map second)
       first))

(defn find-secret-flag [body]
  (->> (re-seq #"FLAG: ([^\"]+)</h2>" body)
       (map second)
       first))


(defn visit [socket opts url]
  (try
    (when (clojure.string/includes? url "logout")
      (throw (ex-info "logout, skipping" {})))
    (let [resp (REQ-follow socket opts {:method "GET"
                                        :url url})
          body (:body resp)
          flag (find-secret-flag body)
          links (->> (re-seq #"href=\"([^\"]+)\"" body)
                     (map second)
                     (filter #(clojure.string/starts-with? % "/"))
                     (map #(str "https://" (:server opts) ":" (:port opts) %))
                     (filter #(not (contains? @visited %)))
                     (filter #(not (contains? @horizon %))))]
      (swap! horizon clojure.set/union (set links))
      (when flag
        (println (blue "OMG FOUND FLAG" flag))
        (swap! flags conj flag))
      (log-body (:body resp))
      {:url url
       :links links
       :resp resp})
    (catch Exception e
      (println (on-red "ERROR" url))
      (log {:url url
            :error (.getMessage e)}))
    (finally
      (swap! visited conj url)
      ))
  socket)

(defn crawl [{:keys [arguments] {:keys [port server] :as opts} :options}]
  (let [[username password] (filter (complement empty?) arguments)]
    (let [socket (connect opts)
          csrf-token (extract-csrf (:body (REQ socket opts {:method "GET"
                                                            :url (login-page opts)})))
          login (REQ-follow
                 socket
                 opts
                 {:method "POST"
                  :url (login-page opts)
                  :headers {"Content-Type" "application/x-www-form-urlencoded"}
                  :body (str "username=" username
                             "&password=" password
                             "&csrfmiddlewaretoken=" csrf-token
                             "&next=/fakebook/")})
          free-connections (async/chan 100)
          ]
      (swap! horizon conj (:url login))
      (spit "body.txt" "" :append false)
      
      (async/>!! free-connections socket)

      (dotimes [i 3]
        (async/>!! free-connections (connect opts)))
      
      (while (not (empty? (clojure.set/difference @horizon @visited)))
        (if-let [target (first (clojure.set/difference @horizon @visited @active))]
          (do (swap! active conj target)
              (go
                (let [socket (async/<! free-connections)]
                  
                  (visit socket opts target)
                  
                  (async/>! free-connections socket)))))))))

(def cli-options
  [["-p" "--port PORT" "port number"
    :default 443
    :parse-fn #(Integer/parseInt %)]
   ["-s" "--server SERVER" "server"
    :default "proj5.3700.network"]])

(defn -main [& args]
  (crawl (parse-opts args cli-options)))

(defn main-default []
  (println "starting!!")
  (crawl {:options {:port 443, :server "proj5.3700.network"}
          :arguments ["morriss.t" "001485200" "" "" "" "" "" ""]}))

(comment
  (main-default))

