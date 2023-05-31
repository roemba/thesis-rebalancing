package anon.rebalancing

import java.io.File
import java.io.PrintWriter
import java.io.BufferedWriter
import java.io.FileWriter
import java.util.*
import java.util.PriorityQueue
import java.util.regex.Pattern
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.collections.ArrayList
import guru.nidi.graphviz.model.Factory.graph as GraphVizGraph
import guru.nidi.graphviz.model.Factory.node as GraphVizNode
import guru.nidi.graphviz.model.Factory.to as GraphTo
import guru.nidi.graphviz.engine.Graphviz
import guru.nidi.graphviz.engine.Format
import anon.revive.ReviveNode
import org.jgrapht.graph.DefaultWeightedEdge
import org.apache.commons.math3.distribution.ExponentialDistribution
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class NodeTypes {
    ParticipantDisc, Hope, Revive, Normal
}

enum class Steps {
    Discover, Rebalance
}

class GraphHolder (
    val g: ChannelNetwork,
    val nodes: List<Node>,
    val nodeType: NodeTypes,
    val random: SeededRandom,
    val logger: Logger,
    val counter: Counter
) {
    val paymentChannels: MutableList<PaymentChannel> = ArrayList()
    val channelBalances: MutableMap<Pair<Node, PaymentChannel>, Int> = HashMap()
    val channelDemands: MutableMap<PaymentChannel, Int> = HashMap()

    val latencyDistribution = ExponentialDistribution(this.random.apacheGenerator, 2.0)
    val maxTransactions = 5000 // Should be 10000

    companion object { 
        fun createGraphHolderFromTxtGraph (txtGraphFileName: String, nodeType: NodeTypes, random: SeededRandom, counter: Counter, rebalancingTriggerPoint: Float = -1F): GraphHolder {
            val g = ChannelNetwork()
            val logger = Logger()

            val resourceName = this::class.java.classLoader.getResource(txtGraphFileName)!!.file

            txtGraphToGraphViz(resourceName, txtGraphFileName)

            val graphFileReader = Scanner(File(resourceName))
            val nOfNodes = graphFileReader.nextInt()
            graphFileReader.nextLine()

            val nodes: MutableList<Node> = ArrayList()
            for (i in 0 until nOfNodes) {
                val n = when (nodeType) {
                    NodeTypes.Hope -> HopeNode(i, g, counter, random, logger, rebalancingTriggerPoint)
                    NodeTypes.Revive -> ReviveNode(i, g, counter, random, logger, rebalancingTriggerPoint)
                    NodeTypes.ParticipantDisc -> ParticipantNodeAlt(i, g, counter, random, logger, rebalancingTriggerPoint)
                    NodeTypes.Normal -> Node(i, g, counter, random, logger, rebalancingTriggerPoint)
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

            return GraphHolder(g, nodes, nodeType, random, logger, counter)
        }

        fun createGraphHolderFromLightningTopology (nodeFileName: String, channelFileName: String, nodeType: NodeTypes, random: SeededRandom, counter: Counter, equallyBalanced: Boolean, rebalancingTriggerPoint: Float = -1F): GraphHolder {
            val logger = Logger()
            val translator = TopologyTranslator(nodeFileName, channelFileName, nodeType, random, logger, counter, equallyBalanced, rebalancingTriggerPoint)
            val (g, nodes) = translator.translate()

            return GraphHolder(g, nodes, nodeType, random, logger, counter)
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

    fun getMessageDelay (): Long {
        val delay = ((latencyDistribution.sample() / 10) * 198) + 2
        return delay.toLong() // SeededRandom.random.nextLong(1L, 200L)
    }

    suspend fun start (algoSettings: AlgoSettings, dynamicRun: Boolean, trial: Trials, randomStartNode: Boolean, writeMutex: Mutex, fileName: String, debug: Boolean = false) {
        // Statistics and logging
        if (!dynamicRun && (trial == Trials.STATIC_REBALANCING_COMPARISON || trial == Trials.SCORE_VS_PERC_LEADERS)) {
            saveChannelBalances()
        }
        var sampledAt = -1L

        //printChannelBalances()
        val samplingInterval = 10000L // In ms
        val sampleTimeList: MutableList<Long> = ArrayList()
        
        val networkImbalance: MutableList<Float> = ArrayList()
        val successRatio: MutableList<Float> = ArrayList()
        val nsOfTxAbortBecauseLocked: MutableList<Int> = ArrayList()
        val nsOfTxAbortBecauseNoFunds: MutableList<Int> = ArrayList()
        val nsOfRebalancingInvocations: MutableList<Int> = ArrayList()
        var nOfRebalancingInvocations = 0

        // Discrete event simulation
        var now = 0L
        val eventQueue: Queue<Event> = PriorityQueue()
        val latestArrivalTimePerNodeChannelID: MutableMap<String, Long> = HashMap()
        val txGen = TransactionGenerator(nodes, 1, this.maxTransactions, this.logger)

        var startNodeIndex: Int? = null 
        if (dynamicRun) {
            eventQueue.add(txGen.generateTransactions(now))
        } else {
            startNodeIndex = if (randomStartNode) this.random.random.nextInt(nodes.size) else min(10, nodes.size - 1)
            eventQueue.add(StartEvent(0, this.random, StartDescription(Steps.Discover, nodes[startNodeIndex])))
        }

        while (eventQueue.isNotEmpty()) {
            var simulInputs: MutableList<SimulationInput> = ArrayList()
            val event = eventQueue.remove()
            now = event.time
            this.logger.time = now

            if (event is MessageEvent) {
                simulInputs.add(event.message.recipient.receiveMessage(event.message))
            } else if (event is StartEvent) {
                if (nodeType != NodeTypes.Normal) {
                    val simulInput = when (event.desc.step) {
                        Steps.Discover -> when (this.nodeType) {
                            NodeTypes.ParticipantDisc -> (event.desc.recipient as ParticipantNodeAlt).findParticipants(algoSettings)
                            else -> (event.desc.recipient as Rebalancer).startSubAlgos(algoSettings)
                        }
                        Steps.Rebalance -> {
                            when (this.nodeType) {
                                NodeTypes.Hope, NodeTypes.Revive -> {
                                    nOfRebalancingInvocations++
                                    (event.desc.recipient as Rebalancer).rebalance(event)
                                }
                                else -> null
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
    
                    eventQueue.add(MessageEvent(eventTime, this.random, message))
                }
    
                if (simulInput.startStopDes != null) {
                    eventQueue.add(StartEvent(now + 1, this.random, simulInput.startStopDes))
                }
            }

            if (trial == Trials.DYNAMIC_REBALANCING_COMPARISON && now % samplingInterval == 0L && now != sampledAt) {
                // Collect all statistical data here
                sampleTimeList.add(now)

                var totalImbalance = 0.0
                var nOfSuccessfullTransactions = 0
                var totalNumberOfTransactions = 0
                var nOfTxAbortBecauseLocked = 0
                var nOfTxAbortBecauseNoFunds = 0
                for (node in nodes) {
                    totalImbalance += node.getGiniCoefficient()
                    nOfSuccessfullTransactions += node.transactionsCompleted
                    totalNumberOfTransactions += node.transactionsCompleted + node.transactionsRetried + node.transactionsFailedBecauseOfLackOfFunds + node.transactionsFailedBecauseChannelLocked + node.transactionsFailedBecauseOther

                    nOfTxAbortBecauseLocked += node.transactionsFailedBecauseChannelLocked
                    nOfTxAbortBecauseNoFunds += node.transactionsFailedBecauseOfLackOfFunds
                }

                // Network Imbalance
                val nImbalance = (1.0 / nodes.size) * totalImbalance
                networkImbalance.add(nImbalance.toFloat())

                // Transaction Success Ratio
                val ratio = nOfSuccessfullTransactions.toFloat() / totalNumberOfTransactions
                successRatio.add(if (ratio.isNaN()) 1.0f else ratio)
                sampledAt = now

                // Other tx statistics
                nsOfTxAbortBecauseLocked += nOfTxAbortBecauseLocked
                nsOfTxAbortBecauseNoFunds += nOfTxAbortBecauseNoFunds
                nsOfRebalancingInvocations += nOfRebalancingInvocations
            }
        }

        // Statistic and logging
        if (!dynamicRun && debug) {
            if (trial == Trials.STATIC_REBALANCING_COMPARISON) {
                checkConservationOfCoins()
                calculateScore()
            }

            val nOfParticipants = (nodes[startNodeIndex!!] as ParticipantNodeAlt).result?.finalParticipants?.size
            println()
            println("Number of participants: $nOfParticipants/${nodes.size}")
            println()
        }

        var nOfParticipantAwake = 0
        var nOfRebalancingAwake = 0
        var nOfNodesWithOngoingTransactions = 0
        var nOfTransactionsComplete = 0
        //var nOfTransactionsFailed = 0
        var totalSpecialCounter = 0
        for (i in 0 until nodes.size) {
            val node = nodes[i]
            totalSpecialCounter += node.specialCounter
            nOfTransactionsComplete += node.transactionsCompleted
            //nOfTransactionsFailed += node.transactionsFailed
            //val giniCoefficient = node.getGiniCoefficient()

            // if (giniCoefficient > 0.0001) {
            //     println("$node has coefficient of $giniCoefficient")
            // }
            
            if (node.ongoingPayments.isNotEmpty()) {
                println("$node has ongoing payments")
                nOfNodesWithOngoingTransactions++
            }

            if (node is ParticipantNodeAlt && node.discoverAwake) {
                println("$node is still doing part discovery")
                nOfParticipantAwake++
            }


            if (node is Rebalancer && node.isRebalancingAwake()) {
                println("$node is still rebalancing")
                nOfRebalancingAwake++
            }
        }
        println("Awake transaction nodes: ${nOfNodesWithOngoingTransactions}/${nodes.size}")
        println("Awake participant nodes: ${nOfParticipantAwake}")
        println("Awake rebalancing nodes: ${nOfRebalancingAwake}")
        println("Special counter: ${totalSpecialCounter}")
        println()
        println("${nOfTransactionsComplete}/${this.maxTransactions} transactions completed")
        //println("${nOfTransactionsFailed}/${this.maxTransactions} transactions failed")
        println()
        println("Total time: ${now / 1000L / 60L / 60L} hours or ${now / 1000L / 60L} minutes or ${now / 1000L} seconds")
        println()

        if (nOfParticipantAwake > 0 || nOfRebalancingAwake > 0) {
            throw IllegalStateException("All nodes should have finished their activities at the end of the simulation!")
        }

        //printChannelBalances()

        // Data acquisition
        if (dynamicRun && trial == Trials.DYNAMIC_REBALANCING_COMPARISON) {
            saveData(sampleTimeList.joinToString(","), writeMutex, fileName, mapOf("successRatio" to successRatio, "networkImbalance" to networkImbalance, "nsOfTxAbortBecauseLocked" to nsOfTxAbortBecauseLocked, "nsOfTxAbortBecauseNoFunds" to nsOfTxAbortBecauseNoFunds, "nsOfRebalancingInvocations" to nsOfRebalancingInvocations))
        } else if (trial == Trials.PART_DISC) {
            val nOfParticipants = (nodes[startNodeIndex!!] as ParticipantNodeAlt).result!!.finalParticipants.size
            saveData(algoSettings.toFileName(), writeMutex, fileName, mapOf("nOfParticipants" to listOf(nOfParticipants)))
        } else if (trial == Trials.SCORE_VS_PERC_LEADERS) {
            val rebalancingScore = calculateScore()
            val nOfRebalanceMessages = this.counter.getCounts().second
            saveData(algoSettings.toFileName(), writeMutex, fileName, mapOf("totalDemandsMet" to listOf(rebalancingScore), "nOfRebalanceMes" to listOf(nOfRebalanceMessages)))
        } else if (trial == Trials.STATIC_REBALANCING_COMPARISON) {
            val rebalancingScore = calculateScore()
            val nOfRebalanceMessages = this.counter.getCounts().second
            saveData(this.nodeType.toString(), writeMutex, fileName, mapOf("totalDemandsMet" to listOf(rebalancingScore), "nOfRebalanceMes" to listOf(nOfRebalanceMessages), "time" to listOf(now)))
        }
    }

    suspend private fun saveData(settings: String, writeMutex: Mutex, fileName: String, dataLists: Map<String, Collection<Any>>) {  
        writeMutex.withLock {
            val fileWriter = FileWriter(fileName, true)
            val printWriter = PrintWriter(BufferedWriter(fileWriter))

            printWriter.use { out -> 
                out.println("settings:${settings}")
                for ((key, value) in dataLists.entries) {
                    out.println("$key:${value.joinToString(",")}")
                }
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

    private fun calculateScore(): Int {
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
        return score
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
}
