package roemer.revive

import roemer.rebalancing.Message
import roemer.rebalancing.MessageTypes
import roemer.rebalancing.PaymentChannel
import roemer.rebalancing.Node
import roemer.rebalancing.Tag
import roemer.rebalancing.Payment

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
