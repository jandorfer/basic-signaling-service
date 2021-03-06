(ns bss.signaling
  (:require [chord.http-kit :refer [with-channel]]
            [clojure.core.async :refer [<! >! put! close! go go-loop]]
            [clojure.tools.logging :as log]
            [clojure.core.async :as a]))

; All connected clients => #{subscribed topics}
(def clients (atom {}))

; The publishing system for message exchange
(def msg-publisher (a/chan))
(def general-messages (a/pub msg-publisher :topic (fn [_] (a/sliding-buffer 1))))

; General events (connect/disconnect)
(def event-publisher (a/chan))
(def events (a/pub event-publisher :event (fn [_] (a/sliding-buffer 1))))

(defn trigger-event!
  [event]
  (go (a/>! event-publisher event)))

(defn on
  [event-id handler-chan]
  (a/sub events event-id handler-chan))

(defn publish!
  [topic message]
  (let [message (if (map? message) message {:data message})]
    (go (a/>! msg-publisher
              (merge message {:topic topic})))))

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
      :unsub (unsubscribe! topic client)
      (do (log/warn "failed to parse message" msg)
          (go (a/>! client {:error "Unrecognized message" :msg msg}))))))

(defn- handle-connect!
  [client]
  (swap! clients assoc client #{})
  (trigger-event! {:event :connect :client client})
  (subscribe! "" client))

(defn- handle-disconnect!
  [client]
  (let [subs (get @clients client)]
    (doseq [sub subs]
      (unsubscribe! sub client))
    (swap! clients dissoc client)
    (trigger-event! {:event :disconnect :client client})))

(defn handle-client
  "Handles a given bidirectional channel as a client to the signaling server.
  The channel will be recorded as connected and so receive messages from other
  clients as appropriate. Any messages received from this client will be parsed
  and acted on."
  [client & {:keys [read-fn]}]
  (handle-connect! client)
  (go-loop []
    ;; read from the new channel until 'nil' (close)
    (let [message (<! client)]
      (if message
        (do (process-message client ((or read-fn identity) message)) (recur))
        (handle-disconnect! client)))))

(defn parse-chord-message
  "The chord library serialization wraps all data in a :message key, which we
  need to unwrap. It also will use strings as keys in the map, which for
  convenience we'll parse to keywords."
  [msg]
  (-> msg
      :message
      clojure.walk/keywordize-keys
      (update-in [:type] #(keyword (or % "unspecified")))))

(defn signaling-channel
  "Ring-compatible requent handling method which defines a web socket service.
  The service will act as a basic signaling server, keeping track of connected
  clients and exchanging events between them."
  [request]
  (with-channel request client-channel {:format :json}
    (handle-client client-channel :read-fn parse-chord-message)))