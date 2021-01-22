package roemer.rebalancingGroups

import java.util.*

data class Payment(
    val from: Node,
    val to: Node,
    val amount: Int,
    val paymentId: UUID = UUID.randomUUID()
) {}

data class LocalPayment(
    val payment: Payment,
    val fromChannel: Channel?,
    val toChannel: Channel,
    val toTx: Transaction
)
