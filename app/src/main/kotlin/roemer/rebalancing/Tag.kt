package roemer.rebalancing

import java.util.UUID

data class Tag (
    val id: UUID = SeededRandom.getRandomUUID() // Int = SeededRandom.getRandomInt() // 
) : Comparable<Tag> {
    override operator fun compareTo(other: Tag): Int {
        return id.compareTo(other.id)
    }
}