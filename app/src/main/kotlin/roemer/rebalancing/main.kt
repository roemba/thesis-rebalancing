package roemer.rebalancing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import roemer.revive.LpSolveDemo

@ExperimentalCoroutinesApi
fun main(args: Array<String>) {
    println("Program has started")

    //val graph = GraphHolder("difficult_graph.txt", NodeTypes.CoinWasher)
    val graph = GraphHolder("nodes_05-05-2021.json", "channels_05-05-2021.json", NodeTypes.CoinWasher)

    val algoSettings = mapOf("hopCount" to 3, "maxNumberOfInvites" to 20, "percentageOfLeaders" to 0.1F)
    graph.start(algoSettings)
    // LpSolveDemo().demo()
}
