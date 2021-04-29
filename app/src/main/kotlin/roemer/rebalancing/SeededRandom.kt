package roemer.rebalancing

import kotlin.random.Random

class SeededRandom {
    companion object {
        val random = Random(42)
    } 
}