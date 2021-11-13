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
        var sum = 0
        for (entry in counters.entries) {
            println("${entry.key}: ${entry.value}")
            sum += entry.value
        }
        println("----------------")
        println("Total: $sum")
        println("")
    }
}
