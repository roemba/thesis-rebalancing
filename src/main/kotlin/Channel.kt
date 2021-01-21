package roemer.rebalancingGroups

class Channel(val node1: Node, val node2: Node, balance1: Int = 0, balance2: Int = 0) {
    var balance1: Int = balance1
        private set
    var balance2: Int = balance2
        private set

    override fun toString(): String {
        return "Channel(balance1=$balance1,balance2=$balance2,node1=${node1.toString()},node2=${node2.toString()})"
    }


}