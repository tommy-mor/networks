the plumbing code is in src/networks/core.clj, and the code that responds to all messages is in src/networks/table.clj

high level approach was to paste the requirements of each function into the function before i wrote it, then replace prose with code slowly.
then i would run the tests, and when things failed i would copy the inputs to functions (that i logged),
and insert them as tests (which are in test/networks/*-test.clj), and fix them to follow the correct properties.

the hardest part of this assignment was the plumbing, because at first i had a multithreaded model, and it would read many messages
before responding to the first one. this would work in real life, but the simulator got confused.

the next most confusing part was the aggregation. I was confused what "numerically adjacent" meant, which was never specified.
stil not sure if my definition is correct. I enjoyed writing the code to find the best netmask for an aggregation, it made me feel clever.
