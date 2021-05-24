package roemer.rebalancing

import org.jgrapht.GraphPath
import org.jgrapht.graph.DefaultWeightedEdge
import java.util.UUID

enum class MessageTypes {
    REQ_TX, EXEC_TX, ABORT_TX, INVITE_P, ACCEPT_P, FINISH_P, DENY_P, COMMIT_R, REQUEST_R, SUCCESS_R, UPDATE_R, FAIL_R, EXEC_R
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
        return "ParticipantMessage(type=$type, sender=$sender, recipient=$recipient, executionId=$executionId, channel=${channel.id})"
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
        return "InviteParticipantMessage(sender=$sender, recipient=$recipient, executionId=$executionId, hopCount=$hopCount, channel=${channel.id})"
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
        return "AcceptParticipantMessage(sender=$sender, recipient=$recipient, executionId=$executionId, channel=${channel.id})"//, participants=$participants)"
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
        return "FinishParticipantMessage(sender=$sender, recipient=$recipient, executionId=$executionId)"//, participants=$participants)"
    }
}

open class RebalancingMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    val startId: UUID,
    val executionId: UUID
    ): Message(type, sender, recipient, channel) {
    override fun toString(): String {
        return "RebalancingMessage(type=$type, startId=$startId, sender=$sender, recipient=$recipient)"
    }
}

open class RequestRebalancingMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    startId: UUID,
    executionId: UUID,
    val seenSet: Set<UUID>,
    ): RebalancingMessage(type, sender, recipient, channel, startId, executionId) {
    override fun toString(): String {
        return "RequestRebalancingMessage(type=$type, startId=$startId, sender=$sender, recipient=$recipient, seenSet=$seenSet)"
    }
}

class UpdateRebalancingMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    startId: UUID,
    executionId: UUID,
    seenSet: Set<UUID>,
    ): RequestRebalancingMessage(type, sender, recipient, channel, startId, executionId, seenSet) {
    override fun toString(): String {
        return "UpdateRebalancingMessage(type=$type, startId=$startId, sender=$sender, recipient=$recipient, seenSet=$seenSet)"
    }
}

open class SuccessRebalancingMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    startId: UUID,
    executionId: UUID,
    val tagList: List<TagDemandPair>,
    ): RebalancingMessage(type, sender, recipient, channel, startId, executionId) {
    override fun toString(): String {
        return "SuccessRebalancingMessage(type=$type, startId=$startId, sender=$sender, recipient=$recipient, tagList=$tagList)"
    }
}

class CommitRebalancingMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    startId: UUID,
    executionId: UUID,
    val tagList: List<TagDemandHTLCPair>,
    val tagTxMap: Map<UUID, Transaction>
    ): RebalancingMessage(type, sender, recipient, channel, startId, executionId) {
    override fun toString(): String {
        return "CommitRebalancingMessage(type=$type, startId=$startId, sender=$sender, recipient=$recipient, tagList=$tagList)"
    }
}

enum class FailReason {
    INCORRECT_ROUND, INCORRECT_EXECUTION_ID, NOT_AWAKE, NO_SUCCESS
}

class FailRebalancingMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    val reason: FailReason,
    val startId: UUID?,
    val executionId: UUID?,
    ): Message(type, sender, recipient, channel) {
    override fun toString(): String {
        return "FailRebalancingMessage(type=$type, startId=$startId, sender=$sender, recipient=$recipient, reason=$reason)"
    }
}

class ExecuteRebalancingMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    startId: UUID,
    executionId: UUID,
    val tag: UUID,
    val preImage: String
    ): RebalancingMessage(type, sender, recipient, channel, startId, executionId) {
    override fun toString(): String {
        return "ExecuteRebalancingMessage(type=$type, startId=$startId, sender=$sender, recipient=$recipient, tag=$tag, preImage=$preImage)"
    }
}