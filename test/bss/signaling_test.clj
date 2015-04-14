(ns bss.signaling_test
  (:require [bss.signaling :as s]
            [chord.channels :refer [bidi-ch]]
            [clojure.core.async :as a]
            [midje.sweet :refer :all]))

(facts "single client connection"
  (let [client-send (a/chan)
        client-recv (a/chan)
        client (bidi-ch client-send client-recv)
        cnt (count @s/clients)]

    (fact "connection works"
      (s/handle-client client)
      (count @s/clients) => (+ 1 cnt))

    (fact "basic pub-sub for topic"
      (a/>!! client-send {:type :sub :topic "test"})
      (a/>!! client-send {:type :pub :topic "test" :message "howdy"})
      (a/<!! client-recv) => "howdy")

    (fact "closing connection disconnects client"
      (a/close! client)
      (a/<!! client-recv) => nil
      (a/<!! client-send) => nil
      (count @s/clients) => cnt)))