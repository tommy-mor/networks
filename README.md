all of the code is in src/networks/core.clj

problem i faced:
  some commands returned two responses.
    that was tricky to handle, because i was getting the second response of the first command when I wanted the first response of the second command. I got around this by polling in a loop (core.clj:22)

other than that was pretty straightforward.

I tested by running commands in the clojure repl and seeing what they did.
then I made some scripts to test the moving logic and cli arg parsing.
then I uploaded to gradescope.

for parsing the url, i just used java.net.URI. java is epic