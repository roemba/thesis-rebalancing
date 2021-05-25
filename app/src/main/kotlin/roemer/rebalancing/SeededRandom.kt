package roemer.rebalancing

import kotlin.random.Random
import java.util.UUID

class SeededRandom {
    companion object {
        val random = Random(422)
        var counter = 0

        fun getRandomUUID(): UUID {
            val uuid = UUID.nameUUIDFromBytes(counter.toString().encodeToByteArray())
            counter++
            return uuid
        }

        fun getRandomInt(): Int {
            return counter++
        }
    } 
}