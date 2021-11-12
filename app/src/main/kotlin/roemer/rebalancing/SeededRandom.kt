package roemer.rebalancing

import kotlin.random.Random
import java.util.UUID
import org.apache.commons.math3.random.Well19937c

class SeededRandom {
    companion object {
        lateinit var random: Random
        lateinit var apacheGenerator: Well19937c
        var counter = 0L
        lateinit var uuidStore: MutableSet<UUID>

        init {
            this.reset()
        }

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

        fun reset () {
            random = Random(42)
            apacheGenerator = Well19937c(20)
            counter = 0L
            uuidStore = HashSet()
        }
    } 
}