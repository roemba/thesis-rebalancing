package roemer.rebalancing

import java.util.UUID
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RoundState {
    WAIT, REQ, SUC, COM, EXEC
}

class StateMachine (val logger: Logger, startState: RoundState = RoundState.WAIT) {
    var state = startState
        set(newState) {
            if (newState > state) {
                logger.debug("Setting new state $newState")
                field = newState
            }
        }

    fun isInState(otherState: RoundState): Boolean {
        return state == otherState
    }

    override fun toString(): String {
        return "StateMachine(state=$state)"
    }

    override fun equals(other: Any?): Boolean {
        return (other is StateMachine) && (this.state == other.state)
    }

    override fun hashCode(): Int {
        return this.state.hashCode()
    }
}

data class CycleChannelPair(
    val endChannel: PaymentChannel,
    val startChannel: PaymentChannel,
    val demand: Int,
    val completed: Boolean
)

data class TagDemandPair(
    val tag: Tag,
    val demand: Int
)

data class TagDemandHTLCPair(
    val tag: Tag,
    val demand: Int,
    val htlc: ByteArray?
) {
    override fun toString(): String {
        if (htlc == null) {
            return "TagDemandHTLCPair(tag=$tag, demand=$demand)"
        } else {
            return "TagDemandHTLCPair(tag=$tag, demand=$demand, htlc is provided)"
        }
    }
}

class RebalancingNode(id: Int, g: ChannelNetwork) : ParticipantNodeAlt(id, g) {
    // Needs to be reset every time the algorithm runs
    var orderOfStarting: List<Tag>? = null
    var roundIndex = 0
    var rebalancingAwake = false
    var outgoingDemandEdges: MutableSet<PaymentChannel> = HashSet()
    var incomingDemandEdges: MutableSet<PaymentChannel> = HashSet()
    
    // Needs to be reset every round
    var roundStateMachine = StateMachine(logger)
    var roundMessageHistory: MutableList<Message> = ArrayList()
    var nOfOutstandingRequests = 0
    var anonIdChannelMap: MutableMap<Tag, PaymentChannel> = HashMap()
    var cycleChannelPairsMap: MutableMap<Tag, CycleChannelPair> = HashMap()
    var receivedRequests: MutableList<RequestRebalancingMessage> = ArrayList()
    var receivedCommits: MutableList<CommitRebalancingMessage> = ArrayList()
    var receivedCycleCommits: MutableList<CommitRebalancingMessage> = ArrayList()
    var receivedSuccesses: MutableList<SuccessRebalancingMessage> = ArrayList()
    var receivedReqTags: MutableSet<Tag> = HashSet()
    var G: MutableMap<Tag, Pair<Int, PaymentChannel>> = HashMap()
    var htlcMap: MutableMap<Tag, String> = HashMap()
    var tagTransactionMap: MutableMap<Tag, Pair<PaymentChannel, Transaction>> = HashMap()
    var sentSuccessChannel: MutableSet<PaymentChannel> = HashSet()
    var nOfIgnoredCycles = 0
    var forwardedNextRoundMessage = false
    var safe = false
    
    // Need to be reset on class creation
    var rebalancingReadyChannel: Channel<Boolean> = Channel(0) // Rendezvous channel
    val lock = Mutex()
    val digest = MessageDigest.getInstance("SHA-256");

    var channelSuccessMessage: MutableSet<PaymentChannel> = HashSet()
    var channelCommitMessage: MutableSet<PaymentChannel> = HashSet()
    
    override suspend fun sortMessage (message: Message) {
        when (message.type) {
            MessageTypes.REQUEST_R -> handleRequestMessage(message as RequestRebalancingMessage)
            MessageTypes.UPDATE_R -> handleUpdateMessage(message as UpdateRebalancingMessage)
            MessageTypes.SUCCESS_R -> handleSuccessMessage(message as SuccessRebalancingMessage)
            MessageTypes.FAIL_R -> handleFailMessage(message as FailRebalancingMessage)
            MessageTypes.COMMIT_R -> handleCommitMessage(message as CommitRebalancingMessage)
            MessageTypes.EXEC_R -> handleExecuteRebalancingMessage(message as ExecuteRebalancingMessage)
            MessageTypes.NEXT_ROUND_R -> handleNextRoundMessage(message as NextRoundMessage)
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
        logger.info("$anonId is starting round $roundIndex!")

        roundStateMachine.state = RoundState.REQ
        for (channel in outgoingDemandEdges) {
            val channelAnonId = Tag()
            anonIdChannelMap.put(channelAnonId, channel)
            val seenSet = receivedReqTags.plus(channelAnonId)

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
            sendMessage(FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, FailReason.INCORRECT_EXECUTION_ID, mes.startId, this.executionId))
            return false
        }

        if (notRequest) {
            if (!this.rebalancingAwake) {
                sendMessage(FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, FailReason.NOT_AWAKE, mes.startId, this.executionId))
                return false
            }
            if (this.orderOfStarting!![this.roundIndex] != mes.startId) {
                logger.error("Received non-request from a different round!")
                sendMessage(FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, FailReason.INCORRECT_ROUND, mes.startId, this.executionId))
                return false
            }
        }

        return true
    }


    suspend fun checkForCycles(mes: RequestRebalancingMessage, seenSetToCheck: Set<Tag>): Boolean {
        val intersect = anonIdChannelMap.keys.intersect(seenSetToCheck)
        if (intersect.isNotEmpty()) {
            val channelAnonId = intersect.first() // We only care about one match

            // TODO: Possible improvement: remove channelAnonId from anonIdChannelMap after detecting a cycle? Not sure if this is necessary
            receivedRequests.removeIf{m -> m.channel == mes.channel} // Make sure that no requests are in receivedRequests if we find the cycle

            val cycleTag = Tag()
            val channelDemand = mes.channel.getDemand(this, true)
            cycleChannelPairsMap.put(cycleTag, CycleChannelPair(mes.channel, anonIdChannelMap.get(channelAnonId)!!, channelDemand, false))
            sentSuccessChannel.add(mes.channel)
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
            sendMessage(FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, FailReason.INCORRECT_ROUND, mes.startId, this.executionId))
            return
        }

        roundMessageHistory.add(mes)

        if (roundStateMachine.isInState(RoundState.WAIT)) {
            roundStateMachine.state = RoundState.REQ
            for (channel in outgoingDemandEdges) {
                val channelAnonId = Tag()
                anonIdChannelMap.put(channelAnonId, channel)
                receivedReqTags.addAll(mes.seenSet)
                val seenSet = receivedReqTags.plus(channelAnonId)

                sendMessage(RequestRebalancingMessage(MessageTypes.REQUEST_R, this, channel.getOppositeNode(this), channel, mes.startId, mes.executionId, seenSet))
                nOfOutstandingRequests++
            }
        } else if (roundStateMachine.isInState(RoundState.REQ)) {
            if (this.checkForCycles(mes, mes.seenSet)) {
                return
            }
            
            val newSeenSetEntries = mes.seenSet.subtract(receivedReqTags)
            if (newSeenSetEntries.isNotEmpty()) {
                receivedReqTags.addAll(mes.seenSet)
                logger.debug("Request got new tags, sending update messages with new tags $newSeenSetEntries")

                var receivedSuccessChannels = receivedSuccesses.map{i -> i.channel}.toHashSet()
                for (channel in outgoingDemandEdges) {
                    if (!(channel in receivedSuccessChannels)) {
                        sendMessage(UpdateRebalancingMessage(MessageTypes.UPDATE_R, this, channel.getOppositeNode(this), channel, mes.startId, mes.executionId, newSeenSetEntries))
                    }
                }
            }
        } else {
            return sendMessage(FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, FailReason.NO_SUCCESS, mes.startId, this.executionId))
        }

        receivedRequests.add(mes)

        // In case when a node has no outgoing edges
        if (nOfOutstandingRequests == 0) {
            replyToRequests()
        }
    }

    suspend fun handleUpdateMessage(mes: UpdateRebalancingMessage) {
        if (!this.checkMessage(mes)) { return }

        roundMessageHistory.add(mes)

        if (!roundStateMachine.isInState(RoundState.REQ)) {
            logger.debug("Ignoring update because already in another roundState")
            return
        }

        if (mes.channel in sentSuccessChannel) {
            logger.debug("Already sent a SUCCESS, therefore ignoring UPDATE")
            return
        }

        val newSeenSetEntries = mes.seenSet.subtract(receivedReqTags)

        if (newSeenSetEntries.isNotEmpty()) {
            receivedReqTags.addAll(newSeenSetEntries)

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

        if (mes.channel in channelSuccessMessage) {
            throw Error("Already gotten success message this round from channel ${mes.channel}!")
        } else {
            channelSuccessMessage.add(mes.channel)
        }

        roundMessageHistory.add(mes)

        if (roundStateMachine.isInState(RoundState.REQ)) {
            receivedSuccesses.add(mes)
            nOfOutstandingRequests--
        }

        if (nOfOutstandingRequests == 0) {
            replyToRequests()
        }
    }

    suspend fun handleFailMessage(mes: FailRebalancingMessage) {
        if (mes.reason != FailReason.NO_SUCCESS) {
            return
        }

        roundMessageHistory.add(mes)

        if (roundStateMachine.isInState(RoundState.REQ)) {
            nOfOutstandingRequests--
        }

        if (nOfOutstandingRequests == 0) {
            replyToRequests()
        }
    }

    suspend fun replyToRequests() {
        if (roundStateMachine.isInState(RoundState.REQ)) {
            roundStateMachine.state = RoundState.SUC
        }

        if (!roundStateMachine.isInState(RoundState.SUC)) {
            return
        }

        // Check if the startChannels of the cycles have replied with a success containing the cycle tag
        // val successChannels: Map<PaymentChannel, SuccessRebalancingMessage> = receivedSuccesses.associate { Pair(it.channel, it) }
        // val iter = cycleChannelPairsMap.iterator()
        // while (iter.hasNext()) {
        //     val entry = iter.next()
        //     val startSuccessMessage = successChannels.get(entry.value.startChannel)
        //     val tags = startSuccessMessage!!.tagList.map { i -> i.tag }
        //     if (!(entry.key in tags)) {
        //         logger.warn("Cycle ${entry.key} has not responded on starting channel, to prevent double claiming, deleting from pair and successMap")
        //         iter.remove()
        //         receivedSuccesses.remove(startSuccessMessage)
        //         nOfIgnoredCycles++
        //     }
        // }

        for (success in receivedSuccesses) {
            for (tagDemandPair in success.tagList) {
                if (tagDemandPair.tag in cycleChannelPairsMap) { 
                    val entry = cycleChannelPairsMap.get(tagDemandPair.tag)!!
                    if (!entry.completed || tagDemandPair.demand > entry.demand) {
                        logger.debug("Storing new demand ${tagDemandPair.demand} for cycle ${tagDemandPair.tag}")
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
    }

    suspend fun commitSource() {
        val P: MutableMap<PaymentChannel, Pair<MutableList<TagDemandHTLCPair>, MutableMap<Tag, Transaction>>> = HashMap()
        for (entry in cycleChannelPairsMap.entries) {
            if (!entry.value.completed) {
                logger.debug("Cycle ${entry.key} not complete, skipping...")
                nOfIgnoredCycles++
                continue
            }

            val pairs = P.getOrPut(entry.value.startChannel, { Pair(ArrayList(), HashMap()) })

            var htlc: ByteArray? = null
            if (entry.value.demand > 0) {
                val preImage = UUID.randomUUID().toString()
                htlcMap.put(entry.key, preImage)
    
                htlc = digest.digest(preImage.encodeToByteArray())
                val tx = Transaction(UUID.randomUUID(), entry.value.demand, this, entry.value.startChannel.getOppositeNode(this))
                
                logger.debug("Source: Requesting tx on ${entry.key}")
                if (!entry.value.startChannel.requestTx(tx, htlc, true)) {
                    throw Error("Channel did not allow me to request TX!")
                }

                pairs.second.put(entry.key, tx)
            }

            
            pairs.first.add(TagDemandHTLCPair(entry.key, entry.value.demand, htlc))
            
        }
        for (channel in outgoingDemandEdges) {
            if (channel in P) {
                sendMessage(CommitRebalancingMessage(MessageTypes.COMMIT_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), this.executionId!!, P.get(channel)!!.first, P.get(channel)!!.second))
            } else {
                sendMessage(CommitRebalancingMessage(MessageTypes.COMMIT_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), this.executionId!!, ArrayList(), HashMap()))
            }
        }

        if (receivedRequests.size + cycleChannelPairsMap.size == 0) { // If no requests and cycles exist including the source, continue to nextRound
            nextRound()
        }
    }

    suspend fun handleCommitMessage(mes: CommitRebalancingMessage) {
        if (!this.checkMessage(mes)) { return }

        roundMessageHistory.add(mes)

        if (mes.channel in channelCommitMessage) {
            throw Error("Already received commit message on ${mes.channel}")
        } else {
            channelCommitMessage.add(mes.channel)
        }

        roundStateMachine.state = RoundState.COM
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
                    if (!entry.value.completed) {
                        logger.debug("Cycle ${entry.key} not complete, skipping...")
                        continue
                    }
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
                        val tagTxMap: MutableMap<Tag, Transaction> = HashMap()
                        for (pair in K.get(channel)!!) {
                            if (pair.htlc != null) {
                                val tx = Transaction(UUID.randomUUID(), pair.demand, this, channel.getOppositeNode(this))
                                logger.debug("Requesting tx on ${pair.tag}")
                                if (!channel.requestTx(tx, pair.htlc, true)) {
                                    throw Error("Channel did not allow me to request TX!")
                                }
                                tagTxMap.put(pair.tag, tx)
                            }
                        }
                        sendMessage(CommitRebalancingMessage(MessageTypes.COMMIT_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), this.executionId!!, K.get(channel)!!, tagTxMap))
                    } else {
                        sendMessage(CommitRebalancingMessage(MessageTypes.COMMIT_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), this.executionId!!, ArrayList(), HashMap()))
                    }
                }
            }
        } else {
            receivedCycleCommits.add(mes)
        }

        // logger.debug("#ofReceivedCommits: ${receivedCommits.size} #ofReceivedCycleCommits: ${receivedCycleCommits.size} #ofIgnoredCycles: $nOfIgnoredCycles #ofReceivedRequests: ")
        if (receivedCommits.size + receivedCycleCommits.size + nOfIgnoredCycles == receivedRequests.size + cycleChannelPairsMap.size) {
            logger.info("Received all commits from requesting and cycle channels")

            // Store which transaction belongs to which channel and tag, but only for commits from sources other than owned cycles
            for (commit in receivedCommits) {
                for (tagDemandPair in commit.tagList) {
                    if (tagDemandPair.tag in commit.tagTxMap) {
                        assert(!(tagDemandPair.tag in tagTransactionMap))

                        tagTransactionMap.put(tagDemandPair.tag, Pair(commit.channel, commit.tagTxMap.get(tagDemandPair.tag)!!))
                    }
                }
            }

            roundStateMachine.state = RoundState.EXEC

            // Execute cycle transactions
            for (commit in receivedCycleCommits) {
                for (tagDemandPair in commit.tagList) {
                    if (tagDemandPair.tag in commit.tagTxMap) {
                        val preImage = htlcMap.get(tagDemandPair.tag)!!
                        commit.channel.executeTx(commit.tagTxMap.get(tagDemandPair.tag)!!, digest.digest(preImage.encodeToByteArray()))
                        sendMessage(ExecuteRebalancingMessage(
                            MessageTypes.EXEC_R, this, commit.channel.getOppositeNode(this), commit.channel, this.getRoundStarter(), this.executionId!!,
                            tagDemandPair.tag, preImage
                        ))
                    }
                }
            }

            // If there are no commits from sources other than an owned cycle, continue to next round
            if (tagTransactionMap.isEmpty()) { 
                safe = true
                logger.debug("Now safe")
                if (iStartedRound() || forwardedNextRoundMessage) {
                    nextRound()
                }
            } else {
                logger.debug("Still waiting for ${tagTransactionMap.keys}")
            }
        }
    }

    suspend fun handleExecuteRebalancingMessage(mes: ExecuteRebalancingMessage) {
        if (!rebalancingAwake || mes.startId != getRoundStarter()) {
            return
        }

        if (!this.checkMessage(mes)) { return }

        if (roundStateMachine.isInState(RoundState.COM)) {
            sendMessage(mes) // Add message to back of queue to try again later
            return
        } else if (!roundStateMachine.isInState(RoundState.EXEC)) {
            throw Error("Received a executing message before COM state!")
        }

        roundMessageHistory.add(mes)

        if (!(mes.tag in tagTransactionMap)) {
            logger.debug("Tag ${mes.tag} not found in tagTransactionMap while executing")
            return
        }

        val entryValue = tagTransactionMap.get(mes.tag)!!
        logger.info("Executing ${mes.tag}")
        entryValue.first.executeTx(entryValue.second, digest.digest(mes.preImage.encodeToByteArray()))
        sendMessage(ExecuteRebalancingMessage(
            MessageTypes.EXEC_R, this, entryValue.first.getOppositeNode(this), entryValue.first, this.getRoundStarter(), this.executionId!!,
            mes.tag, mes.preImage
        ))

        tagTransactionMap.remove(mes.tag)

        if (tagTransactionMap.isEmpty()) {
            safe = true
            logger.debug("Now safe")
            if (forwardedNextRoundMessage) {
                nextRound()
            }
        } else {
            logger.debug("Still waiting for ${tagTransactionMap.keys}")
        }
    }

    suspend fun handleNextRoundMessage(mes: NextRoundMessage) {
        if (!rebalancingAwake || mes.startId != getRoundStarter()) {
            return
        }

        if (!this.checkMessage(mes)) { return }

        roundMessageHistory.add(mes)

        if (!forwardedNextRoundMessage) {
            for (channel in outgoingDemandEdges.plus(incomingDemandEdges)) {
                sendMessage(NextRoundMessage(MessageTypes.NEXT_ROUND_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), executionId!!))
            }
            forwardedNextRoundMessage = true
        }

        if (roundStateMachine.isInState(RoundState.WAIT) || safe) {
            logger.debug("Pushed to next round by nextRoundMessage")
            nextRound()
        }
    }

    fun getRoundStarter(): Tag {
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
        if (iStartedRound()) {
            for (channel in outgoingDemandEdges.plus(incomingDemandEdges)) {
                sendMessage(NextRoundMessage(MessageTypes.NEXT_ROUND_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), executionId!!))
            }
        }

        roundIndex++
        logger.debug("Going to round $roundIndex!")

        // Reset round variables
        roundStateMachine = StateMachine(logger)
        roundMessageHistory = ArrayList()
        nOfOutstandingRequests = 0
        anonIdChannelMap = HashMap()
        cycleChannelPairsMap = HashMap()
        receivedRequests = ArrayList()
        receivedCommits = ArrayList()
        receivedCycleCommits = ArrayList()
        receivedSuccesses = ArrayList()
        receivedReqTags = HashSet()
        G = HashMap()
        htlcMap = HashMap()
        tagTransactionMap = HashMap()
        sentSuccessChannel = HashSet()
        nOfIgnoredCycles = 0
        forwardedNextRoundMessage = false
        safe = false

        channelSuccessMessage = HashSet()
        channelCommitMessage = HashSet()

        if (roundIndex >= this.orderOfStarting!!.size) {
            return terminateRebalancing(true)
        }

        if (iStartedRound()) {
            this.startRound()
        }
    }

    suspend fun terminateRebalancing(success: Boolean) {
        if (success) {
            logger.info("Finished rebalancing successfully")
        } else {
            logger.info("Terminated rebalancing unsuccessfully")
        }

        orderOfStarting = null
        roundIndex = 0
        rebalancingAwake = false
        outgoingDemandEdges = HashSet()
        incomingDemandEdges = HashSet()
    }
}