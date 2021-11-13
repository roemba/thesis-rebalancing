package roemer.rebalancing

import org.apache.commons.math3.distribution.ExponentialDistribution
import kotlin.math.ceil
import kotlin.math.round

class TransactionGenerator (val nodes: List<Node>, val transactionsPerInterval: Int, val maxTransactions: Int, val logger: Logger) {
    val random = nodes[0].random
    val senderRecipientExpDistribution = ExponentialDistribution(this.random.apacheGenerator, 1.0)
    val valueExpDistribution = ExponentialDistribution(this.random.apacheGenerator, 1.0 / 15.0)
    val EURO_SATOSHI_EXC_RATE = 1731 // On 10-11-2021
    val TRANSACTION_INTERVAL_IN_MS = 500

    var nOfTransactionsGenerated = 0

    private fun getSenderRecipientIndex (): Int {
        var index = nodes.size + 10
        while (index >= nodes.size) {
            index = ceil(senderRecipientExpDistribution.sample() * nodes.size / 4.6).toInt()
        }
        return index
    }

    fun generateTransactions (currentTime: Long): StartPaymentEvent? {
        if (this.nOfTransactionsGenerated >= this.maxTransactions) {
            logger.warn("Max number of transactions reached")
            return null
        }

        val payments: MutableList<Payment> = ArrayList()
        for (i in 0 until this.transactionsPerInterval) {
            payments.add(this.generateTransaction())
        }

        this.nOfTransactionsGenerated += payments.size
        
        return StartPaymentEvent(currentTime + this.TRANSACTION_INTERVAL_IN_MS, this.random, payments)
    }

    fun generateTransaction (): Payment {
        val senderIndex = this.getSenderRecipientIndex()

        var receiverIndex = senderIndex
        while (receiverIndex == senderIndex) {
            receiverIndex = this.getSenderRecipientIndex()
        }

        val sender = nodes[senderIndex]
        val receiver = nodes[receiverIndex]

        val value = round(valueExpDistribution.sample() * (512 * this.EURO_SATOSHI_EXC_RATE) / 4.6).toInt()

        logger.debug("Generated transaction from $sender to $receiver with $value satoshis or ${value/this.EURO_SATOSHI_EXC_RATE} euros")

        return Payment(sender, receiver, value)
    }
}