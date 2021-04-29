package roemer.rebalancing

import java.util.UUID

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

    suspend fun start(hopCount: Int) {
        this.awake = true
        this.started = true
        executionId = UUID.randomUUID()
        anonId = UUID.randomUUID()
        participants.add(anonId!!)
        nOfExpectedResponses = this.paymentChannels.size

        for (channel in this.paymentChannels) {
            sendMessage(InviteParticipantMessage(
                MessageTypes.INVITE_P, this, channel.getOppositeNode(this), executionId!!, hopCount
            ))
        }
    } 

    suspend fun handleInviteMessage(mes: InviteParticipantMessage) {
        println("Node $this received invite by ${mes.sender}")
        if (SeededRandom.random.nextInt(10) == 0) { // Deny randomly 1 in 10 times
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, mes.executionId))
        } else if (executionId == null) {
            executionId = mes.executionId
            anonId = UUID.randomUUID()
            participants.add(anonId!!)
        } else if (mes.executionId != executionId) {
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, executionId!!))
        }

        // Get the demand and put it into the correct collection
        val senderChannel = this.getChannelForNode(mes.sender)
        val demand = senderChannel.getDemand(this)
        if (demand > 0) {
            positiveDemandEdges.add(senderChannel)
        } else {
            negativeDemandEdges.add(senderChannel)
        }

        // If hop count is 0, terminate search
        if (mes.hopCount - 1 == 0) {
            if (positiveDemandEdges.isEmpty() || negativeDemandEdges.isEmpty()) {
                return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, executionId!!))
            } else {
                return sendMessage(AcceptParticipantMessage(MessageTypes.ACCEPT_P, this, mes.sender, executionId!!, participants))
            }
        }
        
        // If not awake, propagate invite to other channels
        if (!this.awake) {
            this.awake = true
            for (channel in this.paymentChannels) {
                if (channel !== senderChannel && channel.getCurrentDemand(this) != 0) {
                    sendMessage(InviteParticipantMessage(
                        MessageTypes.INVITE_P, this, channel.getOppositeNode(this), executionId!!, mes.hopCount - 1
                    ))
                }
            }
        }

        if (!positiveDemandEdges.isEmpty() && !negativeDemandEdges.isEmpty()) {
            return sendMessage(AcceptParticipantMessage(MessageTypes.ACCEPT_P, this, mes.sender, executionId!!, participants))
        } else {
            unacceptedInviteEdges.add(senderChannel)
        }
    }

    suspend fun handleAcceptMessage(mes: AcceptParticipantMessage) {
        println("Node $this received accept by ${mes.sender}")
        if (mes.executionId != executionId) {
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, executionId!!))
        }

        participants.addAll(mes.participants)
        this.nOfExpectedResponses -= 1

        val senderChannel = this.getChannelForNode(mes.sender)
        acceptedEdges.add(senderChannel)

        val demand = senderChannel.getDemand(this)
        if (demand > 0) {
            positiveDemandEdges.add(senderChannel)
        } else {
            negativeDemandEdges.add(senderChannel)
        }

        if (!positiveDemandEdges.isEmpty() && !negativeDemandEdges.isEmpty()) {
            for (channel in this.unacceptedInviteEdges) {
                sendMessage(AcceptParticipantMessage(MessageTypes.ACCEPT_P, this, mes.sender, executionId!!, participants))
            }
            this.unacceptedInviteEdges = HashSet()
        }

        if (this.started && this.nOfExpectedResponses == 0) {
            for (channel in this.acceptedEdges) {
                sendMessage(FinishParticipantMessage(MessageTypes.FINISH_P, this, channel.getOppositeNode(this), executionId!!, participants))
            }
            terminate()
        }
    }

    suspend fun handleFinishMessage(mes: FinishParticipantMessage) {
        println("Node $this received finish by ${mes.sender}")
        if (mes.executionId != executionId) {
            return sendMessage(ParticipantMessage(MessageTypes.DENY_P, this, mes.sender, executionId!!))
        }

        participants.addAll(mes.participants)

        for (channel in this.acceptedEdges) {
            sendMessage(FinishParticipantMessage(MessageTypes.FINISH_P, this, channel.getOppositeNode(this), executionId!!, participants))
        }
        terminate()
    }

    suspend fun handleDenyMessage(mes: ParticipantMessage) {
        println("Node $this received deny by ${mes.sender}")
        this.nOfExpectedResponses -= 1
    }

    fun terminate() {
        println("Node $this finished with participants $participants")
        reset()
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
    }
}