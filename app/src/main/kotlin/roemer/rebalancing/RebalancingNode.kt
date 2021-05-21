package roemer.rebalancing

import java.util.UUID
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RoundState {
    WAIT, REQ, SUC, COM
}

data class CycleChannelPair(
    val endChannel: PaymentChannel,
    val startChannel: PaymentChannel,
    val demand: Int,
    val completed: Boolean
)

data class TagDemandPair(
    val tag: UUID,
    val demand: Int
)

data class TagDemandHTLCPair(
    val tag: UUID,
    val demand: Int,
    val htlc: ByteArray?
)

data class TagTransactionPair(
    val tag: UUID,
    val transaction: Transaction
)

class RebalancingNode(id: Int, g: ChannelNetwork, totalFunds: Int = 0) : ParticipantNodeAlt(id, g, totalFunds) {
    var roundState = RoundState.WAIT
    var roundMessageHistory: MutableList<Message> = ArrayList()
    var nOfOutstandingRequests = 0
    var anonIdChannelMap: MutableMap<UUID, PaymentChannel> = HashMap()
    var cycleChannelPairsMap: MutableMap<UUID, CycleChannelPair> = HashMap()
    var receivedRequests: MutableList<RequestRebalancingMessage> = ArrayList()
    var receivedCommits: MutableList<CommitRebalancingMessage> = ArrayList()
    var receivedCycleCommits: MutableList<CommitRebalancingMessage> = ArrayList()
    var receivedSuccesses: MutableList<SuccessRebalancingMessage> = ArrayList()
    var orderOfStarting: List<UUID>? = null
    var roundIndex = 0
    var rebalancingAwake = false
    var outgoingDemandEdges: MutableSet<PaymentChannel> = HashSet()
    var incomingDemandEdges: MutableSet<PaymentChannel> = HashSet()
    var seenSet: MutableSet<UUID> = HashSet()
    var G: MutableMap<UUID, Pair<Int, PaymentChannel>> = HashMap()
    var htlcMap: MutableMap<UUID, String> = HashMap()
    var tagTransactionMap: MutableMap<UUID, Transaction> = HashMap()
    var rebalancingReadyChannel: Channel<Boolean> = Channel(0) // Rendezvous channel

    val lock = Mutex()
    val digest = MessageDigest.getInstance("SHA-256");
    
    override suspend fun sortMessage (message: Message) {
        when (message.type) {
            MessageTypes.REQUEST_R -> handleRequestMessage(message as RequestRebalancingMessage)
            MessageTypes.UPDATE_R -> handleUpdateMessage(message as UpdateRebalancingMessage)
            MessageTypes.SUCCESS_R -> handleSuccessMessage(message as SuccessRebalancingMessage)
            MessageTypes.FAIL_R -> handleFailMessage(message as FailRebalancingMessage)
            MessageTypes.COMMIT_R -> handleCommitMessage(message as CommitRebalancingMessage)
            MessageTypes.EXEC_R -> handleExecuteRebalancingMessage(message as ExecuteRebalancingMessage)
            else -> {
                super.sortMessage(message)
            }
        }
    }

    suspend fun rebalance(hopCount: Int) {
        logger.info("Starting participant discovery before rebalancing")

        this.findParticipants(hopCount)
    }

    suspend fun rebalancingClient() {
        while (true) {
            val foundParticipantsResult = resultReadyChannel.receive()

            logger.info("Participant discovery finished with result $foundParticipantsResult, ${result!!.acceptedEdges}")

            if (!foundParticipantsResult) {
                continue
            }
            
            this.wakeUp()

            if (this.orderOfStarting!![0] == this.anonId) {
                this.startRound()
            }

            rebalancingReadyChannel.receive()
        }
    }

    suspend fun startRound() {
        logger.info("$anonId is starting the round!")

        roundState = RoundState.REQ
        for (channel in outgoingDemandEdges) {
            val channelAnonId = UUID.randomUUID()
            anonIdChannelMap.put(channelAnonId, channel)
            seenSet.add(channelAnonId)

            sendMessage(RequestRebalancingMessage(MessageTypes.REQUEST_R, this, channel.getOppositeNode(this), channel, this.anonId!!, this.executionId!!, seenSet))
            nOfOutstandingRequests++
        }
    }

    suspend fun wakeUp(): Boolean {
        lock.withLock { 
            if (this.rebalancingAwake) {
                return true // Already awake
            }

            logger.debug("I'm waking up!")

            this.orderOfStarting = this.result!!.finalParticipants.toList().sorted()
            this.rebalancingAwake = true

            for (channel in this.result!!.acceptedEdges) {
                val demand = channel.getDemand(this)
                logger.debug("Sorting $channel with balance $demand")
                if (demand < 0) {
                    outgoingDemandEdges.add(channel)
                } else {
                    incomingDemandEdges.add(channel)
                }
            }

            return true
        }
    }

    suspend fun checkMessage(mes: RebalancingMessage, notRequest: Boolean = true): Boolean {
        if (this.executionId == null || this.executionId != mes.executionId) {
            sendMessage(FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, mes.startId, this.executionId))
            return false
        }

        if (notRequest) {
            if (!this.rebalancingAwake) {
                sendMessage(FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, mes.startId, this.executionId))
                return false
            }
            if (this.orderOfStarting!![this.roundIndex] != mes.startId) {
                logger.error("Received non-request from a different round!")
                sendMessage(FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, mes.startId, this.executionId))
                return false
            }
        }

        return true
    }


    suspend fun checkForCycles(mes: RequestRebalancingMessage, seenSetToCheck: Set<UUID>): Boolean {
        val intersect = anonIdChannelMap.keys.intersect(seenSetToCheck)
        if (intersect.isNotEmpty()) {
            val channelAnonId = intersect.first() // We only care about one match

            // TODO: Possible improvement: remove channelAnonId from anonIdChannelMap after detecting a cycle? Not sure if this is necessary
            receivedRequests.removeIf{m -> m.channel == mes.channel} // Make sure that no requests are in receivedRequests if we find the cycle

            val cycleTag = UUID.randomUUID()
            val channelDemand = mes.channel.getDemand(this, true)
            cycleChannelPairsMap.put(cycleTag, CycleChannelPair(mes.channel, anonIdChannelMap.get(channelAnonId)!!, channelDemand, false))
            sendMessage(SuccessRebalancingMessage(
                MessageTypes.SUCCESS_R, this, mes.sender, mes.channel, mes.startId, 
                mes.executionId, listOf(TagDemandPair(cycleTag, channelDemand))
                ))
            logger.debug("Seeing $channelAnonId again, tagged as cycle with tag $cycleTag with initial demand $channelDemand")
            return true
        }
        return false
    }

    suspend fun handleRequestMessage(mes: RequestRebalancingMessage) {
        if (this.executionId != null && this.result == null) { // Participant discovery has started but is not finished
            sendMessage(mes) // Add message to back of queue to try again later
            return
        }

        if (!this.checkMessage(mes, false) || !this.wakeUp()) {
            return
        }

        if (this.orderOfStarting!!.indexOf(mes.startId) > this.roundIndex) { // If the message received is from a later round, put it back in the queue
            sendMessage(mes) // Add message to back of queue to try again later
            return
        } else if (this.orderOfStarting!!.indexOf(mes.startId) < this.roundIndex) { // If the message is from an earlier round, deny straight away
            logger.warn("Received message $mes from earlier round")
            sendMessage(FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, mes.startId, this.executionId))
            return
        }

        roundMessageHistory.add(mes)

        if (roundState == RoundState.WAIT) {
            roundState = RoundState.REQ
            for (channel in outgoingDemandEdges) {
                val channelAnonId = UUID.randomUUID()
                anonIdChannelMap.put(channelAnonId, channel)
                seenSet.addAll(mes.seenSet)
                seenSet.add(channelAnonId)

                sendMessage(RequestRebalancingMessage(MessageTypes.REQUEST_R, this, channel.getOppositeNode(this), channel, mes.startId, mes.executionId, seenSet))
                nOfOutstandingRequests++
            }
        } else if (roundState == RoundState.REQ) {
            if (this.checkForCycles(mes, mes.seenSet)) {
                return
            }
            
            val newSeenSetEntries = mes.seenSet.subtract(seenSet)
            if (newSeenSetEntries.isNotEmpty()) {
                seenSet.addAll(newSeenSetEntries)
                logger.debug("Request got new tags, sending update messages with new tags $newSeenSetEntries")
                for (channel in outgoingDemandEdges) {
                    sendMessage(UpdateRebalancingMessage(MessageTypes.UPDATE_R, this, channel.getOppositeNode(this), channel, mes.startId, mes.executionId, newSeenSetEntries))
                }
            }
        }

        receivedRequests.add(mes)
    }

    suspend fun handleUpdateMessage(mes: UpdateRebalancingMessage) {
        if (!this.checkMessage(mes)) { return }

        roundMessageHistory.add(mes)

        val newSeenSetEntries = mes.seenSet.subtract(seenSet)

        if (newSeenSetEntries.isNotEmpty()) {
            seenSet.addAll(newSeenSetEntries)

            if (this.checkForCycles(mes, newSeenSetEntries)) {
                logger.debug("Found cycle based on update message")
                return
            }

            logger.debug("Received update message with new tags, sending update messages with new tags $newSeenSetEntries")
            for (channel in outgoingDemandEdges) {
                sendMessage(UpdateRebalancingMessage(MessageTypes.UPDATE_R, this, channel.getOppositeNode(this), channel, mes.startId, mes.executionId, newSeenSetEntries))
            }
        }
    }

    suspend fun handleSuccessMessage(mes: SuccessRebalancingMessage) {
        if (!this.checkMessage(mes)) { return }

        roundMessageHistory.add(mes)

        if (roundState == RoundState.REQ) {
            receivedSuccesses.add(mes)
            nOfOutstandingRequests--
        }

        if (nOfOutstandingRequests == 0) {
            replyToRequests()
        }
    }

    suspend fun handleFailMessage(mes: FailRebalancingMessage) {
        roundMessageHistory.add(mes)

        if (roundState == RoundState.REQ) {
            nOfOutstandingRequests--
        }

        if (nOfOutstandingRequests == 0) {
            replyToRequests()
        }
    }

    suspend fun replyToRequests() {
        roundState == RoundState.SUC
        if (receivedSuccesses.isNotEmpty()) {
            for (success in receivedSuccesses) {
                for (tagDemandPair in success.tagList) {
                    if (tagDemandPair.tag in cycleChannelPairsMap) { 
                        val entry = cycleChannelPairsMap.get(tagDemandPair.tag)!!
                        if (!entry.completed || tagDemandPair.demand > entry.demand) {
                            cycleChannelPairsMap.replace(tagDemandPair.tag, 
                                CycleChannelPair(
                                    entry.endChannel, 
                                    success.channel, 
                                    tagDemandPair.demand,
                                    true)
                            )
                        }
                    } else {
                        logger.debug("Tag not in cyleMap, so putting it in G")
                        if (!(tagDemandPair.tag in G)) {
                            G.put(tagDemandPair.tag, Pair(tagDemandPair.demand, success.channel))
                        }
                        if (tagDemandPair.demand > G.get(tagDemandPair.tag)!!.first) {
                            G.replace(tagDemandPair.tag, Pair(tagDemandPair.demand, success.channel))
                        }
                    }
                }
            }

            if (iStartedRound()) {
                commitSource()
            } else {
                val F = G.entries.toList()
                for (request in receivedRequests) {
                    val N = splitEqually(request.channel.getDemand(this, true), F.map { e -> e.value.first }.toIntArray())
                    val K: MutableList<TagDemandPair> = ArrayList()
                    for (i in 0 until F.size) {
                        K.add(TagDemandPair(F[i].key, N[i]))
                    }
                    sendMessage(SuccessRebalancingMessage(MessageTypes.SUCCESS_R, this, request.sender, request.channel, getRoundStarter(), this.executionId!!, K))
                }
            }
        } else {
            if (iStartedRound()) {
                nextRound()
            } else {
                for (request in receivedRequests) {
                    sendMessage(FailRebalancingMessage(MessageTypes.FAIL_R, this, request.sender, request.channel, getRoundStarter(), this.executionId!!))
                }
            }
        }
    }

    suspend fun commitSource() {
        val P: MutableMap<PaymentChannel, Pair<MutableList<TagDemandHTLCPair>, MutableList<TagTransactionPair>>> = HashMap()
        for (entry in cycleChannelPairsMap.entries) {
            val pairs = P.getOrPut(entry.value.startChannel, { Pair(ArrayList(), ArrayList()) })

            var htlc: ByteArray? = null
            if (entry.value.demand > 0) {
                val preImage = UUID.randomUUID().toString()
                htlcMap.put(entry.key, preImage)
    
                htlc = digest.digest(preImage.encodeToByteArray())
                val tx = Transaction(UUID.randomUUID(), entry.value.demand, this, entry.value.startChannel.getOppositeNode(this))
                tagTransactionMap.put(entry.key, tx)
    
                if (!entry.value.startChannel.requestTx(tx, htlc, true)) {
                    throw Error("Channel did not allow me to request TX!")
                }

                pairs.second.add(TagTransactionPair(entry.key, tx))
            }

            
            pairs.first.add(TagDemandHTLCPair(entry.key, entry.value.demand, htlc))
            
        }
        for (channel in outgoingDemandEdges) {
            if (channel in P) {
                sendMessage(CommitRebalancingMessage(MessageTypes.COMMIT_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), this.executionId!!, P.get(channel)!!.first, P.get(channel)!!.second))
            } else {
                sendMessage(CommitRebalancingMessage(MessageTypes.COMMIT_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), this.executionId!!, ArrayList(), ArrayList()))
            }
        }
    }

    suspend fun handleCommitMessage(mes: CommitRebalancingMessage) {
        if (!this.checkMessage(mes)) { return }

        roundMessageHistory.add(mes)

        roundState = RoundState.COM
        val cycleEndChannels = cycleChannelPairsMap.values.map { v -> v.endChannel }
        if (!(mes.channel in cycleEndChannels)) {
            receivedCommits.add(mes)

            if (receivedCommits.size == receivedRequests.size) { // Forward commits
                logger.info("Received all commits from requesting channels")
                val K: MutableMap<PaymentChannel, MutableList<TagDemandHTLCPair>> = HashMap()
                for (commit in receivedCommits) {
                    for (tagDemandPair in commit.tagList) {
                        val pairs = K.getOrPut(G.get(tagDemandPair.tag)!!.second) { ArrayList() }
                        pairs.add(tagDemandPair)
                    }
                }
                for (entry in cycleChannelPairsMap.entries) {
                    var htlc: ByteArray? = null
                    if (entry.value.demand > 0) {
                        val preImage = UUID.randomUUID().toString()
                        htlcMap.put(entry.key, preImage)
    
                        htlc = digest.digest(preImage.encodeToByteArray())
                    }

                    val pairs = K.getOrPut(entry.value.startChannel, { ArrayList() })
                    pairs.add(TagDemandHTLCPair(entry.key, entry.value.demand, htlc))
                }
                for (channel in outgoingDemandEdges) {
                    if (channel in K) {
                        val tagTxList: MutableList<TagTransactionPair> = ArrayList()
                        for (pair in K.get(channel)!!) {
                            if (pair.htlc != null) {
                                val tx = Transaction(UUID.randomUUID(), pair.demand, this, channel.getOppositeNode(this))
                                tagTransactionMap.put(pair.tag, tx)
                                if (!channel.requestTx(tx, pair.htlc, true)) {
                                    throw Error("Channel did not allow me to request TX!")
                                }
                                tagTxList.add(TagTransactionPair(pair.tag, tx))
                            }
                        }
                        sendMessage(CommitRebalancingMessage(MessageTypes.COMMIT_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), this.executionId!!, K.get(channel)!!, tagTxList))
                    } else {
                        sendMessage(CommitRebalancingMessage(MessageTypes.COMMIT_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), this.executionId!!, ArrayList(), ArrayList()))
                    }
                }
            }
        } else {
            receivedCycleCommits.add(mes)
        }

        if (receivedCommits.size + receivedCycleCommits.size == receivedRequests.size + cycleChannelPairsMap.size) {
            logger.info("Received all commits from requesting and cycle channels")

            if (iStartedRound()) {
                
            }

            nextRound()
        }
    }

    suspend fun handleExecuteRebalancingMessage(mes: ExecuteRebalancingMessage) {
        if (!this.checkMessage(mes)) { return }

        roundMessageHistory.add(mes)

        if (mes.tag in tagTransactionMap) {
            throw Error("Tag not found in transactionMap while executing")
        }
    }

    fun getRoundStarter(): UUID {
        return this.orderOfStarting!![this.roundIndex]
    }

    fun iStartedRound(): Boolean {
        return getRoundStarter() == this.anonId
    }

    fun splitEqually(total: Int, S: IntArray): IntArray {
        var t = total
        var R = IntArray(S.size)
        if (t >= S.sum()) {
            R = S
        } else {
            val E = S
            var r = t % E.size
            var c = t / E.size
            while (c > 0) {
                for (i in 0 until E.size) {
                    val d = E[i]
                    val g = Math.min(c, d)
                    R[i] += g
                    E[i] -= g
                    t -= g
                }
                r = t % E.size
                c = t / E.size
            }

            while (r > 0) {
                for (i in 0 until E.size) {
                    val d = E[i]
                    val g = Math.min(1, d)
                    r -= g
                    R[i] += g
                    E[i] -= g

                    if (r == 0) {
                        break
                    }
                }
            }
        }

        assert(R.sum() <= total)
        return R
    }

    suspend fun nextRound() {
        roundIndex++
        logger.debug("Going to round $roundIndex!")
        
        val test1 = digest.digest("test".encodeToByteArray())
        val test2 = digest.digest("test".encodeToByteArray())
        val test3 = digest.digest("test2".encodeToByteArray())
        logger.debug("Test1: ${test1.toString()}, test2: $test2")
        logger.debug(test1 contentEquals test2)
        logger.debug(!(test1 contentEquals test3))

    }

    suspend fun terminateRebalancing(success: Boolean) {
        if (success) {
            logger.info("Finished rebalancing successfully")
        } else {
            logger.info("Terminated rebalancing unsuccessfully")
        }
    }
}