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
 * a quote de-formats to its content with the per-line quote markers stripped, and so does
 * a label or code span soft-broken onto the next line of a quote body (exactly the
 * wrapping quote's depth strips; a content '>' beyond it survives); quote markers strip
 * behind list structure too — a quote or fence inside a list item de-formats with the
 * bullet and the continuation indent kept and the `> ` markers gone — escapes resolve per
 * context, and `[label](url)` becomes `label (url)` so no link target is
 * lost (an empty label or URL degrades to the non-empty piece). Even malformed input
 * loses nothing: an emphasis marker that never closes is re-inserted literally, and
 * unterminated code spans / labels / URLs keep their content as text.
 */
internal fun deformat(rendered: String): String {
    val sb = StringBuilder(rendered.length)
    val openMarkers = ArrayDeque<OpenMarker>()
    // Quote-prefix depth of the line the scan is currently on: an entity opened on the
    // line (a label, a code span) inherits it, because every line of the quote body it
    // lives in carries the same number of prefix units. Refreshed by the line-start
    // scan whenever a line begins
    val first = scanLineStart(rendered, 0, sb)
    var index = first.index
    var lineQuoteDepth = first.quoteDepth
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
                // Fences open only at a line start behind the line's structure, and the
                // line-start scan consumed every such fence already; a backtick here is
                // a code span
                index = appendCodeContent(rendered, index, sb, lineQuoteDepth)
            }

            char == '[' -> {
                index = appendLinkText(rendered, index, sb, lineQuoteDepth)
            }

            char == '_' || char == '*' || char == '~' -> {
                var run = 1
                while (index + run < rendered.length && rendered[index + run] == char) run++
                matchMarkerRun(openMarkers, char, run, sb.length)
                index += run
            }

            char == '\n' -> {
                sb.append(char)
                val line = scanLineStart(rendered, index + 1, sb)
                index = line.index
                lineQuoteDepth = line.quoteDepth
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

/**
 * Inline code from the backtick at [from]: inside code entities only `\`` and `\\` are
 * escapes, everything else is literal; a lone backslash stays. A content line break
 * inside a quote body strips the next line's quote units down to [quoteDepth] while its
 * list context (indent, markers) stays — the markers belong to the quote, not the code;
 * a content '>' beyond the depth survives (escapeCode leaves '>' unescaped, so the depth
 * is the only separator between marker and content — the same trade skipPre makes for
 * fenced blocks). Unterminated, the rest of the string is content. Returns the index
 * after the closing backtick (or the end).
 */
private fun appendCodeContent(
    rendered: String,
    from: Int,
    sb: StringBuilder,
    quoteDepth: Int,
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

            char == '\n' -> {
                sb.append(char)
                val prefix = scanLinePrefix(rendered, index + 1, quoteDepth)
                sb.append(prefix.context)
                index = prefix.contentFrom
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
 * Fenced block from the ``` at [from] — a line start behind [depth] quote levels; the
 * line's own prefix (list context emitted, quote markers stripped) was consumed by the
 * caller's line-start scan: the rest of the fence line (language included) drops, each
 * content line strips the same [depth] quote units while its list context emits, the
 * content unescapes `\`` and `\\` only, and the closing fence line (prefix included)
 * drops — leaving the code the block displayed. A content line carrying fewer units than
 * [depth] strips what is there; one starting with its own '>' keeps it (only the
 * wrapping quote's markers strip). Unterminated, the rest of the string is content.
 * Returns the index at the closing fence line's newline (or the end).
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
    val first = scanLinePrefix(rendered, index, depth)
    sb.append(first.context)
    index = first.contentFrom
    while (index < rendered.length) {
        if (rendered[index] == '\n') {
            val prefix = scanLinePrefix(rendered, index + 1, depth)
            if (rendered.startsWith("```", prefix.contentFrom)) {
                index = prefix.contentFrom
                while (index < rendered.length && rendered[index] != '\n') index++
                return index
            }
            sb.append('\n').append(prefix.context)
            index = prefix.contentFrom
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

// The result of scanning one line's structural prefix: where the content starts, how
// many quote units stripped, and the list context for the caller to emit
private class LinePrefix(
    val contentFrom: Int,
    val quoteDepth: Int,
    val context: String,
)

// The result of a line-start scan: where the main scan resumes (past a whole fenced
// block when one opened) and the line's quote depth for entities that open on it
private class LineStart(
    val index: Int,
    val quoteDepth: Int,
)

/**
 * Scans one line's structural prefix from [from] (a line start): up to [maxDepth] quote
 * units ('>' plus one optional space — one per wrapping quote level) strip, and list
 * context — indent spaces, `• ` bullets, `N\. ` ordered markers — returns as
 * [LinePrefix.context], because plain text keeps the list structure the renderer
 * prefixes every item line with. Content stops the scan. The classification is
 * unambiguous on this library's output: a content character at a line start escapes
 * when it is MarkdownV2-special ('\>' for a literal '>'), and code content doubles its
 * backslashes, so an unescaped `N\. ` or `> ` unit is necessarily structure the
 * renderer emitted.
 */
private fun scanLinePrefix(
    rendered: String,
    from: Int,
    maxDepth: Int,
): LinePrefix {
    val context = StringBuilder()
    var index = from
    var depth = 0
    while (index < rendered.length) {
        val char = rendered[index]
        when {
            char == '>' && depth < maxDepth -> {
                index++
                depth++
                if (index < rendered.length && rendered[index] == ' ') index++
            }

            char == '•' && index + 1 < rendered.length && rendered[index + 1] == ' ' -> {
                context.append("• ")
                index += 2
            }

            char in '0'..'9' -> {
                var end = index
                while (end < rendered.length && rendered[end] in '0'..'9') end++
                // The ordered marker's digits continue as '\. '; digits without that
                // tail are content and stop the scan where it stands. The marker emits
                // unescaped — its dot renders as a plain '.' in the plain text
                if (end + 2 < rendered.length && rendered[end] == '\\' && rendered[end + 1] == '.' && rendered[end + 2] == ' ') {
                    context.append(rendered, index, end)
                    context.append(". ")
                    index = end + 3
                } else {
                    break
                }
            }

            char == ' ' -> {
                val start = index
                while (index < rendered.length && rendered[index] == ' ') index++
                context.append(rendered, start, index)
            }

            else -> {
                break
            }
        }
    }
    return LinePrefix(index, depth, context.toString())
}

// Scans the line starting at [from] into [sb]: the structural prefix emits its list
// context and strips its quote markers (scanLinePrefix), and a fence opening right
// behind the prefix consumes its whole block (skipPre) — fences open at line starts
// only, behind whatever structure prefixes them. Returns where the main scan resumes
// and the line's quote depth
private fun scanLineStart(
    rendered: String,
    from: Int,
    sb: StringBuilder,
): LineStart {
    val prefix = scanLinePrefix(rendered, from, Int.MAX_VALUE)
    sb.append(prefix.context)
    val content = prefix.contentFrom
    val index =
        if (rendered.startsWith("```", content)) {
            skipPre(rendered, content, prefix.quoteDepth, sb)
        } else {
            content
        }
    return LineStart(index, prefix.quoteDepth)
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
    quoteDepth: Int,
): Int {
    val label = StringBuilder()
    val labelEnd = appendLabelText(rendered, from + 1, label, quoteDepth)
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
// block constructs — labels hold inline entities only. A label line break inside a quote
// body strips the next line's quote units down to [quoteDepth] while its list context
// stays (see appendCodeContent). Stops at `](` (returning the index of the ']') or the
// end of the string
private fun appendLabelText(
    rendered: String,
    from: Int,
    sb: StringBuilder,
    quoteDepth: Int,
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
                index = appendCodeContent(rendered, index, sb, quoteDepth)
            }

            char == '_' || char == '*' || char == '~' -> {
                var run = 1
                while (index + run < rendered.length && rendered[index + run] == char) run++
                matchMarkerRun(openMarkers, char, run, sb.length)
                index += run
            }

            char == '\n' -> {
                sb.append(char)
                val prefix = scanLinePrefix(rendered, index + 1, quoteDepth)
                sb.append(prefix.context)
                index = prefix.contentFrom
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
