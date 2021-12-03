package roemer.rebalancing

import java.lang.IllegalArgumentException
import org.jgrapht.graph.DefaultWeightedEdge
import java.util.UUID
import kotlin.math.min

class PaymentChannel(val node1: Node, val node2: Node, val edges: Array<DefaultWeightedEdge>, var balance1: Int, var balance2: Int) {
    val id = this.node1.random.getRandomUUID()
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
    var locked: Boolean = false
        private set
    
    fun getOppositeNode(vertex: Node): Node {
        if (!(this.isChannelNode(vertex))) {
            throw IllegalArgumentException("The given node does not belong to this channel!")
        }

        if (vertex === this.node1) {return this.node2}
        return this.node1
    }

    fun getNodeChannelIdentifier(vertex: Node): String {
        if (!(this.isChannelNode(vertex))) {
            throw IllegalArgumentException("The given node does not belong to this channel!")
        }

        return "${vertex.hashCode()}-${this.hashCode()}"
    }

    fun isChannelNode(vertex: Node): Boolean {
        return vertex === this.node1 || vertex === this.node2
    }

    fun requestTx(tx: Transaction, htlc: ByteArray? = null, overrideLock: Boolean = false) {
        if (!(this.isChannelNode(tx.from) && this.isChannelNode(tx.to))) {
            throw IllegalArgumentException("The given nodes do not belong to this channel!")
        }

        if (!overrideLock && this.locked) {
            throw ChannelLockedException("Channel $this is locked and no override has been given")
        }

        if (tx in pendingTransactions) {
            throw IllegalArgumentException("Transaction $tx has already been requested!")
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
            throw InsufficientFundsException("Channel $this has insufficient balance for the transaction $tx")
        }

        this.pendingBalance1 = newBalance1
        this.pendingBalance2 = newBalance2

        assert(this.totalFunds == this.pendingBalance1 + this.pendingBalance2)

        pendingTransactions.add(tx)
        if (htlc != null) {
            htlcTransactions.put(tx, htlc)
        }
        // println("Gave commit for $tx on $this")
    }

    fun executeTx(tx: Transaction, htlc: ByteArray? = null): Boolean {
        if (tx !in pendingTransactions) {
            throw IllegalArgumentException("Transaction $tx was never requested!")
        } else if (tx in htlcTransactions && (htlc == null || !(htlcTransactions.get(tx) contentEquals htlc))) {
            throw IllegalArgumentException("Incorrect htlc was provided to execute transaction $tx!")
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

        // println("Executing $tx on $this")
        return true
    }

    fun abortTx(tx: Transaction): Boolean {
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

        return true
    }

    fun hasOngoingTx(): Boolean {
        if (this.pendingTransactions.isEmpty()) {
            assert(this.pendingBalance1 == this.balance1 && this.pendingBalance2 == this.balance2)
            return false
        }
        return true
    }

    fun getDemand(vertex: Node?): Int {
        if (!this.locked) {
            this.lock()
        }

        return getCurrentDemand(vertex)
    }

    fun getCurrentDemand(vertex: Node?): Int {
        var diff = (min(this.balance2, this.pendingBalance2) - min(this.balance1, this.pendingBalance1)) / 2

        if (vertex == null) {
            return Math.abs(diff)
        }
        
        if (!this.isChannelNode(vertex)) {
            throw IllegalArgumentException("The given node does not belong to this channel!")
        }
        
        if (vertex === this.node2) {
            diff *= -1
        }

        // println("Node $vertex gets demand $diff on channel $this")
        return diff
    }

    fun getBalance(vertex: Node): Int {
        if (!this.isChannelNode(vertex)) {
            throw IllegalArgumentException("The given node does not belong to this channel!")
        }

        if (vertex === this.node1) {return this.balance1}
        return this.balance2
    }

    fun getChannelBalanceCoefficient(vertex: Node): Double {
        if (!this.isChannelNode(vertex)) {
            throw IllegalArgumentException("The given node does not belong to this channel!")
        }

        return this.getBalance(vertex).toDouble() / this.totalFunds
    }

    fun lock() {
        this.locked = true
    }

    fun unlock() {
        this.locked = false
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
