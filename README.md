all of the code is in src/networks/core.clj


high level approach:
connect/login to the server, then start sending 5 concurrent keep-alive connections gzipped.
then read response (headers and body) using regexes. keep track of the horizon using atoms (thread safe clojure thing) on line 131.

problem i faced:
it was really tricky to get the gizpped responses to work. mostly becuase (it took me a while to figure out) that I couldn't use bufferedreader on the tcp stream. this java object gives you readline, but has to buffer the input.
so theres a chance that it will eat some of your gzip data in its buffer before the gizpinputstreamreader can read it. So I had to just use the raw inputstream, and write my own readline (line 88).

Next challenge was concurrency, but that was made pretty easy by clojure.core.async. Open 5 tcp connections, put them on a channel. Whenever there is a free connection, grab it and run a pending horizon request on it.

How I tested my code:
I ran it interactively in the repl, and when something was wrong I inspected a value (put in an inline def, like on line 107, 113, 123), and could then inspect/manipulate that value in the repl.
