package roemer.rebalancing

import java.util.UUID

enum class TransactionStatus {
    SUCCESS, FAILED, UNKNOWN
}

class TransactionStatusCounter {
    companion object {
        private val uuidToIndexMap: MutableMap<UUID, Int> = HashMap()
        val statusList: MutableList<TransactionStatus> = ArrayList()

        fun updateStatus(payment: Payment, status: TransactionStatus) {
            if (payment.paymentId !in uuidToIndexMap) {
                val index = statusList.size
                statusList.add(status)
                uuidToIndexMap.put(payment.paymentId, index)

                return
            }

            statusList[uuidToIndexMap[payment.paymentId]!!] = status
        }
    } 
}
