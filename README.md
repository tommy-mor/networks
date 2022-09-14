high level approach: use the manifold clojure stream library (and its tcp client).

the wordle strategy just takes the wordlist and filters it based first on a regex built up from the correct letters (core.clj:43).
it does not use the half-correct guesses to filter. Then it choses a random word in the remaining list.

challenges faced were getting the exact correct amount of bytes from the stream. To do this I used the library [gloss](https://troywest.com/2013/10/22/by-example-gloss.html), which allows for describing a byte protocol declaratively.

this let me describe the byte protocol as utf-8 string, null-terminated by a newline. (core.clj:12)
