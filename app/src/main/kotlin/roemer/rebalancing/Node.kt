package roemer.rebalancing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import org.jgrapht.GraphPath
import org.jgrapht.Graphs
import org.jgrapht.Graph
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.DefaultWeightedEdge
import kotlinx.coroutines.delay

open class Node(val id: Int, val g: ChannelNetwork, var totalFunds: Int = 0) {
    val paymentChannels: MutableList<PaymentChannel> = ArrayList()
    val ongoingPayments: MutableMap<Payment, LocalPayment> = HashMap()
    val messageChannel = Channel<Message>(Channel.UNLIMITED)
    val logger = Logger(this)

    suspend fun startPayment(amount: Int, receiver: Node) {
        val payment = Payment(this, receiver, amount)

        val shortestPathDijkstra: DijkstraShortestPath<Node, DefaultWeightedEdge> = DijkstraShortestPath(g.graph)
        val path = shortestPathDijkstra.getPath(this, receiver)

        
        val toChannel = this.getChannelFromEdge(this.getNextEdgeInPath(path))
        val nextNode = toChannel.getOppositeNode(this)
        val tx = Transaction(payment.paymentId, payment.amount, this, nextNode)

        // If commit fails, raise error immediately
        if (!toChannel.requestTx(tx)) {
            throw TransactionAbortedException("Insufficient balance in $toChannel!")
        }

        ongoingPayments[payment] = LocalPayment(payment, null, toChannel, tx)

        sendMessage(
            RequestPaymentMessage(MessageTypes.REQ_TX, this, nextNode, toChannel, payment, path)
        )
    }

    suspend fun sendMessage(message: Message) {
        val neighbours = Graphs.neighborListOf(g.graph, this)
        for (neighbour in neighbours) {
            if (neighbour === message.recipient) {
                val randomDelay = SeededRandom.random.nextLong(200)
                // delay(randomDelay)
                neighbour.messageChannel.send(message)
                return
            }
        }
        throw IllegalArgumentException("No one to deliver $message to!")
    }

    @ExperimentalCoroutinesApi
    suspend fun receiveMessage() {
        while (!messageChannel.isClosedForReceive) {
            val message = messageChannel.receive()

            logger.log("Received $message")
            if (message.recipient !== this) {
                sendMessage(message)
                return
            }

            sortMessage(message)
        }
    }

    open suspend fun sortMessage(message: Message) {
        when (message.type) {
            MessageTypes.REQ_TX -> handleRequestTxMessage(message as RequestPaymentMessage)
            MessageTypes.EXEC_TX -> handleExecTxMessage(message as PaymentMessage)
            MessageTypes.ABORT_TX -> handleAbortTxMessage(message as PaymentMessage)
            else -> {
                logger.log("Cannot process ${message.type}")
            }
        }
    }

    private suspend fun handleRequestTxMessage(mes: RequestPaymentMessage) {
        if (this === mes.path.endVertex) {
            sendMessage(
                PaymentMessage(MessageTypes.EXEC_TX, this, mes.sender, mes.channel, mes.payment)
            )
            return
        }

        // Check if payment already known
        if (mes.payment in ongoingPayments) {
            throw IllegalArgumentException("Payment ${mes.payment} already received by $this")
        }

        // Collect information to create localPayment
        val toChannel = this.getChannelFromEdge(this.getNextEdgeInPath(mes.path))
        val nextNode = toChannel.getOppositeNode(this)
        val previousNode = mes.sender
        val fromChannel = mes.channel

        val tx = Transaction(mes.payment.paymentId, mes.payment.amount, this, nextNode)

        // If commit fails, send ABORT to sender of the message
        if (!toChannel.requestTx(tx)) {
            sendMessage(
                PaymentMessage(MessageTypes.ABORT_TX, this, previousNode, fromChannel, mes.payment)
            )
            return
        }

        ongoingPayments[mes.payment] = LocalPayment(mes.payment, fromChannel, toChannel, tx)

        sendMessage(
            RequestPaymentMessage(MessageTypes.REQ_TX, this, nextNode, toChannel, mes.payment, mes.path)
        )
    }

    private suspend fun handleExecTxMessage(mes: PaymentMessage) {
        if (mes.payment !in ongoingPayments) {
            throw IllegalArgumentException("Payment ${mes.payment} never requested from $this")
        }

        val localPayment = ongoingPayments[mes.payment]!!
        val execSuccess = localPayment.toPaymentChannel.executeTx(localPayment.toTx)

        if (!execSuccess) {
            throw IllegalStateException("Committed transaction ${localPayment.toTx} could not be executed!")
        }

        // If it was not I who started the tx, propagate Exec
        if (localPayment.fromPaymentChannel !== null) {
            sendMessage(
                PaymentMessage(MessageTypes.EXEC_TX, this, localPayment.fromPaymentChannel.getOppositeNode(this), localPayment.fromPaymentChannel, mes.payment)
            )
        }

        ongoingPayments.remove(mes.payment)
    }

    private suspend fun handleAbortTxMessage(mes: PaymentMessage) {
        if (mes.payment !in ongoingPayments) {
            throw IllegalArgumentException("Payment ${mes.payment} never requested from $this")
        }

        val localPayment = ongoingPayments[mes.payment]!!
        val abortSuccess = localPayment.toPaymentChannel.abortTx(localPayment.toTx)

        if (!abortSuccess) {
            throw IllegalStateException("${localPayment.toTx} could not be aborted!")
        }

        // If it was not I who started the tx, propagate Abort
        if (localPayment.fromPaymentChannel !== null) {
            sendMessage(
                PaymentMessage(MessageTypes.ABORT_TX, this, localPayment.fromPaymentChannel.getOppositeNode(this), localPayment.fromPaymentChannel, mes.payment)
            )
        }

        ongoingPayments.remove(mes.payment)
    }

    fun getChannelsForNode(node: Node): List<PaymentChannel> {
        val channels: MutableList<PaymentChannel> = ArrayList()
        for (channel in paymentChannels) {
            if (channel.getOppositeNode(this) === node) {
                channels.add(channel)
            }
        }

        if (channels.isEmpty()) {
            throw IllegalArgumentException("$this has no channel with $node!")
        }

        return channels
    }

    private fun getChannelFromEdge(edge: DefaultWeightedEdge): PaymentChannel {
        for (channel in paymentChannels) {
            if (channel.edges.contains(edge)) {
                return channel
            }
        }

        throw IllegalArgumentException("Edge provided is not a member of any of the channels of $this!")
    }

    private fun getNextEdgeInPath(path: GraphPath<Node, DefaultWeightedEdge>): DefaultWeightedEdge {
        for (edge in path.edgeList) {
            if (g.graph.getEdgeSource(edge) === this) {
                return edge
            }
        }

        throw IllegalArgumentException("$this does not appear in path $path!")
    }

    override fun toString(): String {
        return "Node(id=$id,totalFunds=$totalFunds)"
    }

    override fun equals(other: Any?): Boolean {
        return (other is Node) && (this.id == other.id)
    }

    override fun hashCode(): Int {
        return this.id.hashCode()
    }
}
