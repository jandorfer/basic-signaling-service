(defproject jandorfer/basic-signaling-service "0.1.0-SNAPSHOT"
  :description "A clojure library for websocket-based message exchange, intended for use for RFCPeerConnection setup signaling."
  :url "https://github.com/jandorfer/basic-signaling-service"
  :license {:name "Eclipse Public License" :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/tools.logging "0.3.1"]
                 [jarohen/chord "0.6.0"]]
  :profiles {
    :dev {:dependencies [[midje "1.6.0" :exclusions [org.clojure/clojure]]]
          :plugins [[lein-midje "3.1.3"]]}})
