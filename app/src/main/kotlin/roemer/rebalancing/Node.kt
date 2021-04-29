package roemer.rebalancing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import org.jgrapht.GraphPath
import org.jgrapht.Graphs
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.DefaultWeightedEdge

class Node(val id: Int, val g: ChannelNetwork, var totalFunds: Int = 0) {
    val paymentChannels: MutableList<PaymentChannel> = ArrayList()
    val ongoingPayments: MutableMap<Payment, LocalPayment> = HashMap()
    val messageChannel = Channel<Message>(Channel.UNLIMITED)

    suspend fun startPayment(amount: Int, receiver: Node) {
        val payment = Payment(this, receiver, amount)

        val shortestPathDijkstra: DijkstraShortestPath<Node, DefaultWeightedEdge> = DijkstraShortestPath(g)
        val path = shortestPathDijkstra.getPath(this, receiver)

        val nextNode = this.getNextNodeInPath(path)
        val toChannel = this.getChannelForNode(nextNode)
        val tx = Transaction(payment.paymentId, payment.amount, this, nextNode)

        // If commit fails, raise error immediately
        if (!toChannel.requestTx(tx)) {
            throw TransactionAbortedException("Insufficient balance in $toChannel!")
        }

        ongoingPayments[payment] = LocalPayment(payment, null, toChannel, tx)

        sendMessage(
            RequestPaymentMessage(MessageTypes.REQ_TX, this, nextNode, payment, path)
        )
    }

    suspend fun sendMessage(message: Message) {
        val neighbours = Graphs.neighborListOf(g, this)
        for (neighbour in neighbours) {
            if (neighbour === message.recipient) {
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

            println("$this received $message")
            if (message.recipient !== this) {
                sendMessage(message)
                return
            }

            when (message.type) {
                MessageTypes.REQ_TX -> handleRequestTxMessage(message as RequestPaymentMessage)
                MessageTypes.EXEC_TX -> handleExecTxMessage(message as PaymentMessage)
                MessageTypes.ABORT_TX -> handleAbortTxMessage(message as PaymentMessage)
            }
        }
    }

    private suspend fun handleRequestTxMessage(mes: RequestPaymentMessage) {
        if (this === mes.path.endVertex) {
            sendMessage(
                PaymentMessage(MessageTypes.EXEC_TX, this, mes.sender, mes.payment)
            )
            return
        }

        // Check if payment already known
        if (mes.payment in ongoingPayments) {
            throw IllegalArgumentException("Payment ${mes.payment} already received by $this")
        }

        // Collect information to create localPayment
        val nextNode = this.getNextNodeInPath(mes.path)
        val previousNode = mes.sender
        val fromChannel = this.getChannelForNode(previousNode)
        val toChannel = this.getChannelForNode(nextNode)

        val tx = Transaction(mes.payment.paymentId, mes.payment.amount, this, nextNode)

        // If commit fails, send ABORT to sender of the message
        if (!toChannel.requestTx(tx)) {
            sendMessage(
                PaymentMessage(MessageTypes.ABORT_TX, this, previousNode, mes.payment)
            )
            return
        }

        ongoingPayments[mes.payment] = LocalPayment(mes.payment, fromChannel, toChannel, tx)

        sendMessage(
            RequestPaymentMessage(MessageTypes.REQ_TX, this, nextNode, mes.payment, mes.path)
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
                PaymentMessage(MessageTypes.EXEC_TX, this, localPayment.fromPaymentChannel.getOppositeNode(this), mes.payment)
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
                PaymentMessage(MessageTypes.ABORT_TX, this, localPayment.fromPaymentChannel.getOppositeNode(this), mes.payment)
            )
        }

        ongoingPayments.remove(mes.payment)
    }

    private fun getChannelForNode(node: Node): PaymentChannel {
        for (channel in paymentChannels) {
            if (channel.getOppositeNode(this) === node) {
                return channel
            }
        }

        throw IllegalArgumentException("$this has no channel with $node!")
    }

    private fun getNextNodeInPath(path: GraphPath<Node, DefaultWeightedEdge>): Node {
        for (edge in path.edgeList) {
            if (g.getEdgeSource(edge) === this) {
                return g.getEdgeTarget(edge)
            }
        }

        throw IllegalArgumentException("Path provided does not contain node $this!")
    }

    override fun toString(): String {
        return "Node(id=$id,totalFunds=$totalFunds)"
    }

    override fun equals(other: Any?): Boolean {
        return (other is Node) && (this.toString() == other.toString())
    }

    override fun hashCode(): Int {
        return this.toString().hashCode()
    }
}
