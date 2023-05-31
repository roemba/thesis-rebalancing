Network topology downloaded from https://explorer.acinq.co/

## Running instructions

Running requires Gradle and Kotlin to work correctly. `gradle run` is sufficient to get everything executing. For now, to specify different scenario's, please comment/uncomment lines of code in `src/main/kotlin/anon/rebalancing/main.kt`.

### Dependencies

Running is currently only tested under Linux (Ubuntu 21.04) so your mileage may vary. All dependencies except for one are pulled in by Gradle. 

If you wish to test out `ReviveNode` you need `lp_solve` to work correctly. To do this, follow *only* the **2. INSTALLATION** instructions stated [here](http://web.mit.edu/lpsolve/doc/Java/README.html) and make sure to place your library in `/usr/local/lib`. Running `ldconfig` is not necessary as `gradle run` will already set the environment variable for you so it knows to search in `/usr/local/lib`. 
To complete the instructions, you need to download `lp_solve_<version>_dev_<arch>` and `lp_solve_<version>_java` via [SourceForge](https://sourceforge.net/projects/lpsolve/files/lpsolve/) for version `~5.5`.

## Code layout

The graphing model backing everything is based on JGraphT. An instance of this graph is held by `ChannelNetwork` to add `PaymentChannel` functionality to the graph. ChannelNetworks are instantiated via GraphHolder, which (for now) reads in a .txt or .json and runs the experiment.

A graph consists of `Node`s, which hold a `Queue` that receives messages from other nodes. When a `Node` wishes to send a message to another `Node`, it deposits the message in the queue of the other node. The class `ParticipantNodeAlt` (an alternative, working implementation of the original `ParticipantNode` class) extends `Node` to add functionality for the participant discovery. In a similar manner, `HopeNode` and `ReviveNode` extend `ParticipantNodeAlt` to add functionality of their respective algorithms on top of the participant discovery.  

## Logging

In case of large logfiles, pipe the output as follows:

```gradle run > log.txt```

and use `less` to display it using:

```less -R log.txt```

which will instruct `less` to also print the correct terminal colors
