package roemer.rebalancingGroups

import org.jgrapht.graph.DefaultWeightedEdge
import org.jgrapht.graph.SimpleDirectedWeightedGraph
import org.jgrapht.util.SupplierUtil
import java.lang.IllegalArgumentException

class ChannelNetwork : SimpleDirectedWeightedGraph<Node, DefaultWeightedEdge>(
    NodeSupplier(),
    SupplierUtil.createSupplier(DefaultWeightedEdge::class.java)
) {
    private val channelSet: MutableSet<Channel> = HashSet()

    fun addChannel(vertex1: Node, vertex2: Node, balance1: Int = 0, balance2: Int = 0) {
        val oneToTwoEdge = DefaultWeightedEdge()
        val twoToOneEdge = DefaultWeightedEdge()
        val cha = Channel(vertex1, vertex2, balance1, balance2)

        channelSet.add(cha)
        vertex1.channels.add(cha)
        vertex2.channels.add(cha)

        super.addEdge(vertex1, vertex2, oneToTwoEdge)
        super.addEdge(vertex2, vertex1, twoToOneEdge)
    }

    fun getChannel(vertex1: Node, vertex2: Node): Channel {
        val channelIntersections = vertex1.channels.intersect(vertex2.channels)

        assert(channelIntersections.size == 1)

        return channelIntersections.first()
    }

    fun getChannelsFor(vertex: Node): List<Channel> {
        return channelSet.filter { cha -> (cha.node1 === vertex || cha.node2 === vertex) }
    }
}
