package roemer.rebalancing

import java.util.UUID

class Tag: Comparable<Tag> {
    val id: UUID
    val creator: Node

    private constructor(id: UUID, creator: Node) {
        this.id = id
        this.creator = creator
    }

    override operator fun compareTo(other: Tag): Int {
        return id.compareTo(other.id)
    }

    companion object {
        fun createTag(creator: Node): Tag {
            return Tag(creator.random.getRandomUUID(), creator) // Int = SeededRandom.getRandomInt() // 
        }
    }

    override fun toString(): String {
        if (true || creator.id in arrayOf(2520, 5419)) {
            return "Tag(creat=$creator, id=$id)"
        } else {
            return ""
        }
    }

    override fun equals(other: Any?): Boolean {
        return (other is Tag) && (this.id == other.id)
    }

    override fun hashCode(): Int {
        return this.id.hashCode()
    }
}