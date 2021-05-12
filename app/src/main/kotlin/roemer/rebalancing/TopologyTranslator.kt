package roemer.rebalancing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.afterburner.AfterburnerModule
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.core.type.TypeReference
import java.io.File

class TopologyTranslator (val nodeFileName: String, val channelFileName: String) {
    val nodeResourcePath: String
    val channelResourcePath: String

    init {
        this.nodeResourcePath = this::class.java.classLoader.getResource(nodeFileName)!!.file
        this.channelResourcePath = this::class.java.classLoader.getResource(channelFileName)!!.file
    }

    fun translate(): Pair<ChannelNetwork, List<ParticipantNode>> {
        data class JSONNode (val id: String)
        data class JSONChannel (val id: String, val source: String, val target: String)

        val mapper = jacksonObjectMapper().registerModule(AfterburnerModule())
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        val jsonNodes: List<JSONNode> = mapper.readValue(File(nodeResourcePath))
        val jsonChannels: List<JSONChannel> = mapper.readValue(File(channelResourcePath))


        val nodes: MutableList<ParticipantNode> = ArrayList()
        val nodeIdToIndexMap = HashMap<String, Node>()
        val g = ChannelNetwork()

        var duplicateNodeId = 0
        for (i in 0 until jsonNodes.size) {
            if (nodeIdToIndexMap.containsKey(jsonNodes[i].id)) {
                duplicateNodeId++
                continue
            }

            val n = ParticipantNode(i, g)
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
            }

            g.addChannel(srcNode, dstNode, 2, 5) // Temporarily hardcoded balances
        }

        println("Read ${jsonNodes.size} nodes of which $duplicateNodeId were invalid")
        println("Read ${jsonChannels.size} channels of which $sourceDestNotFound could not be matched to a node src/dst")

        return Pair(g, nodes)
    }
}