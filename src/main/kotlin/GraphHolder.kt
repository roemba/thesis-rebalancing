package roemer.rebalancingGroups

import java.io.File
import java.util.*
import kotlin.collections.ArrayList

import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.*;

class GraphHolder(fileName: String) {

    val channels: MutableList<Channel> = ArrayList()
    private val g = ChannelNetwork()

    init {
        val resourceName = this::class.java.classLoader.getResource(fileName)!!.file
        val graphFileReader = Scanner(File(resourceName))
        val nOfNodes = graphFileReader.nextInt()
        graphFileReader.nextLine()

        val nodes: Array<Node?> = arrayOfNulls(nOfNodes)

        for (i in 0 until nOfNodes) {
            nodes[i] = g.addVertex()
        }

        val edgePattern = "\\d-\\d"
        while (graphFileReader.hasNext(edgePattern)) {
            val edgeString = graphFileReader.next(edgePattern)
            val nodeIds = edgeString.split("-").map { it.toInt() }

            g.addChannel(nodes[nodeIds[0]]!!, nodes[nodeIds[1]]!!, 5, 5)
        }

        nodes[4]!!.startPayment(2, nodes[1]!!)
        //startPayment(2, nodes[4]!!, nodes[1]!!)
//        startPayment(2, nodes[4]!!, nodes[1]!!)
//        startPayment(2, nodes[4]!!, nodes[1]!!)
    }

    fun startPayment(amount: Int, sender: Node, receiver: Node) {
        val paymentId: UUID = UUID.randomUUID()
        val shortestPathDijkstra: DijkstraShortestPath<Node, DefaultWeightedEdge> = DijkstraShortestPath(g)

        val path = shortestPathDijkstra.getPath(sender, receiver)
        println("Starting tx, amount: $amount, path: ${path.edgeList}")

        println("Requesting commits")
        data class ChannelTransaction(val cha: Channel, val tx: Transaction) {}

        val committedChannels = ArrayList<ChannelTransaction>()
        try {
            for (i in 0 until path.vertexList.size) {
                if (path.vertexList[i] === path.endVertex) {
                    break
                }
                val currentNode = path.vertexList[i]
                val nextNode = path.vertexList[i+1]
                val tx = Transaction(paymentId, amount, currentNode, nextNode)

                val cha = g.getChannel(currentNode, nextNode)
                if (!cha.requestTx(tx)) {
                    throw TransactionAbortedException("Not enough balance in $cha!")
                }
                committedChannels.add(ChannelTransaction(cha, tx))
            }
        } catch (e: TransactionAbortedException) {
            println("Aborting transaction")
            for (chaTx in committedChannels.reversed()) {
                chaTx.cha.abortTx(chaTx.tx)
            }
            throw e
        }

        println("Executing transactions")
        for (chaTx in committedChannels.reversed()) {
            chaTx.cha.executeTx(chaTx.tx)
        }





    }

}
