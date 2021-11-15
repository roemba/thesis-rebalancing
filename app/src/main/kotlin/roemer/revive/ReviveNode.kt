package roemer.revive

import roemer.rebalancing.*
import lpsolve.*
import roemer.rebalancing.Rebalancer
import kotlin.math.abs


enum class State {
    WAITING, CONFIRMATION, COLLECTION, SIGNING
}

class ReviveNode(id: Int, g: ChannelNetwork, counter: Counter, random: SeededRandom, globalLogger: Logger) : ParticipantNodeAlt(id, g, counter, random, globalLogger), Rebalancer {
    var orderOfStarting: List<Tag>? = null
    var channelDemands: Map<PaymentChannel, Int> = HashMap()
    var outgoingDemandEdges: Set<PaymentChannel> = HashSet()
    var incomingDemandEdges: Set<PaymentChannel> = HashSet()
    var leader: Node? = null

    var clientStateMachine: StateMachine<State> = StateMachine(logger, State.WAITING)
    var leaderStateMachine: StateMachine<State> = StateMachine(logger, State.WAITING)
    var initMessages: MutableList<ReviveMessage> = ArrayList()
    var confirmMessages: MutableList<ReviveMessage> = ArrayList()
    var denyMessages: MutableList<ReviveMessage> = ArrayList()
    var demandMessages: MutableList<DemandMessage> = ArrayList()
    var signatures: MutableList<Signature> = ArrayList()
    var myTransactions: MutableList<ChannelTransaction> = ArrayList()

    var roundParticipants: List<Node> = ArrayList()
    var channelsToRebalance: Set<PaymentChannel> = HashSet()

    override fun reset() {
        orderOfStarting = null
        channelDemands = HashMap()
        outgoingDemandEdges = HashSet()
        incomingDemandEdges = HashSet()
        leader = null
    
        clientStateMachine = StateMachine(logger, State.WAITING)
        leaderStateMachine = StateMachine(logger, State.WAITING)
        initMessages = ArrayList()
        confirmMessages = ArrayList()
        denyMessages = ArrayList()
        demandMessages = ArrayList()
        signatures = ArrayList()
        myTransactions = ArrayList()

        super.reset()
    }

    override fun isRebalancingAwake(): Boolean {
        return this.rebalancingAwake
    }

    override fun startSubAlgos(algoSettings: Map<String, Any>): SimulationInput? {
        if (this.isRunningAlgo) {
            logger.error("Already rebalancing")
            return null
        }
        
        logger.info("Starting participant discovery before rebalancing")
        
        val simulInput = this.findParticipants(algoSettings)

        this.isRunningAlgo = true

        return simulInput
    }

    override fun rebalance(event: StartEvent): SimulationInput {
        logger.info("Participant discovery finished")
        if (event.desc.step != Steps.Rebalance) {
            throw IllegalArgumentException("Rebalancing may only be woken up after participant discovery is done!")
        }

        this.initMessageSending()

        this.wakeUp()

        this.startClient()
        if (this.leader === this) {
            logger.info("I'm the leader!")
        }

        this.stopMessageSending()
        return SimulationInput(this, this.sendingList, this.startStopDesc)
    }

    fun wakeUp(): Boolean {
        if (this.rebalancingAwake) {
            return true // Already awake
        }

        logger.debug("I'm waking up!")

        this.orderOfStarting = this.result!!.finalParticipants.toList().sorted()

        if (this.orderOfStarting!![0] == this.anonId) {
            this.leader = this
        } else {
            this.leader = g.graph.vertexSet().filter {v -> (v as ReviveNode).anonId == this.orderOfStarting!![0]} .first()
        }

        this.rebalancingAwake = true

        this.channelDemands = this.result!!.acceptedEdges.map { it to it.getDemand(this) }.toMap()
        this.outgoingDemandEdges = this.channelDemands.filter {(_, value) -> value < 0} .keys
        this.incomingDemandEdges = this.channelDemands.keys - this.outgoingDemandEdges

        return true
    }

    fun startClient () {
        sendMessage(ReviveMessage(MessageTypes.INIT_REV, this, this.leader!!, this.executionId!!), true)
    }

    fun checkMessage(mes: ReviveMessage, client: Boolean): Boolean {
        if (this.executionId == null || this.executionId != mes.executionId || !this.rebalancingAwake) {
            sendMessage(ReviveMessage(MessageTypes.FAIL_REV, this, mes.sender, this.executionId!!), true)
            return false
        }

        if (client && mes.sender !== this.leader) {
            logger.error("Client is only allowed to accept messages from the leader!")
            sendMessage(ReviveMessage(MessageTypes.FAIL_REV, this, mes.sender, this.executionId!!), true)
            return false
        }

        return true
    }


    override fun sortMessage (message: Message) {
        when (message.type) {
            MessageTypes.INIT_REV -> handleInitPartMessage(message as ReviveMessage)
            MessageTypes.CONFIRM_REQ_REV -> handleConfirmReqMessage(message as ReviveMessage)
            MessageTypes.CONFIRM_REV, MessageTypes.DENY_REV -> handleConfirmDenyMessage(message as ReviveMessage)
            MessageTypes.ROUND_CONFIRM_REV -> handleRoundConfirmMessage(message as StartRoundMessage)
            MessageTypes.DEMAND_REV -> handleDemandMessage(message as DemandMessage)
            MessageTypes.TX_SET_REV -> handleSigningTxSetRequestMessage(message as SigningRequestMessage)
            MessageTypes.SIGNED_TX_SET_REV -> handleSignedTxSetMessage(message as SignedTxSetMessage)
            MessageTypes.COMPLETE_TX_SET_REV -> handleCompleteTxSetMessage(message as CompleteTxSetMessage)
            else -> {
                super.sortMessage(message)
            }
        }
    }

    fun handleInitPartMessage (message: ReviveMessage) {
        if (this.executionId == message.executionId && !this.rebalancingAwake) {
            sendMessage(message)
            return
        }

        assert(this.leader === this)
        if (!this.checkMessage(message, false)) { return }

        if (leaderStateMachine.isInState(State.WAITING)) {
            initMessages.add(message)

            if (initMessages.size == this.orderOfStarting!!.size) {
                leaderStateMachine.state = State.CONFIRMATION
                for (m in initMessages) {
                    sendMessage(ReviveMessage(MessageTypes.CONFIRM_REQ_REV, this, m.sender, this.executionId!!), true)
                }
            }
        } else {
            sendMessage(ReviveMessage(MessageTypes.FAIL_REV, this, message.sender, this.executionId!!), true)
        }
    }

    fun handleConfirmReqMessage (message: ReviveMessage) {
        if (!this.checkMessage(message, this.leader !== this)) { return }

        if (clientStateMachine.isInState(State.WAITING)) {
            clientStateMachine.state = State.CONFIRMATION
            sendMessage(ReviveMessage(MessageTypes.CONFIRM_REV, this, this.leader!!, this.executionId!!), true)
        }
    }

    fun handleConfirmDenyMessage (message: ReviveMessage) {
        assert(this.leader === this)
        if (!this.checkMessage(message, false)) { return }

        if (leaderStateMachine.isInState(State.CONFIRMATION)) {
            if (message.type === MessageTypes.CONFIRM_REV) {
                confirmMessages.add(message)
            } else {
                denyMessages.add(message)
            }

            if (confirmMessages.size + denyMessages.size == initMessages.size) {
                leaderStateMachine.state = State.COLLECTION
                this.roundParticipants = confirmMessages.map { m -> m.sender }

                for (m in confirmMessages) {
                    sendMessage(StartRoundMessage(MessageTypes.ROUND_CONFIRM_REV, this, m.sender, this.executionId!!, this.roundParticipants), true)
                }
            }
        }
    }

    fun handleRoundConfirmMessage (message: StartRoundMessage) {
        if (!this.checkMessage(message, this.leader !== this)) { return }
        
        if (clientStateMachine.isInState(State.CONFIRMATION)) {
            clientStateMachine.state = State.COLLECTION

            val channelDemandMap: MutableMap<PaymentChannel, Int> = HashMap()
            this.roundParticipants = message.participants
            for (channel in outgoingDemandEdges.union(incomingDemandEdges)) {
                channelDemandMap[channel] = this.channelDemands[channel]!!
            }

            // TODO: Add filter here that randomly denies some edges to participate in rebalancing

            sendMessage(DemandMessage(MessageTypes.DEMAND_REV, this, this.leader!!, this.executionId!!, channelDemandMap), true)
        }
    }

    fun handleDemandMessage (message: DemandMessage) {
        assert(this.leader === this)
        if (!this.checkMessage(message, false)) { return }

        if (leaderStateMachine.isInState(State.COLLECTION)) {
            demandMessages.add(message)

            if (demandMessages.size == confirmMessages.size) {
                leaderStateMachine.state = State.SIGNING

                val (channelDemands, channels) = generateTxSet(demandMessages)
                val transactions = createTransactions(channelDemands, channels)

                val treeDigest = MerkleTree(transactions + this.roundParticipants).digest()

                for (node in this.roundParticipants) {
                    sendMessage(SigningRequestMessage(MessageTypes.TX_SET_REV, this, node, this.executionId!!, transactions, treeDigest), true)
                }
            }
        }
    }

    fun generateTxSet (messages: List<DemandMessage>): Pair<DoubleArray, List<PaymentChannel>> {
        val allNodes = messages.map { m -> m.sender }
        val channelNodeDemandMap: MutableMap<PaymentChannel, MutableMap<Node, Int>> = HashMap()

        for (m in messages) {
            for ((channel, demand) in m.channelDemandMap.entries) {
                val nodeMap = channelNodeDemandMap.getOrPut(channel, { HashMap() })
                nodeMap[m.sender] = demand
            }  
        }

        val allChannels = channelNodeDemandMap.keys.toList()
        
        try {
            val solver = LpSolve.makeLp(0, allChannels.size)

            for (i in 0 until allChannels.size) {
                solver.setColName(i + 1, allChannels[i].id.toString())
                solver.setInt(i + 1, true)
            }

            solver.setAddRowmode(true)
            
            // Channel max capacity
            // I.E. ab <= 20 type constraints
            for (i in 0 until allChannels.size) {
                val colnos = intArrayOf(i + 1) // 1-based columns
                val row = doubleArrayOf(1.0) // Coefficient of 1
                val absChannelDemand = abs(channelNodeDemandMap[allChannels[i]]!![channelNodeDemandMap[allChannels[i]]!!.keys.first()]!!)

                solver.addConstraintex(colnos.size, row, colnos, LpSolve.LE, absChannelDemand.toDouble())
            }

            // Node balance conservation
            // I.E. ab + ap - ca - ga = 0 type constraints
            for (node in allNodes) {
                val columnNos: MutableList<Int> = ArrayList()
                val rowList: MutableList<Double> = ArrayList()

                for (i in 0 until allChannels.size) {
                    if (allChannels[i].isChannelNode(node)) {
                        columnNos.add(i + 1)
                        if (channelNodeDemandMap[allChannels[i]]!![node]!! < 0) {
                            rowList.add(1.0)
                        } else {
                            rowList.add(-1.0)
                        }
                    }
                }

                val colnos = columnNos.toIntArray() // 1-based column
                val row = rowList.toDoubleArray() // Coefficient of 1 or -1 depending on incoming or outgoing edge

                solver.addConstraintex(colnos.size, row, colnos, LpSolve.EQ, 0.0)
            }

            solver.setAddRowmode(false)

            // Set objective function, i.e. sum all demands of all channels
            val colnos = allChannels.mapIndexed { ind, _ -> ind + 1 }.toIntArray()
            val row = DoubleArray (colnos.size) { 1.0 }

            solver.setObjFnex(colnos.size, row, colnos)

            solver.setMaxim()

            solver.solve()

            logger.debug("Value of objective function: " + solver.getObjective())
            val pointerVariables = solver.getPtrVariables()

            if (pointerVariables.size <= 20) {
                for (i in 0 until pointerVariables.size) {
                    logger.debug("Value of var[$i] = ${pointerVariables[i]} -> ${allChannels[i]}")
                }
            } else {
                logger.debug("Too many variables to print!")
            }

            solver.deleteLp()

            return Pair(pointerVariables, allChannels)
        } catch (e: LpSolveException) {
            e.printStackTrace()
            throw e
        }
    }

    fun createTransactions (channelDemands: DoubleArray, channels: List<PaymentChannel>): List<ChannelTransaction> {
        val transactions: MutableList<ChannelTransaction> = ArrayList()
        for (i in 0 until channelDemands.size) {
            var from = channels[i].node1
            var to = channels[i].node2
            val node1Demand = channels[i].getCurrentDemand(channels[i].node1)
            if (node1Demand > 0) {
                val temp = to
                to = from
                from = temp
            }
            val tx = ChannelTransaction(this.random.getRandomUUID(), channelDemands[i].toInt(), from, to, this.random.getRandomUUID(), channels[i])
            if (tx.amount > 0) { transactions += tx }
        }

        return transactions
    }

    fun handleSigningTxSetRequestMessage (message: SigningRequestMessage) {
        if (!this.checkMessage(message, this.leader !== this)) { return }

        if (clientStateMachine.isInState(State.COLLECTION)) {
            clientStateMachine.state = State.SIGNING
        } else { throw IllegalStateException("Expected to be in state COLLECTION when starting to sign!") }
        
        logger.debug("Starting checking transaction set")

        var totalBalance = 0
        for (transaction in message.transactions) {
            if (transaction.from == this) {
                totalBalance -= transaction.amount
                this.myTransactions.add(transaction)
            } else if (transaction.to == this) {
                totalBalance += transaction.amount
            }
        }
        if (totalBalance != 0) { throw IllegalStateException("$this will lose/gain $totalBalance from this rebalancing!") }

        val treeDigest = MerkleTree(message.transactions + this.roundParticipants).digest()
        if (!(treeDigest contentEquals message.digest)) {
            throw IllegalStateException("$this cannot reconstruct the Merkle tree from the given transactions!")
        }

        for (transaction in this.myTransactions) {
            transaction.channel.requestTx(transaction, null, true)
        }
        
        sendMessage(SignedTxSetMessage(MessageTypes.SIGNED_TX_SET_REV, this, this.leader!!, this.executionId!!, Signature(this, message.digest)), true)
    }

    fun handleSignedTxSetMessage (message: SignedTxSetMessage) {
        assert(this.leader === this)
        if (!this.checkMessage(message, false)) { return }

        if (!leaderStateMachine.isInState(State.SIGNING)) {
            throw IllegalStateException("$this did not expect to be in State.SIGNING!")
        }

        signatures.add(message.signature)

        if (signatures.size == this.roundParticipants.size) {
            // Check signatures
            for (signature in signatures) {
                if (signature.signer !in this.roundParticipants) {
                    throw IllegalStateException("Signature $signature was not signed by a round participant!")
                }
                if (signature.data != signatures[0].data) {
                    throw IllegalStateException("Signature $signature was not equal to the other received signatures!")
                }
            }

            for (node in this.roundParticipants) {
                sendMessage(CompleteTxSetMessage(MessageTypes.COMPLETE_TX_SET_REV, this, node, this.executionId!!, signatures[0].data, signatures), true)
            }
        }
    }

    fun handleCompleteTxSetMessage (message: CompleteTxSetMessage) {
        if (!this.checkMessage(message, this.leader !== this)) { return }

        for (signature in message.signatures) {
            if (signature.signer !in this.roundParticipants) {
                throw IllegalStateException("Signature $signature was not signed by a round participant!")
            }
        }

        for (transaction in this.myTransactions) {
            transaction.channel.executeTx(transaction)
            transaction.channel.unlock()
        }

        terminateRebalancing(true)
    }

    fun terminateRebalancing(success: Boolean) {
        if (success) {
            logger.info("Finished rebalancing successfully")
        } else {
            logger.info("Terminated rebalancing unsuccessfully")
        }

        this.reset()
    }
}