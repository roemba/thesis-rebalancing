package roemer.rebalancing

import java.util.UUID
import kotlinx.coroutines.delay

class ParticipantNodeAlt(id: Int, g: ChannelNetwork, totalFunds: Int = 0) : Node(id, g, totalFunds) {
    var awake = false
    var started = false
    var executionId: UUID? = null
    var anonId: UUID? = null
    var participants: MutableSet<UUID> = HashSet()
    var acceptedEdges: MutableSet<PaymentChannel> = HashSet()
    var parentEdge: PaymentChannel? = null
    var invitedEdges: MutableSet<PaymentChannel> = HashSet()
    var nOfExpectedResponses = 0
    var deniedEdges: MutableSet<PaymentChannel> = HashSet()
    var overalSuccess = false
    var finalParticipants: MutableSet<UUID>? = null
    var receivedResponses = 0
    var sendFinalList = false
    
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
            nOfExpectedResponses++
        }
    } 

    suspend fun handleInviteMessage(mes: InviteParticipantMessage) {
        if (SeededRandom.random.nextInt(10) < 2) {
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

        // If not already claimed, become claimed
        if (executionId == null) {
            executionId = mes.executionId
            anonId = UUID.randomUUID()
            participants.add(anonId!!)
            parentEdge = mes.channel
            logger.info("Claimed by execution id: $executionId using participant id: $anonId, parentEdge: $parentEdge")
        
            // If hop count is 0, terminate search
            if (mes.hopCount - 1 == 0) {
                return sendMessage(AcceptParticipantMessage(MessageTypes.ACCEPT_P, this, mes.sender, mes.channel, executionId!!, participants))
            }
            
            // If not awake, propagate invite to other channels
            if (!this.awake) {
                this.awake = true
                for (channel in this.paymentChannels) {
                    if (channel !== parentEdge && !(channel in deniedEdges)) {
                        sendMessage(InviteParticipantMessage(
                            MessageTypes.INVITE_P, this, channel.getOppositeNode(this), channel, executionId!!, mes.hopCount - 1
                        ))
                        invitedEdges.add(channel)
                        nOfExpectedResponses++
                    }
                }
                logger.debug("Invited $nOfExpectedResponses edges")
            }

            if ((this.paymentChannels.size - deniedEdges.size) == 1) {
                return sendMessage(AcceptParticipantMessage(MessageTypes.ACCEPT_P, this, mes.sender, mes.channel, executionId!!, participants))
            }
        }

        if (mes.channel !== parentEdge) {
            return sendMessage(AcceptParticipantMessage(MessageTypes.ACCEPT_P, this, mes.sender, mes.channel, executionId!!, participants))
        }
    }

    suspend fun handleAcceptMessage(mes: AcceptParticipantMessage) {
        if (!awake) {
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
        acceptedEdges.add(senderChannel)

        handleResponses()
    }

    suspend fun handleDenyMessage(mes: ParticipantMessage) {
        if (!awake) {
            return
        }

        if (!(mes.channel in invitedEdges) && !(mes.channel in acceptedEdges)) {
            logger.warn("Already received deny response on this edge!")
            return
        }
        
        invitedEdges.remove(mes.channel)
        acceptedEdges.remove(mes.channel)

        handleResponses()
    }

    suspend fun denyAndTerminate (reason: String) {
        if (!this.started) { 
            sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, parentEdge!!.getOppositeNode(this), parentEdge!!, executionId!!)) 
        }

        terminate(false, reason)
    }

    suspend fun handleResponses() {
        nOfExpectedResponses--
        if (nOfExpectedResponses < 5) {
            for (channel in invitedEdges) {
                logger.debug("Still waiting for response from $channel")
            }
        }


        if (nOfExpectedResponses == 0 && !sendFinalList) {
            sendFinalList = true
            logger.debug("nOfInvitedEdges: ${this.invitedEdges.size} nOfAcceptedEdges: ${this.acceptedEdges.size}")

            if (invitedEdges.isEmpty()) {
                if (this.acceptedEdges.isNotEmpty()) {
                    if (this.started) {
                        for (channel in this.acceptedEdges) {
                            sendMessage(FinishParticipantMessage(MessageTypes.FINISH_P, this, channel.getOppositeNode(this), channel, executionId!!, participants))
                        }
                        return terminate(true)
                    } else {
                        sendMessage(AcceptParticipantMessage(MessageTypes.ACCEPT_P, this, parentEdge!!.getOppositeNode(this), parentEdge!!, executionId!!, participants))
                    }
                } else {
                    denyAndTerminate("Did not receive any accepts")
                }
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



    suspend fun terminate(success: Boolean, reason: String = "Unknown") {
        if (success) {
            // End result: participants and acceptedEdges
            logger.info("Finished with participants size: ${participants.size}")
            overalSuccess = true
            finalParticipants = participants
            if (!(this.anonId in participants)) {
                logger.error("I have not been put up in the participants list!")
            }
        } else {
            logger.info("Finished but was not successfull because: $reason")
        }
        
        reset()
    }

    fun reset() {
        awake = false
        started = false
        executionId = null
        anonId = null
        participants  = HashSet()
        acceptedEdges = HashSet()
        invitedEdges = HashSet()
        parentEdge = null
        nOfExpectedResponses = 0

        sendFinalList = false
    }
}