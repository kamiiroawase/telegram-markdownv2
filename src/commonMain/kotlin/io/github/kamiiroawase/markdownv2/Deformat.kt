package io.github.kamiiroawase.markdownv2

/**
 * De-formats rendered MarkdownV2 — [MarkdownV2.render] output or one
 * [MarkdownV2.renderChunked] piece — back into the plain text Telegram would display:
 * the last-resort fallback for a message the Bot API rejects, sent without parse_mode.
 *
 * One linear pass with explicit states (text, inline code, fenced code, link label,
 * link URL) — no regex, no recursion, the same posture as the rest of the library.
 * Emphasis markers vanish (`*bold*`, `_italic_`, `__underline__`, `~strike~`), runs of
 * adjacent markers included (`_a__b_`, the render of two italics side by side, resolves
 * close-then-open into plain `ab`); fences and quote prefixes drop — a code block inside
 * a quote de-formats to its content with the per-line quote markers stripped — escapes
 * resolve per context, and `[label](url)` becomes `label (url)` so no link target is
 * lost (an empty label or URL degrades to the non-empty piece). Even malformed input
 * loses nothing: an emphasis marker that never closes is re-inserted literally, and
 * unterminated code spans / labels / URLs keep their content as text.
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
                // A fence opens at a line start, including right behind quote prefixes
                // ("> ```" opens a code block inside a quote); elsewhere it is a code span
                val depth =
                    if (rendered.startsWith("```", index)) quoteDepthBefore(rendered, index) else -1
                index =
                    if (depth >= 0) {
                        skipPre(rendered, index, depth, sb)
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
                var run = 1
                while (index + run < rendered.length && rendered[index + run] == char) run++
                matchMarkerRun(openMarkers, char, run, sb.length)
                index += run
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

/**
 * Matches a run of [count] identical marker characters against the open-marker stack:
 * each unit first closes the entity on top when its marker matches ('_' closes '_',
 * '__' takes two; '*' and '~' are single-char), the remainder opens new entities —
 * '__' as underline, a lone '_' as italic (Telegram's rule that `__` is always
 * underline). Resolving the run as a whole is what handles adjacency: `_a__b_` (the
 * render of two italics side by side) reads the first '_' of the run as the closer of
 * the open italic and the second as the next opener, where the old char-greedy read
 * took '__' for one underline marker, matched nothing, and re-inserted all three
 * markers literally. [position] is the output position the run starts at (it emits
 * nothing itself), shared by every marker the run opens.
 */
private fun matchMarkerRun(
    openMarkers: ArrayDeque<OpenMarker>,
    marker: Char,
    count: Int,
    position: Int,
) {
    var remaining = count
    while (remaining > 0) {
        val top = openMarkers.lastOrNull()
        val closeCost =
            when {
                top == null -> 0
                top.marker == "__" && marker == '_' && remaining >= 2 -> 2
                top.marker == marker.toString() -> 1
                else -> 0
            }
        if (closeCost > 0) {
            openMarkers.removeLast()
            remaining -= closeCost
        } else if (marker == '_' && remaining >= 2) {
            openMarkers.addLast(OpenMarker("__", position))
            remaining -= 2
        } else {
            openMarkers.addLast(OpenMarker(marker.toString(), position))
            remaining--
        }
    }
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
 * Fenced block from the ``` at [from], possibly behind [depth] quote-prefix levels: the
 * whole fence line (fence, language and prefix) drops, each content line strips the same
 * [depth] prefix units, the content unescapes `\`` and `\\` only, and the closing fence
 * line (prefix included) drops — leaving exactly the code the block displayed. A content
 * line carrying fewer units than [depth] strips what is there; one starting with its own
 * '>' keeps it (only the wrapping quote's markers strip). Unterminated, the rest of the
 * string is content. Returns the index after the closing fence (or the end).
 */
private fun skipPre(
    rendered: String,
    from: Int,
    depth: Int,
    sb: StringBuilder,
): Int {
    var index = from
    while (index < rendered.length && rendered[index] != '\n') index++
    if (index < rendered.length) index++ // the fence line's own newline
    index = skipQuoteUnits(rendered, index, depth) // the first content line's prefix
    while (index < rendered.length) {
        if (rendered[index] == '\n') {
            val afterPrefix = skipQuoteUnits(rendered, index + 1, depth)
            if (rendered.startsWith("```", afterPrefix)) {
                index = afterPrefix
                while (index < rendered.length && rendered[index] != '\n') index++
                return index
            }
            sb.append('\n')
            index = afterPrefix
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

// The number of quote-prefix units ('>' plus one optional space each) between the start
// of the line and [index], or -1 when [index] does not sit at a line start — only those
// characters may precede a fence's opening. The walk covers the line's prefix only, and
// each prefix precedes at most one fence check, so the de-format stays linear
private fun quoteDepthBefore(
    rendered: String,
    index: Int,
): Int {
    var i = index - 1
    var depth = 0
    while (i >= 0 && (rendered[i] == '>' || rendered[i] == ' ')) {
        if (rendered[i] == '>') depth++
        i--
    }
    return if (i < 0 || rendered[i] == '\n') depth else -1
}

// Strips up to [depth] quote-prefix units ('>' plus one optional space each) from the
// line at [from]; a line carrying fewer units strips what is there
private fun skipQuoteUnits(
    rendered: String,
    from: Int,
    depth: Int,
): Int {
    var index = from
    var remaining = depth
    while (remaining > 0 && index < rendered.length && rendered[index] == '>') {
        index++
        if (index < rendered.length && rendered[index] == ' ') index++
        remaining--
    }
    return index
}

/**
 * Link from the `[` at [from]. The label parses as inline text — emphasis, code spans and
 * escapes all apply inside labels, so `[*b*](u)` yields `b (u)` — and a complete `](url)`
 * shape emits `label (url)`, keeping the target visible in the plain-text fallback; an
 * empty label or URL degrades to the non-empty piece, never dangling parentheses.
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
        if (!closed) {
            // No closing ')': emit everything literally and stop — the shape is not a link
            sb
                .append('[')
                .append(label)
                .append("](")
                .append(url)
        } else if (url.isEmpty()) {
            sb.append(label)
        } else if (label.isEmpty()) {
            sb.append(url)
        } else {
            sb
                .append(label)
                .append(" (")
                .append(url)
                .append(')')
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
                var run = 1
                while (index + run < rendered.length && rendered[index + run] == char) run++
                matchMarkerRun(openMarkers, char, run, sb.length)
                index += run
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
