package roemer.rebalancing

import kotlin.random.Random
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SeededRandom {
    companion object {
        val random = Random(42)
        var counter = 0L

       fun getRandomUUID(): UUID {
            val uuid = UUID.nameUUIDFromBytes(counter.toString().encodeToByteArray())
            counter++
            return uuid
        }

        fun getIncreasingLong(): Long {
            return counter++
        }
    } 
}