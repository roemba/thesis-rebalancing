package roemer.rebalancing

import java.util.UUID
import kotlinx.coroutines.delay

class ParticipantNode(id: Int, g: ChannelNetwork, totalFunds: Int = 0) : Node(id, g, totalFunds) {
    var awake = false
    var nOfExpectedResponses: Int = 0
    var started = false
    var executionId: UUID? = null
    var anonId: UUID? = null
    var participants: MutableSet<UUID> = HashSet()
    var unacceptedInviteEdges: MutableSet<PaymentChannel> = HashSet()
    var acceptedEdges: MutableSet<PaymentChannel> = HashSet()
    var positiveDemandEdges: MutableSet<PaymentChannel> = HashSet()
    var negativeDemandEdges: MutableSet<PaymentChannel> = HashSet()

    var deniedEdges: MutableSet<PaymentChannel> = HashSet()
    
    override suspend fun sortMessage (message: Message) {
        when (message.type) {
            MessageTypes.INVITE_P -> handleInviteMessage(message as InviteParticipantMessage)
            MessageTypes.FINISH_P -> handleFinishMessage(message as FinishParticipantMessage)
            MessageTypes.DENY_P -> handleDenyMessage(message as ParticipantMessage)
            MessageTypes.ACCEPT_P -> handleAcceptMessage(message as AcceptParticipantMessage)
            else -> {
                super.sortMessage(message)
            }
        }
    }

    suspend fun startFindingParticipants(hopCount: Int) {
        this.awake = true
        this.started = true
        executionId = UUID.randomUUID()
        anonId = UUID.randomUUID()
        participants.add(anonId!!)
        nOfExpectedResponses = this.paymentChannels.size

        for (channel in this.paymentChannels) {
            sendMessage(InviteParticipantMessage(
                MessageTypes.INVITE_P, this, channel.getOppositeNode(this), channel, executionId!!, hopCount
            ))
        }
    } 

    suspend fun handleInviteMessage(mes: InviteParticipantMessage) {
        if (SeededRandom.random.nextInt(10) < -1) { // Deny randomly 1 in 10 times
            deniedEdges.add(mes.channel)
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, mes.executionId))
        } else if (executionId == null) {
            executionId = mes.executionId
            anonId = UUID.randomUUID()
            participants.add(anonId!!)
        } else if (mes.executionId != executionId) {
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, executionId!!))
        }

        // Get the demand and put it into the correct collection
        val senderChannel = mes.channel
        val (demand, success) = this.getDemandForChannel(senderChannel)
        if (!success) {
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, executionId!!))
        }

        if (demand > 0) {
            positiveDemandEdges.add(senderChannel)
        } else {
            negativeDemandEdges.add(senderChannel)
        }
        
        // If hop count is 0, terminate search
        if (mes.hopCount - 1 == 0) {
            if (positiveDemandEdges.isEmpty() || negativeDemandEdges.isEmpty()) {
                sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, executionId!!))
                return terminate(false, "Hop count is zero and not enough participating edges")
            } else {
                return sendMessage(AcceptParticipantMessage(MessageTypes.ACCEPT_P, this, mes.sender, mes.channel, executionId!!, participants))
            }
        }
        
        // If not awake, propagate invite to other channels
        if (!this.awake) {
            this.awake = true
            for (channel in this.paymentChannels) {
                if (channel !== senderChannel && channel.getCurrentDemand(this) != 0 && !deniedEdges.contains(channel)) {
                    sendMessage(InviteParticipantMessage(
                        MessageTypes.INVITE_P, this, channel.getOppositeNode(this), channel, executionId!!, mes.hopCount - 1
                    ))
                    nOfExpectedResponses += 1
                }
            }

            if (nOfExpectedResponses == 0) {
                sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, executionId!!))
                return terminate(false, "No one to sent invites to")
            }
        }

        if (!positiveDemandEdges.isEmpty() && !negativeDemandEdges.isEmpty()) {
            return sendMessage(AcceptParticipantMessage(MessageTypes.ACCEPT_P, this, mes.sender, mes.channel, executionId!!, participants))
        } else {
            unacceptedInviteEdges.add(senderChannel)
        }
    }

    suspend fun handleAcceptMessage(mes: AcceptParticipantMessage) {
        if (!awake) {
            return
        } else if (mes.executionId != executionId) {
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, executionId!!))
        }

        participants.addAll(mes.participants)
        this.nOfExpectedResponses -= 1

        val senderChannel = mes.channel
        acceptedEdges.add(senderChannel)

        val (demand, success) = this.getDemandForChannel(senderChannel)
        if (!success) {
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, executionId!!))
        }

        if (demand > 0) {
            positiveDemandEdges.add(senderChannel)
        } else {
            negativeDemandEdges.add(senderChannel)
        }

        handleResponses()
    }

    suspend fun handleResponses() {
        val canSendAccept = !positiveDemandEdges.isEmpty() && !negativeDemandEdges.isEmpty()

        val denyAndTerminate = suspend {
            for (channel in this.unacceptedInviteEdges) {
                sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, channel.getOppositeNode(this), channel, executionId!!))
            }
            terminate(false, "Received only deny's")
        }

        if (canSendAccept) {
            for (channel in this.unacceptedInviteEdges) {
                sendMessage(AcceptParticipantMessage(MessageTypes.ACCEPT_P, this, channel.getOppositeNode(this), channel, executionId!!, participants))
            }
            this.unacceptedInviteEdges = HashSet()
        }

        if (this.nOfExpectedResponses == 0) {
            if (this.acceptedEdges.isNotEmpty()) {
                if (this.started) {
                    for (channel in this.acceptedEdges) {
                        sendMessage(FinishParticipantMessage(MessageTypes.FINISH_P, this, channel.getOppositeNode(this), channel, executionId!!, participants))
                    }
                    return terminate(true)
                } else if (!canSendAccept) {
                    denyAndTerminate()
                }
            } else {
                denyAndTerminate()
            }
        }
    }

    suspend fun handleFinishMessage(mes: FinishParticipantMessage) {
        if (!awake) {
            return
        } else if (mes.executionId != executionId) {
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, executionId!!))
        }

        participants.addAll(mes.participants)

        for (channel in this.acceptedEdges) {
            sendMessage(FinishParticipantMessage(MessageTypes.FINISH_P, this, channel.getOppositeNode(this), channel, executionId!!, participants))
        }
        terminate(true)
    }

    suspend fun handleDenyMessage(mes: ParticipantMessage) {
        if (!awake) {
            return
        } else if (mes.executionId != executionId) {
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, executionId!!))
        }
        
        this.nOfExpectedResponses -= 1

        positiveDemandEdges.remove(mes.channel)
        negativeDemandEdges.remove(mes.channel)

        handleResponses()
    }

    suspend fun terminate(success: Boolean, reason: String = "Unknown") {
        if (success) {
            logger.log("Finished with participants $participants")
        } else {
            for (channel in this.paymentChannels) {
                channel.unlock()
            }
            logger.log("Finished but was not successfull because: $reason")
        }
        
        reset()
    }

    suspend fun getDemandForChannel(cha: PaymentChannel): Pair<Int, Boolean>  {
        var demand: Int
        try {
            demand = cha.getDemand(this)
        } catch (e1: IllegalStateException) { // Still have ongoing transactions, wait and try again
            delay(5000)
            try {
                demand = cha.getDemand(this)
            } catch (e2: IllegalStateException) { // Give up
                cha.unlock()
                return Pair(0, false)
            }
        }
        return Pair(demand, true)
    }

    fun reset() {
        awake = false
        nOfExpectedResponses = 0
        started = false
        executionId = null
        anonId = null
        participants  = HashSet()
        unacceptedInviteEdges = HashSet()
        acceptedEdges = HashSet()
        positiveDemandEdges = HashSet()
        negativeDemandEdges = HashSet()
        deniedEdges = HashSet()
    }
}