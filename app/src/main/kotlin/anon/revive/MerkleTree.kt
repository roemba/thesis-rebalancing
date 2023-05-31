package anon.rebalancing

import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.roundToInt
import java.math.BigInteger

data class MerkleNode (
    val leftChild: MerkleNode?,
    val rightChild: MerkleNode?,
    val data: Any?
) {
    val hashFunc = MessageDigest.getInstance("SHA-256")

    init {
        if (data != null && (leftChild != null || rightChild != null)) {
            throw IllegalArgumentException("Data cannot be set while the MerkleNode also has children!")
        }
    }

    fun digest(): ByteArray {
        if (this.data != null) {
            return hashFunc.digest(BigInteger.valueOf(this.data.hashCode().toLong()).toByteArray())
        }

        var childDigest = this.leftChild!!.digest()
        if (this.rightChild != null) {
            childDigest += this.rightChild.digest()
        }

        return hashFunc.digest(childDigest)
    }
}

data class MerkleTree (
    val dataArray: Collection<Any>
) {
    val rootNode: MerkleNode

    init {
        val representingNodes: MutableList<MerkleNode> = ArrayList()

        for (data in dataArray) {
            representingNodes += MerkleNode(null, null, data)
        }

        var previousLayerNodes: MutableList<MerkleNode> = representingNodes.toMutableList()
        var newLayerNodes: MutableList<MerkleNode> = ArrayList()
        while (previousLayerNodes.size > 1) {
            for (i in 0 until previousLayerNodes.size step 2) {
                val leftChild = previousLayerNodes[i]
                var rightChild: MerkleNode? = null
                if (i + 1 < previousLayerNodes.size) {
                    rightChild = previousLayerNodes[i + 1]
                }
                newLayerNodes.add(MerkleNode(leftChild, rightChild, null))
            }

            previousLayerNodes = newLayerNodes.toMutableList()
            newLayerNodes = ArrayList()
        }

        this.rootNode = previousLayerNodes[0]
    }

    fun digest(): ByteArray {
        return this.rootNode.digest()
    }
}

