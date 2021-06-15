package roemer.rebalancing

import org.jgrapht.GraphPath
import org.jgrapht.graph.DefaultWeightedEdge
import java.util.UUID

enum class MessageTypes {
    REQ_TX, EXEC_TX, ABORT_TX, // Transaction execution
    INVITE_P, ACCEPT_P, FINISH_P, DENY_P, // Participant discovery
    COMMIT_R, REQUEST_R, SUCCESS_R, UPDATE_R, FAIL_R, EXEC_R, NEXT_ROUND_R, // Privacy-preserving rebalancing
    FAIL_REV, DENY_REV, INIT_REV, CONFIRM_REQ_REV, CONFIRM_REV, ROUND_CONFIRM_REV, DEMAND_REV, TX_SET_REV, SIGNED_TX_SET_REV, COMPLETE_TX_SET_REV // Revive  
}

abstract class Message(
    val type: MessageTypes,
    val sender: Node,
    val recipient: Node,
    )

open class ChannelMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    val channel: PaymentChannel
): Message(type, sender, recipient) {
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
    ): ChannelMessage(type, sender, recipient, channel) {
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
    val executionId: Tag
): ChannelMessage(type, sender, recipient, channel) {
    override fun toString(): String {
        return "ParticipantMessage(type=$type, sender=$sender, recipient=$recipient, executionId=$executionId, channel=${channel.id})"
    }
}

class InviteParticipantMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    executionId: Tag,
    val hopCount: Int,
    val maxNumberOfInvites: Int
): ParticipantMessage(type, sender, recipient, channel, executionId) {
    override fun toString(): String {
        return "InviteParticipantMessage(sender=$sender, recipient=$recipient, executionId=$executionId, hopCount=$hopCount, maxNumberOfInvites=$maxNumberOfInvites, channel=${channel.id})"
    }
}

open class AcceptParticipantMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    executionId: Tag,
    val participants: Set<Tag>
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
    executionId: Tag,
    participants: Set<Tag>
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
    val startId: Tag,
    val executionId: Tag
    ): ChannelMessage(type, sender, recipient, channel) {
    override fun toString(): String {
        return "RebalancingMessage(type=$type, startId=$startId, sender=$sender, recipient=$recipient)"
    }
}

open class RequestRebalancingMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    startId: Tag,
    executionId: Tag,
    val seenSet: Set<Tag>,
    ): RebalancingMessage(type, sender, recipient, channel, startId, executionId) {
    override fun toString(): String {
        return "RequestRebalancingMessage(startId=$startId, sender=$sender, recipient=$recipient, seenSet=$seenSet, channelId=${channel.id})"
    }
}

class UpdateRebalancingMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    startId: Tag,
    executionId: Tag,
    seenSet: Set<Tag>,
    ): RequestRebalancingMessage(type, sender, recipient, channel, startId, executionId, seenSet) {
    override fun toString(): String {
        return "UpdateRebalancingMessage(startId=$startId, sender=$sender, recipient=$recipient, seenSet=$seenSet, channelId=${channel.id})"
    }
}

open class SuccessRebalancingMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    startId: Tag,
    executionId: Tag,
    val tagList: List<TagDemandPair>,
    ): RebalancingMessage(type, sender, recipient, channel, startId, executionId) {
    override fun toString(): String {
        return "SuccessRebalancingMessage(startId=$startId, sender=$sender, recipient=$recipient, tagList=$tagList, channelId=${channel.id})"
    }
}

class CommitRebalancingMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    startId: Tag,
    executionId: Tag,
    val tagList: List<TagDemandHTLCPair>,
    val tagTxMap: Map<Tag, Transaction>
    ): RebalancingMessage(type, sender, recipient, channel, startId, executionId) {
    override fun toString(): String {
        return "CommitRebalancingMessage(startId=$startId, sender=$sender, recipient=$recipient, tagList=$tagList, channelId=${channel.id})"
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
    val startId: Tag?,
    val executionId: Tag?,
    ): ChannelMessage(type, sender, recipient, channel) {
    override fun toString(): String {
        return "FailRebalancingMessage(startId=$startId, sender=$sender, recipient=$recipient, reason=$reason, channelId=${channel.id})"
    }
}

class ExecuteRebalancingMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    startId: Tag,
    executionId: Tag,
    val tag: Tag,
    val preImage: String
    ): RebalancingMessage(type, sender, recipient, channel, startId, executionId) {
    override fun toString(): String {
        return "ExecuteRebalancingMessage(startId=$startId, sender=$sender, recipient=$recipient, tag=$tag, preImage=$preImage, channelId=${channel.id})"
    }
}

class NextRoundMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    channel: PaymentChannel,
    startId: Tag,
    executionId: Tag
    ): RebalancingMessage(type, sender, recipient, channel, startId, executionId) {
    override fun toString(): String {
        return "NextRoundMessage(startId=$startId, sender=$sender, recipient=$recipient, channelId=${channel.id})"
    }
}