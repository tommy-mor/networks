(defproject networks "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [aleph "0.5.0"]
                 [org.clojure/data.json "2.4.0"]
                 [org.clj-commons/gloss "0.3.0"]
                 [org.clojure/tools.cli "1.0.206"]
                 [org.clojure/core.match "1.0.0"]
                 ]
  
  :repl-options {:init-ns networks.core}

  :uberjar-name "networks.jar"
  :profiles {:uberjar {:aot :all}
             :dev {:plugins [[lein-shell "0.5.0"]]}}


  :main networks.core
  :aliases
  {"native"
   ["shell"
    "native-image" "--report-unsupported-elements-at-runtime" "--no-server" "--no-fallback"
    "-H:+ReportExceptionStackTraces"
    "--diagnostics-mode"


    "--initialize-at-build-time"

    "--initialize-at-run-time=io.netty.channel.epoll.Epoll,io.netty.channel.epoll.EpollEventArray,io.netty.channel.unix.Errors,io.netty.channel.unix.IovArray,io.netty.channel.unix.Socket,io.netty.channel.epoll.Native,io.netty.channel.epoll.EpollEventLoop,io.netty.util.internal.logging.Log4JLogger,io.netty.channel.unix.Limits,io.netty.util.AbstractReferenceCounted"
    "--initialize-at-run-time=io.netty.channel.epoll.Epoll
    --initialize-at-run-time=io.netty.channel.epoll.Native
    --initialize-at-run-time=io.netty.channel.epoll.EpollEventLoop
    --initialize-at-run-time=io.netty.channel.epoll.EpollEventArray
  --initialize-at-run-time=io.netty.channel.DefaultFileRegion
  --initialize-at-run-time=io.netty.channel.kqueue.KQueueEventArray
  --initialize-at-run-time=io.netty.channel.kqueue.KQueueEventLoop
  --initialize-at-run-time=io.netty.channel.kqueue.Native
  --initialize-at-run-time=io.netty.channel.unix.Errors
  --initialize-at-run-time=io.netty.channel.unix.IovArray
  --initialize-at-run-time=io.netty.channel.unix.Limits
  --initialize-at-run-time=io.netty.channel.kqueue.KQueue
  --initialize-at-run-time=io.netty.util.internal.logging.Log4JLogger"
    "--allow-incomplete-classpath"
    "-jar" "./target/${:uberjar-name:-${:name}-${:version}-standalone.jar}"
    "-H:Name=./target/${:name}"]

   "run-native" ["shell" "./target/${:name}"]})
