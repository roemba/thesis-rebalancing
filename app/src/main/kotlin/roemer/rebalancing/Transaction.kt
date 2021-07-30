package roemer.rebalancing

import java.util.*

open class Transaction(
    val paymentId: UUID,
    val amount: Int,
    val from: Node,
    val to: Node,
    val id: UUID = UUID.randomUUID()
) {
    override fun toString(): String {
        return "Transaction(paymentId=$paymentId, amount=$amount, from=$from, to=$to, id=$id)"
    }

    override fun equals(other: Any?): Boolean {
        return (other is Transaction) && 
        (this.paymentId == other.paymentId) && 
        (this.amount == other.amount) && 
        (this.id == other.id) &&
        (this.from == other.from) &&
        (this.to == other.to)
    }

    override fun hashCode(): Int {
        return Objects.hash(paymentId, amount, from, to, id)
    }
}

class ChannelTransaction (
    paymentId: UUID,
    amount: Int,
    from: Node,
    to: Node,
    id: UUID = UUID.randomUUID(),
    val channel: PaymentChannel
): Transaction(paymentId, amount, from, to, id) {
    init {
        if (!channel.isChannelNode(from) || !channel.isChannelNode(to)) {
            throw IllegalArgumentException("Both from and to nodes are required to belong the the paymentchannel!")
        }
    }

    override fun toString(): String {
        return "ChannelTransaction(paymentId=$paymentId, amount=$amount, from=$from, to=$to, id=$id, channel=$channel)"
    }

    override fun equals(other: Any?): Boolean {
        return super.equals(other) && (other is ChannelTransaction) && (this.channel == other.channel)
    }

    override fun hashCode(): Int {
        return Objects.hash(super.hashCode(), channel)
    }
}