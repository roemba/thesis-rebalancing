package roemer.rebalancing

import org.jgrapht.GraphPath
import org.jgrapht.Graphs
import org.jgrapht.Graph
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.DefaultWeightedEdge
import roemer.revive.ReviveMessage
import java.util.LinkedList
import java.util.Queue
import kotlin.math.abs

open class Node(val id: Int, val g: ChannelNetwork) {
    val paymentChannels: MutableList<PaymentChannel> = ArrayList()
    val ongoingPayments: MutableMap<Payment, LocalPayment> = HashMap()
    val logger = Logger(this)

    var specialCounter = 0
    var transactionsCompleted = 0
    var transactionsRetried = 0
    var transactionsFailed = 0

    var sendingList: MutableList<Message> = ArrayList()
    var startStopDesc: StartDescription? = null
    var sendingEnabled = false
    var unprocessedMessages: MutableList<Message> = ArrayList()

    val REBALANCING_TRIGGER_POINT = 0.1

    fun startPayment(payment: Payment): SimulationInput {
        if (payment.from != this) {
            throw IllegalArgumentException("Payment should be starting at node $this !")
        }

        this.initMessageSending()

        val shortestPathDijkstra: DijkstraShortestPath<Node, DefaultWeightedEdge> = DijkstraShortestPath(g.graph)
        val path = shortestPathDijkstra.getPath(this, payment.to)

        
        val toChannel = this.getChannelFromEdge(this.getNextEdgeInPath(path))
        val nextNode = toChannel.getOppositeNode(this)
        val tx = Transaction(payment.paymentId, payment.amount, this, nextNode)

        // If commit fails, raise error immediately
        try {
            toChannel.requestTx(tx)

            ongoingPayments[payment] = LocalPayment(payment, null, toChannel, tx)

            sendMessage(
                RequestPaymentMessage(MessageTypes.REQ_TX, this, nextNode, toChannel, payment, path)
            )
        } catch (e: IllegalStateException) {
            this.transactionsFailed++
            //TransactionStatusCounter.updateStatus(payment, TransactionStatus.FAILED)
            logger.warn("Could not start transaction because $toChannel has insufficient balance for amount ${tx.amount}")
        }

        this.stopMessageSending()
        return SimulationInput(this, sendingList, null)
    }

    private fun canLogMessage(message: Message): Boolean {
        return (
            message is PaymentMessage ||
            message is ParticipantMessage ||
            message is RebalancingMessage || message is FailRebalancingMessage ||
            message is ReviveMessage
        ) // && message.type != MessageTypes.UPDATE_R

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
        MessageCounter.count(message)

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
        var nOfProcessedMessages: Int
        var totalNumberOfProcessedMessages = 0
        do {
            val copyOfList = this.unprocessedMessages.toMutableList()
            this.unprocessedMessages = ArrayList()
            nOfProcessedMessages = 0
            var oldUnprocessedMessagesSize = 0

            for (mess in copyOfList) {
                if (this.canLogMessage(mess)) {
                    logger.debug("Retrying $mess")
                }
                sortMessage(mess)
                if (this.unprocessedMessages.size - oldUnprocessedMessagesSize == 0) { // Message got processed
                    nOfProcessedMessages++
                }
                oldUnprocessedMessagesSize = this.unprocessedMessages.size
            }

            totalNumberOfProcessedMessages += nOfProcessedMessages
        } while (nOfProcessedMessages != 0) // If a message gets processed, other unprocessable messages might also be processable now so we have to try again

        if (totalNumberOfProcessedMessages != 0) {
            logger.info("Processed ${totalNumberOfProcessedMessages} messages that couldn't be processed before")
        }

        if (this.unprocessedMessages.isNotEmpty()) {
            logger.info("${this.unprocessedMessages.size} unprocessed message(s)")
            for (mess in this.unprocessedMessages) {
                logger.debug("Unprocessed: $mess")
            }
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
        return SimulationInput(this, sendingList, startStopDesc)
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
        try {
            toChannel.requestTx(tx)
        } catch (e: IllegalStateException) {
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
        } else {
            this.transactionsCompleted++
            //TransactionStatusCounter.updateStatus(mes.payment, TransactionStatus.SUCCESS)
        }

        ongoingPayments.remove(mes.payment)
        this.checkIfRebalancingRequired()
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
        } else {
            this.transactionsFailed++
        }

        ongoingPayments.remove(mes.payment)
    }

    // Based on Pickhardt and Nowostawski - 2019 - Imbalance measure and proactive channel rebalancing
    fun getGiniCoefficient(): Double {
        var nom = 0.0
        var denom = 0.0

        for (i in paymentChannels) {
            for (j in paymentChannels) {
                nom += abs(i.getChannelBalanceCoefficient(this) - j.getChannelBalanceCoefficient(this))
                denom += j.getChannelBalanceCoefficient(this)
            }
        }

        return nom / (2.0 * denom)
    }

    fun checkIfRebalancingRequired() {
        if (this.getGiniCoefficient() >= this.REBALANCING_TRIGGER_POINT) {
            if (this.startStopDesc == null) {
                logger.info("Started rebalancing because Gini coefficient above ${this.REBALANCING_TRIGGER_POINT}")
                doesn't compile : // Add way to prevent starting discovery if it is already going
                this.startStopDesc = StartDescription(Steps.Discover, this)
            } else {
                logger.warn("Something else already reserved the startStopDesc!")
            }
        }  
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
