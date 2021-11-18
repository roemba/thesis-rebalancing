package roemer.rebalancing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.afterburner.AfterburnerModule
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.core.type.TypeReference
import java.io.File
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector
import org.jgrapht.graph.DefaultWeightedEdge
import roemer.revive.ReviveNode

import org.apache.commons.math3.distribution.ExponentialDistribution

class TopologyTranslator (val nodeFileName: String, val channelFileName: String, val nodeType: NodeTypes, val random: SeededRandom, val logger: Logger, val counter: Counter, val equallyBalanced: Boolean) {
    val nodeResourcePath: String
    val channelResourcePath: String
    val channelCapacityDistribution = ExponentialDistribution(SeededRandom(2).apacheGenerator, 1.0)//ExponentialDistribution(this.random.apacheGenerator, 1.0)
    val EURO_SATOSHI_EXC_RATE = 1731

    init {
        this.nodeResourcePath = this::class.java.classLoader.getResource(nodeFileName)!!.file
        this.channelResourcePath = this::class.java.classLoader.getResource(channelFileName)!!.file
    }

    fun translate(): Pair<ChannelNetwork, List<Node>> {
        data class JSONNode (val id: String)
        data class JSONChannel (val id: String, val source: String, val target: String)

        val mapper = jacksonObjectMapper().registerModule(AfterburnerModule())
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        val jsonNodes: List<JSONNode> = mapper.readValue(File(nodeResourcePath))
        val jsonChannels: List<JSONChannel> = mapper.readValue(File(channelResourcePath))


        var nodes: MutableList<Node> = ArrayList()
        val nodeIdToIndexMap = HashMap<String, Node>()
        val g = ChannelNetwork()

        var duplicateNodeId = 0
        for (i in 0 until jsonNodes.size) {
            if (nodeIdToIndexMap.containsKey(jsonNodes[i].id)) {
                duplicateNodeId++
                continue
            }

            val n = when (this.nodeType) {
                NodeTypes.CoinWasher -> CoinWasherNode(i, g, counter, this.random, this.logger)
                NodeTypes.Revive -> ReviveNode(i, g, counter, this.random, this.logger)
                NodeTypes.ParticipantDisc -> ParticipantNodeAlt(i, g, counter, this.random, this.logger)
            }
            g.graph.addVertex(n)
            nodes.add(n)
            nodeIdToIndexMap.put(jsonNodes[i].id, n)
        }
        
        var sourceDestNotFound = 0
        var i = 0
        for (jsonChannel in jsonChannels) {
            val srcNode = nodeIdToIndexMap.get(jsonChannel.source)
            val dstNode = nodeIdToIndexMap.get(jsonChannel.target)

            if (srcNode == null || dstNode == null) {
                sourceDestNotFound++
                continue
            } else if (srcNode == dstNode) {
                println("Self-loop found!")
            }

            val channelBalance1 = this.getChannelBalance()
            val channelBalance2 = if (this.equallyBalanced) channelBalance1 else this.getChannelBalance()
            // if (i % 100 == 0) { println("Channel has $channelBalance satoshis or ${channelBalance/this.EURO_SATOSHI_EXC_RATE}") }
            g.addChannel(srcNode, dstNode, channelBalance1, channelBalance2)
            i += 1
        }

        println("Read ${jsonNodes.size} nodes of which $duplicateNodeId were duplicates")
        println("Read ${jsonChannels.size} channels of which $sourceDestNotFound could not be matched to a node src/dst")
        nodes = this.getLargestConnectedComponent(g)
        return Pair(g, nodes)
    }

    private fun getChannelBalance(): Int {
        return (channelCapacityDistribution.sample() * EURO_SATOSHI_EXC_RATE * 10).toInt() + (5 * this.EURO_SATOSHI_EXC_RATE) // Used to do *10
    }

    fun getLargestConnectedComponent (g: ChannelNetwork): MutableList<Node> {
        val scAlg = KosarajuStrongConnectivityInspector<Node, DefaultWeightedEdge>(g.graph)
        val stronglyConnectedSubgraphs = scAlg.getStronglyConnectedComponents();

        // println("Strongly connected components:")
        // for (i in 0 until stronglyConnectedSubgraphs.size) {
        //     println("$i - Size: " + stronglyConnectedSubgraphs[i].vertexSet().size)
        // }

        val largestVertexSet = stronglyConnectedSubgraphs.map { graph -> graph.vertexSet() }.maxByOrNull { nodes -> nodes.size }
        println("Continuing with largest component with ${largestVertexSet!!.size} nodes")

        return largestVertexSet.toMutableList()
    }
}