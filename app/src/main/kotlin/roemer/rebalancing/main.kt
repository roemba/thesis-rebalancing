package roemer.rebalancing

import roemer.revive.LpSolveDemo
import com.github.sh0nk.matplotlib4j.Plot

fun main(args: Array<String>) {
    println("Program has started")

    // val tree1 = MerkleTree(listOf("test11", "test2", "test3", "test4", "test5"))
    // val tree2 = MerkleTree(listOf("test11", "test2", "test3", "test4", "test5"))
    // val tree1Dig = tree1.digest()
    // val tree2Dig = tree2.digest()

    // assert(tree1Dig contentEquals tree2Dig)
    val plt = Plot.create()
    plt.plot()
        .add(listOf(1.3, 2))
        .label("label")
        .linestyle("--")
    plt.xlabel("xlabel")
    plt.ylabel("ylabel")
    plt.text(0.5, 0.2, "text")
    plt.title("Title!")
    plt.legend()
    plt.show()

    //val graph = GraphHolder("difficult_graph.txt", NodeTypes.CoinWasher)
    val graph = GraphHolder("nodes_05-05-2021.json", "channels_05-05-2021.json", NodeTypes.CoinWasher)

    val algoSettings = mapOf("hopCount" to 3, "maxNumberOfInvites" to 20, "percentageOfLeaders" to 0.5F)
    graph.start(algoSettings)
    // LpSolveDemo().demo()
}
