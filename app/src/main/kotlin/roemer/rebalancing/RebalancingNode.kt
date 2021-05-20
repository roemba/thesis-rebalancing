package roemer.rebalancing

import java.util.UUID
import kotlinx.coroutines.delay

enum class RoundState {
    WAIT, REQ, SUC, COM
}

data class CycleChannelPair(
    val endChannel: PaymentChannel,
    val startChannel: PaymentChannel,
    val demand: Int
)

data class TagDemandPair(
    val tag: UUID,
    val demand: Int
)

class RebalancingNode(id: Int, g: ChannelNetwork, totalFunds: Int = 0) : ParticipantNodeAlt(id, g, totalFunds) {
    var roundState = RoundState.WAIT
    var roundMessageHistory: MutableList<Message> = ArrayList()
    var nOfOutstandingRequests = 0
    var anonIdChannelMap: MutableMap<UUID, PaymentChannel> = HashMap()
    var cycleChannelPairsMap: MutableMap<UUID, CycleChannelPair> = HashMap()
    var receivedRequests: MutableList<RequestRebalancingMessage> = ArrayList()
    var receivedCommits: MutableList<CommitRebalancingMessage> = ArrayList()
    var receivedSuccesses: MutableList<SuccessRebalancingMessage> = ArrayList()
    var orderOfStarting: List<UUID>? = null
    var roundIndex = 0
    var rebalancingAwake = false
    var outgoingDemandEdges: MutableSet<PaymentChannel> = HashSet()
    var incomingDemandEdges: MutableSet<PaymentChannel> = HashSet()
    var seenSet: MutableSet<UUID> = HashSet()
    var G: MutableMap<UUID, Pair<Int, PaymentChannel>> = HashMap()
    
    override suspend fun sortMessage (message: Message) {
        when (message.type) {
            MessageTypes.REQUEST_R -> handleRequestMessage(message as RequestRebalancingMessage)
            MessageTypes.UPDATE_R -> handleUpdateMessage(message as UpdateRebalancingMessage)
            MessageTypes.SUCCESS_R -> handleSuccessMessage(message as SuccessRebalancingMessage)
            MessageTypes.FAIL_R -> handleFailMessage(message as FailRebalancingMessage)
            MessageTypes.COMMIT_R -> handleCommitMessage(message as CommitRebalancingMessage)
            else -> {
                super.sortMessage(message)
            }
        }
    }

    suspend fun rebalance(hopCount: Int): Boolean {
        val foundParticipants = this.findParticipants(hopCount)
        if (!foundParticipants) {
            return false
        }

        this.wakeUp()

        if (this.orderOfStarting!![0] == this.anonId) {
            this.startRound()
        }

        return true
    }

    suspend fun startRound() {
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
        if (this.rebalancingAwake) {
            return true // Already awake
        }

        this.orderOfStarting = this.result!!.finalParticipants.toList().sorted()
        this.rebalancingAwake = true

        for (channel in this.result!!.acceptedEdges) {
            val demand = channel.getDemand(this)
            if (demand > 0) {
                outgoingDemandEdges.add(channel)
            } else {
                incomingDemandEdges.add(channel)
            }
        }

        return true
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
            cycleChannelPairsMap.put(cycleTag, CycleChannelPair(mes.channel, anonIdChannelMap.get(channelAnonId)!!, channelDemand))
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
                    if (tagDemandPair.tag in cycleChannelPairsMap && tagDemandPair.demand > cycleChannelPairsMap.get(tagDemandPair.tag)!!.demand) {
                        cycleChannelPairsMap.replace(tagDemandPair.tag, 
                            CycleChannelPair(
                                cycleChannelPairsMap.get(tagDemandPair.tag)!!.endChannel, 
                                success.channel, 
                                tagDemandPair.demand)
                        )
                    } else {
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
                nextRound()
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
        val P: MutableMap<PaymentChannel, MutableList<TagDemandPair>> = HashMap()
        for (entry in cycleChannelPairsMap.entries) {
            val pairs = P.getOrPut(entry.value.startChannel, { ArrayList() })
            pairs.add(TagDemandPair(entry.key, entry.value.demand))
        }
        for (channel in outgoingDemandEdges) {
            if (channel in P) {
                sendMessage(CommitRebalancingMessage(MessageTypes.COMMIT_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), P.get(channel)!!, this.executionId!!))
            } else {
                sendMessage(CommitRebalancingMessage(MessageTypes.COMMIT_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), ArrayList(), this.executionId!!))
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
                val K: MutableMap<PaymentChannel, MutableList<TagDemandPair>> = HashMap()
                for (commit in receivedCommits) {
                    for (tagDemandPair in commit.tagList) {
                        val pairs = K.getOrPut(G.get(tagDemandPair)!!.second) { ArrayList() }
                        pairs.add(tagDemandPair)
                    }
                }
                for (entry in cycleChannelPairsMap.entries) {
                    val pairs = K.getOrPut(entry.value.startChannel, { ArrayList() })
                    pairs.add(TagDemandPair(entry.key, entry.value.demand))
                }
                for (channel in outgoingDemandEdges) {
                    if (channel in K) {
                        sendMessage(CommitRebalancingMessage(MessageTypes.COMMIT_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), K.get(channel)!!, this.executionId!!))
                    } else {
                        sendMessage(CommitRebalancingMessage(MessageTypes.COMMIT_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), ArrayList(), this.executionId!!))
                    }
                }
                nextRound()
            }
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
        logger.debug("Going to next round!")
    }

    suspend fun terminateRebalancing(success: Boolean) {
        if (success) {
            logger.info("Finished rebalancing successfully")
        } else {
            logger.info("Terminated rebalancing unsuccessfully")
        }
    }
}