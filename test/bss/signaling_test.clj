(ns bss.signaling_test
  (:require [bss.signaling :as s]
            [chord.channels :refer [bidi-ch]]
            [clojure.core.async :as a]
            [midje.sweet :refer :all]))

(facts "single client connection"
  (let [client-send (a/chan)
        client-recv (a/chan)
        client (bidi-ch client-send client-recv)
        events (a/chan)]

    (s/on :connect events)
    (s/on :disconnect events)

    (fact "connection works"
      (s/handle-client client)
      (a/<!! events) => {:event :connect :client client})

    (fact "basic pub-sub for topic"
      (a/>!! client-send {:type :sub :topic "test"})
      (a/>!! client-send {:type :pub :topic "test" :message {:data "qwerty"}})
      (:data (a/<!! client-recv)) => "qwerty")

    (fact "closing connection disconnects client"
      (a/close! client)
      (a/<!! events) => {:event :disconnect :client client})))