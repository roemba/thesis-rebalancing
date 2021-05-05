package roemer.rebalancing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import guru.nidi.graphviz.model.Factory.graph as GraphVizGraph
import guru.nidi.graphviz.model.Factory.node as GraphVizNode
import guru.nidi.graphviz.model.Factory.to as GraphTo
import guru.nidi.graphviz.engine.Graphviz
import guru.nidi.graphviz.engine.Format

@ExperimentalCoroutinesApi
class GraphHolder(fileName: String) {

    val paymentChannels: MutableList<PaymentChannel> = ArrayList()
    private val g = ChannelNetwork()

    init {
        val resourceName = this::class.java.classLoader.getResource(fileName)!!.file
        
        txtGraphToGraphvix(resourceName, fileName)

        val graphFileReader = Scanner(File(resourceName))
        val nOfNodes = graphFileReader.nextInt()
        graphFileReader.nextLine()

        val nodes: Array<ParticipantNode?> = arrayOfNulls(nOfNodes)

        runBlocking {
            for (i in 0 until nOfNodes) {
                val n = ParticipantNode(i, g)
                g.graph.addVertex(n)
                nodes[i] = n
                launch { n.receiveMessage() }
            }
            
            val edgePattern = "\\d+-\\d+"
            while (graphFileReader.hasNext(edgePattern)) {
                val edgeString = graphFileReader.next(edgePattern)
                val nodeIds = edgeString.split("-").map { it.toInt() }
                val balances = graphFileReader.next(edgePattern).split("-").map { it.toInt() }

                g.addChannel(nodes[nodeIds[0]]!!, nodes[nodeIds[1]]!!, balances[0], balances[1])
            }
            println("Continued while nodes are waiting")
            // println("\nStarting test payment 1, amount 2\n")
            // printChannelBalances()
            // nodes[0]!!.startPayment(2, nodes[3]!!)

            // //delay(2000)
            // println("\nStarting test payment 2, amount 2\n")
            // //printChannelBalances()
            // nodes[4]!!.startPayment(2, nodes[1]!!)

            // //delay(2000)
            // println("\nStarting test payment 3, amount 2\n")
            // //printChannelBalances()
            // nodes[2]!!.startPayment(2, nodes[4]!!)

            // delay(2000)
            // println("\nStarting test payment 4, amount 2\n")
            // //printChannelBalances()

            nodes[0]!!.startFindingParticipants(20)
            nodes[4]!!.startFindingParticipants(20)

            // try {
            //     nodes[4]!!.startPayment(2, nodes[1]!!)
            // } catch (e: TransactionAbortedException) {
            //     println(e)
            // }

            delay(15000)
            println()
            printChannelBalances()
            
            val awake = Array(nOfNodes) {_ -> false}
            for (i in 0 until nOfNodes) {
                awake[i] = nodes[i]!!.awake
            }
            println("Awake nodes: " + Arrays.toString(awake))
        }
    }

    private fun printChannelBalances() {
        println("Channel balances:")
        for (channel in g.getChannelSet()) {
            println("$channel, Ongoing=${channel.hasOngoingTx()}")
        }
        println()
    }

    private fun txtGraphToGraphvix(resourceName: String, fileName: String) {
        var g = GraphVizGraph(resourceName).directed().graphAttr().with("splines", true)
        val graphFileReader = Scanner(File(resourceName))
        graphFileReader.nextLine()
        graphFileReader.nextLine()

        val edgePattern = "\\d+-\\d+"
        while (graphFileReader.hasNext(edgePattern)) {
            val edgeString = graphFileReader.next(edgePattern)
            val nodeIds = edgeString.split("-")
            val balances = graphFileReader.next(edgePattern).split("-")

            var i = 0
            var j = 1
            if (balances[1].toInt() > balances[0].toInt()) {
                i = 1
                j = 0
            }

            var node1 = GraphVizNode(nodeIds[i]).link(
                GraphTo(GraphVizNode(nodeIds[j])).with("headlabel", balances[j]).with("taillabel", balances[i])
            )
            
            g = g.with(node1)
        }

        graphFileReader.close()

        Graphviz.fromGraph(g).width(400).render(Format.SVG).toFile(File("visualisation/" + fileName + "_vis.svg"))
    }
}
