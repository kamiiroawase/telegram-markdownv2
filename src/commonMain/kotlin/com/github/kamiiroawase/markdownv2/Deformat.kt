package com.github.kamiiroawase.markdownv2

/**
 * De-formats rendered MarkdownV2 — [MarkdownV2.render] output or one
 * [MarkdownV2.renderChunked] piece — back into the plain text Telegram would display:
 * the last-resort fallback for a message the Bot API rejects, sent without parse_mode.
 *
 * One linear pass with explicit states (text, inline code, fenced code, link label,
 * link URL) — no regex, no recursion, the same posture as the rest of the library.
 * Emphasis markers vanish, fences and quote prefixes drop, escapes resolve per context,
 * and `[label](url)` becomes `label (url)` so no link target is lost. Even malformed
 * input loses nothing: an emphasis marker that never closes is re-inserted literally,
 * and unterminated code spans / labels / URLs keep their content as text.
 */
internal fun deformat(rendered: String): String {
    val sb = StringBuilder(rendered.length)
    val openMarkers = ArrayDeque<OpenMarker>()
    var index = 0
    while (index < rendered.length) {
        val char = rendered[index]
        when {
            char == '\\' -> {
                val next = rendered.getOrNull(index + 1)
                if (next != null && next in SPECIAL_CHARS) {
                    sb.append(next)
                    index += 2
                } else {
                    sb.append(char)
                    index++
                }
            }

            char == '`' -> {
                index =
                    if (rendered.startsWith("```", index) && isLineStart(rendered, index)) {
                        skipPre(rendered, index, sb)
                    } else {
                        appendCodeContent(rendered, index, sb)
                    }
            }

            char == '[' -> {
                index = appendLinkText(rendered, index, sb)
            }

            char == '>' && isLineStart(rendered, index) -> {
                // An unescaped '>' at line start is Telegram's quote marker — a literal
                // one carries a backslash outside entities — so the marker and one
                // optional space per level drop; nested quotes strip repeatedly
                while (index < rendered.length && rendered[index] == '>') {
                    index++
                    if (index < rendered.length && rendered[index] == ' ') index++
                }
            }

            char == '_' || char == '*' || char == '~' -> {
                // '__' is always underline (Telegram), never two italics
                val marker = if (char == '_' && rendered.getOrNull(index + 1) == '_') "__" else char.toString()
                val top = openMarkers.lastOrNull()
                if (top != null && top.marker == marker) {
                    openMarkers.removeLast()
                } else {
                    openMarkers.addLast(OpenMarker(marker, sb.length))
                }
                index += marker.length
            }

            else -> {
                sb.append(char)
                index++
            }
        }
    }
    reopenUnmatched(sb, openMarkers)
    return sb.toString()
}

// An open emphasis marker plus the output position it was seen at, so a marker that never
// closes can be re-inserted literally at the end of the parse — lossless on garbage input
private class OpenMarker(
    val marker: String,
    val position: Int,
)

// Re-inserting top-down (descending output positions) keeps same-position markers in
// push order — the earlier-opened marker lands before the later one — while distinct
// positions are unaffected by the shifts that inserts at higher indices would cause
private fun reopenUnmatched(
    sb: StringBuilder,
    open: ArrayDeque<OpenMarker>,
) {
    open.asReversed().forEach { sb.insert(it.position, it.marker) }
}

private fun isLineStart(
    text: String,
    index: Int,
): Boolean = index == 0 || text[index - 1] == '\n'

/**
 * Inline code from the backtick at [from]: inside code entities only `\`` and `\\` are
 * escapes, everything else is literal; a lone backslash stays. Unterminated, the rest of
 * the string is content. Returns the index after the closing backtick (or the end).
 */
private fun appendCodeContent(
    rendered: String,
    from: Int,
    sb: StringBuilder,
): Int {
    var index = from + 1
    while (index < rendered.length) {
        val char = rendered[index]
        when {
            char == '\\' -> {
                val next = rendered.getOrNull(index + 1)
                if (next == '`' || next == '\\') {
                    sb.append(next)
                    index += 2
                } else {
                    sb.append(char)
                    index++
                }
            }

            char == '`' -> {
                return index + 1
            }

            else -> {
                sb.append(char)
                index++
            }
        }
    }
    return index
}

/**
 * Fenced block from the ``` at [from]: the whole fence line (fence plus language) drops,
 * the content unescapes `\`` and `\\` only, and the closing fence line drops. Unterminated,
 * the rest of the string is content. Returns the index after the closing fence (or the end).
 */
private fun skipPre(
    rendered: String,
    from: Int,
    sb: StringBuilder,
): Int {
    var index = from
    while (index < rendered.length && rendered[index] != '\n') index++
    if (index < rendered.length) index++ // the fence line's own newline
    while (index < rendered.length) {
        if (rendered[index] == '\n') {
            if (rendered.startsWith("```", index + 1)) {
                index++ // the newline only terminates the last content line
                while (index < rendered.length && rendered[index] != '\n') index++
                return index
            }
            sb.append('\n')
            index++
            continue
        }
        val char = rendered[index]
        val next = rendered.getOrNull(index + 1)
        if (char == '\\' && (next == '`' || next == '\\')) {
            sb.append(next)
            index += 2
        } else {
            sb.append(char)
            index++
        }
    }
    return index
}

/**
 * Link from the `[` at [from]. The label parses as inline text — emphasis, code spans and
 * escapes all apply inside labels, so `[*b*](u)` yields `b (u)` — and a complete `](url)`
 * shape emits `label (url)`, keeping the target visible in the plain-text fallback.
 * Anything incomplete degrades to literal text: the `[` and the processed label stay.
 * Returns the index after the link (or wherever the degradation stopped).
 */
private fun appendLinkText(
    rendered: String,
    from: Int,
    sb: StringBuilder,
): Int {
    val label = StringBuilder()
    val labelEnd = appendLabelText(rendered, from + 1, label)
    if (rendered.startsWith("](", labelEnd)) {
        var index = labelEnd + 2
        val url = StringBuilder()
        var closed = false
        while (index < rendered.length && !closed) {
            val char = rendered[index]
            val next = rendered.getOrNull(index + 1)
            when {
                char == '\\' && (next == ')' || next == '\\') -> {
                    url.append(next)
                    index += 2
                }

                char == ')' -> {
                    index++
                    closed = true
                }

                else -> {
                    url.append(char)
                    index++
                }
            }
        }
        if (closed) {
            sb
                .append(label)
                .append(" (")
                .append(url)
                .append(')')
        } else {
            // No closing ')': emit everything literally and stop — the shape is not a link
            sb
                .append('[')
                .append(label)
                .append("](")
                .append(url)
        }
        return index
    }
    sb.append('[').append(label)
    return labelEnd
}

// Label content from [from]: the text-state logic (escapes, markers, code spans) minus
// block constructs — labels hold inline entities only. Stops at `](` (returning the index
// of the ']') or the end of the string
private fun appendLabelText(
    rendered: String,
    from: Int,
    sb: StringBuilder,
): Int {
    val openMarkers = ArrayDeque<OpenMarker>()
    var index = from
    while (index < rendered.length) {
        val char = rendered[index]
        when {
            char == '\\' -> {
                val next = rendered.getOrNull(index + 1)
                if (next != null && next in SPECIAL_CHARS) {
                    sb.append(next)
                    index += 2
                } else {
                    sb.append(char)
                    index++
                }
            }

            char == ']' && rendered.getOrNull(index + 1) == '(' -> {
                reopenUnmatched(sb, openMarkers)
                return index
            }

            char == '`' -> {
                index = appendCodeContent(rendered, index, sb)
            }

            char == '_' || char == '*' || char == '~' -> {
                val marker = if (char == '_' && rendered.getOrNull(index + 1) == '_') "__" else char.toString()
                val top = openMarkers.lastOrNull()
                if (top != null && top.marker == marker) {
                    openMarkers.removeLast()
                } else {
                    openMarkers.addLast(OpenMarker(marker, sb.length))
                }
                index += marker.length
            }

            else -> {
                sb.append(char)
                index++
            }
        }
    }
    reopenUnmatched(sb, openMarkers)
    return index
}
