all of the code is in src/networks/core.clj

problem i faced:
===============

halfway through implementing, I realized I was doing it wrong.
I was keeping track of timers for each packet individually, and, keeping the whole file in memory.
Then I realized that that was potentially an advantage, so I started researching other formats besides TCP. I spent a lot of time read QUIC RFCs.

But that was too complicated, and I spent ~10 hours working on code that I did not end up using at all.
I was not able to save the code before the deadline to salvage myself.

But the assignment was fun, really enjoyed the emulator making it easy, and balancing different tradeoffs trying to design a new protocol.

The hardest part (with my QUIC-like scheme was getting the window right..)

Also, getting graalvm to compile my program was a bit of a headache but not too bad


overall design
=============
I ended up doing something slightly different than tcp, where each individual packet is tracked and ackd.
so there are a lot more acks than in normal tcp. and also the window size jumps very wildly, but it seems to be okay.
both send and receive are funcitons in the same file. both programs are the same except they split at the first CLI argument


things I like about my design
===========================
i like that its different from tcp. I felt clever using the set functions. it makes my thinking clear.
I also liked using the math on line 193, because you can miss multiple packets in that step, so you cant just divide once to keep up the multiplication of every ack.


how I tested the code
============
I just used test.py to run the tests a lot. there are some helper functions that I used and tested briefly, but just one-offs in the repl to prove that that part worked.
