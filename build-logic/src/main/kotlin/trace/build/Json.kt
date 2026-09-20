package trace.build

/**
 * A small JSON reader and writer for the build's own files: JMH result files and harness results.
 *
 * Deliberately not a library. The build already pins enough moving parts, and the two shapes read
 * here are produced by code in this repository or by JMH, whose format is stable.
 */
internal object Json {

    fun parse(text: String): Any? = Parser(text).let { p ->
        val value = p.value()
        p.skipWhitespace()
        value
    }

    @Suppress("UNCHECKED_CAST")
    fun asMap(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    fun asList(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

    fun asDouble(value: Any?): Double? = when (value) {
        is Double -> value
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    /** Escapes a string for embedding in JSON output. */
    fun quote(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        value.forEach { c ->
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun value(): Any? {
            skipWhitespace()
            return when (val c = peek()) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else fail("unexpected '$c'")
            }
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                expect(':')
                map[key] = value()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> return map
                    else -> fail("expected ',' or '}' but found '$c'")
                }
            }
        }

        private fun array(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return list
            }
            while (true) {
                list.add(value())
                skipWhitespace()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> return list
                    else -> fail("expected ',' or ']' but found '$c'")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return out.toString()
                    '\\' -> when (val esc = next()) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            out.append(text.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> fail("bad escape '\\$esc'")
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) index++
            return text.substring(start, index).toDouble()
        }

        private fun <T> literal(word: String, value: T): T {
            require(text.startsWith(word, index)) { fail("expected '$word'") }
            index += word.length
            return value
        }

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun peek(): Char = if (index < text.length) text[index] else fail("unexpected end of input")

        private fun next(): Char = peek().also { index++ }

        private fun expect(c: Char) {
            if (next() != c) fail("expected '$c'")
        }

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("Malformed JSON at offset $index: $message")
    }
}
