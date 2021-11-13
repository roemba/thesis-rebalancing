package roemer.rebalancing

abstract class Event (
    val time: Long,
    val random: SeededRandom
): Comparable<Event> {
    val id: Long

    init {
        this.id = this.random.getIncreasingLong()
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
    random: SeededRandom,
    val message: Message
): Event(time, random)

class StartDescription (
    val step: Steps,
    val recipient: Node
)

class StartEvent (
    time: Long,
    random: SeededRandom,
    val desc: StartDescription
): Event(time, random)

data class SimulationInput (
    val creator: Node,
    val messages: List<Message>,
    val startStopDes: StartDescription?
)

class StartPaymentEvent (
    time: Long,
    random: SeededRandom,
    val payments: List<Payment>
): Event(time, random)