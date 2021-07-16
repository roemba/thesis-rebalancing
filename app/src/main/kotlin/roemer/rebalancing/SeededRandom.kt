package roemer.rebalancing

import kotlin.random.Random
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SeededRandom {
    companion object {
        val random = Random(42)
        var counter = 0L
        val uuidStore: MutableSet<UUID> = HashSet()

       fun getRandomUUID(): UUID {
            val uuid = UUID.nameUUIDFromBytes(counter.toString().encodeToByteArray())
            counter++
            if (uuid in uuidStore) {
                throw IllegalStateException("Duplicate UUID generated!")
            }
            uuidStore.add(uuid)
            return uuid
        }

        fun getIncreasingLong(): Long {
            return counter++
        }
    } 
}