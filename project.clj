(defproject networks "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/data.json "2.4.0"]
                 [org.clj-commons/gloss "0.3.0"]
                 [org.clojure/tools.cli "1.0.206"]
                 [org.clojure/core.match "1.0.0"] ]
  
  :repl-options {:init-ns networks.core}

  :uberjar-name "networks.jar"
  :profiles {:uberjar {:aot :all} 
             :dev {:plugins [[lein-shell "0.5.0"]]}}


  :main networks.core
  
  :aliases
  {"native"
   ["shell"
    "native-image" "--report-unsupported-elements-at-runtime" "--no-server" "--no-fallback"
    "--static" "--libc=musl"
    "-H:+ReportExceptionStackTraces"
    "--initialize-at-build-time"
    "--allow-incomplete-classpath"
    "-jar" "./target/${:uberjar-name:-${:name}-${:version}-standalone.jar}"
    "-H:Name=./target/${:name}"]})


