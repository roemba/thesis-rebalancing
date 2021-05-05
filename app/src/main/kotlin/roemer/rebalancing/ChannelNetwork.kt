package roemer.rebalancing

import org.jgrapht.graph.DefaultWeightedEdge
import org.jgrapht.graph.SimpleDirectedWeightedGraph
import org.jgrapht.graph.builder.GraphTypeBuilder
import org.jgrapht.Graph


class ChannelNetwork {
    private val paymentChannelSet: MutableSet<PaymentChannel> = HashSet()
    val graph = GraphTypeBuilder.directed<Node, DefaultWeightedEdge>().allowingMultipleEdges(true).allowingSelfLoops(true).edgeClass(DefaultWeightedEdge::class.java).weighted(true).buildGraph()

    fun addChannel(vertex1: Node, vertex2: Node, balance1: Int = 0, balance2: Int = 0) {
        val oneToTwoEdge = DefaultWeightedEdge()
        val twoToOneEdge = DefaultWeightedEdge()
        val cha = PaymentChannel(vertex1, vertex2, arrayOf(oneToTwoEdge, twoToOneEdge), balance1, balance2)

        paymentChannelSet.add(cha)
        vertex1.paymentChannels.add(cha)
        vertex2.paymentChannels.add(cha)

        graph.addEdge(vertex1, vertex2, oneToTwoEdge)
        graph.addEdge(vertex2, vertex1, twoToOneEdge)
    }

    fun getChannels(vertex1: Node, vertex2: Node): Set<PaymentChannel> {
        val channelIntersections = vertex1.paymentChannels.intersect(vertex2.paymentChannels)

        return channelIntersections
    }

    fun getChannelsFor(vertex: Node): List<PaymentChannel> {
        return paymentChannelSet.filter { cha -> cha.isChannelNode(vertex) }
    }

    fun getChannelSet(): Set<PaymentChannel> {
        return paymentChannelSet
    }
}
