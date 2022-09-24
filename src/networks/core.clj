(ns networks.core
  (:require [aleph.tcp :as tcp]
            [manifold.stream :as s]
            [clojure.data.json :as json]
            [gloss.core :as gloss]
            [gloss.io :as io]
            [clojure.tools.cli :refer [parse-opts]])
  (:gen-class))

(def url "ftp://ftp.3700.network/")

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
