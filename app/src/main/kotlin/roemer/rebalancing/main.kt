package roemer.rebalancing

import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
fun main(args: Array<String>) {
    // println("Program will start in five seconds")
    // Thread.sleep(10000)
    println("Program has started")
//    val n1 = Node()
//    val n2 = Node()
//    println(n1.id)
//
//    val c = Channel(n1, n2, 100, 50)

//    println(c)
    val graph = GraphHolder("difficult_graph.txt")
    //val graph = GraphHolder("nodes_05-05-2021.json", "channels_05-05-2021.json")
    graph.start()
}
