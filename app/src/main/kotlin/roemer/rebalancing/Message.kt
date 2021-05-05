package roemer.rebalancing

import org.jgrapht.GraphPath
import org.jgrapht.graph.DefaultWeightedEdge
import java.util.UUID

enum class MessageTypes {
    REQ_TX, EXEC_TX, ABORT_TX, INVITE_P, ACCEPT_P, FINISH_P, DENY_P
}

abstract class Message(
    val type: MessageTypes,
    val sender: Node,
    val recipient: Node,
    val channel: PaymentChannel
    ) {
        init {
            assert(channel.isChannelNode(sender))
            assert(channel.isChannelNode(recipient))
        }
    }

open class PaymentMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    val payment: Payment
    ): Message(type, sender, recipient, channel) {
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
    channel: PaymentChannel,
    payment: Payment,
    val path: GraphPath<Node, DefaultWeightedEdge>
): PaymentMessage(type, sender, recipient, channel, payment) {
    override fun toString(): String {
        return "RequestPaymentMessage(type=$type, payment=$payment, sender=$sender, recipient=$recipient, path=$path)"
    }
}

open class ParticipantMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    val executionId: UUID
): Message(type, sender, recipient, channel) {
    override fun toString(): String {
        return "ParticipantMessage(type=$type, sender=$sender, recipient=$recipient, executionId=$executionId)"
    }
}

class InviteParticipantMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    executionId: UUID,
    val hopCount: Int
): ParticipantMessage(type, sender, recipient, channel, executionId) {
    override fun toString(): String {
        return "InviteParticipantMessage(type=$type, sender=$sender, recipient=$recipient, executionId=$executionId, hopCount=$hopCount)"
    }
}

open class AcceptParticipantMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    executionId: UUID,
    val participants: Set<UUID>
): ParticipantMessage(type, sender, recipient, channel, executionId) {
    override fun toString(): String {
        return "AcceptParticipantMessage(type=$type, sender=$sender, recipient=$recipient, executionId=$executionId, participants=$participants)"
    }
}

class FinishParticipantMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    executionId: UUID,
    participants: Set<UUID>
): AcceptParticipantMessage(type, sender, recipient, channel, executionId, participants) {
    override fun toString(): String {
        return "FinishParticipantMessage(type=$type, sender=$sender, recipient=$recipient, executionId=$executionId, participants=$participants)"
    }
}
