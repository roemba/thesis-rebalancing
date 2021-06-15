package roemer.rebalancing

import kotlin.random.Random
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SeededRandom {
    companion object {
        val random = Random(42)
        var counter = 0
        val lock = Mutex()

        suspend fun getRandomUUID(): UUID {
            lock.withLock() { 
                val uuid = UUID.nameUUIDFromBytes(counter.toString().encodeToByteArray())
                counter++
                return uuid
            }
        }

        suspend fun getRandomInt(): Int {
            lock.withLock() { 
                return counter++
            }
        }
    } 
}