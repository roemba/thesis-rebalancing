package roemer.rebalancing

import java.util.UUID
import kotlinx.coroutines.delay

class ParticipantNode(id: Int, g: ChannelNetwork, totalFunds: Int = 0) : Node(id, g, totalFunds) {
    var awake = false
    var started = false
    var executionId: UUID? = null
    var anonId: UUID? = null
    var participants: MutableSet<UUID> = HashSet()
    var unacceptedInviteEdges: MutableSet<PaymentChannel> = HashSet()
    var acceptedEdges: MutableSet<PaymentChannel> = HashSet()
    var positiveDemandEdges: MutableSet<PaymentChannel> = HashSet()
    var negativeDemandEdges: MutableSet<PaymentChannel> = HashSet()
    var invitedEdges: MutableSet<PaymentChannel> = HashSet()
    var sourceEdge: PaymentChannel? = null
    
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

        logger.info("Starting to find participants with execution id: $executionId and participant id: $anonId")

        for (channel in this.paymentChannels) {
            sendMessage(InviteParticipantMessage(
                MessageTypes.INVITE_P, this, channel.getOppositeNode(this), channel, executionId!!, hopCount
            ))
            invitedEdges.add(channel)
        }
    } 

    suspend fun handleInviteMessage(mes: InviteParticipantMessage) {
        // Deny if execution id is not the same
        if (executionId != null && mes.executionId != executionId) {
            logger.info("Denying because other execution id")
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, executionId!!))
        }

        // Check if, with current knowledge, it makes sense to participate
        if (!possibleToParticipate2()) {
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, mes.executionId))
        }

        // Deny if the channel still has ongoing transactions after 5s
        val senderChannel = mes.channel
        val (demand, success) = this.getDemandForChannel(senderChannel)
        if (!success) {
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, executionId!!))
        }

        // If not already claimed, become claimed
        if (executionId == null) {
            executionId = mes.executionId
            anonId = UUID.randomUUID()
            participants.add(anonId!!)
            sourceEdge = mes.channel
            logger.info("Claimed by execution id: $executionId using participant id: $anonId")
        }

        // Put demand into the correct collection
        if (demand > 0) {
            positiveDemandEdges.add(senderChannel)
        } else {
            negativeDemandEdges.add(senderChannel)
        }
        
        // If hop count is 0, terminate search
        if (mes.hopCount - 1 == 0) {
            if (positiveDemandEdges.isEmpty() || negativeDemandEdges.isEmpty()) { // Fix here that, only terminate if not already accepted
                sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, executionId!!))
                if (!this.awake) {
                    return terminate(false, "Hop count is zero and not enough participating edges")
                }
            } else {
                return sendMessage(AcceptParticipantMessage(MessageTypes.ACCEPT_P, this, mes.sender, mes.channel, executionId!!, participants))
            }
        }
        
        // If not awake, propagate invite to other channels
        if (!this.awake) {
            this.awake = true
            for (channel in this.paymentChannels) {
                if (channel !== senderChannel && channel.getCurrentDemand(this) != 0) {
                    sendMessage(InviteParticipantMessage(
                        MessageTypes.INVITE_P, this, channel.getOppositeNode(this), channel, executionId!!, mes.hopCount - 1
                    ))
                    invitedEdges.add(channel)
                }
            }

            if (invitedEdges.isEmpty()) {
                sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, executionId!!))
                return terminate(false, "No one to sent invites to")
            }
        }

        if (!positiveDemandEdges.isEmpty() && !negativeDemandEdges.isEmpty()) {
            acceptedEdges.add(mes.channel)
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

        val senderChannel = mes.channel
        invitedEdges.remove(senderChannel)
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

    suspend fun denyAndTerminate (reason: String) {
        val edgesToDeny: MutableSet<PaymentChannel> = HashSet()
        edgesToDeny.addAll(unacceptedInviteEdges)
        edgesToDeny.addAll(invitedEdges)
        edgesToDeny.addAll(acceptedEdges)

        for (channel in edgesToDeny) {
            sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, channel.getOppositeNode(this), channel, executionId!!))
        }
        terminate(false, reason)
    }

    suspend fun handleResponses() {
        val canSendAccept = !positiveDemandEdges.isEmpty() && !negativeDemandEdges.isEmpty()

        if (!this.started && !possibleToParticipate()) {
            return denyAndTerminate("Made no sense to continue participation")
        }

        logger.debug("nOfInvitedEdges: ${this.invitedEdges.size} nOfAcceptedEdges: ${this.acceptedEdges.size} canSendAccept: $canSendAccept")

        if (invitedEdges.isEmpty()) {
            if (this.acceptedEdges.isNotEmpty()) {
                if (this.started) {
                    for (channel in this.acceptedEdges) {
                        sendMessage(FinishParticipantMessage(MessageTypes.FINISH_P, this, channel.getOppositeNode(this), channel, executionId!!, participants))
                    }
                    return terminate(true)
                } else if (canSendAccept) {
                    for (channel in this.unacceptedInviteEdges) {
                        acceptedEdges.add(channel)
                        sendMessage(AcceptParticipantMessage(MessageTypes.ACCEPT_P, this, channel.getOppositeNode(this), channel, executionId!!, participants))
                    }
                    this.unacceptedInviteEdges = HashSet()
                } else {
                    denyAndTerminate("Did not receive enough accepts")
                }
            } else {
                denyAndTerminate("Did not receive any accepts")
            }
        }
    }

    suspend fun handleFinishMessage(mes: FinishParticipantMessage) {
        if (!awake) {
            return
        } else if (mes.executionId != executionId) {
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, executionId!!))
        }

        participants = mes.participants.toMutableSet()

        for (channel in this.acceptedEdges) {
            if (channel != mes.channel) {
                sendMessage(FinishParticipantMessage(MessageTypes.FINISH_P, this, channel.getOppositeNode(this), channel, executionId!!, participants))
            }
        }
        terminate(true)
    }

    suspend fun handleDenyMessage(mes: ParticipantMessage) {
        if (!awake) {
            return
        }
        
        unacceptedInviteEdges.remove(mes.channel)
        invitedEdges.remove(mes.channel)
        acceptedEdges.remove(mes.channel)
        positiveDemandEdges.remove(mes.channel)
        negativeDemandEdges.remove(mes.channel)

        if (sourceEdge == mes.channel) {
            return denyAndTerminate("Source edge has sent deny!")
        }

        handleResponses()
    }

    /*
        Determine if, with the current information, it still makes sense for the node to keep trying to participate
    */
    suspend fun possibleToParticipate(): Boolean {
        var nOfPotentialPositiveEdges = 0
        var nOfPotentialNegativeEdges = 0

        val edges: MutableSet<PaymentChannel> = HashSet()
        edges.addAll(acceptedEdges)
        edges.addAll(invitedEdges)
        edges.addAll(positiveDemandEdges)
        edges.addAll(negativeDemandEdges)
        edges.addAll(unacceptedInviteEdges)

        // Find the channels on which we have sent *and* received an invite, as we can then assume that both parties want to accept. We can also check these for potential.
        for (channel in edges) {
            val demand = channel.getCurrentDemand(this)

            if (demand > 0) {
                nOfPotentialPositiveEdges += 1
            } else {
                nOfPotentialNegativeEdges += 1
            }
        }

        // logger.log("$nOfPotentialPositiveEdges - $nOfPotentialNegativeEdges")

        return nOfPotentialPositiveEdges != 0 && nOfPotentialNegativeEdges != 0
    }

    suspend fun possibleToParticipate2(): Boolean {
        var nOfPotentialPositiveEdges = 0
        var nOfPotentialNegativeEdges = 0

        // Find the channels on which we have sent *and* received an invite, as we can then assume that both parties want to accept. We can also check these for potential.
        for (channel in paymentChannels) {
            val demand = channel.getCurrentDemand(this)

            if (demand > 0) {
                nOfPotentialPositiveEdges += 1
            } else {
                nOfPotentialNegativeEdges += 1
            }
        }

        return nOfPotentialPositiveEdges != 0 && nOfPotentialNegativeEdges != 0
    }

    suspend fun terminate(success: Boolean, reason: String = "Unknown") {
        if (success) {
            // End result: participants and acceptedEdges
            logger.info("Finished with participants size: ${participants.size} set: $participants")
        } else {
            for (channel in this.paymentChannels) {
                channel.unlock()
            }
            logger.info("Finished but was not successfull because: $reason")
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
        started = false
        executionId = null
        anonId = null
        participants  = HashSet()
        unacceptedInviteEdges = HashSet()
        acceptedEdges = HashSet()
        positiveDemandEdges = HashSet()
        negativeDemandEdges = HashSet()
        invitedEdges = HashSet()
        sourceEdge = null
    }
}