package roemer.rebalancing

data class ParticipantFindingResult (
    val executionId: Tag,
    val finalParticipants: Set<Tag>,
    val acceptedEdges: Set<PaymentChannel>
)

open class ParticipantNodeAlt(id: Int, g: ChannelNetwork, val randomDeny: Boolean = false) : Node(id, g) {
    var started = false
    var invitesSend = false
    var executionId: Tag? = null
    var anonId: Tag? = null
    var participants: MutableSet<Tag> = HashSet()
    var edgesThatAcceptedInvite: MutableSet<PaymentChannel> = HashSet()
    var childEdges: MutableSet<PaymentChannel> = HashSet()
    var parentEdge: PaymentChannel? = null
    var invitedEdges: MutableSet<PaymentChannel> = HashSet()
    var nOfExpectedResponses = 0
    var deniedEdges: MutableSet<PaymentChannel> = HashSet()
    var edgesIAccepted: MutableSet<PaymentChannel> = HashSet()
    var receivedResponses = 0
    var sendFinalList = false
    var result: ParticipantFindingResult? = null
    lateinit var algoSettings: Map<String, Any>
    
    override fun sortMessage (message: Message) {
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

    fun findParticipants(algoSettings: Map<String, Any>): SimulationInput {
        if (this.isRunningAlgo) {
            throw IllegalStateException("Already running an algorithm, cannot start another")
        }

        this.isRunningAlgo = true

        this.algoSettings = algoSettings
        this.initMessageSending()

        this.discoverAwake = true
        this.started = true
        this.invitesSend = true

        if (executionId != null) {
            throw IllegalStateException("Execution id must be null as otherwise I'm overwriting another execution!")
        }

        executionId = Tag.createTag(this)
        anonId = Tag.createTag(this)
        participants.add(anonId!!)

        logger.info("Starting to find participants with execution id: $executionId and participant id: $anonId")

        for (channel in this.paymentChannels) {
            sendMessage(InviteParticipantMessage(
                MessageTypes.INVITE_P, this, channel.getOppositeNode(this), channel, executionId!!, algoSettings["hopCount"] as Int, this.algoSettings
            ))
            invitedEdges.add(channel)
            nOfExpectedResponses++
        }

        this.stopMessageSending()
        return SimulationInput(this, sendingList, null)
    } 

    fun handleInviteMessage(mes: InviteParticipantMessage) {
        if (randomDeny && SeededRandom.random.nextInt(10) < 2) {
            deniedEdges.add(mes.channel)
        }

        if (mes.channel in deniedEdges) {
            logger.info("Denying because I feel like it")
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, mes.executionId))
        }

        // Deny if execution id is not the same
        if (executionId != null && mes.executionId != executionId) {
            logger.info("Denying because other execution id")
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, executionId!!))
        }

        // If not already claimed or finished, become claimed
        if (executionId == null && result == null) {
            this.discoverAwake = true
            this.isRunningAlgo = true
            executionId = mes.executionId
            this.algoSettings = mes.algoSettings
            anonId = Tag.createTag(this)
            participants.add(anonId!!)
            parentEdge = mes.channel
            logger.info("Claimed by execution id: $executionId using participant id: $anonId")
        }

        // If not send invites yet, propagate invite to other channels
        if (!this.invitesSend && mes.hopCount - 1 > 0 && (this.paymentChannels.size - deniedEdges.size) > 1) {
            this.invitesSend = true
            parentEdge = mes.channel
            val maxNumberOfInvites = this.algoSettings["maxNumberOfInvites"] as Int
            for (channel in this.paymentChannels) {
                if (channel !== parentEdge && !(channel in deniedEdges)) {
                    sendMessage(InviteParticipantMessage(
                        MessageTypes.INVITE_P, this, channel.getOppositeNode(this), channel, executionId!!, mes.hopCount - 1, this.algoSettings
                    ))
                    invitedEdges.add(channel)
                    nOfExpectedResponses++

                    if (invitedEdges.size >= maxNumberOfInvites) {
                        break
                    }
                }
            }
            logger.debug("Invited $nOfExpectedResponses edges")
            return
        } 
            
        edgesIAccepted.add(mes.channel)
        return sendMessage(AcceptParticipantMessage(MessageTypes.ACCEPT_P, this, mes.sender, mes.channel, executionId!!, participants, parentEdge == mes.channel))
    }

    fun handleAcceptMessage(mes: AcceptParticipantMessage) {
        if (!discoverAwake) {
            return
        } else if (mes.executionId != executionId) {
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, executionId!!))
        }

        if (!(mes.channel in invitedEdges)) {
            logger.warn("Already received accept response on this edge!")
            return
        }

        participants.addAll(mes.participants)

        val senderChannel = mes.channel
        invitedEdges.remove(senderChannel)
        edgesThatAcceptedInvite.add(senderChannel)
        
        if (mes.parent) {
            childEdges.add(senderChannel)
        }

        handleResponses()
    }

    fun handleDenyMessage(mes: ParticipantMessage) {
        if (!discoverAwake) {
            return
        }

        if (!(mes.channel in invitedEdges) && !(mes.channel in edgesThatAcceptedInvite)) {
            logger.warn("Already received deny response on this edge!")
            return
        }
        
        invitedEdges.remove(mes.channel)
        edgesThatAcceptedInvite.remove(mes.channel)
        childEdges.remove(mes.channel)
        edgesIAccepted.remove(mes.channel)

        handleResponses()
    }

    fun handleResponses() {
        nOfExpectedResponses--
        // if (nOfExpectedResponses < 5) {
        //     for (channel in invitedEdges) {
        //         logger.debug("Still waiting for response from $channel")
        //     }
        // }

        if (nOfExpectedResponses == 0 && !sendFinalList) {
            sendFinalList = true
            // logger.debug("nOfInvitedEdges: ${this.invitedEdges.size} nOfAcceptedEdges: ${this.edgesThatAcceptedInvite.size}")

            if (invitedEdges.isEmpty()) {
                if (this.started) {
                    if (this.edgesThatAcceptedInvite.isEmpty()) {
                        return terminate(false, "As starter, I did not receive any ACCEPTs!")
                    }
                    for (channel in this.childEdges) {
                        sendMessage(FinishParticipantMessage(MessageTypes.FINISH_P, this, channel.getOppositeNode(this), channel, executionId!!, participants))
                    }
                    return terminate(true)
                } else {
                    edgesIAccepted.add(parentEdge!!)
                    sendMessage(AcceptParticipantMessage(MessageTypes.ACCEPT_P, this, parentEdge!!.getOppositeNode(this), parentEdge!!, executionId!!, participants, true))
                }
            }
        }
    }

    fun handleFinishMessage(mes: FinishParticipantMessage) {
        if (!discoverAwake) {
            return
        } else if (mes.executionId != executionId) {
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.channel, executionId!!))
        }

        participants = mes.participants.toMutableSet()
	
        for (channel in this.childEdges) {
            if (channel != mes.channel) {
                sendMessage(FinishParticipantMessage(MessageTypes.FINISH_P, this, channel.getOppositeNode(this), channel, executionId!!, participants))
            }
        }
        terminate(true)
    }

    fun terminate(success: Boolean, reason: String = "Unknown") {
        if (success) {
            // End result: participants and acceptedEdges, do not reset yet
            logger.info("Finished with participants size: ${participants.size}")
            if (!(this.anonId in participants)) {
                throw IllegalStateException("I have not been put up in the participants list!")
            }
            
            result = ParticipantFindingResult(executionId!!, participants.toSet(), edgesThatAcceptedInvite.plus(edgesIAccepted))
            discoverAwake = false // Prevent participant discovery from doing anything else
            this.startStopDesc = StartDescription(Steps.Rebalance, this)
        } else {
            logger.info("Finished but was not successfull because: $reason")
            reset()
        }
    }

    override fun reset() {
        started = false
        invitesSend = false
        executionId = null
        anonId = null
        participants  = HashSet()
        edgesThatAcceptedInvite = HashSet()
        childEdges = HashSet()
        parentEdge = null
        invitedEdges = HashSet()
        nOfExpectedResponses = 0
        deniedEdges = HashSet()
        edgesIAccepted = HashSet()
        receivedResponses = 0
        sendFinalList = false
        result = null

        super.reset()
    }
}
