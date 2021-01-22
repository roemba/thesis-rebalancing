package roemer.rebalancingGroups

import org.jgrapht.GraphPath
import org.jgrapht.graph.DefaultWeightedEdge

enum class MessageTypes {
    REQ_TX, EXEC_TX, ABORT_TX
}

abstract class Message(
    val type: MessageTypes,
    val sender: Node,
    val recipient: Node
    ) {}

open class PaymentMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    val payment: Payment
    ): Message(type, sender, recipient) {
    override fun equals(other: Any?): Boolean {
        return (other is PaymentMessage) && (this.toString() == other.toString())
    }

    override fun hashCode(): Int {
        return this.toString().hashCode()
    }

    override fun toString(): String {
        return "TransactionMessage(type=$type, payment=$payment, sender=$sender, recipient=$recipient)"
    }
}

class RequestPaymentMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    payment: Payment,
    val path: GraphPath<Node, DefaultWeightedEdge>
): PaymentMessage(type, sender, recipient, payment) {
    override fun toString(): String {
        return "RequestPaymentMessage(type=$type, payment=$payment, sender=$sender, recipient=$recipient, path=$path)"
    }
}
