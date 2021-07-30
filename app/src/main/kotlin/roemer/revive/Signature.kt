package roemer.revive

import roemer.rebalancing.Node

// Dummy signature class

data class Signature (
    val signer: Node,
    val data: ByteArray
)