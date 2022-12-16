all of the code is in src/networks/core.clj

High level approach:
=======

I have a fairly normal rpc system that allows the nodes to talk to eachother.
In the main method, on line ~480, it starts off several threads which
  1. read all messages from the socket as fast as possible, and put them into the appropriate queues(line 457).
  2. (line 493) wait for appropriate conditions to heartbeat, then do that.
  3. (line 504) If I have an rpc request pending, then answer that.
  4. (line 523) If I have a client request (put/get), answer that.

As data progresses through the system, it ends up in various important atoms.
  1. (line 171). whenever the leader-state changes (the nextIndex[] and matchIndex[] maps), then run a check on that to see if we should advance the commit index.
  2. (line 191) when commit index changes, fold over the difference in log entries, and update last-applied.

Rpc responses are in the respond-rpc multimethod. 

Challenges faced
===
Problem I had: I was slurping in too many requests. One of my threads was constantly slurping the port, so I would take as many requests as it would give me. Often, I wouldn't be able to service all of them because the rest of the system worked slower than I could absorb new requests. But I couldn't stop taking things in from the port, because I needed all the RPC messages as I could get. There was no backpressure anywhere in the system, so things became unweildly very quickly.

I really did not like how I handled retrying. How I had it as is, there are lots of concurrent outdated requests that were retrying, which muddied everything a whole lot. I tried to implement a "superceded" sort of thing, but that failed. If I had enough time I would rewrite that system, give every other replica its own queue, only run one rpc at a time in paralell, and short circuit/clean the queue of any retries when I have a request that supercedes it. 

I had a lot of race conditions and weirdnesses to do with concurrent processes. Turns out that its easy to bite yourself in the foot when process are so cheap (ilysm go macro..). I want to get better at that though, and this was a fun step in that direction.

Another problem I had was that when leaders changed, the new leader would issue its heartbeat, but by then there were several requests already being serviced, old rpcs from last term, and some other stuff probably, in flight. It was very confusing, and I could not descipher what was happening. And then for some reason, someone always restarted the election, even though there were plenty of heartbeats being sent... So things got out of hand very quickly when the leader dies. Which is the whole point of this algorithm so I feel like I failed at that.


What I think is good
======
I like my system for dealing with leader-state, commit-index, and the log/state-machine. that part used mostly pure functions and worked pretty well.


How I tested my code
=======
I ran the full stack tests, and looked at the log.

When I needed to see if a singular bit of code worked, I would spit example data to a file, then put it in my emacs buffer as a data literal. Then I would play with a bit of code until I decided it worked well enough.