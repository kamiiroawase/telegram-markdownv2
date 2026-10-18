package io.github.kamiiroawase.markdownv2

// Telegram's HTML parse mode requires escaping '<', '>' and '&' wherever they appear as
// text (inside <pre>/<code> included — unlike MarkdownV2 code entities the set does not
// narrow); '"' additionally escapes inside the double-quoted attribute values this
// renderer emits. All numeric entities stay supported by the parser, but the three
// shortest named forms are emitted here: they keep the chunking escape unit at 5 chars

/**
 * Escapes all HTML special characters in [text] so the result renders as literal text.
 */
internal fun escapeHtmlText(text: String): String =
    buildString(text.length + 16) {
        for (char in text) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(char)
            }
        }
    }

// The attribute twin: values are emitted double-quoted, so the quote itself must escape
internal fun escapeHtmlAttribute(text: String): String =
    buildString(text.length + 16) {
        for (char in text) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(char)
            }
        }
    }

// The longest unit escapeHtmlText can emit ("&amp;"): the floor every chunking seam below
// this library's HTML paths must respect, the same role the 2 of "\x" plays for MarkdownV2
internal const val MAX_HTML_UNIT = 5

/**
 * Escapes char by char and truncates to [maxLength]; shared by the HTML truncation
 * fallback paths. Escape units (&amp; and friends) and complete surrogate pairs advance
 * as atomic units and are never split; a unit that does not fit ends the output —
 * truncation drops by design, so no progress floor is needed (the chunking twin below
 * has one).
 */
internal fun appendHtmlEscapedTruncated(
    sb: StringBuilder,
    text: String,
    maxLength: Int,
) {
    var used = 0
    var index = 0
    while (index < text.length) {
        if (isSurrogatePairAt(text, index)) {
            if (used + 2 > maxLength) break
            sb.append(text[index]).append(text[index + 1])
            used += 2
            index += 2
        } else {
            val unit = htmlUnitOf(text, index)
            if (used + unit > maxLength) break
            sb.append(htmlEscapedUnit(text, index, unit))
            used += unit
            index++
        }
    }
}

/**
 * Escapes char by char and cuts into pieces of at most [limit] chars — the HTML
 * chunking counterpart of [appendHtmlEscapedTruncated]. Escape units and complete
 * surrogate pairs advance as atomic units and are never split; [limit] must be at least
 * [MAX_HTML_UNIT] (the cost of one "&amp;", which guarantees every piece makes progress)
 * and is checked. The concatenation of all pieces equals escapeHtmlText(text).
 */
internal fun htmlEscapeChunks(
    text: String,
    limit: Int,
): List<String> {
    // A smaller limit cannot hold even one escape unit — fail fast rather than emit
    // over-limit or empty pieces downstream
    require(limit >= MAX_HTML_UNIT) { "limit must be at least $MAX_HTML_UNIT: $limit" }
    if (text.isEmpty()) return listOf("")
    val chunks = mutableListOf<String>()
    val sb = StringBuilder()
    var index = 0
    while (index < text.length) {
        if (isSurrogatePairAt(text, index)) {
            if (sb.length + 2 > limit) {
                chunks += sb.toString()
                sb.clear()
            }
            sb.append(text[index]).append(text[index + 1])
            index += 2
        } else {
            val unit = htmlUnitOf(text, index)
            if (sb.length + unit > limit) {
                chunks += sb.toString()
                sb.clear()
            }
            sb.append(htmlEscapedUnit(text, index, unit))
            index++
        }
    }
    if (sb.isNotEmpty()) chunks += sb.toString()
    return chunks
}

/**
 * Splits already-escaped HTML ([escapeHtmlText] output, where an '&' can only start a
 * named entity this library emits) into pieces of at most [limit] chars without
 * splitting entities or surrogate pairs. [limit] must be at least [MAX_HTML_UNIT] and is
 * checked.
 */
internal fun splitHtmlEscapedUnits(
    text: String,
    limit: Int,
): List<String> {
    require(limit >= MAX_HTML_UNIT) { "limit must be at least $MAX_HTML_UNIT: $limit" }
    val pieces = mutableListOf<String>()
    var start = 0
    var end = 0
    while (end < text.length) {
        val unit =
            if (text.startsWith("&amp;", end) || text.startsWith("&lt;", end) || text.startsWith("&gt;", end)) {
                // All three named forms end at ';' — locate it within the unit bound
                text.indexOf(';', end) + 1 - end
            } else if (isSurrogatePairAt(text, end)) {
                2
            } else {
                1
            }
        // end == start forces progress; callers keep the limit at the unit floor, so an
        // over-long piece cannot come out
        if (end > start && end - start + unit > limit) {
            pieces += text.substring(start, end)
            start = end
        }
        end += unit
    }
    if (end > start) pieces += text.substring(start, end)
    return pieces
}

// The emitted length of the escape unit starting at [index] (a raw char there, never a
// surrogate — callers handle pairs before asking)
private fun htmlUnitOf(
    text: String,
    index: Int,
): Int =
    when (text[index]) {
        '&' -> 5
        '<', '>' -> 4
        else -> 1
    }

// The escaped spelling of that unit
private fun htmlEscapedUnit(
    text: String,
    index: Int,
    unit: Int,
): String =
    when (unit) {
        5 -> "&amp;"
        4 -> if (text[index] == '<') "&lt;" else "&gt;"
        else -> text[index].toString()
    }
