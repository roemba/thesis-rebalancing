package roemer.rebalancing

import roemer.revive.LpSolveDemo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.nio.file.Files

enum class Trials {
    NO_REBALANCING,
    PART_DISC,
    SCORE_VS_PERC_LEADERS,
    STATIC_REBALANCING_COMPARISON,
    DYNAMIC_REBALANCING_COMPARISON,
    COINWASHER_PARAM
}

fun main() {
    println("Program has started")

    // val tree1 = MerkleTree(listOf("test11", "test2", "test3", "test4", "test5"))
    // val tree2 = MerkleTree(listOf("test11", "test2", "test3", "test4", "test5"))
    // val tree1Dig = tree1.digest()
    // val tree2Dig = tree2.digest()

    // assert(tree1Dig contentEquals tree2Dig)

    // val statistics = Statistics()
    // for (i in 0 until 1) {
    //     val counter = Counter()
    //     val graph = GraphHolder.createGraphHolderFromTxtGraph("difficult_graph.txt", NodeTypes.CoinWasher, SeededRandom(i), counter)

    //     val algoSettings = AlgoSettings(3, 5, 1F)
        
    //     runBlocking {
    //         graph.start(algoSettings, false, Trials.SCORE_VS_PERC_LEADERS, false, Mutex(), "output_files/manual_test.csv")
    //     }
        
    //     statistics.process(counter)
    // }

    // statistics.printStatistics()
    
    // //val graph = GraphHolder("nodes_05-05-2021.json", "channels_05-05-2021.json", NodeTypes.CoinWasher)

    
    // return

    val trials = arrayOf(
        //Trials.NO_REBALANCING,
        //Trials.PART_DISC,
        Trials.SCORE_VS_PERC_LEADERS, 
        //Trials.REBALANCING_COMPARISON
    )
    runBlocking(Dispatchers.Default) {
        for (trial in trials) {
            var runCounter = 0
            for (seed in 0 until 10) {
                val seedMutex = Mutex()
                val dirName = "output_files/${trial}"
                val dir = File(dirName)

                if (!dir.exists()) {
                    dir.mkdirs()
                }

                if (trial == Trials.PART_DISC) {
                    val hopFileName = "$dirName/hopCount.csv"
                    val hopMutex = Mutex()
                    deleteFile(hopFileName)

                    for (hopCount in listOf(1, 5, 10, 15, 20, 25, 30)) {
                        val algoSettings = AlgoSettings(hopCount, 5, 1F)
                        launch { 
                            val graph = GraphHolder.createGraphHolderFromLightningTopology("nodes_05-05-2021.json", "channels_05-05-2021.json", NodeTypes.ParticipantDisc, SeededRandom(seed), Counter(), true)
                            graph.start(algoSettings, false, trial, false, hopMutex, hopFileName)
                            println("$seed - Finished $algoSettings")
                        }
                    }

                    val inviteFileName = "$dirName/maxNumberOfInvites.csv"
                    val inviteMutex = Mutex()
                    deleteFile(inviteFileName)
                    for (maxNumberOfInvites in listOf(1, 5, 10, 15, 20, 25, 30)) {
                        val algoSettings = AlgoSettings(3, maxNumberOfInvites, 1F)
                        launch { 
                            val graph = GraphHolder.createGraphHolderFromLightningTopology("nodes_05-05-2021.json", "channels_05-05-2021.json", NodeTypes.ParticipantDisc, SeededRandom(seed), Counter(), true)
                            graph.start(algoSettings, false, trial, false, inviteMutex, inviteFileName) 
                            println("$seed - Finished $algoSettings")
                        }
                    }
                } else if (trial == Trials.SCORE_VS_PERC_LEADERS) {
                    val scoreFileName = "$dirName/percentageLeaders.csv"
                    val scoreMutex = Mutex()
                    deleteFile(scoreFileName)

                    for (percentageLeader in listOf(0.01F, 0.05F, 0.1F, 0.15F,0.2F, 0.4F, 0.6F, 0.8F, 1.0F)) {
                        val algoSettings = AlgoSettings(3, 5, percentageLeader)
                        launch { 
                            val graph = GraphHolder.createGraphHolderFromLightningTopology("nodes_05-05-2021.json", "channels_05-05-2021.json", NodeTypes.CoinWasher, SeededRandom(seed), Counter(), false)
                            graph.start(algoSettings, false, trial, false, scoreMutex, scoreFileName)
                            println("$seed - Finished $algoSettings")
                        }
                    }
                } else {
                    val algoSettings = AlgoSettings(3, 5, 1F)
                    launch { 
                        val graph = GraphHolder.createGraphHolderFromLightningTopology("nodes_05-05-2021.json", "channels_05-05-2021.json", NodeTypes.CoinWasher, SeededRandom(seed), Counter(), true)
                        graph.start(algoSettings, true, trial, true, seedMutex, "output_files/lalala.csv") 
                    }
                }
                runCounter++
            }
        }
    }

    // LpSolveDemo().demo()
}

fun deleteFile(path: String) {
    Files.deleteIfExists(File(path).toPath())
}
