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

    // val graph = GraphHolder("difficult_graph.txt", NodeTypes.Revive)
    // val algoSettings = mapOf("hopCount" to 3, "maxNumberOfInvites" to 5, "percentageOfLeaders" to 0.5F)
    // graph.start(algoSettings, false, "revive")
    
    // return

    val trials = arrayOf(
        "no_rebalancing", 
        "coinwasher", 
        "revive"
    )
    runBlocking {
        for (trial in trials) {
            launch(Dispatchers.Default) { 
                println("Starting $trial")
                runTrial(trial) 
            }
        }
    }

    // LpSolveDemo().demo()
}

suspend fun runTrial(trial: String) {
    val nodeType = when (trial) {
        "no_rebalancing" -> NodeTypes.ParticipantDisc
        "coinwasher" -> NodeTypes.CoinWasher
        "revive" -> NodeTypes.Revive
        else -> throw IllegalArgumentException("Trial name should match to a NodeType!")
    }

    val graph = GraphHolder("nodes_05-05-2021.json", "channels_05-05-2021.json", nodeType)

    val algoSettings = mapOf("hopCount" to 3, "maxNumberOfInvites" to 5, "percentageOfLeaders" to 0.5F)
    graph.start(algoSettings, true, trial)
}
