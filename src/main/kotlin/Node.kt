package roemer.rebalancingGroups

class Node(val id: Int) {
    val channels: MutableList<Channel> = ArrayList()
    val neighbours: MutableSet<Node> = HashSet()

    fun addChannel(channel: Channel) {
        this.channels.add(channel)

        if (this != channel.node1) {
            neighbours.add(channel.node1)
        } else {
            neighbours.add(channel.node2)
        }
    }

    override fun toString(): String {
        return "Node(id=$id,n_of_channels=${channels.size})"
    }
}