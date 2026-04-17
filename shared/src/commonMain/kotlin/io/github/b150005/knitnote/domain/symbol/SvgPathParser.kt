package io.github.b150005.knitnote.domain.symbol

/**
 * Minimal SVG path-data parser covering the subset needed for knitting symbols:
 * `M m L l H h V v C c Q q Z z` and `T t S s` (smooth shorthand). Elliptical arcs
 * (`A a`) are intentionally unsupported — no stitch glyph in JIS L 0201 needs one,
 * and a circle can be approximated with four cubic Béziers (already produced by any
 * vector editor on export).
 *
 * Relative commands are resolved against the running current point so every emitted
 * [PathCommand] carries absolute unit-square coordinates.
 */
object SvgPathParser {
    /**
     * Parses an SVG path `d` attribute string into absolute [PathCommand]s.
     *
     * @throws IllegalArgumentException when the path uses an unsupported command
     *   or has a malformed operand list.
     */
    fun parse(pathData: String): List<PathCommand> {
        if (pathData.isBlank()) return emptyList()

        val tokens = tokenize(pathData)
        val output = mutableListOf<PathCommand>()

        var i = 0
        var currentX = 0.0
        var currentY = 0.0
        // Subpath start — used for Z/z return.
        var subpathStartX = 0.0
        var subpathStartY = 0.0
        // Reflection anchors for S/s and T/t smooth commands.
        var lastCubicC2X: Double? = null
        var lastCubicC2Y: Double? = null
        var lastQuadC1X: Double? = null
        var lastQuadC1Y: Double? = null
        var lastCommand: Char? = null

        while (i < tokens.size) {
            val token = tokens[i]
            val cmd0 = token.singleOrNull()
            require(cmd0 != null && cmd0.isLetter()) {
                "Malformed SVG path — expected command letter at token $i but got '$token'"
            }
            require(cmd0 in SUPPORTED) {
                "Unsupported SVG path command '$cmd0' at token $i (elliptical arc 'A' and similar are not implemented)"
            }
            i++
            // `cmd` is var so we can implicitly downgrade M→L after the first pair.
            var cmd: Char = cmd0

            // Consume operand groups for this command; repeating operands reuse
            // the same command letter implicitly (per SVG spec).
            val operandCount = OPERAND_COUNT.getValue(cmd.lowercaseChar())
            val isRelative = cmd.isLowerCase()

            if (operandCount == 0) {
                // Z / z
                output += PathCommand.ClosePath
                currentX = subpathStartX
                currentY = subpathStartY
                lastCubicC2X = null
                lastCubicC2Y = null
                lastQuadC1X = null
                lastQuadC1Y = null
                lastCommand = cmd
                continue
            }

            var consumedAtLeastOnce = false
            while (i < tokens.size && tokens[i].firstOrNull()?.let { it.isDigit() || it == '-' || it == '.' || it == '+' } == true) {
                require(i + operandCount <= tokens.size) {
                    "Truncated operand list for command '$cmd' at token $i"
                }
                consumedAtLeastOnce = true
                val args =
                    DoubleArray(operandCount) {
                        tokens[i + it].toDoubleOrNull()
                            ?: throw IllegalArgumentException("Non-numeric operand '${tokens[i + it]}' for '$cmd'")
                    }
                i += operandCount

                when (cmd.lowercaseChar()) {
                    'm' -> {
                        val x = if (isRelative) currentX + args[0] else args[0]
                        val y = if (isRelative) currentY + args[1] else args[1]
                        output += PathCommand.MoveTo(x, y)
                        currentX = x
                        currentY = y
                        subpathStartX = x
                        subpathStartY = y
                        // After the initial moveto, subsequent coordinate pairs
                        // become implicit linetos (per SVG spec).
                        cmd = if (isRelative) 'l' else 'L'
                        lastCommand = cmd
                        lastCubicC2X = null
                        lastCubicC2Y = null
                        lastQuadC1X = null
                        lastQuadC1Y = null
                    }
                    'l' -> {
                        val x = if (isRelative) currentX + args[0] else args[0]
                        val y = if (isRelative) currentY + args[1] else args[1]
                        output += PathCommand.LineTo(x, y)
                        currentX = x
                        currentY = y
                        lastCubicC2X = null
                        lastCubicC2Y = null
                        lastQuadC1X = null
                        lastQuadC1Y = null
                        lastCommand = cmd
                    }
                    'h' -> {
                        val x = if (isRelative) currentX + args[0] else args[0]
                        output += PathCommand.LineTo(x, currentY)
                        currentX = x
                        lastCubicC2X = null
                        lastCubicC2Y = null
                        lastQuadC1X = null
                        lastQuadC1Y = null
                        lastCommand = cmd
                    }
                    'v' -> {
                        val y = if (isRelative) currentY + args[0] else args[0]
                        output += PathCommand.LineTo(currentX, y)
                        currentY = y
                        lastCubicC2X = null
                        lastCubicC2Y = null
                        lastQuadC1X = null
                        lastQuadC1Y = null
                        lastCommand = cmd
                    }
                    'c' -> {
                        val c1x = if (isRelative) currentX + args[0] else args[0]
                        val c1y = if (isRelative) currentY + args[1] else args[1]
                        val c2x = if (isRelative) currentX + args[2] else args[2]
                        val c2y = if (isRelative) currentY + args[3] else args[3]
                        val x = if (isRelative) currentX + args[4] else args[4]
                        val y = if (isRelative) currentY + args[5] else args[5]
                        output += PathCommand.CurveTo(c1x, c1y, c2x, c2y, x, y)
                        currentX = x
                        currentY = y
                        lastCubicC2X = c2x
                        lastCubicC2Y = c2y
                        lastQuadC1X = null
                        lastQuadC1Y = null
                        lastCommand = cmd
                    }
                    's' -> {
                        val prev = lastCommand?.lowercaseChar()
                        val (rx, ry) =
                            if (prev == 'c' || prev == 's') {
                                2 * currentX - (lastCubicC2X ?: currentX) to
                                    2 * currentY - (lastCubicC2Y ?: currentY)
                            } else {
                                currentX to currentY
                            }
                        val c2x = if (isRelative) currentX + args[0] else args[0]
                        val c2y = if (isRelative) currentY + args[1] else args[1]
                        val x = if (isRelative) currentX + args[2] else args[2]
                        val y = if (isRelative) currentY + args[3] else args[3]
                        output += PathCommand.CurveTo(rx, ry, c2x, c2y, x, y)
                        currentX = x
                        currentY = y
                        lastCubicC2X = c2x
                        lastCubicC2Y = c2y
                        lastQuadC1X = null
                        lastQuadC1Y = null
                        lastCommand = cmd
                    }
                    'q' -> {
                        val c1x = if (isRelative) currentX + args[0] else args[0]
                        val c1y = if (isRelative) currentY + args[1] else args[1]
                        val x = if (isRelative) currentX + args[2] else args[2]
                        val y = if (isRelative) currentY + args[3] else args[3]
                        output += PathCommand.QuadTo(c1x, c1y, x, y)
                        currentX = x
                        currentY = y
                        lastQuadC1X = c1x
                        lastQuadC1Y = c1y
                        lastCubicC2X = null
                        lastCubicC2Y = null
                        lastCommand = cmd
                    }
                    't' -> {
                        val prev = lastCommand?.lowercaseChar()
                        val (c1x, c1y) =
                            if (prev == 'q' || prev == 't') {
                                2 * currentX - (lastQuadC1X ?: currentX) to
                                    2 * currentY - (lastQuadC1Y ?: currentY)
                            } else {
                                currentX to currentY
                            }
                        val x = if (isRelative) currentX + args[0] else args[0]
                        val y = if (isRelative) currentY + args[1] else args[1]
                        output += PathCommand.QuadTo(c1x, c1y, x, y)
                        currentX = x
                        currentY = y
                        lastQuadC1X = c1x
                        lastQuadC1Y = c1y
                        lastCubicC2X = null
                        lastCubicC2Y = null
                        lastCommand = cmd
                    }
                }
            }
            require(consumedAtLeastOnce) {
                "Command '$cmd' at token ${i - 1} has no operands"
            }
        }

        return output
    }

    private val SUPPORTED: Set<Char> =
        setOf('M', 'm', 'L', 'l', 'H', 'h', 'V', 'v', 'C', 'c', 'S', 's', 'Q', 'q', 'T', 't', 'Z', 'z')

    private val OPERAND_COUNT: Map<Char, Int> =
        mapOf('m' to 2, 'l' to 2, 'h' to 1, 'v' to 1, 'c' to 6, 's' to 4, 'q' to 4, 't' to 2, 'z' to 0)

    /**
     * Splits a path string into tokens: command letters become single-char tokens,
     * numbers are extracted with sign and decimal handling. Whitespace and commas
     * separate operands; signs may also act as separators (`.5-.5` == `.5 -.5`).
     */
    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            when {
                ch.isWhitespace() || ch == ',' -> i++
                ch.isLetter() -> {
                    // Any letter becomes a single-char command token. Supported-ness
                    // is re-checked in parse() so error messages can name the letter.
                    tokens += ch.toString()
                    i++
                }
                ch == '-' || ch == '+' || ch == '.' || ch.isDigit() -> {
                    val start = i
                    // Sign
                    if (ch == '-' || ch == '+') i++
                    var seenDot = false
                    var seenExp = false
                    while (i < input.length) {
                        val c = input[i]
                        when {
                            c.isDigit() -> i++
                            c == '.' && !seenDot && !seenExp -> {
                                seenDot = true
                                i++
                            }
                            (c == 'e' || c == 'E') && !seenExp -> {
                                seenExp = true
                                i++
                                if (i < input.length && (input[i] == '-' || input[i] == '+')) i++
                            }
                            else -> break
                        }
                    }
                    require(i > start) { "Malformed numeric token starting at index $start in path" }
                    tokens += input.substring(start, i)
                }
                else -> throw IllegalArgumentException("Unexpected character '$ch' at index $i in path data")
            }
        }
        return tokens
    }
}
