package roemer.rebalancing

import kotlin.math.pow
import kotlin.math.sqrt

class Counter {
    val messageCounters: MutableMap<MessageTypes, Int> = HashMap()
    var nOfCycles: Int = 0

    fun countMessage(mes: Message) {
        var count = messageCounters.getOrPut(mes.type) { 0 }
        count++
        messageCounters[mes.type] = count
    }

    fun countOwnedCycles(nOfOwnedCycles: Int) {
        this.nOfCycles += nOfOwnedCycles
    }

    fun printCounts() {
        println("Message counts per type:")
        var partSum = 0
        var rebalanceSum = 0
        for (entry in this.messageCounters.entries) {
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
        println("Number of cycles: $nOfCycles")
        println("Total participant mes: $partSum")
        println("Total rebalance mes: $rebalanceSum")
        println("")
    }
}

class Statistics {
    var nOfRuns = 0
    val runCounters: MutableMap<MessageTypes, MutableList<Int>> = HashMap()
    val runSums: MutableMap<MessageTypes, Int> = HashMap()

    val runNOfCycles: MutableList<Int> = ArrayList()

    fun process(counter: Counter) {
        for ((key, value) in counter.messageCounters.entries) {
            val runCounter = this.runCounters.getOrPut(key, { ArrayList() })
            val runSum = this.runSums.getOrPut(key, { 0 })
            
            runCounter += value
            this.runSums[key] = runSum + value
        }

        this.runNOfCycles += counter.nOfCycles
    }

    private fun printIntCollectionStatistics(name: String, list: Collection<Int>) {
        val mean = list.average()
        val variance: Double = list.map {it -> it.toDouble()} .fold(0.0) {accSum, currentEntry -> 
            val diff = currentEntry - mean
            accSum + (diff * diff)
        } * (1.0 / (list.size - 1))
        val std = sqrt(variance)
        println("$name: Max=${list.maxOrNull()}, Min=${list.minOrNull()}, Avg=$mean, Standard deviation=$std")
    }

    fun printStatistics() {
        println("Overall run statistics:")
        for ((key, value) in this.runCounters.entries) {
            this.printIntCollectionStatistics(key.toString(), value)
        }
        println("----------")
        this.printIntCollectionStatistics("Number of cycles", this.runNOfCycles)
        println()
    }


}