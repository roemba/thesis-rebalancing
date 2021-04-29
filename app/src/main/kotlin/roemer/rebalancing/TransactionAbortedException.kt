package roemer.rebalancing

class TransactionAbortedException private constructor(message: String) : Exception(message) {
    companion object {
        operator fun invoke(message: String): TransactionAbortedException {
            return TransactionAbortedException("Transaction aborted because: $message")
        }
    }
}
