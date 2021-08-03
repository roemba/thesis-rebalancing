package roemer.revive

import roemer.rebalancing.Message
import roemer.rebalancing.MessageTypes
import roemer.rebalancing.PaymentChannel
import roemer.rebalancing.Node
import roemer.rebalancing.Tag
import roemer.rebalancing.Payment
import roemer.rebalancing.ChannelTransaction

open class ReviveMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    val executionId: Tag
): Message(type, sender, recipient) {
    override fun toString(): String {
        return "ReviveMessage(type=$type, sender=$sender, recipient=$recipient, executionId=$executionId)"
    }
}

class StartRoundMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    executionId: Tag,
    val participants: List<Node>
): ReviveMessage(type, sender, recipient, executionId) {
    override fun toString(): String {
        return "StartRoundMessage(type=$type, sender=$sender, recipient=$recipient, executionId=$executionId)"
    }
}

class DemandMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    executionId: Tag,
    val channelsToRebalance: Set<PaymentChannel>
): ReviveMessage(type, sender, recipient, executionId) {
    override fun toString(): String {
        return "DemandMessage(type=$type, sender=$sender, recipient=$recipient, executionId=$executionId)"
    }
}

class SigningRequestMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    executionId: Tag,
    val transactions: List<ChannelTransaction>,
    val digest: ByteArray
): ReviveMessage(type, sender, recipient, executionId) {
    override fun toString(): String {
        return "SigningRequestMessage(type=$type, sender=$sender, recipient=$recipient, digest=$digest)"
    }
}

class SignedTxSetMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    executionId: Tag,
    val signature: Signature,
): ReviveMessage(type, sender, recipient, executionId) {
    override fun toString(): String {
        return "SignedTxSetMessage(type=$type, sender=$sender, recipient=$recipient, signature=$signature)"
    }
}

class CompleteTxSetMessage(
    type: MessageTypes,
    sender: Node,
    recipient: Node,
    executionId: Tag,
    val digest: ByteArray,
    val signatures: List<Signature>,
): ReviveMessage(type, sender, recipient, executionId) {
    override fun toString(): String {
        return "CompleteTxSetMessage(type=$type, sender=$sender, recipient=$recipient, digest=$digest)"
    }
}

