package roemer.rebalancing

import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel

data class ParticipantFindingResult (
    val executionId: Tag,
    val finalParticipants: Set<Tag>,
    val acceptedEdges: Set<PaymentChannel>
)

open class ParticipantNodeAlt(id: Int, g: ChannelNetwork, val randomDeny: Boolean = false) : Node(id, g) {
    var awake = false
    var started = false
    var executionId: Tag? = null
    var anonId: Tag? = null
    var participants: MutableSet<Tag> = HashSet()
    var edgesThatAcceptedInvite: MutableSet<PaymentChannel> = HashSet()
    var parentEdge: PaymentChannel? = null
    var invitedEdges: MutableSet<PaymentChannel> = HashSet()
    var nOfExpectedResponses = 0
    var deniedEdges: MutableSet<PaymentChannel> = HashSet()
    var edgesIAccepted: MutableSet<PaymentChannel> = HashSet()
    var receivedResponses = 0
    var sendFinalList = false
    var result: ParticipantFindingResult? = null

    var resultReadyChannel: Channel<Boolean> = Channel(0) // Rendezvous channel
    
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

    suspend fun findParticipants(hopCount: Int, maxNOfInvites: Int) {
        this.awake = true
        this.started = true
        executionId = Tag.createTag()
        anonId = Tag.createTag()
        participants.add(anonId!!)

        logger.info("Starting to find participants with execution id: $executionId and participant id: $anonId")

        for (channel in this.paymentChannels) {
            sendMessage(InviteParticipantMessage(
                MessageTypes.INVITE_P, this, channel.getOppositeNode(this), channel, executionId!!, hopCount, maxNOfInvites
            ))
            invitedEdges.add(channel)
            nOfExpectedResponses++
        }
    } 

    suspend fun handleInviteMessage(mes: InviteParticipantMessage) {
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
            executionId = mes.executionId
            anonId = Tag.createTag()
            participants.add(anonId!!)
            parentEdge = mes.channel
            logger.info("Claimed by execution id: $executionId using participant id: $anonId, parentEdge: $parentEdge")
        
            // If hop count is 0, terminate search
            if (mes.hopCount - 1 == 0) {
                this.awake = true
                edgesIAccepted.add(mes.channel)
                return sendMessage(AcceptParticipantMessage(MessageTypes.ACCEPT_P, this, mes.sender, mes.channel, executionId!!, participants))
            }
            
            // If not awake, propagate invite to other channels
            if (!this.awake) {
                this.awake = true
                for (channel in this.paymentChannels) {
                    if (channel !== parentEdge && !(channel in deniedEdges)) {
                        sendMessage(InviteParticipantMessage(
                            MessageTypes.INVITE_P, this, channel.getOppositeNode(this), channel, executionId!!, mes.hopCount - 1, mes.maxNumberOfInvites
                        ))
                        invitedEdges.add(channel)
                        nOfExpectedResponses++

                        if (invitedEdges.size >= mes.maxNumberOfInvites) {
                            break
                        }
                    }
                }
                logger.debug("Invited $nOfExpectedResponses edges")
            }

            if ((this.paymentChannels.size - deniedEdges.size) == 1) {
                edgesIAccepted.add(mes.channel)
                return sendMessage(AcceptParticipantMessage(MessageTypes.ACCEPT_P, this, mes.sender, mes.channel, executionId!!, participants))
            }
        }

        if (mes.channel !== parentEdge) {
            edgesIAccepted.add(mes.channel)
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
        edgesThatAcceptedInvite.add(senderChannel)

        handleResponses()
    }

    suspend fun handleDenyMessage(mes: ParticipantMessage) {
        if (!awake) {
            return
        }

        if (!(mes.channel in invitedEdges) && !(mes.channel in edgesThatAcceptedInvite)) {
            logger.warn("Already received deny response on this edge!")
            return
        }
        
        invitedEdges.remove(mes.channel)
        edgesThatAcceptedInvite.remove(mes.channel)
        edgesIAccepted.remove(mes.channel)

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
        // if (nOfExpectedResponses < 5) {
        //     for (channel in invitedEdges) {
        //         logger.debug("Still waiting for response from $channel")
        //     }
        // }


        if (nOfExpectedResponses == 0 && !sendFinalList) {
            sendFinalList = true
            // logger.debug("nOfInvitedEdges: ${this.invitedEdges.size} nOfAcceptedEdges: ${this.edgesThatAcceptedInvite.size}")

            if (invitedEdges.isEmpty()) {
                if (this.edgesThatAcceptedInvite.isNotEmpty()) {
                    if (this.started) {
                        for (channel in this.edgesThatAcceptedInvite) {
                            sendMessage(FinishParticipantMessage(MessageTypes.FINISH_P, this, channel.getOppositeNode(this), channel, executionId!!, participants))
                        }
                        return terminate(true)
                    } else {
                        edgesIAccepted.add(parentEdge!!)
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

        for (channel in this.edgesThatAcceptedInvite) {
            if (channel != mes.channel) {
                sendMessage(FinishParticipantMessage(MessageTypes.FINISH_P, this, channel.getOppositeNode(this), channel, executionId!!, participants))
            }
        }
        terminate(true)
    }



    suspend fun terminate(success: Boolean, reason: String = "Unknown") {
        if (success) {
            // End result: participants and acceptedEdges, do not reset yet
            logger.info("Finished with participants size: ${participants.size}")
            if (!(this.anonId in participants)) {
                logger.error("I have not been put up in the participants list!")
            }
            
            result = ParticipantFindingResult(executionId!!, participants.toSet(), edgesThatAcceptedInvite.plus(edgesIAccepted))
            awake = false // Prevent participant discovery from doing anything else
            resultReadyChannel.send(true)
        } else {
            logger.info("Finished but was not successfull because: $reason")

            resultReadyChannel.send(false)
            reset()
        }
    }

    fun reset() {
        awake = false
        started = false
        executionId = null
        anonId = null
        participants  = HashSet()
        edgesThatAcceptedInvite = HashSet()
        edgesIAccepted = HashSet()
        invitedEdges = HashSet()
        parentEdge = null
        nOfExpectedResponses = 0

        sendFinalList = false
    }
}