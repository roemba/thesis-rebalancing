package roemer.rebalancing

open class ChannelException (message: String): Exception(message) {}

class InsufficientFundsException (message: String): ChannelException(message) {}

class ChannelLockedException (message: String): ChannelException(message) {}