package roemer.rebalancing

interface Rebalancer {
    fun startSubAlgos(algoSettings: AlgoSettings): SimulationInput?
    fun rebalance(event: StartEvent): SimulationInput
    fun isRebalancingAwake(): Boolean
}