package roemer.rebalancing

abstract class Event (
    val time: Long
): Comparable<Event> {
    val id: Long

    init {
        this.id = SeededRandom.getIncreasingLong()
    }

    override fun compareTo (other: Event): Int {
        val res = this.time.compareTo(other.time)
        if (res == 0) {
            println("TIME CONFLICT - ${this.id}-${other.id}")
            return this.id.compareTo(other.id)
        }
        return res
    }
}

class MessageEvent (
    time: Long,
    val message: Message
): Event(time)

class StartStopDescription (
    val start: Boolean,
    val algorithm: Algorithm,
    val recipient: Node?
)

class StartStopEvent (
    time: Long,
    val desc: StartStopDescription
): Event(time)

typealias SimulationInput = Pair<List<Message>, StartStopDescription?>