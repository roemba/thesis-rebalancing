package roemer.rebalancing

enum class LEVEL {
    DEBUG, INFO, WARN, ERROR
}

class Logger(val vertex: Node) {
    val ANSI_RESET = "\u001B[0m";
    val ANSI_BLACK = "\u001B[30m";
    val ANSI_RED = "\u001B[31m";
    val ANSI_GREEN = "\u001B[32m";
    val ANSI_YELLOW = "\u001B[33m";
    val ANSI_BLUE = "\u001B[34m";
    val ANSI_PURPLE = "\u001B[35m";
    val ANSI_CYAN = "\u001B[36m";
    val ANSI_WHITE = "\u001B[37m";
    val colors = arrayOf(ANSI_RED, ANSI_GREEN, ANSI_YELLOW, ANSI_BLUE, ANSI_PURPLE, ANSI_CYAN, ANSI_WHITE)
    val logLevel = LEVEL.DEBUG

    companion object {
        var time = 0L
    } 
    
    fun debug(s: Any) {
        log(s, LEVEL.DEBUG)
    }

    fun info(s: Any) {
        log(s, LEVEL.INFO)
    }

    fun warn(s: Any) {
        log(s, LEVEL.WARN)
    }

    fun error(s: Any) {
        log(s, LEVEL.ERROR)
    }

    fun log(s: Any, l: LEVEL) {
        val color = colors[vertex.id % colors.size]

        if (l >= logLevel) {
            var round = ""
            if (vertex is CoinWasherNode) {
                round = "-R:${vertex.roundIndex}"
            }

            if (vertex.id in arrayOf(5953) && vertex is CoinWasherNode)  { // 3150
                println("$color${Logger.time}-$l$round: $vertex - $s $ANSI_RESET")
            }
        }

    }
}