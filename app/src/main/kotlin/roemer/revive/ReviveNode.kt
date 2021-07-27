package roemer.revive

import roemer.rebalancing.*
import lpsolve.*
import roemer.rebalancing.Rebalancer

enum class State {
    WAITING, CONFIRMATION, COLLECTION, SIGNING
}

class ReviveNode(id: Int, g: ChannelNetwork) : ParticipantNodeAlt(id, g), Rebalancer {
    var orderOfStarting: List<Tag>? = null
    var rebalancingAwake = false
    var outgoingDemandEdges: MutableSet<PaymentChannel> = HashSet()
    var incomingDemandEdges: MutableSet<PaymentChannel> = HashSet()
    var leader: Node? = null

    var stateMachine = StateMachine<State>(logger, State.WAITING)
    var initMessages: MutableList<ReviveMessage> = ArrayList()
    var confirmMessages: MutableList<ReviveMessage> = ArrayList()
    var denyMessages: MutableList<ReviveMessage> = ArrayList()
    var demandMessages: MutableList<DemandMessage> = ArrayList()

    override fun isRebalancingAwake(): Boolean {
        return this.rebalancingAwake
    }

    override fun startSubAlgos(algoSettings: Map<String, Any>): SimulationInput {
        logger.info("Starting participant discovery before rebalancing")
        this.algoSettings = algoSettings

        return this.findParticipants(algoSettings)
    }

    override fun rebalance(event: StartStopEvent): SimulationInput {
        logger.info("Participant discovery finished")
        if (event.desc.algorithm != Algorithm.ParticipantDisc) {
            throw IllegalArgumentException("Rebalancing may only be woken up after participant discovery is done!")
        }

        this.initMessageSending()

        this.wakeUp()

        if (this.leader !== this) {
            this.startClient()
        } else {
            logger.info("I'm the leader!")
        }

        this.stopMessageSending()
        return Pair(this.sendingList, this.startStopDesc)
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

        if (stateMachine.isInState(State.WAITING)) {
            initMessages.add(message)

            if (initMessages.size == this.orderOfStarting!!.size - 1) {
                stateMachine.state = State.CONFIRMATION
                for (m in initMessages) {
                    sendMessage(ReviveMessage(MessageTypes.CONFIRM_REQ_REV, this, m.sender, this.executionId!!), true)
                }
            }
        } else {
            sendMessage(ReviveMessage(MessageTypes.FAIL_REV, this, message.sender, this.executionId!!), true)
        }
    }

    fun handleConfirmReqMessage (message: ReviveMessage) {
        assert(this.leader !== this)
        if (!this.checkMessage(message, true)) { return }

        if (stateMachine.isInState(State.WAITING)) {
            stateMachine.state = State.CONFIRMATION
            sendMessage(ReviveMessage(MessageTypes.CONFIRM_REV, this, this.leader!!, this.executionId!!), true)
        }
    }

    fun handleConfirmDenyMessage (message: ReviveMessage) {
        assert(this.leader === this)
        if (!this.checkMessage(message, false)) { return }

        if (stateMachine.isInState(State.CONFIRMATION)) {
            if (message.type === MessageTypes.CONFIRM_REV) {
                confirmMessages.add(message)
            } else {
                denyMessages.add(message)
            }

            if (confirmMessages.size + denyMessages.size == initMessages.size) {
                stateMachine.state = State.COLLECTION
                val participants = confirmMessages.map { m -> m.sender }

                for (m in confirmMessages) {
                    sendMessage(StartRoundMessage(MessageTypes.ROUND_CONFIRM_REV, this, m.sender, this.executionId!!, participants), true)
                }
            }
        }
    }

    fun handleRoundConfirmMessage (message: StartRoundMessage) {
        assert(this.leader !== this)
        if (!this.checkMessage(message, true)) { return }
        
        if (stateMachine.isInState(State.CONFIRMATION)) {
            stateMachine.state = State.COLLECTION

            val channelsToRebalance = outgoingDemandEdges.union(incomingDemandEdges)
            for (channel in channelsToRebalance) {
                channel.lock() // Make sure the channel locks now
            }

            // TODO: Add filter here that randomly denies some edges to participate in rebalancing

            sendMessage(DemandMessage(MessageTypes.DEMAND_REV, this, this.leader!!, this.executionId!!, channelsToRebalance), true)
        }
    }

    fun handleDemandMessage (message: DemandMessage) {
        assert(this.leader === this)
        if (!this.checkMessage(message, false)) { return }

        if (stateMachine.isInState(State.COLLECTION)) {
            demandMessages.add(message)

            if (demandMessages.size == confirmMessages.size) {
                stateMachine.state = State.SIGNING

                generateTxSet(demandMessages)
            }
        }
    }

    fun generateTxSet (messages: List<DemandMessage>) {
        val allChannels = messages.map { m -> m.channelsToRebalance }.reduce {accSet, channelSet -> accSet.union(channelSet)}.toList()
        val allNodes = messages.map { m -> m.sender }
        
        try {
            val solver = LpSolve.makeLp(0, allChannels.size)

            for (i in 0 until allChannels.size) {
                solver.setColName(i + 1, allChannels[i].id.toString())
            }

            solver.setAddRowmode(true)
            
            // Channel max capacity
            // I.E. ab <= 20 type constraints
            for (i in 0 until allChannels.size) {
                val colnos = intArrayOf(i + 1) // 1-based columns
                val row = doubleArrayOf(1.0) // Coefficient of 1

                solver.addConstraintex(colnos.size, row, colnos, LpSolve.LE, allChannels[i].getDemand(null).toDouble())
            }

            // Node balance conservation
            // I.E. ab + ap - ca - ga = 0 type constraints
            for (node in allNodes) {
                val columnNos: MutableList<Int> = ArrayList()
                val rowList: MutableList<Double> = ArrayList()

                for (i in 0 until allChannels.size) {
                    if (allChannels[i].isChannelNode(node)) {
                        columnNos.add(i + 1)
                        if (allChannels[i].getDemand(node) < 0) {
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

            println("Value of objective function: " + solver.getObjective())
            val pointerVariables = solver.getPtrVariables()
            for (i in 0 until pointerVariables.size) {
                println("Value of var[$i] = ${pointerVariables[i]} -> ${allChannels[i]}")
            }

            solver.deleteLp()
        } catch (e: LpSolveException) {
            e.printStackTrace()
        }
    }
}