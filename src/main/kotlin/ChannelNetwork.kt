package roemer.rebalancingGroups

import org.jgrapht.graph.DefaultWeightedEdge
import org.jgrapht.graph.SimpleDirectedWeightedGraph

class ChannelNetwork : SimpleDirectedWeightedGraph<Node, DefaultWeightedEdge>(
    DefaultWeightedEdge::class.java
) {
    private val paymentChannelSet: MutableSet<PaymentChannel> = HashSet()

    fun addChannel(vertex1: Node, vertex2: Node, balance1: Int = 0, balance2: Int = 0) {
        val oneToTwoEdge = DefaultWeightedEdge()
        val twoToOneEdge = DefaultWeightedEdge()
        val cha = PaymentChannel(vertex1, vertex2, balance1, balance2)

        paymentChannelSet.add(cha)
        vertex1.paymentChannels.add(cha)
        vertex2.paymentChannels.add(cha)

        super.addEdge(vertex1, vertex2, oneToTwoEdge)
        super.addEdge(vertex2, vertex1, twoToOneEdge)
    }

    fun getChannel(vertex1: Node, vertex2: Node): PaymentChannel {
        val channelIntersections = vertex1.paymentChannels.intersect(vertex2.paymentChannels)

        assert(channelIntersections.size == 1)

        return channelIntersections.first()
    }

    fun getChannelsFor(vertex: Node): List<PaymentChannel> {
        return paymentChannelSet.filter { cha -> (cha.node1 === vertex || cha.node2 === vertex) }
    }

    fun getChannelSet(): Set<PaymentChannel> {
        return paymentChannelSet
    }
}
