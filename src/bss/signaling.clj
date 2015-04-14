(ns bss.signaling
  (:require [chord.http-kit :refer [with-channel]]
            [clojure.core.async :refer [<! >! put! close! go go-loop]]
            [clojure.tools.logging :as log]
            [clojure.core.async :as a]))

; All connected clients => #{subscribed topics}
(def clients (atom {}))

; The publishing system for message exchange
(def publisher (a/chan))
(def general-messages (a/pub publisher #(:topic %)))

(defn publish!
  [topic message]
  (go (a/>! general-messages
            (merge message {:topic topic}))))

(defn subscribe!
  [topic subscriber]
  (a/sub general-messages topic subscriber)
  (swap! clients #(merge-with clojure.set/union % {subscriber #{topic}})))

(defn unsubscribe!
  [topic subscriber]
  (a/unsub general-messages topic subscriber)
  (swap! clients #(merge-with disj % {subscriber #{topic}})))

(defn process-message [client msg]
  (let [{:keys [type topic message]} msg]
    (case type
      :pub (publish! topic message)
      :sub (subscribe! topic client)
      :unsub (unsubscribe! topic client))))

(defn- handle-connect!
  [client]
  (swap! clients assoc client #{}))

(defn- handle-disconnect!
  [client]
  (let [subs (get-in @clients client)]
    (swap! clients dissoc client)
    (doseq [sub subs]
      (unsubscribe! sub client))))

(defn handle-client
  "Handles a given bidirectional channel as a client to the signaling server.
  The channel will be recorded as connected and so receive messages from other
  clients as appropriate. Any messages received from this client will be parsed
  and acted on."
  [client]
  (handle-connect! client)
  (go-loop []
    ;; read from the new channel until 'nil' (close)
    (let [message (<! client)]
      (if message
        (do (process-message client message) (recur))
        (handle-disconnect! client)))))

(defn signaling-channel
  "Ring-compatible requent handling method which defines a web socket service.
  The service will act as a basic signaling server, keeping track of connected
  clients and exchanging events between them."
  [request]
  (with-channel request client-channel {:format :json}
    (handle-client client-channel)))