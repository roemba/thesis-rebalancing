package roemer.rebalancing

import roemer.revive.LpSolveDemo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers

fun main() {
    println("Program has started")

    // val tree1 = MerkleTree(listOf("test11", "test2", "test3", "test4", "test5"))
    // val tree2 = MerkleTree(listOf("test11", "test2", "test3", "test4", "test5"))
    // val tree1Dig = tree1.digest()
    // val tree2Dig = tree2.digest()

    // assert(tree1Dig contentEquals tree2Dig)

    val statistics = Statistics()
    for (i in 0 until 1000) {
        val counter = Counter()
        val graph = GraphHolder.createGraphHolderFromTxtGraph("difficult_graph.txt", NodeTypes.CoinWasher, SeededRandom(i), counter)

        val algoSettings = mapOf("hopCount" to 3, "maxNumberOfInvites" to 5, "percentageOfLeaders" to 0.0001F)
        graph.start(algoSettings, false, "coinwasher")
        statistics.process(counter)
    }

    statistics.printStatistics()
    
    //val graph = GraphHolder("nodes_05-05-2021.json", "channels_05-05-2021.json", NodeTypes.CoinWasher)

    
    return

    val trials = arrayOf(
        "no_rebalancing", 
        "coinwasher", 
        "revive"
    )
    runBlocking {
        for (trial in trials) {
            for (seed in 0 until 100) {
                launch(Dispatchers.Default) { 
                    println("Starting $trial")
                    runTrial(trial, seed) 
                }
            }

        }
    }

    // LpSolveDemo().demo()
}

suspend fun runTrial(trial: String, seed: Int) {
    val nodeType = when (trial) {
        "no_rebalancing" -> NodeTypes.ParticipantDisc
        "coinwasher" -> NodeTypes.CoinWasher
        "revive" -> NodeTypes.Revive
        else -> throw IllegalArgumentException("Trial name should match to a NodeType!")
    }

    val graph = GraphHolder.createGraphHolderFromLightningTopology("nodes_05-05-2021.json", "channels_05-05-2021.json", nodeType, SeededRandom(seed), Counter())

    val algoSettings = mapOf("hopCount" to 3, "maxNumberOfInvites" to 5, "percentageOfLeaders" to 0.5F)
    graph.start(algoSettings, true, trial)
}
