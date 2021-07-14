package roemer.rebalancing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import roemer.revive.LpSolveDemo

@ExperimentalCoroutinesApi
fun main(args: Array<String>) {
    println("Program has started")

    val graph = GraphHolder("difficult_graph.txt", NodeTypes.CoinWasher)
    //val graph = GraphHolder("nodes_05-05-2021.json", "channels_05-05-2021.json", RebalancerTypes.CoinWasher)
    graph.start(10, 20)
    // LpSolveDemo().demo()
}
