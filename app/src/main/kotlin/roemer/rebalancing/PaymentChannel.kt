package roemer.rebalancing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.IllegalArgumentException
import org.jgrapht.graph.DefaultWeightedEdge
import java.util.UUID

class PaymentChannel(val node1: Node, val node2: Node, val edges: Array<DefaultWeightedEdge>, var balance1: Int, var balance2: Int) {
    val id = UUID.randomUUID()
    var totalFunds = balance1 + balance2
        private set
    var pendingBalance1 = balance1
        private set
    var pendingBalance2 = balance2
        private set
    var pendingTransactions = HashSet<Transaction>()
        private set
    var htlcTransactions = HashMap<Transaction, ByteArray>()
        private set
    private val mutex = Mutex()
    var locked: Boolean = false
        private set
    
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

    suspend fun requestTx(tx: Transaction, htlc: ByteArray? = null, overrideLock: Boolean = false): Boolean {
        if (!(this.isChannelNode(tx.from) && this.isChannelNode(tx.to))) {
            throw IllegalArgumentException("The given nodes do not belong to this channel!")
        }

        mutex.withLock {
            if (!overrideLock && this.locked) {
                println("Channel $this is locked and no override has been given")
                return false
            }

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
                println("Channel $this has insufficient balance for the transaction $tx")
                return false
            }

            this.pendingBalance1 = newBalance1
            this.pendingBalance2 = newBalance2

            assert(this.totalFunds == this.pendingBalance1 + this.pendingBalance2)

            pendingTransactions.add(tx)
            if (htlc != null) {
                htlcTransactions.put(tx, htlc)
            }
            println("Gave commit for $tx on $this")
            return true
        }
    }

    suspend fun executeTx(tx: Transaction, htlc: ByteArray? = null): Boolean {
        mutex.withLock {
            if (tx !in pendingTransactions) {
                return false
            } else if (tx in htlcTransactions && (htlc == null || !(htlcTransactions.get(tx) contentEquals htlc))) {
                println("Incorrect htlc was provided to execute transaction!")
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

    @Throws(IllegalStateException::class)
    suspend fun getDemand(vertex: Node, absolute: Boolean = false): Int {
        mutex.withLock {
            this.locked = true

            if (this.hasOngoingTx()) {
                throw IllegalStateException("Cannot lock channel as there are still pending transactions, check back later!")
            }

            return getCurrentDemand(vertex, absolute)
        }
    }

    suspend fun getCurrentDemand(vertex: Node, absolute: Boolean = false): Int {
        var diff = (this.balance2 - this.balance1) / 2

        if (absolute) {
            return Math.abs(diff)
        } else if (vertex === this.node2) {
            diff *= -1
        }

        // println("Node $vertex gets demand $diff on channel $this")
        return diff
    }

    suspend fun unlock() {
        mutex.withLock {
            this.locked = false
        }
    }

    override fun toString(): String {
        return "Channel(balance1=$balance1,balance2=$balance2,totalFunds=$totalFunds,node1=$node1,node2=$node2,locked=$locked)"
    }

    override fun equals(other: Any?): Boolean {
        return (other is PaymentChannel) && (this.id == other.id)
    }

    override fun hashCode(): Int {
        return this.id.hashCode()
    }


}
