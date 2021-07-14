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
        fun createTag(vertex: Node): Tag {
            return Tag(SeededRandom.getRandomUUID(), vertex) // Int = SeededRandom.getRandomInt() // 
        }
    }

    override fun toString(): String {
        return "Tag(creat=$creator, id=$id)"
    }

    override fun equals(other: Any?): Boolean {
        return (other is Tag) && (this.id == other.id)
    }

    override fun hashCode(): Int {
        return this.id.hashCode()
    }
}