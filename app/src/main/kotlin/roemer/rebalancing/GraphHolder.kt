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
import roemer.revive.ReviveNode

enum class RebalancerTypes {
    CoinWasher, Revive
}

@ExperimentalCoroutinesApi
class GraphHolder {
    val paymentChannels: MutableList<PaymentChannel> = ArrayList()
    val g: ChannelNetwork
    val nodes: List<Node>
    val rebalancerType: RebalancerTypes

    constructor (g: ChannelNetwork, nodes: List<Node>, rebalancerType: RebalancerTypes) {
        this.g = g
        this.nodes = nodes
        this.rebalancerType = rebalancerType
    }

    constructor (nodeFileName: String, channelFileName: String, rebalancerType: RebalancerTypes) {
        this.rebalancerType = rebalancerType
        val translator = TopologyTranslator(nodeFileName, channelFileName, rebalancerType)
        val (g, nodes) = translator.translate()

        this.g = g
        this.nodes = nodes
    }

    constructor (txtGraphFileName: String, rebalancerType: RebalancerTypes) {
        this.g = ChannelNetwork()
        this.rebalancerType = rebalancerType

        val resourceName = this::class.java.classLoader.getResource(txtGraphFileName)!!.file

        txtGraphToGraphViz(resourceName, txtGraphFileName)

        val graphFileReader = Scanner(File(resourceName))
        val nOfNodes = graphFileReader.nextInt()
        graphFileReader.nextLine()

        val nodes: MutableList<Node> = ArrayList()
        for (i in 0 until nOfNodes) {
            val n = when (this.rebalancerType) {
                RebalancerTypes.CoinWasher -> CoinWasherNode(i, g)
                RebalancerTypes.Revive -> ReviveNode(i, g)
            }

            g.graph.addVertex(n)
            nodes.add(n)
        }
        
        val edgePattern = "\\d+-\\d+"
        while (graphFileReader.hasNext(edgePattern)) {
            val edgeString = graphFileReader.next(edgePattern)
            val nodeIds = edgeString.split("-").map { it.toInt() }
            val balances = graphFileReader.next(edgePattern).split("-").map { it.toInt() }

            g.addChannel(nodes[nodeIds[0]], nodes[nodeIds[1]], balances[0], balances[1])
        }

        this.nodes = nodes
    }

    fun start () {
        runBlocking {
            if (rebalancerType == RebalancerTypes.Revive) {
                for (node in nodes) {
                    val n = node as ReviveNode
                    n.threshold = 4
                }
            }

            for (node in nodes) {
                launch { node.receiveMessage() }
                val reb = node as Rebalancer
                launch { reb.rebalancingClient() }
            }
            println("Continued while nodes are waiting")
            println("Rebalancer type $rebalancerType")

            val startNode = nodes[0] as Rebalancer
            startNode.rebalance(20)
            // println(nodes[0].splitEqually(112, intArrayOf(10, 30, 20, 3, 50)).contentToString())
            // nodes[4].startFindingParticipants(20)

            // try {
            //     nodes[4]!!.startPayment(2, nodes[1]!!)
            // } catch (e: TransactionAbortedException) {
            //     println(e)
            // }
            
            // outerLoop@ while (true) {
            //     println("Still running, will check in 5s...")
            //     var nOfAwake = 0
            //     val seenPartSizes: MutableSet<Int> = HashSet() 
            //     for (i in 0 until nodes.size) {
            //         if (nodes[i].awake) {
            //             nOfAwake++
            //         }
            //         if (nodes[i].overalSuccess) {
            //             seenPartSizes.add(nodes[i].finalParticipants!!.size)
            //         }
            //     }
            //     println("Awake nodes: $nOfAwake")
            //     for (i in seenPartSizes) {
            //         println("Seen: $i")
            //     }
            //     delay(5000)
            //     // for (i in 0 until nodes.size) {
            //     //     if (!nodes[i].messageChannel.isEmpty) {
            //     //         continue@outerLoop
            //     //     }
            //     // }
            //     // break
            // }

            delay(30000)
            println()

            printChannelBalances()
            var nOfAwake = 0
            var totalNumberOfTransactionMessages = 0
            var totalNumberOfParticipantMessages = 0
            var totalNumberOfRebalancingMessages = 0
            for (i in 0 until nodes.size) {
                val node = nodes[i] as ParticipantNodeAlt
                totalNumberOfTransactionMessages += node.numberOfTransactionMessages
                totalNumberOfParticipantMessages += node.numberOfParticipantMessages
                totalNumberOfRebalancingMessages += node.numberOfRebalancingMessages

                if (node.awake) {
                    println(node)
                    nOfAwake++
                }
            }
            println("Awake nodes: $nOfAwake")
            println("Total # of tx messages: $totalNumberOfTransactionMessages")
            println("Total # of participant messages: $totalNumberOfParticipantMessages")
            println("Total # of rebalancing messages: $totalNumberOfRebalancingMessages")
            println("Total # of messages: ${totalNumberOfTransactionMessages + totalNumberOfParticipantMessages + totalNumberOfRebalancingMessages}")
        }
    }

    private fun printChannelBalances() {
        println("Channel balances:")
        for (channel in g.getChannelSet()) {
            println("$channel, Ongoing=${channel.hasOngoingTx()}, demand=${channel.getCurrentDemand(null)}")
        }
        println()
    }

    private fun txtGraphToGraphViz(resourceName: String, fileName: String) {
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
