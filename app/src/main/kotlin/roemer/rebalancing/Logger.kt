package roemer.rebalancing

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

enum class TerminalColor(val code: String) {
    // ANSI_BLACK("\u001B[30m"),
    ANSI_RED("\u001B[31m"),
    ANSI_GREEN("\u001B[32m"),
    ANSI_YELLOW("\u001B[33m"),
    ANSI_BLUE("\u001B[34m"),
    ANSI_PURPLE("\u001B[35m"),
    ANSI_CYAN("\u001B[36m"),
    ANSI_WHITE("\u001B[37m")
}

class NodeLogger (val id: Int, val parent: Logger): Logger() {
    var vertex: Node? = null

    constructor (vertex: Node, parent: Logger) : this(vertex.id, parent) {
        this.vertex = vertex
    }

    override fun getColor(l: LogLevel): TerminalColor {
        val colors = TerminalColor.values()
        return colors[this.id % colors.size]
    }
    
    override fun log(s: Any, l: LogLevel) {
        val v = this.vertex

        var round = ""
        if (v != null && v is CoinWasherNode) {
            round = "R${v.roundIndex}:"
        }

        if (true) { // || (v != null && v.id in arrayOf(2019, 5288, 422) && v is CoinWasherNode)) {
            super.logAtTime("$round $v - $s", l, this.parent.time)
        }
    }
}


open class Logger {
    var time = 0L
    val logLevel = LogLevel.DEBUG
    val ANSI_RESET = "\u001B[0m"
    
    fun getNodeLogger (vertex: Node): NodeLogger {
        return NodeLogger(vertex, this)
    }

    fun debug(s: Any) {
        log(s, LogLevel.DEBUG)
    }

    fun info(s: Any) {
        log(s, LogLevel.INFO)
    }

    fun warn(s: Any) {
        log(s, LogLevel.WARN)
    }

    fun error(s: Any) {
        log(s, LogLevel.ERROR)
    }

    open fun getColor(l: LogLevel): TerminalColor {
        return TerminalColor.ANSI_WHITE
    }

    open fun log(s: Any, l: LogLevel) {
        this.logAtTime(s, l, this.time)
    }

    fun logAtTime(s: Any, l: LogLevel, runTime: Long) {
        val color = this.getColor(l).code

        if (l >= this.logLevel) {
            println("$color${runTime}-$l:$s $ANSI_RESET")
        }
    }
}