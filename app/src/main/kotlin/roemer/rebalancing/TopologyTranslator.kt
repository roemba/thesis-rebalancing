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

class TopologyTranslator (val nodeFileName: String, val channelFileName: String) {
    val nodeResourcePath: String
    val channelResourcePath: String

    init {
        this.nodeResourcePath = this::class.java.classLoader.getResource(nodeFileName)!!.file
        this.channelResourcePath = this::class.java.classLoader.getResource(channelFileName)!!.file
    }

    fun translate(): Pair<ChannelNetwork, List<RebalancingNode>> {
        data class JSONNode (val id: String)
        data class JSONChannel (val id: String, val source: String, val target: String)

        val mapper = jacksonObjectMapper().registerModule(AfterburnerModule())
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        val jsonNodes: List<JSONNode> = mapper.readValue(File(nodeResourcePath))
        val jsonChannels: List<JSONChannel> = mapper.readValue(File(channelResourcePath))


        val nodes: MutableList<RebalancingNode> = ArrayList()
        val nodeIdToIndexMap = HashMap<String, Node>()
        val g = ChannelNetwork()

        var duplicateNodeId = 0
        for (i in 0 until jsonNodes.size) {
            if (nodeIdToIndexMap.containsKey(jsonNodes[i].id)) {
                duplicateNodeId++
                continue
            }

            val n = RebalancingNode(i, g)
            g.graph.addVertex(n)
            nodes.add(n)
            nodeIdToIndexMap.put(jsonNodes[i].id, n)
        }
        
        var sourceDestNotFound = 0
        for (jsonChannel in jsonChannels) {
            val srcNode = nodeIdToIndexMap.get(jsonChannel.source)
            val dstNode = nodeIdToIndexMap.get(jsonChannel.target)

            if (srcNode == null || dstNode == null) {
                sourceDestNotFound++
                continue
            } else if (srcNode == dstNode) {
                println("Self-loop found!")
            }

            g.addChannel(srcNode, dstNode, 2, 5) // Temporarily hardcoded balances
        }

        println("Read ${jsonNodes.size} nodes of which $duplicateNodeId were invalid")
        println("Read ${jsonChannels.size} channels of which $sourceDestNotFound could not be matched to a node src/dst")
        analysis(g)

        return Pair(g, nodes)
    }

    fun analysis (g: ChannelNetwork) {
        val scAlg = KosarajuStrongConnectivityInspector<Node, DefaultWeightedEdge>(g.graph)
        val stronglyConnectedSubgraphs = scAlg.getStronglyConnectedComponents();

        println("Strongly connected components:")
        for (i in 0 until stronglyConnectedSubgraphs.size) {
            println("$i - Size: " + stronglyConnectedSubgraphs[i].vertexSet().size)
        }
    }
}