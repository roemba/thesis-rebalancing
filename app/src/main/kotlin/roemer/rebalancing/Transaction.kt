package roemer.rebalancing

import java.util.*

data class Transaction(
    val paymentId: UUID,
    val amount: Int,
    val from: Node,
    val to: Node,
    val id: UUID = UUID.randomUUID()
) {}
