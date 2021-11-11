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
            // println("TIME CONFLICT - ${this.id}-${other.id}")
            return this.id.compareTo(other.id)
        }
        return res
    }
}

class MessageEvent (
    time: Long,
    val message: Message
): Event(time)

class StartDescription (
    val step: Steps,
    val recipient: Node
)

class StartEvent (
    time: Long,
    val desc: StartDescription
): Event(time)

data class SimulationInput (
    val creator: Node,
    val messages: List<Message>,
    val startStopDes: StartDescription?
)

class StartPaymentEvent (
    time: Long,
    val payments: List<Payment>
): Event(time)