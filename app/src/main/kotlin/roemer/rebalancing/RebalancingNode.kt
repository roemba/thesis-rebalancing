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
    var anonIdChannelMap: Map<UUID, PaymentChannel> = HashMap()
    var cycleChannelPairsMap: Map<UUID, CycleChannelPair> = HashMap()
    var receivedRequests: MutableList<RequestRebalancingMessage> = ArrayList()
    var receivedCommits: MutableList<CommitRebalancingMessage> = ArrayList()
    var receivedSuccesses: MutableList<SuccessRebalancingMessage> = ArrayList()
    var orderOfStarting: List<UUID>? = null
    var roundIndex = 0
    var rebalancingAwake = false
    var outgoingDemandEdges: MutableSet<PaymentChannel> = HashSet()
    var incomingDemandEdges: MutableSet<PaymentChannel> = HashSet()
    
    override suspend fun sortMessage (message: Message) {
        when (message.type) {
            MessageTypes.REQUEST_R -> handleRequestMessage(message as InviteParticipantMessage)
            MessageTypes.UPDATE_R -> handleUpdateMessage(message as FinishParticipantMessage)
            MessageTypes.SUCCESS_R -> handleSuccessMessage(message as ParticipantMessage)
            MessageTypes.FAIL_R -> handleFailMessage(message as AcceptParticipantMessage)
            MessageTypes.COMMIT_R -> handleCommitMessage(message as AcceptParticipantMessage)
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

    suspend fun checkMessage(mes: RebalancingMessage, notRequest: Boolean = false): Boolean {
        if (this.executionId == null || this.executionId != mes.executionId) {
            sendMessage(FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, mes.startId, this.executionId))
            return false
        }

        if (notRequest) {
            if (!this.rebalancingAwake) {
                sendMessage(FailRebalancingMessage(MessageTypes.FAIL_R, this, mes.sender, mes.channel, mes.startId, this.executionId))
                return false
            }
        }

        return true
    }

    suspend fun handleRequestMessage(mes: RequestRebalancingMessage) {
        if (this.executionId != null && this.result == null) { // Participant discovery has started but has not finished
            sendMessage(mes) // Add message to back of queue to try again later
            return
        }

        if (!this.checkMessage(mes, false) || !this.wakeUp()) {
            return
        }

        if (roundState == RoundState.WAIT) {
            
        }
    }
}