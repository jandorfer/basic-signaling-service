(ns bss.signaling_test
  (:require [bss.signaling :as s]
            [chord.channels :refer [bidi-ch]]
            [clojure.core.async :as a]
            [midje.sweet :refer :all]))

(defn- create-event-listener
  "Route connect/disconnect events to a channel we can watch to verify firing"
  []
  (let [events (a/chan)]
    (s/on :connect events)
    (s/on :disconnect events)
    events))

(a/unsub-all s/events)
(def events (create-event-listener))

(defn- create-client []
  (let [send (a/chan)
        recv (a/chan)
        bidi (bidi-ch send recv)]
    {:send send :receive recv :bidi bidi}))

(defmacro <!!-t
  "A wrapper around the <!! method which adds a timeout. If the timeout expires
  before anything is read from the given channel, :timeout is returned,
  otherwise the value returned from the given channel is returned."
  [chan & {:keys [timeout] :or {timeout 1000}}]
  `(let [~'t (a/timeout ~timeout)
         ~'r (a/alts!! [~chan ~'t])]
     (if (= ~'t (second ~'r)) :timeout (first ~'r))))

(background
  ;; flush event queue before each check
  (before :facts (while (not (= (<!!-t events :timeout 250) :timeout)))))

(facts "chord parsing check"
  (fact "valid message"
    (s/parse-chord-message {:message {"type" "sub" "topic" "abd def"}})
         => {:type :sub :topic "abd def"})
  (fact "malformed message"
    (s/parse-chord-message {:message {"random" "data"}})
         => {:type :unspecified :random "data"})
  (fact "nil message"
    (s/parse-chord-message {:message nil})
         => {:type :unspecified})
  (fact "nil everything"
    (s/parse-chord-message nil)
         => {:type :unspecified}))

(facts "custom read-fn is applied"
  (let [{client :bidi, :keys [send receive]} (create-client)]
    (s/handle-client client :read-fn :wrapper)
    (a/>!! send {:wrapper {:type :pub :topic "" :message {:data "howdy"}}})
    (:data (<!!-t receive)) => "howdy"
    (a/close! client)))

(facts "single client connection"
  (let [{client :bidi, :keys [send receive]} (create-client)]

    (fact "connection works"
      (s/handle-client client)
      (<!!-t events) => {:event :connect :client client}
      (count @s/clients) => 1)

    (fact "send message to self (topic pub-sub)"
      (a/>!! send {:type :sub :topic "test"})
      (a/>!! send {:type :pub :topic "test" :message {:data "qwerty"}})
      (:data (<!!-t receive)) => "qwerty")

    (fact "default channel is also available"
      (a/>!! send {:type :pub :topic "" :message {:data "howdy"}})
      (:data (<!!-t receive)) => "howdy")

    (fact "shorthand message sending"
      (a/>!! send {:type :pub :topic "" :message "not in a map!"})
      (:data (<!!-t receive)) => "not in a map!")

    (fact "everybody makes mistakes (bad message format)"
      (a/>!! send {:oops "my message!"})
      (<!!-t receive) => {:error "Unrecognized message" :msg {:oops "my message!"}})

    (fact "unsubscribing is polite (and should stop the flow of messages)"
      (a/>!! send {:type :unsub :topic "test"})
      (<!!-t receive) => :timeout)

    (fact "closing connection disconnects client"
      (a/close! client)
      (<!!-t events) => {:event :disconnect :client client}
      (count @s/clients) => 0)))

(facts "two clients"
  (let [{client-a :bidi, send-a :send, recv-a :receive} (create-client)
        {client-b :bidi, send-b :send, recv-b :receive} (create-client)]

    (fact "connect two clients"
      (s/handle-client client-a)
      (<!!-t events) => {:event :connect :client client-a}
      (count @s/clients) => 1
      (s/handle-client client-b)
      (<!!-t events) => {:event :connect :client client-b}
      (count @s/clients) => 2)

    (fact "exchange messages between clients"
      ;; All listen on the "test" topic, so we can hear each other
      (a/>!! send-a {:type :sub :topic "test"})
      (a/>!! send-b {:type :sub :topic "test"})

      (fact "first client can send and be received by both listeners"
        (a/>!! send-a {:type :pub :topic "test" :message {:data "qwerty"}})
        (:data (<!!-t recv-a)) => "qwerty"
        (:data (<!!-t recv-b)) => "qwerty")

      (fact "second client can send and be received by both listeners"
        (a/>!! send-b {:type :pub :topic "test" :message {:data "asdafgh"}})
        (:data (<!!-t recv-a)) => "asdafgh"
        (:data (<!!-t recv-b)) => "asdafgh"))

    (fact "disconnecting one client doesn't impact the other client"
      (a/close! client-a)
      (<!!-t events) => {:event :disconnect :client client-a}
      (count @s/clients) => 1

      (a/>!! send-b {:type :pub :topic "test" :message {:data "still ok"}})
      (:data (<!!-t recv-b)) => "still ok"

      (a/close! client-b)
      (<!!-t events) => {:event :disconnect :client client-b}
      (count @s/clients) => 0)))

(facts "many clients"
  ;; Create 100 clients to work with
  (let [subscribed (into [] (take 100 (repeatedly create-client)))]

    ;; Subscribe everyone to the "test" channel
    (doseq [s subscribed]
      (s/handle-client (:bidi s))
      (a/>!! (:send s) {:type :sub :topic "test"}))

    (fact "all 100 clients ready and waiting"
      (count @s/clients) => 100)

    ;; Send a message...
    (a/>!! (:send (first subscribed))
           {:type :pub :topic "test" :message {:data "ping"}})

    ;; ..make sure each subscriber gets it
    (fact "all clients receive the message"
      (doseq [s subscribed]
        (<!!-t (:receive s)) => {:data "ping" :topic "test"}))

    ;; Clean up
    (fact "everything cleans up ok"
      (doseq [{s :bidi} subscribed]
        (a/close! s)
        ;; event order not gauranteed;
        ;; check that any disconnect happened, will ensure we get 100
        (:event (<!!-t events)) => :disconnect)
      (count @s/clients) => 0)))