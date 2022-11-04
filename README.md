all of the code is in src/networks/core.clj

problem i faced:
halfway through implementing, I realized I was doing it wrong.
I was keeping track of timers for each packet individually, and, keeping the whole file in memory.
Then I realized that that was potentially an advantage, so I started researching other formats besides TCP. I spent a lot of time read QUIC RFCs.

But that was too complicated, and I spent ~10 hours working on code that I did not end up using at all.
I was not able to save the code before the deadline to salvage myself.

But the assignment was fun, really enjoyed the emulator making it easy, and balancing different tradeoffs trying to design a new protocol.

The hardest part (with my QUIC-like scheme was getting the window right..)
