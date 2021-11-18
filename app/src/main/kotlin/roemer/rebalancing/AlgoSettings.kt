package roemer.rebalancing

data class AlgoSettings (
    val hopCount: Int,
    val maxNumberOfInvites: Int,
    val percentageOfLeaders: Float
) {
    fun toFileName(): String {
        return "${hopCount}_${maxNumberOfInvites}_${percentageOfLeaders}"
    }
}