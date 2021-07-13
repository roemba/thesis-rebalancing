package roemer.rebalancing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.*
import java.util.PriorityQueue
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.collections.ArrayList
import guru.nidi.graphviz.model.Factory.graph as GraphVizGraph
import guru.nidi.graphviz.model.Factory.node as GraphVizNode
import guru.nidi.graphviz.model.Factory.to as GraphTo
import guru.nidi.graphviz.engine.Graphviz
import guru.nidi.graphviz.engine.Format
import roemer.revive.ReviveNode

enum class NodeTypes {
    ParticipantDisc, CoinWasher, Revive
}

enum class Algorithm {
    ParticipantDisc, Revive, CoinWasher
}

class GraphHolder {
    val paymentChannels: MutableList<PaymentChannel> = ArrayList()
    val g: ChannelNetwork
    val nodes: List<Node>
    val nodeType: NodeTypes
    val channelBalances: MutableMap<PaymentChannel, Int> = HashMap()

    constructor (g: ChannelNetwork, nodes: List<Node>, nodeType: NodeTypes) {
        this.g = g
        this.nodes = nodes
        this.nodeType = nodeType
    }

    constructor (nodeFileName: String, channelFileName: String, nodeType: NodeTypes) {
        this.nodeType = nodeType
        val translator = TopologyTranslator(nodeFileName, channelFileName, nodeType)
        val (g, nodes) = translator.translate()

        this.g = g
        this.nodes = nodes
    }

    constructor (txtGraphFileName: String, nodeType: NodeTypes) {
        this.g = ChannelNetwork()
        this.nodeType = nodeType

        val resourceName = this::class.java.classLoader.getResource(txtGraphFileName)!!.file

        txtGraphToGraphViz(resourceName, txtGraphFileName)

        val graphFileReader = Scanner(File(resourceName))
        val nOfNodes = graphFileReader.nextInt()
        graphFileReader.nextLine()

        val nodes: MutableList<Node> = ArrayList()
        for (i in 0 until nOfNodes) {
            val n = when (this.nodeType) {
                NodeTypes.CoinWasher -> CoinWasherNode(i, g)
                NodeTypes.Revive -> ReviveNode(i, g)
                NodeTypes.ParticipantDisc -> ParticipantNodeAlt(i, g)
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

    fun getMessageDelay (): Long {
        return SeededRandom.random.nextLong(1L, 100L)
    }

    fun start () {
        var now = 0L
        val eventQueue: Queue<Event> = PriorityQueue()
        var started = false
        val latestArrivalTimePerChannel: MutableMap<PaymentChannel, Long> = HashMap()

        while (!started || eventQueue.isNotEmpty()) {
            var simulInput: SimulationInput = Pair(ArrayList(), null)
            var currentSender: Node? = null
            if (!started) {
                val startNode = nodes[0]
                when (this.nodeType) {
                    NodeTypes.CoinWasher, NodeTypes.Revive -> simulInput = (startNode as Rebalancer).startSubAlgos(3, 20)
                    NodeTypes.ParticipantDisc -> simulInput = (startNode as ParticipantNodeAlt).findParticipants(3, 20)
                }     

                started = true
                currentSender = startNode
            } else {
                val event = eventQueue.remove()
                now = event.time
                println("--- time is $now ---")

                if (event is MessageEvent) {
                    simulInput = event.message.recipient.receiveMessage(event.message)
                    currentSender = event.message.recipient
                } else if (event is StartStopEvent) {
                    if (event.desc.recipient != null && !event.desc.start && event.desc.algorithm == Algorithm.ParticipantDisc) {
                        when (this.nodeType) {
                            NodeTypes.CoinWasher, NodeTypes.Revive -> simulInput = (event.desc.recipient as Rebalancer).rebalance(event) 
                        }     
                    }
                } else {
                    throw IllegalStateException("Event type in queue is unknown!")
                }
            }

            for (message in simulInput.first) {
                var eventTime: Long

                // Ensure the messages are always ordered in time
                if (message is ChannelMessage) {
                    val latestArrivalTime = max(latestArrivalTimePerChannel.getOrDefault(message.channel, now), now)
                    eventTime = latestArrivalTime + this.getMessageDelay()
                    
                    latestArrivalTimePerChannel.put(message.channel, eventTime)
                } else {
                    eventTime = now + this.getMessageDelay()
                }

                eventQueue.add(MessageEvent(eventTime, message))
            }

            if (simulInput.second != null) {
                // Ensure start stop events always happen after all messages have been send and received
                eventQueue.add(StartStopEvent(now + 1, simulInput.second!!))
            }
        }

        println("Program has finished")
        // runBlocking {
        //     saveChannelBalances()

        //     for (node in nodes) {
        //         launch { node.receiveMessage() }
        //         val reb = node as Rebalancer
        //         launch { reb.rebalancingClient() }
        //     }
        //     println("Continued while nodes are waiting")
        //     println("Rebalancer type $rebalancerType")

        //     val startNode = nodes[0] as Rebalancer
        //     startNode.rebalance(3, 20)
        //     // println(nodes[0].splitEqually(112, intArrayOf(10, 30, 20, 3, 50)).contentToString())
        //     // nodes[4].startFindingParticipants(20)

        //     // try {
        //     //     nodes[4]!!.startPayment(2, nodes[1]!!)
        //     // } catch (e: TransactionAbortedException) {
        //     //     println(e)
        //     // }
            
        //     // outerLoop@ while (true) {
        //     //     println("Still running, will check in 5s...")
        //     //     var nOfAwake = 0
        //     //     val seenPartSizes: MutableSet<Int> = HashSet() 
        //     //     for (i in 0 until nodes.size) {
        //     //         if (nodes[i].awake) {
        //     //             nOfAwake++
        //     //         }
        //     //         if (nodes[i].overalSuccess) {
        //     //             seenPartSizes.add(nodes[i].finalParticipants!!.size)
        //     //         }
        //     //     }
        //     //     println("Awake nodes: $nOfAwake")
        //     //     for (i in seenPartSizes) {
        //     //         println("Seen: $i")
        //     //     }
        //     //     delay(5000)
        //     //     // for (i in 0 until nodes.size) {
        //     //     //     if (!nodes[i].messageChannel.isEmpty) {
        //     //     //         continue@outerLoop
        //     //     //     }
        //     //     // }
        //     //     // break
        //     // }

        //     delay(120 * 1000)
        //     println()
            
        //     // printChannelBalances()
        //     calculateScore()
        //     var nOfParticipantAwake = 0
        //     var nOfRebalancingAwake = 0
        //     var totalNumberOfTransactionMessages = 0
        //     var totalNumberOfParticipantMessages = 0
        //     var totalNumberOfRebalancingMessages = 0
        //     for (i in 0 until nodes.size) {
        //         val node = nodes[i] as ParticipantNodeAlt
        //         totalNumberOfTransactionMessages += node.numberOfTransactionMessages
        //         totalNumberOfParticipantMessages += node.numberOfParticipantMessages
        //         totalNumberOfRebalancingMessages += node.numberOfRebalancingMessages

        //         if (node.awake) {
        //             println(node)
        //             nOfParticipantAwake++
        //         }

        //         val t = nodes[i] as Rebalancer
        //         if (t.isRebalancingAwake()) {
        //             println(node)
        //             nOfRebalancingAwake++
        //         }
        //     }
        //     println("Awake participant nodes: $nOfParticipantAwake")
        //     println("Awake rebalancing nodes: $nOfRebalancingAwake")
        //     println("Total # of tx messages: $totalNumberOfTransactionMessages")
        //     println("Total # of participant messages: $totalNumberOfParticipantMessages")
        //     println("Total # of rebalancing messages: $totalNumberOfRebalancingMessages")
        //     println("Total # of messages: ${totalNumberOfTransactionMessages + totalNumberOfParticipantMessages + totalNumberOfRebalancingMessages}")
        // }
    }

    private fun saveChannelBalances() {
        for (channel in g.getChannelSet()) {
            channelBalances.put(channel, channel.getCurrentDemand(null))
        }
    }

    private fun calculateScore() {
        var score = 0
        println("Rebalancing success:")
        for (channel in g.getChannelSet()) {
            val oldDemand = channelBalances.get(channel)!!
            val channelScore = oldDemand - channel.getCurrentDemand(null)
            assert(channelScore >= 0)
            // println("$channelScore/$oldDemand -> $channel")
            score += channelScore
        }
        println("Total score: $score")
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
