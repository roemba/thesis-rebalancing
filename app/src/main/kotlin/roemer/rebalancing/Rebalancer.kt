package roemer.rebalancing

interface Rebalancer {
    suspend fun rebalance(hopCount: Int, maxNOfInvites: Int)
    suspend fun rebalancingClient()
    fun isRebalancingAwake(): Boolean
}