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
    COINWASHER_PARAM,
    ONE_ROUND_COINWASHER_NUMBER_OF_MESSAGES
}

fun main() {
    println("Program has started")

    // val tree1 = MerkleTree(listOf("test11", "test2", "test3", "test4", "test5"))
    // val tree2 = MerkleTree(listOf("test11", "test2", "test3", "test4", "test5"))
    // val tree1Dig = tree1.digest()
    // val tree2Dig = tree2.digest()

    // assert(tree1Dig contentEquals tree2Dig)

    // val statistics = Statistics()
    // for (i in 9 until 10) {
    //     val counter = Counter()
    //     //val graph = GraphHolder.createGraphHolderFromTxtGraph("complete_graph.txt", NodeTypes.CoinWasher, SeededRandom(i), counter)
    //     val graph = GraphHolder.createGraphHolderFromLightningTopology("nodes_05-05-2021.json", "channels_05-05-2021.json", NodeTypes.CoinWasher, SeededRandom(i), counter, false)

    //     val algoSettings = AlgoSettings(3, 10, 0.5F)
        
    //     runBlocking {
    //         graph.start(algoSettings, false, Trials.STATIC_REBALANCING_COMPARISON, false, Mutex(), "output_files/manual_test.csv", true)
    //     }
        
    //     statistics.process(counter)
    // }

    // statistics.printStatistics()
    
    // // val graph = GraphHolder("nodes_05-05-2021.json", "channels_05-05-2021.json", NodeTypes.CoinWasher)

    
    // return

    val trials = arrayOf(
        //Trials.NO_REBALANCING,
        //Trials.PART_DISC,
        //Trials.SCORE_VS_PERC_LEADERS, 
        //Trials.STATIC_REBALANCING_COMPARISON,
        Trials.DYNAMIC_REBALANCING_COMPARISON,
        //Trials.ONE_ROUND_COINWASHER_NUMBER_OF_MESSAGES
    )

    val statistics = Statistics()
    runBlocking(Dispatchers.Default) {
        for (trial in trials) {
            var runCounter = 0
            for (seed in 0 until 10) {
                val seedMutex = Mutex()
                var dirName = "output_files/${trial}"
                if (trial != Trials.DYNAMIC_REBALANCING_COMPARISON) {
                    val dir = File(dirName)

                    if (!dir.exists()) {
                        dir.mkdirs()
                    }
                }

                when (trial) {
                    Trials.PART_DISC -> {
                        for (maxNumberOfInvites in listOf(5, 7, 9)) {
                            val hopFileName = "$dirName/hopCount_${maxNumberOfInvites}.csv"
                            val hopMutex = Mutex()
                            deleteFile(hopFileName)

                            for (hopCount in listOf(1, 5, 10, 15, 20, 25, 30)) {
                                val algoSettings = AlgoSettings(hopCount, maxNumberOfInvites, 1F, true)
                                launch { 
                                    val graph = GraphHolder.createGraphHolderFromLightningTopology("nodes_05-05-2021.json", "channels_05-05-2021.json", NodeTypes.ParticipantDisc, SeededRandom(seed), Counter(), true)
                                    graph.start(algoSettings, false, trial, false, hopMutex, hopFileName)
                                    println("$seed - Finished $algoSettings")
                                }
                            }
                        }

                        for (hopCount in listOf(3, 4, 5)) {
                            val inviteFileName = "$dirName/maxNumberOfInvites_${hopCount}.csv"
                            val inviteMutex = Mutex()
                            deleteFile(inviteFileName)

                            for (maxNumberOfInvites in listOf(1, 5, 10, 15, 20, 25, 30)) {
                                val algoSettings = AlgoSettings(hopCount, maxNumberOfInvites, 1F, true)
                                launch { 
                                    val graph = GraphHolder.createGraphHolderFromLightningTopology("nodes_05-05-2021.json", "channels_05-05-2021.json", NodeTypes.ParticipantDisc, SeededRandom(seed), Counter(), true)
                                    graph.start(algoSettings, false, trial, false, inviteMutex, inviteFileName) 
                                    println("$seed - Finished $algoSettings")
                                }
                            }
                        }
                    }
                    Trials.SCORE_VS_PERC_LEADERS -> {
                        for (maxNumberOfInvites in listOf(5, 7, 9)) {
                            val scoreFileName = "$dirName/percentageLeaders_${maxNumberOfInvites}.csv"
                            val scoreMutex = Mutex()
                            deleteFile(scoreFileName)

                            for (percentageLeader in listOf(0.01F, 0.05F, 0.1F, 0.15F,0.2F, 0.4F, 0.6F, 0.8F, 1.0F)) {
                                val algoSettings = AlgoSettings(3, maxNumberOfInvites, percentageLeader, false)
                                launch { 
                                    val graph = GraphHolder.createGraphHolderFromLightningTopology("nodes_05-05-2021.json", "channels_05-05-2021.json", NodeTypes.CoinWasher, SeededRandom(seed), Counter(), false)
                                    graph.start(algoSettings, false, trial, false, scoreMutex, scoreFileName)
                                    println("$seed - Finished $algoSettings")
                                }
                            }
                        }

                    }
                    Trials.STATIC_REBALANCING_COMPARISON -> {
                        val algoSettings = AlgoSettings(3, 10, 0.2F, false)
                        for (graphName in listOf("difficult_graph.txt", "complete_graph.txt", "lightning")) {
                            val scoreFileName = "$dirName/score_$graphName.csv"
                            val scoreMutex = Mutex()
                            deleteFile(scoreFileName)

                            for (nodeType in listOf(NodeTypes.CoinWasher, NodeTypes.Revive)) {
                                launch {
                                    var graph: GraphHolder
                                    if (graphName == "lightning") {
                                        graph = GraphHolder.createGraphHolderFromLightningTopology("nodes_05-05-2021.json", "channels_05-05-2021.json", nodeType, SeededRandom(seed), Counter(), false)
                                    } else {
                                        graph = GraphHolder.createGraphHolderFromTxtGraph(graphName, nodeType, SeededRandom(seed), Counter())
                                    }
                                    
                                    graph.start(algoSettings, false, trial, false, scoreMutex, scoreFileName)
                                }
                            }
                        }
                    } 
                    Trials.DYNAMIC_REBALANCING_COMPARISON -> {
                        val algoSettings = AlgoSettings(3, 3, 0.2F, true)
                        for (giniCoefficient in listOf(0.225F)) {
                            dirName = "output_files/${trial}_${giniCoefficient}"
                            val dir = File(dirName)

                            if (!dir.exists()) {
                                dir.mkdirs()
                            }
                            runBlocking(Dispatchers.Default) {
                                for (nodeType in listOf(NodeTypes.CoinWasher, NodeTypes.Revive, NodeTypes.Normal)) {
                                    val scoreFileName = "${dirName}/data_${nodeType}.csv"
                                    val scoreMutex = Mutex()
                                    deleteFile(scoreFileName)

                                    launch {
                                        val graph = GraphHolder.createGraphHolderFromLightningTopology("nodes_05-05-2021.json", "channels_05-05-2021.json", nodeType, SeededRandom(seed), Counter(), false, giniCoefficient)
                                        
                                        graph.start(algoSettings, true, trial, false, scoreMutex, scoreFileName)
                                    }
                                }
                            }

                        }
                    }
                    Trials.ONE_ROUND_COINWASHER_NUMBER_OF_MESSAGES -> {
                        val scoreFileName = "$dirName/one_round.csv"
                        val scoreMutex = Mutex()
                        deleteFile(scoreFileName)

                        val algoSettings = AlgoSettings(3, 5, 0.0F, true)
                        val counter = Counter()

                        launch { 
                            val graph = GraphHolder.createGraphHolderFromLightningTopology("nodes_05-05-2021.json", "channels_05-05-2021.json", NodeTypes.CoinWasher, SeededRandom(seed), counter, false)
                            graph.start(algoSettings, false, trial, true, scoreMutex, scoreFileName) 
                            println("$seed - Finished $algoSettings")
                            statistics.process(counter)
                        }
                    }  
                    else -> {
                        val algoSettings = AlgoSettings(3, 5, 1F, true)
                        launch { 
                            val graph = GraphHolder.createGraphHolderFromLightningTopology("nodes_05-05-2021.json", "channels_05-05-2021.json", NodeTypes.CoinWasher, SeededRandom(seed), Counter(), true)
                            graph.start(algoSettings, true, trial, true, seedMutex, "output_files/lalala.csv") 
                        }
                    }
                }
                runCounter++
            }
        }
    }

    // LpSolveDemo().demo()
    statistics.printStatistics()
}

val alreadyTriedDeleting: MutableSet<String> = HashSet()

fun deleteFile(path: String) {
    if (path !in alreadyTriedDeleting) {
        Files.deleteIfExists(File(path).toPath())
        alreadyTriedDeleting.add(path)
    }
    
}
