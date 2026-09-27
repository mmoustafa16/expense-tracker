package privacy

data class SmsLogViolation(
    val file: String,
    val line: Int,
    val detail: String,
) {
    fun describe(): String = "$file:$line: $detail"
}

object SmsLogPolicy {
    private val logCall = Regex(
        """(?:android\.util\.Log|Log|Timber|System\.out|System\.err)\s*\.\s*(?:d|i|w|e|v|wtf|println|print|debug|info|warn|error|trace)\s*\(|(?<![.\w])(?:logger|log)\s*\.\s*(?:d|i|w|e|v|debug|info|warn|error|trace)\s*\(|(?<![.\w])println\s*\(""",
    )
    private val sensitive = Regex(
        """\.body(?!Hash\b)|(?<![.\w])body(?!Hash\b)|amountMinor|\.amount\b|(?<![.\w])amount\b|foreignAmount|\.balance\b|(?<![.\w])balance\b""",
        RegexOption.IGNORE_CASE,
    )

    fun violations(source: String, fileName: String): List<SmsLogViolation> {
        val cleaned = stripComments(source)
        val found = mutableListOf<SmsLogViolation>()
        for (match in logCall.findAll(cleaned)) {
            val args = argumentSpan(cleaned, match.range.last)
            if (args == null) continue
            if (!sensitive.containsMatchIn(args)) continue
            val line = cleaned.take(match.range.first).count { it == '\n' } + 1
            found += SmsLogViolation(fileName, line, "logging an SMS body or amount is not allowed")
        }
        return found
    }

    private fun argumentSpan(source: String, openParenIndex: Int): String? {
        if (openParenIndex > source.lastIndex || source[openParenIndex] != '(') return null
        var depth = 0
        val start = openParenIndex
        var index = openParenIndex
        while (index < source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
            index++
        }
        return source.substring(start)
    }

    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        var mode = Mode.CODE
        while (index < source.length) {
            val current = source[index]
            val next = source.getOrNull(index + 1)
            when (mode) {
                Mode.CODE -> when {
                    current == '/' && next == '/' -> {
                        out.append("  ")
                        index += 2
                        mode = Mode.LINE
                    }
                    current == '/' && next == '*' -> {
                        out.append("  ")
                        index += 2
                        mode = Mode.BLOCK
                    }
                    current == '"' && next == '"' && source.getOrNull(index + 2) == '"' -> {
                        out.append("\"\"\"")
                        index += 3
                        mode = Mode.TRIPLE
                    }
                    current == '"' -> {
                        out.append(current)
                        index++
                        mode = Mode.STRING
                    }
                    else -> {
                        out.append(current)
                        index++
                    }
                }
                Mode.LINE -> {
                    if (current == '\n') {
                        out.append(current)
                        mode = Mode.CODE
                    } else {
                        out.append(' ')
                    }
                    index++
                }
                Mode.BLOCK -> {
                    if (current == '*' && next == '/') {
                        out.append("  ")
                        index += 2
                        mode = Mode.CODE
                    } else {
                        out.append(if (current == '\n') '\n' else ' ')
                        index++
                    }
                }
                Mode.STRING -> {
                    out.append(current)
                    index++
                    if (current == '\\') {
                        val escaped = source.getOrNull(index)
                        if (escaped != null) {
                            out.append(escaped)
                            index++
                        }
                    } else if (current == '"') {
                        mode = Mode.CODE
                    }
                }
                Mode.TRIPLE -> {
                    if (current == '"' && next == '"' && source.getOrNull(index + 2) == '"') {
                        out.append("\"\"\"")
                        index += 3
                        mode = Mode.CODE
                    } else {
                        out.append(current)
                        index++
                    }
                }
            }
        }
        return out.toString()
    }

    private enum class Mode { CODE, LINE, BLOCK, STRING, TRIPLE }
}
