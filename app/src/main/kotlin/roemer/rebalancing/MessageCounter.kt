package roemer.rebalancing

class MessageCounter {
    private val counters: MutableMap<MessageTypes, Int> = HashMap()

    fun count(mes: Message) {
        var count = counters.getOrPut(mes.type) { 0 }
        count++
        counters.put(mes.type, count)
    }

    fun printCounts() {
        println("Message counts per type:")
        var partSum = 0
        var rebalanceSum = 0
        for (entry in counters.entries) {
            println("${entry.key}: ${entry.value}")
            if (entry.key in arrayOf(MessageTypes.REQ_TX, MessageTypes.EXEC_TX, MessageTypes.ABORT_TX)) {
                continue
            } else if (entry.key in arrayOf(MessageTypes.INVITE_P, MessageTypes.ACCEPT_P, MessageTypes.FINISH_P, MessageTypes.DENY_P)) {
                partSum += entry.value
            } else {
                rebalanceSum += entry.value
            }
        }
        println("----------------")
        println("Total participant mes: $partSum")
        println("Total rebalance mes: $rebalanceSum")
        println("")
    }
}
