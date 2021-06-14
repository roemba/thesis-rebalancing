package roemer.rebalancing

class StateMachine<T : Enum<T>> (val logger: Logger, startState: T) {
    var state = startState
        set(newState) {
            if (newState > state) {
                logger.debug("Setting new state $newState")
                field = newState
            }
        }

    fun isInState(otherState: T): Boolean {
        return state == otherState
    }

    override fun toString(): String {
        return "StateMachine(state=$state)"
    }

    override fun equals(other: Any?): Boolean {
        return (other is StateMachine<*>) && (this.state == other.state)
    }

    override fun hashCode(): Int {
        return this.state.hashCode()
    }
}