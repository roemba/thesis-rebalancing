package roemer.rebalancingGroups

import java.io.File
import java.util.*
import kotlin.collections.ArrayList

import org.jgrapht.*;
import org.jgrapht.graph.*;
import org.jgrapht.traverse.*;

class GraphHolder(fileName: String) {
    val nodes: MutableList<Node> = ArrayList()
    val channels: MutableList<Channel> = ArrayList()
    val g: Graph<Node, DefaultWeightedEdge> = DefaultDirectedGraph(DefaultWeightedEdge::class.java)

    init {
        val resourceName = this::class.java.classLoader.getResource(fileName)!!.file
        val graphFileReader = Scanner(File(resourceName))
        val nOfNodes = graphFileReader.nextInt()
        graphFileReader.nextLine()

        for (i in 0 until nOfNodes) {
            nodes.add(Node(i))
        }

        val edgePattern = "\\d-\\d"
        while (graphFileReader.hasNext(edgePattern)) {
            val edgeString = graphFileReader.next(edgePattern)
            val nodeIds = edgeString.split("-").map { it.toInt() }

            val n1 = this.nodes[nodeIds[0]]
            val n2 = this.nodes[nodeIds[1]]

            val chan = Channel(n1, n2)

            n1.addChannel(chan)
            n2.addChannel(chan)
            channels.add(chan)
        }

        println(nodes)
        println(channels)
        println(nodes[0].neighbours)
    }

    fun startTransaction(amount: Int, sender: Node, receiver: Node) {

    }

}