package roemer.rebalancing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import roemer.revive.LpSolveDemo

@ExperimentalCoroutinesApi
fun main(args: Array<String>) {
    // println("Program will start in five seconds")
    // Thread.sleep(10000)
    println("Program has started")

    val graph = GraphHolder("difficult_graph.txt", RebalancerTypes.Revive)
    //val graph = GraphHolder("nodes_05-05-2021.json", "channels_05-05-2021.json")
    graph.start()
    // LpSolveDemo().demo()
}
