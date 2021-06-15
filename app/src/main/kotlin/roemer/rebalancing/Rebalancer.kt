package roemer.rebalancing

interface Rebalancer {
    suspend fun rebalance(hopCount: Int)
    suspend fun rebalancingClient()
    fun isRebalancingAwake(): Boolean
}