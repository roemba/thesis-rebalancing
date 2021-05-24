package roemer.rebalancing

import kotlin.random.Random
import java.util.UUID

class SeededRandom {
    companion object {
        val random = Random(42)
        var counter = 0

        fun getRandomUUID(): UUID {
            val uuid = UUID.nameUUIDFromBytes(counter.toString().encodeToByteArray())
            counter++
            return uuid
        }
    } 
}