package roemer.rebalancing

import java.util.UUID
import java.security.MessageDigest
import kotlin.reflect.KFunction1
import kotlin.math.roundToInt
import kotlin.math.abs

enum class RoundState {
    WAIT, REQ, SUC, COM, EXEC
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

class CoinWasherNode(id: Int, g: ChannelNetwork, messageCounter: MessageCounter, random: SeededRandom, globalLogger: Logger) : ParticipantNodeAlt(id, g, messageCounter, random, globalLogger), Rebalancer {
    val digest = MessageDigest.getInstance("SHA-256");
    
    // Needs to be reset every time the algorithm runs
    var orderOfStarting: List<Tag> = ArrayList()
    var roundIndex = 0
    var maxRound = 0
    
    // Needs to be reset every round
    lateinit var outgoingDemandEdges: Set<PaymentChannel>
    lateinit var incomingDemandEdges: Set<PaymentChannel>
    lateinit var channelDemands: Map<PaymentChannel, Int>
    lateinit var roundStateMachine: StateMachine<RoundState>
    var nOfOutstandingRequests = 0
    lateinit var anonIdChannelMap: MutableMap<Tag, PaymentChannel>
    lateinit var cycleChannelPairsMap: MutableMap<Tag, CycleChannelPair>
    lateinit var receivedRequests: MutableList<RequestRebalancingMessage>
    lateinit var receivedCommits: MutableList<CommitRebalancingMessage>
    lateinit var receivedCycleCommits: MutableList<CommitRebalancingMessage>
    lateinit var receivedSuccesses: MutableList<SuccessRebalancingMessage>
    lateinit var receivedReqTags: MutableSet<Tag>
    lateinit var G: MutableMap<Tag, Pair<Int, PaymentChannel>>
    lateinit var htlcMap: MutableMap<Tag, String>
    lateinit var tagTransactionMap: MutableMap<Tag, Pair<PaymentChannel, Transaction>>
    lateinit var sentSuccessChannel: MutableSet<PaymentChannel>
    var forwardedNextRoundMessage = false
    var executionSafe = false
    lateinit var failedChannels: MutableSet<PaymentChannel>
    lateinit var channelSuccessMessage: MutableSet<PaymentChannel>
    lateinit var channelCommitMessage: MutableSet<PaymentChannel>

    init {
        this.resetRoundVars()
    }

    fun resetRoundVars() {
        outgoingDemandEdges = HashSet()
        incomingDemandEdges = HashSet()
        channelDemands = HashMap()
        roundStateMachine = StateMachine(logger, RoundState.WAIT)
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
        forwardedNextRoundMessage = false
        executionSafe = false
        failedChannels = HashSet()


        channelSuccessMessage = HashSet()
        channelCommitMessage = HashSet()
    }

    override fun reset() {
        this.resetRoundVars()

        orderOfStarting = ArrayList()
        roundIndex = 0
        outgoingDemandEdges = HashSet()
        incomingDemandEdges = HashSet()
        maxRound = 0

        super.reset()
    }
    
    override fun sortMessage (message: Message) {
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

    override fun isRebalancingAwake(): Boolean {
        return this.rebalancingAwake
    }

    override fun startSubAlgos(algoSettings: Map<String, Any>): SimulationInput? {
        if (this.isRunningAlgo) {
            logger.warn("Already running another algorithm, aborting...")
            return null
        }

        logger.info("Starting participant discovery before rebalancing")

        this.algoSettings = algoSettings

        val simulInput = this.findParticipants(algoSettings)

        this.isRunningAlgo = true

        return simulInput
    }

    override fun rebalance(event: StartEvent): SimulationInput {
        logger.info("Participant discovery finished with result ") //$foundParticipantsResult, ${result!!.acceptedEdges}")
        if (event.desc.step != Steps.Rebalance) {
            throw IllegalArgumentException("$this - Rebalancing may only be woken up after participant discovery is done!")
        }

        this.initMessageSending()

        this.wakeUp()

        this.stopMessageSending()
        return SimulationInput(this, this.sendingList, this.startStopDesc)
    }

    fun startRound() {
        logger.info("$anonId is starting round $roundIndex!")

        // If round starter has no outgoing edges, move directly to next round
        if (outgoingDemandEdges.isEmpty()) {
            return nextRound()
        }

        roundStateMachine.state = RoundState.REQ
        for (channel in outgoingDemandEdges) {
            val channelAnonId = Tag.createTag(this)
            anonIdChannelMap[channelAnonId] = channel
            val seenSet = receivedReqTags + channelAnonId

            sendMessage(RequestRebalancingMessage(MessageTypes.REQUEST_R, this, channel.getOppositeNode(this), channel, this.anonId!!, this.executionId!!, seenSet))
            nOfOutstandingRequests++
        }
    }

    fun wakeUp() {
        if (this.rebalancingAwake) {
            return // Already awake
        }

        logger.debug("I'm waking up!")

        val r = this.result
        if (r == null) {
            throw IllegalStateException("Result cannot be null when waking up!")
        }

        this.orderOfStarting = r.finalParticipants.toList().sorted()
        this.rebalancingAwake = true
        this.isRunningAlgo = true

        val percentageOfLeaders = this.algoSettings["percentageOfLeaders"] as Float
        this.maxRound = (this.orderOfStarting.size.toFloat() * percentageOfLeaders).roundToInt()

        this.retrieveChannelDemands()
        logger.debug("${getRoundStarterAsNode()} is round starter")

        if (iStartedRound()) {
            this.startRound()
        }
    }

    fun retrieveChannelDemands () {
        val r = this.result
        if (r == null) {
            throw IllegalStateException("Result cannot be null!")
        }

        this.channelDemands = r.acceptedEdges.map { it to it.getDemand(this) }.toMap()
        this.outgoingDemandEdges = this.channelDemands.filter {(_, value) -> value < 0} .keys
        this.incomingDemandEdges = this.channelDemands.keys - this.outgoingDemandEdges
    }

    // ---------- START Message checking functions -------------
    fun runMultipleMessageCheckingFunc(m: RebalancingMessage, functions: Array<KFunction1<RebalancingMessage, Message?>>): Message? {
        for (func in functions) {
            val res = func(m)
            res?.let { return res }
        }
        return null
    }

    fun disallowIncorrectExecutionId(mes: RebalancingMessage): Message? {
        if (this.executionId == null || this.executionId != mes.executionId) {
            logger.error("Received message with incorrect execution id!")
            return FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, FailReason.INCORRECT_EXECUTION_ID, mes.startId, this.executionId)
        }
        return null
    }

    fun disallowIfNotRebalancingAwake(mes: RebalancingMessage): Message? {
        if (!this.rebalancingAwake) {
            logger.error("Received message while not rebalancing awake!")
            return FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, FailReason.NOT_AWAKE, mes.startId, this.executionId)
        }
        return null
    }

    fun disallowIfFromEarlierRound(mes: RebalancingMessage): Message? {
        if (this.orderOfStarting.indexOf(mes.startId) < this.roundIndex) {
            logger.warn("Received message from earlier round")
            return FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, FailReason.INCORRECT_ROUND, mes.startId, this.executionId)
        }
        return null
    }

    fun disallowIfFromDifferentRound(mes: RebalancingMessage): Message? {
        if (this.getRoundStarter() != mes.startId) {
            logger.error("Received message from a different round while not allowed!")
            throw IllegalStateException("Message $this received from different round while not allowed!")
            // return FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, FailReason.INCORRECT_ROUND, mes.startId, this.executionId)
        }
        return null
    }

    fun deferProcessingIfNotFinishedWithPartDisc(mes: RebalancingMessage): Message? {
        if (this.executionId != null && !this.rebalancingAwake) {
            logger.warn("Not finished with part. discovery!")
            return mes // Add message to back of queue to try again later
        }
        return null
    }

    fun deferProcessingIfFromFutureRound(mes: RebalancingMessage): Message? {
        val roundIndexOfMessage = this.orderOfStarting.indexOf(mes.startId)
        if (roundIndexOfMessage > this.roundIndex) {
            if (roundIndexOfMessage > this.maxRound) {
                throw IllegalStateException("Future round will never be reached!")
            }
            logger.warn("Message is for future round!")
            return mes // Add message to back of queue to try again later
        }
        return null
    }

    fun generateDeferProcessingIfBeforeState(state: RoundState): KFunction1<RebalancingMessage, Message?> {
        fun deferProcessingIfBeforeState(mes: RebalancingMessage): Message? { 
            if (this.roundStateMachine.state < state) {
                logger.warn("Message is before state $state!")
                return mes // Add message to back of queue to try again later
            }
            return null
        }

        return ::deferProcessingIfBeforeState
    }

    // ---------- END Message checking functions -------------

    fun checkForCycles(mes: RequestRebalancingMessage, seenSetToCheck: Set<Tag>): Boolean {
        val intersect = anonIdChannelMap.keys intersect seenSetToCheck
        if (intersect.isNotEmpty()) {
            val channelAnonId = intersect.first() // We only care about one match

            receivedRequests.removeIf{m -> m.channel == mes.channel} // Make sure that no requests are in receivedRequests if we find the cycle
            if (!this.iStartedRound() && receivedRequests.isEmpty()) {
                logger.error("Received request is never allowed to be empty after checking for cycles if not round starter!")
                throw IllegalStateException("$this - Received request is never allowed to be empty after checking for cycles if not round starter!")
            }

            val cycleTag = Tag.createTag(this)
            val channelDemand = abs(this.channelDemands[mes.channel]!!)
            cycleChannelPairsMap[cycleTag] = CycleChannelPair(mes.channel, anonIdChannelMap.get(channelAnonId)!!, channelDemand, false)
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

    fun handleRequestMessage(mes: RequestRebalancingMessage) {
        // --- START checks
        val checkBeforeWaking = arrayOf(this::deferProcessingIfNotFinishedWithPartDisc, this::disallowIncorrectExecutionId)
        val checkBeforeWakeRes = this.runMultipleMessageCheckingFunc(mes, checkBeforeWaking)
        if (checkBeforeWakeRes != null) {
            return sendMessage(checkBeforeWakeRes)
        }

        if (!this.rebalancingAwake) {
            throw IllegalStateException("I should be awake here! ${this.executionId} ${this.result}")
        }

        val checkAfterWake = arrayOf(this::deferProcessingIfFromFutureRound, this::disallowIfFromEarlierRound)
        val checkAfterWakeRes = this.runMultipleMessageCheckingFunc(mes, checkAfterWake)
        if (checkAfterWakeRes != null) {
            return sendMessage(checkAfterWakeRes)
        }
        // --- END checks

        // Case when node has no outgoing edges
        if (outgoingDemandEdges.isEmpty()) {
            return sendMessage(FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, FailReason.NO_SUCCESS, mes.startId, this.executionId))
        }

        if (roundStateMachine.isInState(RoundState.WAIT)) {
            roundStateMachine.state = RoundState.REQ
            receivedReqTags.addAll(mes.seenSet)

            for (channel in outgoingDemandEdges) {
                val channelAnonId = Tag.createTag(this)
                anonIdChannelMap[channelAnonId] = channel
                val seenSet = receivedReqTags + channelAnonId

                sendMessage(RequestRebalancingMessage(MessageTypes.REQUEST_R, this, channel.getOppositeNode(this), channel, mes.startId, mes.executionId, seenSet))
                nOfOutstandingRequests++
            }
        } else if (roundStateMachine.isInState(RoundState.REQ)) {
            if (!this.checkForCyclesAndNewTags(mes)) {
                return
            }
        } else {
            return sendMessage(FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, FailReason.NO_SUCCESS, mes.startId, this.executionId))
        }

        receivedRequests.add(mes)
    }

    fun checkForCyclesAndNewTags(mes: RequestRebalancingMessage): Boolean {
        if (this.checkForCycles(mes, mes.seenSet)) {
            return false
        }

        if (this.iStartedRound()) {
            throw IllegalStateException("$this - A round starter should never send update messages!")
        }

        var topic = "Request"
        if (mes is UpdateRebalancingMessage) {
            topic = "Update"
        }
        
        val newSeenSetEntries = mes.seenSet.subtract(receivedReqTags)
        if (newSeenSetEntries.isNotEmpty()) {
            receivedReqTags.addAll(mes.seenSet)
            logger.debug("$topic message got new tags, sending update messages with new tags $newSeenSetEntries")

            for (channel in this.getChannelsThatHaveNotReplied()) {
                sendMessage(UpdateRebalancingMessage(MessageTypes.UPDATE_R, this, channel.getOppositeNode(this), channel, mes.startId, mes.executionId, newSeenSetEntries))
            }
        }
        return true
    }

    fun getChannelsThatHaveNotReplied(): Set<PaymentChannel> {
        return outgoingDemandEdges.subtract(this.getChannelsThatReplied())
    }

    fun getChannelsThatReplied(): Set<PaymentChannel> {
        return this.getChannelsThatRepliedWithSuccess().union(failedChannels)
    }

    fun getChannelsThatRepliedWithSuccess(): Set<PaymentChannel> {
        return receivedSuccesses.map{i -> i.channel}.toHashSet()
    }

    fun handleUpdateMessage(mes: UpdateRebalancingMessage) {
        // --- START checks
        val checks = arrayOf(this::disallowIncorrectExecutionId, this::disallowIfNotRebalancingAwake, this::deferProcessingIfFromFutureRound, 
            this::disallowIfFromEarlierRound, this.generateDeferProcessingIfBeforeState(RoundState.REQ))
        val checksRes = this.runMultipleMessageCheckingFunc(mes, checks)
        if (checksRes != null) {
            return sendMessage(checksRes)
        }
        // --- END checks

        if (roundStateMachine.state > RoundState.REQ) {
            logger.debug("Ignoring update because already in future roundState")
            return
        }

        if (mes.channel in sentSuccessChannel) {
            logger.debug("Already sent a SUCCESS over this channel, therefore ignoring UPDATE")
            return
        }

        if (!this.checkForCyclesAndNewTags(mes)) {
            return
        }
    }

    fun handleSuccessMessage(mes: SuccessRebalancingMessage) {
        // --- START checks
        val checks = arrayOf(this::disallowIncorrectExecutionId, this::disallowIfNotRebalancingAwake, this::disallowIfFromDifferentRound)
        val checksRes = this.runMultipleMessageCheckingFunc(mes, checks)
        if (checksRes != null) {
            return sendMessage(checksRes)
        }
        // --- END checks

        if (mes.channel in channelSuccessMessage) {
            throw IllegalStateException("$this - Already gotten success message this round from channel ${mes.channel}!")
        } else {
            channelSuccessMessage.add(mes.channel)
        }

        if (roundStateMachine.isInState(RoundState.REQ)) {
            receivedSuccesses.add(mes)
            nOfOutstandingRequests--

            replyToRequests()
        }
    }

    fun handleFailMessage(mes: FailRebalancingMessage) {
        if (mes.reason != FailReason.NO_SUCCESS) {
            return
        }

        if (roundStateMachine.isInState(RoundState.REQ)) {
            nOfOutstandingRequests--
            failedChannels.add(mes.channel)

            replyToRequests()
        }
    }

    fun replyToRequests() {
        if (nOfOutstandingRequests != 0) {
            logger.debug("Waiting for $nOfOutstandingRequests more responses")
            return
        }

        if (!roundStateMachine.isInState(RoundState.REQ)) {
            throw IllegalStateException("$this can only reply if in RoundState.SUC!")
        }

        roundStateMachine.state = RoundState.SUC
        
        for (success in receivedSuccesses) {
            for (tagDemandPair in success.tagList) {
                if (tagDemandPair.tag in cycleChannelPairsMap) { 
                    val entry = cycleChannelPairsMap[tagDemandPair.tag]!!
                    if (!entry.completed || tagDemandPair.demand > entry.demand) {
                        logger.debug("Storing new demand ${tagDemandPair.demand} for cycle ${tagDemandPair.tag}")
                        cycleChannelPairsMap[tagDemandPair.tag] =
                            CycleChannelPair(
                                entry.endChannel, 
                                success.channel, 
                                tagDemandPair.demand,
                                true
                            )
                    }
                } else {
                    logger.debug("Tag not in cyleMap, so putting it in G")
                    if (tagDemandPair.tag !in G || (tagDemandPair.demand > G[tagDemandPair.tag]!!.first)) {
                        G[tagDemandPair.tag] = Pair(tagDemandPair.demand, success.channel)
                    }
                }
            }
        }

        if (iStartedRound()) {
            commitLeader()
        } else {
            val F = G.entries.toList()
            assert(receivedRequests.isNotEmpty())
            for (request in receivedRequests) {
                val channelDemand = abs(this.channelDemands[request.channel]!!)
                val N = splitEqually(channelDemand, F.map { e -> e.value.first }.toIntArray())
                val K: MutableList<TagDemandPair> = ArrayList()
                for (i in 0 until F.size) {
                    K += TagDemandPair(F[i].key, N[i])
                }
                sendMessage(SuccessRebalancingMessage(MessageTypes.SUCCESS_R, this, request.sender, request.channel, getRoundStarter(), this.executionId!!, K))
            }
        }
    }

    fun commitLeader() {
        val P: MutableMap<PaymentChannel, Pair<MutableList<TagDemandHTLCPair>, MutableMap<Tag, Transaction>>> = HashMap()
        for (entry in cycleChannelPairsMap.entries) {
            if (!entry.value.completed) {
                throw IllegalStateException("It should be impossible for the leader to obtain an incomplete cycle!")
            }

            if (entry.value.demand > 0) {
                val preImage = UUID.randomUUID().toString()
                htlcMap[entry.key] = preImage
    
                val htlc = digest.digest(preImage.encodeToByteArray())
                val tx = Transaction(UUID.randomUUID(), entry.value.demand, this, entry.value.startChannel.getOppositeNode(this))
                
                logger.debug("Source: Requesting tx on ${entry.key}")
                entry.value.startChannel.requestTx(tx, htlc, true)
                
                val pairs = P.getOrPut(entry.value.startChannel, { Pair(ArrayList(), HashMap()) })
                pairs.first += TagDemandHTLCPair(entry.key, entry.value.demand, htlc)
                pairs.second[entry.key] = tx
            }
        }

        for (channel in this.getChannelsThatRepliedWithSuccess()) {
            if (channel in P) {
                sendMessage(CommitRebalancingMessage(MessageTypes.COMMIT_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), this.executionId!!, P.get(channel)!!.first, P.get(channel)!!.second))
            } else {
                sendMessage(CommitRebalancingMessage(MessageTypes.COMMIT_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), this.executionId!!, ArrayList(), HashMap()))
            }
        }

        if (cycleChannelPairsMap.isEmpty()) { // If no cycles exist, continue to nextRound
            nextRound()
        }
    }

    fun handleCommitMessage(mes: CommitRebalancingMessage) {
        // --- START checks
        val checks = arrayOf(this::disallowIncorrectExecutionId, this::disallowIfNotRebalancingAwake, this::disallowIfFromDifferentRound, this.generateDeferProcessingIfBeforeState(RoundState.SUC))
        val checksRes = this.runMultipleMessageCheckingFunc(mes, checks)
        if (checksRes != null) {
            return sendMessage(checksRes)
        }
        // --- END checks

        if (mes.channel in channelCommitMessage) {
            throw IllegalStateException("$this - Already received commit message on ${mes.channel}")
        } else {
            channelCommitMessage.add(mes.channel)
        }

        if (roundStateMachine.isInState(RoundState.SUC)) {
            roundStateMachine.state = RoundState.COM
        }

        if (roundStateMachine.state > RoundState.COM) {
            throw IllegalStateException("Received a COMMIT while in a future state! This can never happen")
        }
        
        val cycleEndChannels = cycleChannelPairsMap.values.map { v -> v.endChannel }
        if (mes.channel !in cycleEndChannels) {
            receivedCommits.add(mes)

            if (receivedCommits.size == receivedRequests.size) { // Forward commits
                logger.info("Received all commits from requesting channels")
                val K: MutableMap<PaymentChannel, MutableList<TagDemandHTLCPair>> = HashMap()
                for (commit in receivedCommits) {
                    for (tagDemandPair in commit.tagList) {
                        val pairs = K.getOrPut(G.get(tagDemandPair.tag)!!.second) { ArrayList() }
                        pairs += tagDemandPair
                    }
                }
                for (entry in cycleChannelPairsMap.entries) {
                    if (!entry.value.completed) { // Do not give commit for an owned cycle if the cycle tag hasn't made it all the way back to the owner
                        logger.debug("Cycle ${entry.key} not complete, skipping...")
                        continue
                    }

                    if (entry.value.demand > 0) {
                        val preImage = UUID.randomUUID().toString()
                        htlcMap[entry.key] = preImage
    
                        val htlc = digest.digest(preImage.encodeToByteArray())

                        val pairs = K.getOrPut(entry.value.startChannel, { ArrayList() })
                        pairs += TagDemandHTLCPair(entry.key, entry.value.demand, htlc)
    
                        logger.debug("Added cycle with tag ${entry.key} to outgoing commits")
                    }
                }

                for (channel in this.getChannelsThatRepliedWithSuccess()) {
                    val pairs = K[channel]
                    if (pairs != null) {
                        val tagTxMap: MutableMap<Tag, Transaction> = HashMap()
                        for (pair in pairs) {
                            if (pair.htlc != null) {
                                val tx = Transaction(UUID.randomUUID(), pair.demand, this, channel.getOppositeNode(this))
                                logger.debug("Requesting tx on ${pair.tag}")
                                channel.requestTx(tx, pair.htlc, true)
                                tagTxMap[pair.tag] = tx
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

            // Immediately execute cycle commit
            for (tagDemandPair in mes.tagList) {
                if (tagDemandPair.tag in mes.tagTxMap) {
                    val preImage = htlcMap[tagDemandPair.tag]!!
                    mes.channel.executeTx(mes.tagTxMap[tagDemandPair.tag]!!, digest.digest(preImage.encodeToByteArray()))
                    sendMessage(ExecuteRebalancingMessage(
                        MessageTypes.EXEC_R, this, mes.channel.getOppositeNode(this), mes.channel, this.getRoundStarter(), this.executionId!!,
                        tagDemandPair.tag, preImage
                    ))
                }
            }
        }

        if (receivedCommits.size + receivedCycleCommits.size == receivedRequests.size + cycleChannelPairsMap.size) {
            logger.info("Received all commits from requesting and cycle channels")

            // Store which transaction belongs to which channel and tag, but only for commits from sources other than owned cycles
            for (commit in receivedCommits) {
                for (tagDemandPair in commit.tagList) {
                    if (tagDemandPair.tag in commit.tagTxMap) {
                        assert(tagDemandPair.tag !in tagTransactionMap)

                        tagTransactionMap[tagDemandPair.tag] = Pair(commit.channel, commit.tagTxMap.get(tagDemandPair.tag)!!)
                    }
                }
            }

            roundStateMachine.state = RoundState.EXEC

            checkIfExecutionSafe()
        } else {
            logger.debug("#ofReceivedCommits: ${receivedCommits.size} #ofReceivedCycleCommits: ${receivedCycleCommits.size} #ofReceivedRequests: ${receivedRequests.size} #ofCycleChannelPairsMap ${cycleChannelPairsMap.size}")
            val nodesThatStillNeedToCommit = receivedRequests.map {m -> m.sender } - receivedCommits.map {m -> m.sender }
            for (node in nodesThatStillNeedToCommit) {
                logger.debug("$node still needs to send a commit message")
            }
        }
    }

    fun handleExecuteRebalancingMessage(mes: ExecuteRebalancingMessage) {
        // --- START checks
        if (!rebalancingAwake || mes.startId != getRoundStarter()) {
            return
        }

        val checks = arrayOf(this::disallowIncorrectExecutionId, this.generateDeferProcessingIfBeforeState(RoundState.EXEC))
        val checksRes = this.runMultipleMessageCheckingFunc(mes, checks)
        if (checksRes != null) {
            return sendMessage(checksRes)
        }
        // --- END checks

        if (!roundStateMachine.isInState(RoundState.EXEC)) {
            throw IllegalStateException("$this - Received a executing message before COM state!")
        }

        if (mes.tag !in tagTransactionMap) {
            if (mes.tag in cycleChannelPairsMap) {
                logger.debug("Received execution message back for own cycle ${mes.tag}, skipping...")
                return
            } else {
                throw IllegalStateException("$this - Tag ${mes.tag} not found in tagTransactionMap while executing")
            }
        }

        val entryValue = tagTransactionMap[mes.tag]!!
        logger.info("Executing ${mes.tag}")
        entryValue.first.executeTx(entryValue.second, digest.digest(mes.preImage.encodeToByteArray()))
        sendMessage(ExecuteRebalancingMessage(
            MessageTypes.EXEC_R, this, entryValue.first.getOppositeNode(this), entryValue.first, this.getRoundStarter(), this.executionId!!,
            mes.tag, mes.preImage
        ))

        tagTransactionMap.remove(mes.tag)

        checkIfExecutionSafe()
    }

    fun handleNextRoundMessage(mes: NextRoundMessage) {
        // --- START checks
        val checkBeforeWaking = arrayOf(this::deferProcessingIfNotFinishedWithPartDisc, this::disallowIncorrectExecutionId)
        val checkBeforeWakeRes = this.runMultipleMessageCheckingFunc(mes, checkBeforeWaking)
        checkBeforeWakeRes?.let { return sendMessage(checkBeforeWakeRes) }

        if (!this.rebalancingAwake) {
            throw IllegalStateException("I should be awake here! ${this.executionId} ${this.result}")
        }

        this.disallowIfFromEarlierRound(mes)?.let { return } // Filter out FailMessages that relate to incorrect round, as this happens often
        val checkAfterWakeRes = this.deferProcessingIfFromFutureRound(mes)
        checkAfterWakeRes?.let { return sendMessage(checkAfterWakeRes) }
        // --- END checks

        if (!forwardedNextRoundMessage) {
            for (channel in (outgoingDemandEdges union incomingDemandEdges)) {
                sendMessage(NextRoundMessage(MessageTypes.NEXT_ROUND_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), executionId!!))
            }
            forwardedNextRoundMessage = true
        }

        if (canGoToNextRound()) {
            logger.debug("Pushed to next round by nextRoundMessage")
            nextRound()
        }
    }

    // If there are no commits from sources other than an owned cycle, continue to next round
    fun checkIfExecutionSafe() {
        if (tagTransactionMap.isEmpty()) {
            executionSafe = true
            logger.debug("Now executionSafe")
            if (canGoToNextRound()) {
                nextRound()
            }
        } else {
            logger.debug("Still waiting for ${tagTransactionMap.keys}")
        }
    }

    fun getRoundStarter(): Tag {
        return this.orderOfStarting[this.roundIndex]
    }

    fun getRoundStarterAsNode(): Node? {
        return g.graph.vertexSet().filter {v -> (v as CoinWasherNode).anonId == this.getRoundStarter()} .firstOrNull()
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

    fun canGoToNextRound(): Boolean {
        return roundStateMachine.isInState(RoundState.WAIT) || (executionSafe && (forwardedNextRoundMessage || iStartedRound()))
    }

    fun nextRound() {
        if (iStartedRound()) {
            for (channel in (outgoingDemandEdges union incomingDemandEdges)) {
                sendMessage(NextRoundMessage(MessageTypes.NEXT_ROUND_R, this, channel.getOppositeNode(this), channel, getRoundStarter(), executionId!!))
            }
        }


        roundIndex++
        logger.debug("Going to round $roundIndex!")

        this.resetRoundVars()

        if (roundIndex >= this.maxRound) {
            return terminateRebalancing(true)
        }

        this.retrieveChannelDemands()
        logger.debug("${getRoundStarterAsNode()} is round starter")

        if (iStartedRound()) {
            this.startRound()
        }
    }

    fun terminateRebalancing(success: Boolean) {
        // Unlock all incoming edges for normal txs, as those can only be executed by current node
        for (channel in incomingDemandEdges) {
            if (channel.hasOngoingTx()) {
                throw IllegalStateException("Channel $channel is not allowed to be unlocked by $this as it still has ongoing transactions!")
            }
            
            channel.unlock()    
        }

        if (success) {
            logger.info("Finished rebalancing successfully")
        } else {
            logger.info("Terminated rebalancing unsuccessfully")
        }

        this.reset()
    }
}