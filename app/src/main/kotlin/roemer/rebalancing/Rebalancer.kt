package roemer.rebalancing

interface Rebalancer {
    fun startSubAlgos(algoSettings: Map<String, Any>): SimulationInput
    fun rebalance(event: StartStopEvent): SimulationInput
    fun isRebalancingAwake(): Boolean
}