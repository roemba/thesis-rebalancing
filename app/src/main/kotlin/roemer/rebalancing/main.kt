package roemer.rebalancing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import roemer.revive.LpSolveDemo

@ExperimentalCoroutinesApi
fun main(args: Array<String>) {
    println("Program has started")

    // val tree1 = MerkleTree(listOf("test11", "test2", "test3", "test4", "test5"))
    // val tree2 = MerkleTree(listOf("test11", "test2", "test3", "test4", "test5"))
    // val tree1Dig = tree1.digest()
    // val tree2Dig = tree2.digest()

    // assert(tree1Dig contentEquals tree2Dig)

    val graph = GraphHolder("difficult_graph.txt", NodeTypes.Revive)
    //val graph = GraphHolder("nodes_05-05-2021.json", "channels_05-05-2021.json", NodeTypes.CoinWasher)

    val algoSettings = mapOf("hopCount" to 3, "maxNumberOfInvites" to 20, "percentageOfLeaders" to 1F)
    graph.start(algoSettings)
    // LpSolveDemo().demo()
}
