(ns networks.core
  (:require
   [clojure.tools.cli :refer [parse-opts]]
   [clj-commons.byte-streams :refer [to-byte-arrays convert]]
   [clojure.term.colors :refer [blue on-red]]
   [clojure.set])
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

(def socket (atom nil))

(defn connect [{:keys [port server] :as opts}]
  (when-not @socket
    (let [factory (SSLSocketFactory/getDefault)
          s (.createSocket factory ^String server ^int port)]
      (.startHandshake ^SSLSocket s)
      (reset! socket {:socket s
                      :in (BufferedReader. (InputStreamReader. (.getInputStream s)))
                      :out (PrintWriter. (.getOutputStream s))}))))

(def cookies (atom {}))

(defn read-cookies [headers]
  (swap! cookies merge (->> (get headers "Set-Cookie")
                            (map #(clojure.string/split % #"[=;] *" 3))
                            (map #(take 2 %))
                            (map vec)
                            (into {}))))

(defn char-seq 
  [^java.io.Reader rdr]
  (let [chr (.read rdr)]
    (if (>= chr 0)
      (cons chr (lazy-seq (char-seq rdr))))))

(defn REQ [opts {:keys [method url headers body]}]
  (connect opts)
  (try
    (let [{:keys [socket in out]} @socket
          {:keys [port server]} opts
          start (now)]
      (println (blue (str method " " url)))
      (.println out (str method " " url " HTTP/1.1"))
      (.println out (str "Host: " server))
      (.println out "Connection: Keep-Alive")
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

      (let [status (-> in .readLine)
            headers (->> (repeatedly #(-> in .readLine))
                         (take-while #(not (empty? %)))
                         (map #(clojure.string/split % #": *" 2))
                         (group-by first)
                         (map (fn [[k v]] [k (map second v)]))
                         (into {}))

            content-length (Integer/parseInt (first (get headers "Content-Length" "0")))

            body (->> (char-seq in)
                      (take content-length)
                      (map char)
                      clojure.string/join)]
        (read-cookies headers)
        {:url url
         :status status
         :headers headers
         :body body
         :time (- (now) start)}))
    
    (finally
      (when @socket
        (.close (:socket @socket))
        (reset! socket nil)))))

(def horizon (atom #{}))
(def visited (atom #{}))
(def flags (atom #{}))

(defn REQ-follow [opts args]
  (let [resp (REQ opts args)]
    (cond
      (clojure.string/includes? (:status resp) "302")
      (let [location (first (get-in resp [:headers "Location"]))]
        (println (blue "302") location)
        (REQ opts {:method "GET"
                   :url location}))
      
      (clojure.string/includes? (:status resp) "403")
      (println (on-red "403 forbidden" (:url resp)))
      
      (clojure.string/includes? (:status resp) "503")
      (REQ-follow opts args)
      
      
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


(defn visit [opts url]
  (swap! visited conj url)
  (try
    (when (clojure.string/includes? url "logout")
      (throw (ex-info "logout, skipping" {})))
    (let [resp (REQ-follow opts {:method "GET"
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
            :error (.getMessage e)}))))

(defn crawl [{:keys [arguments] {:keys [port server] :as opts} :options}]
  (let [[username password] (filter (complement empty?) arguments)]
    (let [csrf-token (extract-csrf (:body (REQ opts {:method "GET"
                                                     :url (login-page opts)})))
          login (REQ-follow opts {:method "POST"
                                  :url (login-page opts)
                                  :headers {"Content-Type" "application/x-www-form-urlencoded"}
                                  :body (str "username=" username
                                             "&password=" password
                                             "&csrfmiddlewaretoken=" csrf-token
                                             "&next=/fakebook/")})]
      (swap! horizon conj (:url login))
      (while (not (empty? (clojure.set/difference @horizon @visited)))
        (visit opts (first (clojure.set/difference @horizon @visited)))))))

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

