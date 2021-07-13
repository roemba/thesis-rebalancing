package roemer.rebalancing

interface Rebalancer {
    fun startSubAlgos(hopCount: Int, maxNOfInvites: Int): SimulationInput
    fun rebalance(event: StartStopEvent): SimulationInput
    fun isRebalancingAwake(): Boolean
}