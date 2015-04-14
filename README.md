# Basic Signaling Service

A Clojure library designed to provide a basic signaling server for use
over web sockets, to facilitate RTCPeerConnection. Really it exposes a 
generic pub-sub messaging system via core.async channels, then optionally
pulls in the chord library to attach that to a web socket implementation.

## Usage

To attach to web server (http-kit):

````
(start-server (-> #'bss.signaling/signaling-channel) {:port 3000})
````

Of course, that would just be the web service channel, you'd want to use
something like compojure for routing to serve html etc too.

If you don't care about websockets and just want the pub/sub messaging
system, for now take a look at the unit tests, that's what they do.

## License

Copyright © 2015 Jason Andorfer

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
