package roemer.rebalancing

import java.util.UUID

class Tag: Comparable<Tag> {
    val id: UUID

    private constructor(id: UUID) {
        this.id = id
    }

    override operator fun compareTo(other: Tag): Int {
        return id.compareTo(other.id)
    }

    companion object {
        suspend fun createTag(): Tag {
            return Tag(SeededRandom.getRandomUUID()) // Int = SeededRandom.getRandomInt() // 
        }
    }

    override fun toString(): String {
        return "Tag(id=$id)"
    }

    override fun equals(other: Any?): Boolean {
        return (other is Tag) && (this.id == other.id)
    }

    override fun hashCode(): Int {
        return this.id.hashCode()
    }
}