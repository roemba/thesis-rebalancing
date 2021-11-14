package roemer.rebalancing

import kotlin.random.Random
import java.util.UUID
import org.apache.commons.math3.random.Well19937c

class SeededRandom (val seed: Int) {
    val random = Random(seed)
    val apacheGenerator = Well19937c(seed)
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