package roemer.rebalancingGroups

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.*
import kotlin.collections.ArrayList

@ExperimentalCoroutinesApi
class GraphHolder(fileName: String) {

    val paymentChannels: MutableList<PaymentChannel> = ArrayList()
    private val g = ChannelNetwork()

    init {
        val resourceName = this::class.java.classLoader.getResource(fileName)!!.file
        val graphFileReader = Scanner(File(resourceName))
        val nOfNodes = graphFileReader.nextInt()
        graphFileReader.nextLine()

        val nodes: Array<Node?> = arrayOfNulls(nOfNodes)

        runBlocking {
            for (i in 0 until nOfNodes) {
                val n = Node(i, g)
                g.addVertex(n)
                nodes[i] = n
                launch { n.receiveMessage() }
            }

            val edgePattern = "\\d-\\d"
            while (graphFileReader.hasNext(edgePattern)) {
                val edgeString = graphFileReader.next(edgePattern)
                val nodeIds = edgeString.split("-").map { it.toInt() }

                g.addChannel(nodes[nodeIds[0]]!!, nodes[nodeIds[1]]!!, 5, 5)
            }
            println("Continued while nodes are waiting")
            println("\nStarting test payment 1, amount 2\n")
            printChannelBalances()
            nodes[4]!!.startPayment(2, nodes[1]!!)

            //delay(2000)
            println("\nStarting test payment 2, amount 2\n")
            //printChannelBalances()
            nodes[4]!!.startPayment(2, nodes[1]!!)

            //delay(2000)
            println("\nStarting test payment 3, amount 2\n")
            //printChannelBalances()
            nodes[2]!!.startPayment(2, nodes[4]!!)

            delay(2000)
            println("\nStarting test payment 4, amount 2\n")
            //printChannelBalances()

            try {
                nodes[4]!!.startPayment(2, nodes[1]!!)
            } catch (e: TransactionAbortedException) {
                println(e)
            }

            delay(2000)
            println()
            printChannelBalances()
        }
    }

    private fun printChannelBalances() {
        println("Channel balances:")
        for (channel in g.getChannelSet()) {
            println("$channel, Ongoing=${channel.hasOngoingTx()}")
        }
        println()
    }
}
