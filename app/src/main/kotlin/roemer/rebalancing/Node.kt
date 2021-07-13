package roemer.rebalancing

import org.jgrapht.GraphPath
import org.jgrapht.Graphs
import org.jgrapht.Graph
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.DefaultWeightedEdge
import roemer.revive.ReviveMessage
import java.util.LinkedList
import java.util.Queue

open class Node(val id: Int, val g: ChannelNetwork) {
    val paymentChannels: MutableList<PaymentChannel> = ArrayList()
    val ongoingPayments: MutableMap<Payment, LocalPayment> = HashMap()
    val logger = Logger(this)

    var numberOfTransactionMessages = 0
    var numberOfParticipantMessages = 0
    var numberOfRebalancingMessages = 0

    var sendingList: MutableList<Message> = ArrayList()
    var startStopDesc: StartStopDescription? = null
    var sendingEnabled = false
    var unprocessedMessages: MutableList<Message> = ArrayList()

    fun startPayment(amount: Int, receiver: Node) {
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

    private fun canLogMessage(message: Message): Boolean {
        return (
            message is PaymentMessage ||
            message is ParticipantMessage ||
            message is RebalancingMessage || message is FailRebalancingMessage ||
            message is ReviveMessage
        ) && message.type != MessageTypes.UPDATE_R

    }

    fun sendMessage(message: Message, direct: Boolean = false) {
        if (!sendingEnabled) throw IllegalStateException("Sending message while sending is not enabled!")

        if (message.sender != this) {
            this.unprocessedMessages.add(message)
            return
        }

        if (!direct) {
            var recipientNode: Node? = null

            val neighbours = Graphs.neighborListOf(g.graph, this).plus(listOf(this)) // List of neighbours + self
            for (neighbour in neighbours) {
                if (neighbour === message.recipient) {
                    recipientNode = neighbour
                    break
                }
            }

            if (recipientNode == null) {
                throw IllegalArgumentException("No one to deliver $message to!")
            }
        }

        // Log number of messages
        when (message.type) {
            MessageTypes.REQ_TX, MessageTypes.EXEC_TX, MessageTypes.ABORT_TX -> this.numberOfTransactionMessages++
            MessageTypes.INVITE_P, MessageTypes.ACCEPT_P, MessageTypes.FINISH_P, MessageTypes.DENY_P -> this.numberOfParticipantMessages++
            MessageTypes.COMMIT_R, MessageTypes.REQUEST_R, MessageTypes.SUCCESS_R, MessageTypes.UPDATE_R, MessageTypes.FAIL_R, MessageTypes.EXEC_R, MessageTypes.NEXT_ROUND_R -> this.numberOfRebalancingMessages++
            else -> {
                logger.error("Cannot count ${message.type}")
            }
        }

        if (this.canLogMessage(message)) {
            logger.debug("Send $message")
        }
        
        sendingList.add(message)
    }

    fun initMessageSending() {
        sendingList = ArrayList()
        startStopDesc = null
        sendingEnabled = true
    }

    fun stopMessageSending() {
        // Process any unprocessed messages
        val copyOfList = this.unprocessedMessages.toMutableList()
        for (mess in copyOfList) {
            sortMessage(mess)
        }

        if (this.unprocessedMessages.isNotEmpty()) {
            logger.info("${this.unprocessedMessages.size} unprocessed messages")
        }

        sendingEnabled = false
    }

    fun receiveMessage(message: Message): SimulationInput {
        this.initMessageSending()

        if (this.canLogMessage(message)) {
            logger.debug("Received $message")
        }

        if (message.recipient !== this) {
            throw IllegalArgumentException("Message is not meant for me!")
        }

        sortMessage(message)

        this.stopMessageSending()
        return Pair(sendingList, startStopDesc)
    }

    open fun sortMessage(message: Message) {
        when (message.type) {
            MessageTypes.REQ_TX -> handleRequestTxMessage(message as RequestPaymentMessage)
            MessageTypes.EXEC_TX -> handleExecTxMessage(message as PaymentMessage)
            MessageTypes.ABORT_TX ->  handleAbortTxMessage(message as PaymentMessage)
            else -> {
                throw IllegalArgumentException("Cannot process ${message.type}")
            }
        }
    }

    private fun handleRequestTxMessage(mes: RequestPaymentMessage) {
        if (this === mes.path.endVertex) {
            sendMessage(
                PaymentMessage(MessageTypes.EXEC_TX, this, mes.sender, mes.channel, mes.payment)
            )
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

    private fun handleExecTxMessage(mes: PaymentMessage) {
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

    private fun handleAbortTxMessage(mes: PaymentMessage) {
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
        return "Node(id=$id)"
    }

    override fun equals(other: Any?): Boolean {
        return (other is Node) && (this.id == other.id)
    }

    override fun hashCode(): Int {
        return this.id.hashCode()
    }
}
