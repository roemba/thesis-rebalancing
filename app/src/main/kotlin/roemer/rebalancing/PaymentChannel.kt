package roemer.rebalancing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.IllegalArgumentException

class PaymentChannel(val node1: Node, val node2: Node, var balance1: Int, var balance2: Int) {
    var totalFunds = balance1 + balance2
    var pendingBalance1 = balance1
    var pendingBalance2 = balance2
    var pendingTransactions = HashSet<Transaction>()
    private val mutex = Mutex()

    fun getOppositeNode(vertex: Node): Node {
        if (!(this.isChannelNode(vertex))) {
            throw IllegalArgumentException("The given node does not belong to this channel!")
        }

        if (vertex === this.node1) {return this.node2}
        return this.node1
    }

    fun isChannelNode(vertex: Node): Boolean {
        return vertex === this.node1 || vertex === this.node2
    }

    suspend fun requestTx(tx: Transaction): Boolean {
        if (!(this.isChannelNode(tx.from) && this.isChannelNode(tx.to))) {
            throw IllegalArgumentException("The given nodes do not belong to this channel!")
        }

        mutex.withLock {
            if (tx in pendingTransactions) {
                return true
            }

            var newBalance1 = this.pendingBalance1
            var newBalance2 = this.pendingBalance2

            if (tx.from === this.node1) {
                newBalance2 += tx.amount
                newBalance1 -= tx.amount
            } else {
                newBalance1 += tx.amount
                newBalance2 -= tx.amount
            }

            if (newBalance1 < 0 || newBalance2 < 0) {
                return false
            }

            this.pendingBalance1 = newBalance1
            this.pendingBalance2 = newBalance2

            assert(this.totalFunds == this.pendingBalance1 + this.pendingBalance2)

            pendingTransactions.add(tx)
            println("Gave commit for $tx on $this")
            return true
        }
    }

    suspend fun executeTx(tx: Transaction): Boolean {
        mutex.withLock {
            if (tx !in pendingTransactions) {
                return false
            }

            if (tx.from === this.node1) {
                this.balance2 += tx.amount
                this.balance1 -= tx.amount
            } else {
                this.balance1 += tx.amount
                this.balance2 -= tx.amount
            }

            pendingTransactions.remove(tx)

            assert(this.totalFunds == this.balance1 + this.balance2)

            println("Executing commit for $tx on $this")
            return true
        }
    }

    suspend fun abortTx(tx: Transaction): Boolean {
        mutex.withLock {
            if (tx !in pendingTransactions) {
                return false
            }

            pendingTransactions.remove(tx)

            if (tx.from === this.node1) {
                this.pendingBalance2 -= tx.amount
                this.pendingBalance1 += tx.amount
            } else {
                this.pendingBalance1 -= tx.amount
                this.pendingBalance2 += tx.amount
            }

            assert(this.totalFunds == this.pendingBalance1 + this.pendingBalance2)

            println("Aborting for $tx on $this")
            return true
        }
    }

    fun hasOngoingTx(): Boolean {
        if (this.pendingTransactions.isEmpty()) {
            assert(this.pendingBalance1 == this.balance1 && this.pendingBalance2 == this.balance2)
            return false
        }
        return true
    }

    override fun toString(): String {
        return "Channel(balance1=$balance1,balance2=$balance2,totalFunds=$totalFunds,node1=$node1,node2=$node2)"
    }

    override fun equals(other: Any?): Boolean {
        return (other is PaymentChannel) && (this.toString() == other.toString())
    }

    override fun hashCode(): Int {
        return this.toString().hashCode()
    }


}
