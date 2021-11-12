package roemer.rebalancing

import java.io.File
import java.io.PrintWriter
import java.io.BufferedWriter
import java.io.FileWriter
import java.util.*
import java.util.PriorityQueue
import java.util.regex.Pattern
import java.time.Instant
import kotlin.math.max
import kotlin.collections.ArrayList
import guru.nidi.graphviz.model.Factory.graph as GraphVizGraph
import guru.nidi.graphviz.model.Factory.node as GraphVizNode
import guru.nidi.graphviz.model.Factory.to as GraphTo
import guru.nidi.graphviz.engine.Graphviz
import guru.nidi.graphviz.engine.Format
import roemer.revive.ReviveNode
import org.jgrapht.graph.DefaultWeightedEdge
import org.apache.commons.math3.distribution.ExponentialDistribution

enum class NodeTypes {
    ParticipantDisc, CoinWasher, Revive
}

enum class Steps {
    Discover, Rebalance
}

class GraphHolder {
    val paymentChannels: MutableList<PaymentChannel> = ArrayList()
    val g: ChannelNetwork
    val nodes: List<Node>
    val nodeType: NodeTypes
    val channelBalances: MutableMap<Pair<Node, PaymentChannel>, Int> = HashMap()
    val channelDemands: MutableMap<PaymentChannel, Int> = HashMap()

    val latencyDistribution = ExponentialDistribution(SeededRandom.apacheGenerator, 2.0)
    val maxTransactions = 5000 // Should be 10000

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
        val delay = ((latencyDistribution.sample() / 10) * 198) + 2
        return delay.toLong() // SeededRandom.random.nextLong(1L, 200L)
    }

    fun start (algoSettings: Map<String, Any>, generateTransactions: Boolean, trialName: String) {
        // Statistics and logging
        if (!generateTransactions) {
            saveChannelBalances()
        }
        printChannelBalances()
        val samplingInterval = 1000L // In ms
        val sampleTimeList: MutableList<Long> = ArrayList()
        
        val networkImbalance: MutableList<Float> = ArrayList()
        val successRatio: MutableList<Float> = ArrayList()

        // Discrete event simulation
        var now = 0L
        val eventQueue: Queue<Event> = PriorityQueue()
        var started = false
        val latestArrivalTimePerNodeChannelID: MutableMap<String, Long> = HashMap()
        val txGen = TransactionGenerator(nodes, 1, this.maxTransactions)

        if (generateTransactions) {
            eventQueue.add(txGen.generateTransactions(now))
        }

        // Parameters
        val startNodeIndex = 0 // SeededRandom.random.nextInt(nodes.size)

        while (true && (!started || eventQueue.isNotEmpty())) {
            var simulInputs: MutableList<SimulationInput> = ArrayList()
            if (!started) {
                val startNode = nodes[startNodeIndex]
                // when (this.nodeType) {
                //     NodeTypes.CoinWasher, NodeTypes.Revive -> {
                //         simulInputs.add((startNode as Rebalancer).startSubAlgos(algoSettings))
                //         simulInputs.add((nodes[4] as Rebalancer).startSubAlgos(algoSettings))
                //     }
                //     NodeTypes.ParticipantDisc -> simulInputs.add((startNode as ParticipantNodeAlt).findParticipants(algoSettings))
                // }     

                println("Node coefficient: ${startNode.getGiniCoefficient()}")

                started = true
            } else {
                val event = eventQueue.remove()
                now = event.time
                Logger.time = now

                if (event is MessageEvent) {
                    simulInputs.add(event.message.recipient.receiveMessage(event.message))
                } else if (event is StartEvent) {
                    if (trialName != "no_rebalancing") {
                        val simulInput = when (event.desc.step) {
                            Steps.Discover -> (event.desc.recipient as Rebalancer).startSubAlgos(algoSettings)
                            Steps.Rebalance -> {
                                when (this.nodeType) {
                                    NodeTypes.CoinWasher, NodeTypes.Revive -> (event.desc.recipient as Rebalancer).rebalance(event)
                                    else -> throw IllegalStateException("Unsupported node type for step Rebalance!")
                                } 
                            }
                        }
                        if (simulInput != null) simulInputs.add(simulInput)
                    }
                } else if (event is StartPaymentEvent) {
                    for (payment in event.payments) {
                        simulInputs.add(payment.from.startPayment(payment))
                    }
                    val simulInput = txGen.generateTransactions(now)
                    if (simulInput != null) { eventQueue.add(simulInput) }
                } else {
                    throw IllegalStateException("Event type in queue is unknown!")
                }
            }

            for (simulInput in simulInputs) {
                for (message in simulInput.messages) {
                    var eventTime: Long
    
                    // Ensure the messages are always ordered in time
                    if (message is ChannelMessage) {
                        val id = message.channel.getNodeChannelIdentifier(message.sender)
                        val latestArrivalTime = max(latestArrivalTimePerNodeChannelID.getOrDefault(id, now), now)
                        eventTime = latestArrivalTime + this.getMessageDelay()
                        
                        latestArrivalTimePerNodeChannelID.put(id, eventTime)
                    } else {
                        if (message.recipient === message.sender) { // When sending a message to yourself
                            eventTime = now + 1L
                        } else {
                            eventTime = now + this.getMessageDelay()
                        }
                    }
    
                    eventQueue.add(MessageEvent(eventTime, message))
                }
    
                if (simulInput.startStopDes != null) {
                    eventQueue.add(StartEvent(now + 1, simulInput.startStopDes))
                }
            }

            if (now % samplingInterval == 0L) {
                // Collect all statistical data here
                sampleTimeList.add(now)

                var totalImbalance = 0.0
                var nOfSuccessfullTransactions = 0
                var totalNumberOfTransactions = 0
                for (node in nodes) {
                    totalImbalance += node.getGiniCoefficient()
                    nOfSuccessfullTransactions += node.transactionsCompleted
                    totalNumberOfTransactions += node.transactionsCompleted + node.transactionsRetried + node.transactionsFailed
                }

                // Network Imbalance
                val nImbalance = (1.0 / nodes.size) * totalImbalance
                networkImbalance.add(nImbalance.toFloat())

                // Transaction Success Ratio
                val ratio = nOfSuccessfullTransactions.toFloat() / totalNumberOfTransactions
                successRatio.add(if (ratio.isNaN()) 1.0f else ratio)
            }
        }

        // Statistic and logging
        if (!generateTransactions) {
            checkConservationOfCoins()
            calculateScore()
        }

        var nOfParticipantAwake = 0
        var nOfRebalancingAwake = 0
        var nOfNodesWithOngoingTransactions = 0
        var nOfTransactionsComplete = 0
        var nOfTransactionsFailed = 0
        var totalSpecialCounter = 0
        for (i in 0 until nodes.size) {
            val node = nodes[i] as ParticipantNodeAlt
            totalSpecialCounter += node.specialCounter
            nOfTransactionsComplete += node.transactionsCompleted
            nOfTransactionsFailed += node.transactionsFailed
            val giniCoefficient = node.getGiniCoefficient()

            // if (giniCoefficient > 0.0001) {
            //     println("$node has coefficient of $giniCoefficient")
            // }
            
            if (node.ongoingPayments.isNotEmpty()) {
                println("$node has ongoing payments")
                nOfNodesWithOngoingTransactions++
            }

            if (node.discoverAwake) {
                println("$node is still doing part discovery")
                nOfParticipantAwake++
            }

            if (nodes[i] is Rebalancer) {
                val t = nodes[i] as Rebalancer
                if (t.isRebalancingAwake()) {
                    println("$node is still rebalancing")
                    nOfRebalancingAwake++
                }
            }
        }
        println("Awake transaction nodes: ${nOfNodesWithOngoingTransactions}/${nodes.size}")
        println("Awake participant nodes: ${nOfParticipantAwake}")
        println("Awake rebalancing nodes: ${nOfRebalancingAwake}")
        println("Special counter: ${totalSpecialCounter}")
        println()
        println("${nOfTransactionsComplete}/${this.maxTransactions} transactions completed")
        println("${nOfTransactionsFailed}/${this.maxTransactions} transactions failed")
        println()
        println("Total time: ${now / 1000L / 60L / 60L} hours or ${now / 1000L / 60L} minutes or ${now / 1000L} seconds")
        println()
        MessageCounter.printCounts()

        printChannelBalances()

        if (generateTransactions) {
            saveData(trialName, sampleTimeList, successRatio, networkImbalance)
        }

        println("Program has finished")
    }

    private fun <T> saveData(trialName: String, vararg dataLists: List<T>) {  
        val fileWriter = FileWriter("output_files/${trialName}.csv", false)
        val printWriter = PrintWriter(BufferedWriter(fileWriter))

        printWriter.use { out -> 
            for (list in dataLists) {
                out.println(list.joinToString(","))
            }
        }
    }

    private fun saveChannelBalances() {
        for (node in this.nodes) {
            val channels = g.getChannelsFor(node)
            for (channel in channels) {
                channelDemands.put(channel, channel.getCurrentDemand(null))
                channelBalances.put(Pair(node, channel), channel.getBalance(node))
            }
        }
    }

    private fun checkConservationOfCoins() {
        for (node in this.nodes) {
            val channels = g.getChannelsFor(node)
            var oldSum = 0
            var newSum = 0

            for (channel in channels) {
                oldSum += channelBalances.get(Pair(node, channel))!!
                newSum += channel.getBalance(node)
            }
            
            if (oldSum != newSum) {
                printChannelBalances()
                throw IllegalStateException("$node used to have a total balance of $oldSum but now has a balance of $newSum while they should be equal!")
            }
        }
    }

    private fun calculateScore() {
        var score = 0
        var printing = true
        println("Rebalancing success:")
        if (g.getChannelSet().size > 20) {
            printing = false
        }

        for (channel in g.getChannelSet()) {
            val oldDemand = channelDemands.get(channel)
            if (oldDemand == null) { continue }

            val channelScore = oldDemand - channel.getCurrentDemand(null)
            assert(channelScore >= 0)
            if (printing) println("$channelScore/$oldDemand -> $channel")
            score += channelScore
        }
        println("Total score: $score")
    }

    private fun printChannelBalances() {
        println("Channel balances:")
        if (g.getChannelSet().size > 20) {
            println("Too many channels to print!")
            return
        }

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
