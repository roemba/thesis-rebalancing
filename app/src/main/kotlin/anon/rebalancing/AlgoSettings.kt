package anon.rebalancing

data class AlgoSettings (
    val hopCount: Int,
    val maxNumberOfInvites: Int,
    val percentageOfLeaders: Float,
    val randomizeChosenEdgesToInvite: Boolean
) {
    fun toFileName(): String {
        return "${hopCount}_${maxNumberOfInvites}_${percentageOfLeaders}"
    }
}