package io.github.kamiiroawase.markdownv2

import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak

/**
 * Lossless chunking of over-length content into several messages: blocks are packed whole
 * while they fit; a block larger than [maxLength] on its own is split along its natural
 * seams (code lines, list items, quote children, paragraph line groups, then inline nodes);
 * a seam still larger than the limit flattens to escaped plain text, which keeps every
 * character. Each piece is independently valid MarkdownV2 and at most [maxLength] long —
 * entities opened inside a piece are completed at its end and never leak into the next.
 */
internal fun chunkBlocks(
    first: Node?,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
): List<String> {
    val acc = ChunkAccumulator()
    var node = first
    while (node != null) {
        val rendered = renderBlock(node, depth, options)
        if (rendered.isNotBlank()) {
            val separator = if (acc.current.isEmpty()) "" else "\n\n"
            if (acc.current.length + separator.length + rendered.length <= maxLength) {
                acc.appendRendered(separator + rendered)
            } else {
                acc.flush()
                chunkBlockInto(node, rendered, maxLength, depth, options, acc)
            }
        }
        node = node.next
    }
    return acc.finish()
}

internal fun chunkBlock(
    node: Node,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
): List<String> {
    val acc = ChunkAccumulator()
    chunkBlockInto(node, renderBlock(node, depth, options), maxLength, depth, options, acc)
    return acc.finish()
}

// Appends the node's completed pieces and leaves its last one open in [acc].current, so the
// caller can pack its own next content alongside — the handoff the old return-a-list shape
// made via pieces.last(). [rendered] is the node's own renderBlock output, supplied by the
// caller (the packing fit check or the single-block entry), so the block renders exactly
// once. The block-shape classification (fence-wrapped kinds, lists, headings) is shared with
// the truncation pipeline — see Blocks.kt
private fun chunkBlockInto(
    node: Node,
    rendered: String,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: ChunkAccumulator,
) {
    when {
        node.isFenceWrappedBlock() -> {
            chunkRenderedCode(node, rendered, maxLength, depth, options, acc)
        }

        node is BulletList || node is OrderedList -> {
            chunkList(node, maxLength, depth, options, acc)
        }

        node is BlockQuote -> {
            chunkQuote(node, maxLength, depth, options, acc)
        }

        node is Heading -> {
            chunkParagraph(node, maxLength, options, headingPrefixOf(node), acc)
        }

        node is Paragraph -> {
            chunkParagraph(node, maxLength, options, "", acc)
        }

        else -> {
            if (rendered.length <= maxLength) {
                acc.appendRendered(rendered)
            } else {
                flattenToPlainText(node, maxLength, depth, options, acc)
            }
        }
    }
}

/**
 * The chunking pipeline's shared output state: completed pieces plus the in-progress chunk,
 * flowing through every seam so an oversized block's last piece can stay open for the
 * caller's next content to pack alongside. Shared with the HTML chunking pipeline
 * (HtmlChunking.kt), whose pieces follow the same rendered/verbatim distinction.
 *
 * [verbatimTail] distinguishes the two content kinds that meet here. Rendered structure
 * flushes trimmed — its trailing whitespace is block-separator junk. Flattened escaped
 * text flushes raw and is never blank-skipped — its trailing whitespace is content (a
 * code line's trailing space, say) that the losslessness guarantee forbids dropping;
 * trimming flushes used to drop exactly those characters at the flatten-to-flush boundary.
 */
internal class ChunkAccumulator {
    val pieces: MutableList<String> = mutableListOf()
    val current: StringBuilder = StringBuilder()
    var verbatimTail = false

    /** Appends rendered structure: separator whitespace after it stays trimmable. */
    fun appendRendered(content: String) {
        current.append(content)
        verbatimTail = false
    }

    /** Appends flattened escaped text: everything down to its last character is content. */
    fun appendVerbatim(content: String) {
        current.append(content)
        verbatimTail = true
    }

    /**
     * Flushes the in-progress chunk, trimmed unless it ends with flattened text. A chunk
     * that is whitespace all the way through drops — the server trims message text and
     * rejects blank text with a 400, so it could never be sent (see finish); the one
     * lossless-chunking exception, and it takes a whitespace run the whole chunk long to
     * reach.
     */
    fun flush() {
        if (current.isEmpty()) return
        val piece = if (verbatimTail) current.toString() else current.toString().trimEnd()
        if (piece.isNotBlank()) pieces += piece
        current.setLength(0)
        verbatimTail = false
    }

    /**
     * Flushes unconditionally raw: the inline seam glues nodes without separators, so even
     * a rendered tail's trailing whitespace is text there (the space before an emphasis
     * marker, say). A whitespace-only flush drops all the same — a blank chunk can never
     * be sent (see flush).
     */
    fun flushRaw() {
        if (current.isEmpty()) return
        val piece = current.toString()
        if (piece.isNotBlank()) pieces += piece
        current.setLength(0)
        verbatimTail = false
    }

    /**
     * The final flush: a blank in-progress chunk is nothing sendable — rendered
     * nothing-content, or flattened text that is all whitespace (the server trims
     * message text and rejects blank text with a 400, and trailing whitespace never
     * displays anyway) — so both drop, and only a chunk with visible text completes.
     */
    fun finish(): List<String> {
        if (current.isNotEmpty() && current.isNotBlank()) flush()
        return pieces
    }
}

// The flatten seam's text source: the node's plain text when it carries any, otherwise
// the de-formatted render — a thematic break renders a visible em-dash line but extracts
// to no plain text, so a plain-text-only source skipped it as no-content and an
// over-limit break chunked to zero pieces. Deriving the fallback from the render keeps
// the two in sync by construction; the de-format resolves the render's entities and
// escapes losslessly, and never throws. Truncation keeps its plain-text-only fallback:
// it drops by design, and there the break's decoration yielding to real text is the
// better cut (the full document's text fills the budget in document order)
private fun flattenTextOf(
    node: Node,
    depth: Int,
    options: RenderOptions,
): String {
    val text = plainText(listOf(node))
    if (text.isNotBlank()) return text
    val rendered = renderBlock(node, depth, options)
    return if (rendered.isBlank()) text else deformat(rendered)
}

// Last-resort seam: fully escaped text is valid MarkdownV2 at any atomic cut, so
// unit-boundary chunking loses no characters (structure degrades, text never does);
// the isBlank guard keeps the nothing-content skip, the text itself flattens untrimmed
private fun flattenToPlainText(
    node: Node,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: ChunkAccumulator,
) {
    val text = flattenTextOf(node, depth, options)
    if (text.isBlank()) return
    appendVerbatimChunks(text, maxLength, acc)
}

// Every piece but the tail completes; the tail stays open in [acc] as verbatim content.
// A whitespace-only piece drops instead of shipping — the server trims message text and
// rejects blank text with a 400 (the one lossless-chunking exception; see
// ChunkAccumulator.flush)
private fun appendVerbatimChunks(
    text: String,
    maxLength: Int,
    acc: ChunkAccumulator,
) {
    val escaped = escapeChunks(text, maxLength)
    escaped.subList(0, escaped.size - 1).forEach { piece ->
        if (!piece.isBlank()) acc.pieces += piece
    }
    acc.appendVerbatim(escaped.last())
}

/**
 * The rendered output of the four fence-wrapped block kinds is always "open\n…\nclose":
 * lines are distributed across chunks, every chunk keeping both fences so it stays valid.
 * A single line longer than the per-line budget splits at escape-unit boundaries into
 * one fenced chunk per segment. The last piece stays open in [acc] — rendered content, it
 * ends with the closing fence — for the caller to pack alongside.
 */
private fun chunkRenderedCode(
    node: Node,
    rendered: String,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: ChunkAccumulator,
) {
    // The four fence-wrapped kinds render to nothing when their content drops out
    // entirely (comment-only or empty HtmlBlock, row-less TableBlock): nothing to chunk —
    // "".lines() is a single line and the subList below needs at least two
    if (rendered.isEmpty()) return
    val lines = rendered.lines()
    val open = lines.first()
    val close = lines.last()
    val middle = lines.subList(1, lines.size - 1)
    // 2 = newline after open and before close; 2 more = one two-char escape unit per line
    val lineBudget = maxLength - open.length - close.length - 2
    if (lineBudget < 2) {
        flattenToPlainText(node, maxLength, depth, options, acc)
        return
    }

    val pieces = mutableListOf<String>()
    val current = StringBuilder(open)
    // Known lossless-chunking exception: the piece guards on real content, so pending
    // blank lines placed at the top of the block drop when the split fires before any
    // real line joins them, and blank lines pending at a mid-content seam drop with the
    // trailing-newline trim (the piece keeps no visible blank line there — the HTML and
    // entities twins' pieces show none either, so the formats agree on the display). A
    // piece whose content is whitespace only would be a message the server trims and
    // rejects (400 "message text is empty") — it never ships
    for (line in middle) {
        if (current.length + 1 + line.length + 1 + close.length <= maxLength) {
            current.append('\n').append(line)
        } else {
            if (current.length > open.length && !isBlankBeyond(current, open.length)) {
                pieces += current.toString().trimEnd('\n') + "\n" + close
                current.setLength(0)
                current.append(open)
            } else {
                // Only pending blank lines ever sit here (the fit check placed real
                // content, and trailing newlines never reach this loop — the render
                // trims them): drop them and start the next piece unplaced
                current.setLength(open.length)
            }
            if (line.length <= lineBudget) {
                current.append('\n').append(line)
            } else {
                splitEscapedUnits(line, lineBudget).forEach { segment ->
                    // A segment of a whitespace-only line is blank visible text — the
                    // same unsendable piece shape the seam drop above refuses, so the
                    // line drops whole (the HTML twin's guard makes the same trade)
                    if (!segment.isBlank()) pieces += "$open\n$segment\n$close"
                }
            }
        }
    }
    if (current.length > open.length && !isBlankBeyond(current, open.length)) {
        pieces += current.toString().trimEnd('\n') + "\n" + close
    }
    if (pieces.isEmpty()) return
    pieces.subList(0, pieces.size - 1).forEach(acc.pieces::add)
    acc.appendRendered(pieces.last())
}

/**
 * Items are packed whole; numbering runs across chunks (a chunk containing items 4–6 starts
 * at "4\\."). An item larger than the limit alone flattens to escaped text, the marker kept
 * on its first piece only. The walk itself (marker consumption, blank-body skip) is
 * forEachListItem, shared with the truncation side.
 */
private fun chunkList(
    list: Node,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: ChunkAccumulator,
) {
    forEachListItem(list, depth, options) { _, prefix, body ->
        val full = applyPrefix(prefix, body)
        val separator = if (acc.current.isEmpty()) "" else "\n"
        if (acc.current.length + separator.length + full.length <= maxLength) {
            acc.appendRendered(separator + full)
        } else {
            acc.flush()
            if (full.length <= maxLength) {
                acc.appendRendered(full)
            } else {
                // Untrimmed: edge whitespace of the item's text is content the
                // flattening path must preserve (a trailing code-line space, say).
                // The body render is the source: it carries the nested structure the
                // plain extraction drops (a nested list's markers exist only in
                // rendered output), and the de-format resolves its entities and
                // escapes losslessly — marker loss stays scoped to the documented
                // tiny-limit tier instead of firing at any limit the item alone
                // exceeds. A body rendering visible output from no plain text (a
                // lone thematic break) is the same source by construction
                val text = deformat(body)
                if (text.isNotBlank()) flattenWithLead(prefix, text, maxLength, acc)
            }
        }
        true
    }
}

/**
 * Quote children are packed whole, each line "> "-prefixed; "> " alone on its own line
 * separates two quote paragraphs within a chunk. A child larger than the limit alone
 * chunks on its own at the inner limit and its pieces re-prefix line by line — the same
 * structured split the HTML and entities twins make (inline formatting, list markers and
 * numbering all survive; the old whole-child flatten lost every one of them). A quote no
 * child of which yields content (empty, or every child blank-rendering) emits the single
 * bare ">" the full render emits (visit(blockQuote)'s empty-pieces fallback) — the marker
 * is the quote's whole render, and dropping it loses it from the chunk list.
 */
private fun chunkQuote(
    quote: Node,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: ChunkAccumulator,
) {
    // Whether any child placed content (mirrors visit(blockQuote)'s pieces.isEmpty()
    // test for the bare-marker fallback below)
    var content = false
    // The expandable "**" rides the quote's first placed line (see
    // expandableQuoteMarkerOf); consumed by whichever emission places it, and yielded
    // outright where its two characters have no reserved room — the structured seam and
    // the tiny limits below budget their pieces to the bare "> " prefix exactly
    var lead = expandableQuoteMarkerOf(quote)
    var node = quote.firstChild
    while (node != null) {
        // renderBlock already trims trailing newlines — the same shape renderQuoteChild
        // packs on the full-render side; edge whitespace of the child's text is content
        // and stays (the parser never forms it; a whitespace-only child skips here and
        // in visit(blockQuote) alike)
        val rendered = renderBlock(node, depth + 1, options)
        if (rendered.isNotBlank()) {
            val nested = node is BlockQuote
            val prefixed = prefixQuoteLevel(nested, rendered)
            val separator = if (acc.current.isEmpty()) "" else "\n>\n"
            if (acc.current.length + lead.length + separator.length + prefixed.length <= maxLength) {
                acc.appendRendered(lead + separator + prefixed)
                lead = ""
                content = true
            } else {
                acc.flush()
                if (lead.length + prefixed.length <= maxLength) {
                    acc.appendRendered(lead + prefixed)
                    lead = ""
                    content = true
                } else {
                    // The structured split keeps the child's formatting; the flatten
                    // fallback covers the tiny limits no prefix headroom exists for.
                    // The expandable lead rides the seam's first placed piece — the
                    // seam reserves its width while it is pending — and yields only
                    // when nothing placed can fit it
                    if (appendStructuredQuoteChild(node, rendered, nested, maxLength, depth, options, acc, lead)) {
                        lead = ""
                        content = true
                    } else {
                        // Untrimmed, same trade as the list path above; flattenTextOf for
                        // the same renders-but-extracts-to-nothing shapes (a lone break)
                        val text = flattenTextOf(node, depth + 1, options)
                        if (text.isNotBlank()) {
                            chunkQuotedText(text, maxLength, acc, lead)
                            lead = ""
                            content = true
                        }
                    }
                }
            }
        }
        node = node.next
    }
    if (!content) {
        // Reaching here means every child blank-skipped, so the caller's flushed/current
        // state is empty (both entries into chunkBlockInto flush or start fresh) and the
        // 1-char marker fits every chunking maxLength (the public guard keeps it ≥ 2).
        // The expandable marker is 3 characters: at a limit too small for it the flag
        // yields and the bare regular marker ships — the tiny-limit trade
        acc.appendRendered(if (lead.isEmpty() || lead.length + 1 <= maxLength) "$lead>" else ">")
    }
}

/**
 * The oversized quote child's structured seam — the MarkdownV2 twin of the HTML quote's
 * temp-chunk-then-wrap: the child chunks on its own at [maxLength] minus the "> " each
 * line will take, then every piece re-prefixes. A piece that still overflows after its
 * prefixes decomposes to its lines: each line fits a chunk alone (inner pieces cap at
 * the inner limit, so every line does), except the lines of a fence pair, which
 * regroup with their markers re-emitted per chunk — splitting between them would strand
 * an unterminated fence. A fence inside a list item opens behind the item's marker
 * ("• ```") and closes behind the continuation indent ("  ```"); the pairing sees
 * through both shapes (isFenceMarkerLine). Nested-quote children keep piece granularity
 * only: their lines already carry the inner level's markers, and a deeper level's fence
 * markers hide behind them, so pairing markers from text alone cannot be exact — an
 * overflowing nested piece flattens instead (the tier the old path always took).
 *
 * [lead] (the expandable quote's "**", pending while the quote's first line is still
 * unplaced) rides the first placed piece: the inner limit reserves its width while it
 * is pending, so a single-line first piece always carries it, and it yields only when
 * nothing placed can fit it (the tiny-limit tier) — the same ride-or-yield every other
 * seam makes.
 */
private fun appendStructuredQuoteChild(
    node: Node,
    rendered: String,
    nested: Boolean,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: ChunkAccumulator,
    lead: String = "",
): Boolean {
    val innerLimit = maxLength - 2 - lead.length
    if (innerLimit < 2) return false
    if (node.isFenceWrappedBlock()) {
        // A fence child goes straight to the prefixed-fence packer: the generic inner
        // chunker's own open/close overhead would shatter the content (its line budget
        // knows nothing of the quote prefixes still to come), and the packer's budget
        // is the one that actually counts. Its own tiny-limit tier then flattens the
        // content as quoted text — keeping every line the old whole-child flatten kept
        val lines = rendered.lines()
        return packPrefixedFence(lines.first(), lines.subList(1, lines.size - 1), maxLength, acc, lead)
    }
    val temp = ChunkAccumulator()
    chunkBlockInto(node, rendered, innerLimit, depth + 1, options, temp)
    val pieces = temp.finish()
    if (pieces.isEmpty()) return false

    var pendingLead = lead
    var produced = false
    for (piece in pieces) {
        if (nested) {
            val glued = prefixQuoteLevel(nested = true, block = piece)
            if (pendingLead.length + glued.length <= maxLength) {
                appendQuoteChunk(pendingLead + glued, maxLength, acc)
                pendingLead = ""
                produced = true
            } else {
                val text = deformat(piece)
                if (text.isNotBlank()) {
                    chunkQuotedText(text, maxLength, acc, pendingLead)
                    pendingLead = ""
                    produced = true
                }
            }
            continue
        }
        val prefixed = prefixQuoteLevel(nested = false, block = piece)
        if (pendingLead.length + prefixed.length <= maxLength) {
            appendQuoteChunk(pendingLead + prefixed, maxLength, acc)
            pendingLead = ""
            produced = true
            continue
        }
        var index = 0
        val lines = piece.lines()
        while (index < lines.size) {
            val line = lines[index]
            if (isFenceMarkerLine(line)) {
                // Markers pair sequentially: a fence's opener matches the next marker
                // line as its closer. The renderers always emit both and fences never
                // nest. A fence inside a list item opens behind the item's marker and
                // closes behind the continuation indent — real fence lines a bare
                // "```"-line-start test used to miss, stranding openers and closers on
                // different chunks — so the classification strips that structure first.
                // Mimics stay impossible the other way around: both code escapers
                // escape content backticks, the language run's filter admits no '>',
                // and text-side backticks escape alike — the pairing is exact
                var end = index + 1
                while (end < lines.size && !isFenceMarkerLine(lines[end])) end++
                if (end < lines.size) {
                    val placed =
                        packPrefixedFence(line, lines.subList(index + 1, end), maxLength, acc, pendingLead)
                    produced = placed || produced
                    if (placed) pendingLead = ""
                    index = end + 1
                    continue
                }
                // An unpaired marker cannot come from the renderers; it must not ride
                // into a chunk raw — a bare "```" line opens a fence no chunk closes,
                // exactly the Telegram parse failure this regrouping exists to prevent —
                // so it flattens as quoted text, the same trade packPrefixedFence's
                // tiny-budget fallback makes (the marker line carries no escape
                // sequences; the unescape keeps the round-trip honest regardless)
                chunkQuotedText(unescapeMarkdownV2(line), maxLength, acc, pendingLead)
                pendingLead = ""
                produced = true
                index++
                continue
            }
            appendQuoteChunk(pendingLead + prefixQuoteLevel(nested = false, block = line), maxLength, acc)
            pendingLead = ""
            produced = true
            index++
        }
    }
    return produced
}

// Packs one already-prefixed quote unit (a single line, a whole piece, or a re-emitted
// fence group — always ≤ maxLength by construction) into [acc], flushing the in-progress
// chunk first when the unit does not fit alongside it
private fun appendQuoteChunk(
    chunk: String,
    maxLength: Int,
    acc: ChunkAccumulator,
) {
    val separator = if (acc.current.isEmpty()) "" else "\n"
    if (acc.current.length + separator.length + chunk.length <= maxLength) {
        acc.appendRendered(separator + chunk)
    } else {
        acc.flush()
        acc.appendRendered(chunk)
    }
}

// Re-prefixes one fence's already-escaped content lines into "> "-marked groups, every
// group carrying its own marker pair (each group is a standalone fence inside the quote,
// the shape the full render's quoted fences take). Whitespace-only groups drop — a piece
// whose visible text is blank is unsendable (see ChunkAccumulator.flush). [lead] (the
// expandable "**") rides the first emitted group's open line, its width reserving into
// that group's budget alone. Returns whether anything was placed
private fun packPrefixedFence(
    openLine: String,
    contentLines: List<String>,
    maxLength: Int,
    acc: ChunkAccumulator,
    lead: String = "",
): Boolean {
    val open = "> $openLine"
    val close = "> ```"
    // 2 = the newlines joining the marker lines to the content
    val budget = maxLength - open.length - close.length - 2
    if (budget - lead.length < 2) {
        // Not even one escape unit fits between the markers: the fence formatting
        // yields — the content flattens as quoted text (chunkQuotedText's own
        // tiny-limit trade), unescaped first (the lines arrive already escaped)
        val text = unescapeMarkdownV2(contentLines.joinToString("\n"))
        if (text.isNotBlank()) chunkQuotedText(text, maxLength, acc, lead)
        return text.isNotBlank()
    }
    var produced = false
    var current = StringBuilder()
    var firstGroup = true
    for (line in contentLines) {
        val segments =
            if (line.length > budget - lead.length) {
                splitEscapedUnits(line, budget - lead.length)
            } else {
                listOf(line)
            }
        for (segment in segments) {
            val joiner = if (current.isEmpty()) "" else "\n"
            if (current.length + joiner.length + segment.length <= budget - (if (firstGroup) lead.length else 0)) {
                current.append(joiner).append(segment)
            } else {
                if (current.isNotBlank()) {
                    appendQuoteChunk("${if (firstGroup) lead else ""}$open\n$current\n$close", maxLength, acc)
                    produced = true
                    firstGroup = false
                }
                current.setLength(0)
                current.append(segment)
            }
        }
    }
    if (current.isNotBlank()) {
        appendQuoteChunk("${if (firstGroup) lead else ""}$open\n$current\n$close", maxLength, acc)
        produced = true
    }
    return produced
}

// A fence marker line as the renderers emit it: "```" or "```" plus the filtered language
// run (letters/digits/-/+/#, ≤ 32 — the Visitor's fence filter), optionally behind the
// list structure a fence inside a list item carries — a continuation indent (applyPrefix)
// and/or a rendered list marker ("• ", "N\. ") when the fence opens the item's first line,
// so "• ```", "  ```" and "1\. ```kotlin" classify alike — or behind a footnote
// definition's escaped label, whose first block opens after the "\[…\]: " the definition
// renders ahead of it (footnote-defined fences in list items stack both shapes). Both
// code escapers escape content backticks and text-side backticks escape the same way, so
// no line outside a fence — flattened escaped text, paragraph content, another item's
// marker-bearing text line — can carry an unescaped one behind that structure: the
// classification is exact
private fun isFenceMarkerLine(line: String): Boolean {
    var index = 0
    while (index < line.length && line[index] == ' ') index++
    if (line.startsWith("• ", index)) {
        index += 2
    } else {
        val markerStart = index
        while (index < line.length && line[index] in '0'..'9') index++
        // The ordered marker's dot escapes in MarkdownV2 output; digits without that
        // tail are content and leave the scan where it stands
        if (index > markerStart && line.startsWith("\\. ", index)) {
            index += 3
        } else {
            index = markerStart
        }
    }
    if (isFenceAt(line, index)) return true
    // The footnote label: "\[" as text, the label's chars escaped or plain, then the
    // "\]" whose tail reads ": " before the fence. The label may hold its own "\]: " —
    // that candidate's tail is label content, not a fence, and the scan continues to
    // the real terminator
    if (!line.startsWith("\\[", index)) return false
    var scan = index + 2
    while (scan + 1 < line.length) {
        if (line[scan] == '\\' && line[scan + 1] == ']' &&
            line.startsWith(": ", scan + 2) &&
            isFenceAt(line, scan + 4)
        ) {
            return true
        }
        scan += if (line[scan] == '\\') 2 else 1
    }
    return false
}

// The fence head the classification strips down to: "```" plus the filtered language run
private fun isFenceAt(
    line: String,
    index: Int,
): Boolean {
    if (!line.startsWith("```", index)) return false
    val language = line.substring(index + 3)
    return language.isEmpty() ||
        (language.length <= 32 && language.all { it.isLetterOrDigit() || it == '-' || it == '+' || it == '#' })
}

// Resolves the escape sequences of already-escaped rendered text (\ + any char — inside
// code entities every backslash starts a pair, and escapeText/escapeCode never emit a
// lone one); the packPrefixedFence flatten fallback needs plain text to re-escape
private fun unescapeMarkdownV2(text: String): String =
    buildString {
        var index = 0
        while (index < text.length) {
            if (index + 1 < text.length && text[index] == '\\') {
                append(text[index + 1])
                index += 2
            } else {
                append(text[index])
                index++
            }
        }
    }

// Escaped text chunked line-wise with "> " prefixes; a line longer than the budget splits at
// escape-unit boundaries. With a maxLength too small for "> " + one escape unit the prefix
// is dropped — validity and text losslessness take precedence over the quote formatting.
// [lead] (the expandable "**", carried while the quote's first line is still unplaced)
// rides the first placed line when its width fits alongside one escape unit and drops
// otherwise — the same precedence
private fun chunkQuotedText(
    text: String,
    maxLength: Int,
    acc: ChunkAccumulator,
    lead: String = "",
) {
    val prefixFits = maxLength >= 4
    // The lead rides only while one escape unit still fits beside "> " and it; below
    // that floor the split budget itself would collapse, so the flag yields — the
    // tiny-limit precedence
    var pendingLead = if (prefixFits && maxLength - 2 - lead.length >= 2) lead else ""
    for (line in text.lines()) {
        val escapedLine = escapeText(line)
        val budget = if (prefixFits) maxLength - 2 - pendingLead.length else maxLength
        val segments =
            if (escapedLine.length > budget) splitEscapedUnits(escapedLine, budget) else listOf(escapedLine)
        for (segment in segments) {
            val prefixed =
                when {
                    !prefixFits -> segment
                    segment.isEmpty() -> if (pendingLead.isEmpty()) ">" else "$pendingLead>"
                    else -> "$pendingLead> $segment"
                }
            if (acc.current.isEmpty()) {
                acc.appendVerbatim(prefixed)
            } else if (acc.current.length + 1 + prefixed.length <= maxLength) {
                acc.appendVerbatim("\n" + prefixed)
            } else {
                acc.flush()
                acc.appendVerbatim(prefixed)
            }
            pendingLead = ""
        }
    }
}

/**
 * Paragraphs (and headings, whose hashes prefix the first piece) split at line breaks
 * first — every piece is a standalone run of lines. The heading prefix glues directly
 * onto the first line group and is never emitted as a piece of its own (a bare "\#" chunk
 * detached from all text, or a newline between the hashes and the title, would be the
 * wrong shape). A line group larger than the limit goes one level deeper
 * ([chunkInlineGroup]).
 */
private fun chunkParagraph(
    node: Node,
    maxLength: Int,
    options: RenderOptions,
    prefix: String,
    acc: ChunkAccumulator,
) {
    acc.appendRendered(prefix)
    // How much of current is still just the un-glued prefix
    var bare = prefix.length
    for (group in lineGroups(node)) {
        if (group.isEmpty()) continue
        val rendered = renderInlineGroup(group, options)
        // A group that renders to nothing (a lone comment, an empty-alt empty-URL image)
        // glues nothing and separates nothing: appending it would only stack separators
        // between its neighbors (and, first in a heading, strand the lead for the tail
        // check below to flatten)
        if (rendered.isEmpty()) continue
        val separator = if (acc.current.length == bare) "" else "\n"
        if (acc.current.length + separator.length + rendered.length <= maxLength) {
            acc.appendRendered(separator + rendered)
        } else {
            if (acc.current.length > bare) {
                acc.flush()
                bare = 0
            }
            if (acc.current.length + rendered.length <= maxLength) {
                acc.appendRendered(rendered)
            } else {
                // chunkInlineGroup consumes the still-un-glued prefix, if any
                chunkInlineGroup(group, maxLength, options, acc)
                bare = 0
            }
        }
    }
    // The lead never glued onto any content — an empty heading, or one whose every group
    // renders to nothing. Within the limit it flushes as rendered structure; an over-limit
    // lead would flush as an over-limit chunk, and dropping it outright (the trade the
    // with-content paths may make) would turn the render's visible output into an empty
    // chunk list — so it flattens at escape-unit boundaries instead, the same shape the
    // code-line seam takes: every piece valid, within the limit, and joining back to the
    // full render
    if (bare > 0 && acc.current.length == bare && prefix.length > maxLength) {
        val pieces = splitEscapedUnits(prefix.trimEnd(), maxLength)
        pieces.subList(0, pieces.size - 1).forEach(acc.pieces::add)
        acc.current.setLength(0)
        acc.appendVerbatim(pieces.last())
    }
}

// Soft/hard breaks delimit line groups; consecutive breaks cannot occur in CommonMark
// (a blank line closes the paragraph), so skipped empty groups lose nothing
private fun lineGroups(node: Node): List<List<Node>> {
    val groups = mutableListOf<List<Node>>()
    val group = mutableListOf<Node>()
    var child = node.firstChild
    while (child != null) {
        if (child is SoftLineBreak || child is HardLineBreak) {
            groups += group.toList()
            group.clear()
        } else {
            group += child
        }
        child = child.next
    }
    groups += group.toList()
    return groups
}

/**
 * A line group larger than the message: pack whole inline nodes; a single node larger than
 * the message flattens to escaped text. Each node renders with its entities completed by
 * its own visitor, so a boundary between nodes never splits an entity (pathological HTML
 * pairs spanning nodes degrade to literal closers — the same trade as the depth-flattening
 * paths in Visitor). [acc].current arrives either empty or holding only a heading prefix (the
 * lead): content packs after the lead, and the lead never becomes a standalone piece — it
 * stays glued to whichever node flattens next, or is dropped outright when the first node
 * fits the limit only without it (a bare marker piece is the wrong shape either way, and
 * keeping the node's rendered structure beats keeping the marker).
 *
 * The lead is tracked by flag, not by length: once real content has packed, current holds
 * text the drop-and-flatten branches must not treat as the disposable marker — and a
 * length comparison alone cannot tell "the lead" from "content exactly as long as the
 * lead was", because the lead-drop branch deliberately reuses the lead's room for the
 * node that fit without it.
 */
private fun chunkInlineGroup(
    nodes: List<Node>,
    maxLength: Int,
    options: RenderOptions,
    acc: ChunkAccumulator,
) {
    // Length of the un-glued lead current starts with (a heading prefix, or 0 when it
    // starts empty); 0 from the moment any real content packs
    var lead = acc.current.length
    for (node in nodes) {
        // A fresh visitor renders each node, so the glue this chunk's trailing backtick
        // would form with the node's opening one is invisible to it — pass the seam
        // state in (`x` packed directly against `y` would glue ``), and the underscore
        // twin for a trailing '_'/'__' closer (an <i> atom completing directly before
        // the next atom's emphasis opener would glue; see Visitor.atUnderscoreGlue)
        val seamGlue = acc.current.isNotEmpty() && endsWithUnescapedBacktick(acc.current)
        val seamUnderscore = acc.current.isNotEmpty() && endsWithUnescapedUnderscore(acc.current)
        val rendered = renderInlineGroup(listOf(node), options, seamGlue, seamUnderscore)
        if (acc.current.length + rendered.length <= maxLength) {
            acc.appendRendered(rendered)
            // An empty render (a lone comment) leaves current exactly the lead — the
            // marker's bare-piece protection must survive it
            if (rendered.isNotEmpty()) lead = 0
        } else {
            if (lead > 0 && rendered.length <= maxLength) {
                // current is exactly the un-glued lead and the node fits once the lead
                // is dropped: drop it rather than flatten the node to escaped text
                acc.current.setLength(0)
                lead = 0
            } else if (lead == 0 && acc.current.isNotEmpty()) {
                // current holds real content (the lead, if any, glued at its front —
                // the marker's own shape): it ships before the node continues on fresh
                // ground
                acc.flushRaw()
            }
            if (rendered.length <= maxLength && acc.current.isEmpty()) {
                // The seam state no longer applies here: current is empty, so the node
                // starts a fresh chunk where nothing can glue onto it — re-render
                // without the degradation the seam had forced (a suppressed code
                // delimiter, a silent underscore opener) whenever one was applied. The
                // seam-degraded render stays as the fallback for the case its shorter
                // output is the only one that fits
                val fresh =
                    if (seamGlue || seamUnderscore) {
                        renderInlineGroup(listOf(node), options)
                    } else {
                        rendered
                    }
                acc.appendRendered(if (fresh.length <= maxLength) fresh else rendered)
            } else {
                // isNotBlank keeps the nothing-content skip; a mid-group node's edge
                // spaces (" c" after an emphasis, say) are content and stay untrimmed
                val text = plainText(listOf(node))
                if (text.isNotBlank()) flattenWithLead(acc.current.toString(), text, maxLength, acc)
            }
            lead = 0
        }
    }
}

/**
 * Last-resort flattening of an oversized item or inline node to escaped text: [lead] (a
 * list marker or a heading prefix — never mid-paragraph content; the inline seam flushes
 * that first) stays glued
 * to the first piece while maxLength leaves room for it plus one escape unit — the ≥2
 * limit escapeChunks is promised; otherwise the lead is dropped, because chunk validity
 * and text losslessness take precedence over the marker (the same trade chunkQuotedText
 * makes for its "> " prefix). Consumes [acc].current and leaves only the final piece in it,
 * as verbatim content — its edge whitespace is flattened text, which the trimming and
 * blank-skipping flushes must not touch.
 */
private fun flattenWithLead(
    lead: String,
    text: String,
    maxLength: Int,
    acc: ChunkAccumulator,
) {
    val limit = maxLength - lead.length
    val keepLead = limit >= 2
    val escaped = escapeChunks(text, if (keepLead) limit else maxLength)
    acc.current.setLength(0)
    if (escaped.size == 1) {
        // The lone piece is also the tail: it stays open as verbatim content for the
        // caller to pack alongside — the same shape appendVerbatimChunks produces.
        // Completing it outright used to force the following item onto its own chunk
        acc.appendVerbatim((if (keepLead) lead else "") + escaped.first())
        return
    }
    // No trimming: a piece edge can land on a space of the original text, and trimming
    // it would drop a character the flattening paths promise to preserve. A piece that
    // is whitespace all the way through is the exception — the server trims message text
    // and rejects blank text with a 400, so it drops instead of shipping an unsendable
    // chunk (reaching it takes a whitespace run at least maxLength long; a blank first
    // piece implies a blank lead, and real leads are markers, never blank); the tail
    // stays regardless, merging with whatever follows inside the accumulator
    val firstPiece = (if (keepLead) lead else "") + escaped.first()
    if (!firstPiece.isBlank()) acc.pieces += firstPiece
    for (i in 1 until escaped.size - 1) {
        if (!escaped[i].isBlank()) acc.pieces += escaped[i]
    }
    acc.appendVerbatim(escaped.last())
}
