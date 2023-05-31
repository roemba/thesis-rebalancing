package anon.revive

import anon.rebalancing.Node

// Dummy signature class

data class Signature (
    val signer: Node,
    val data: ByteArray
)