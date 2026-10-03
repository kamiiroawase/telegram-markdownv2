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
                chunkBlockInto(node, maxLength, depth, options, acc)
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
    chunkBlockInto(node, maxLength, depth, options, acc)
    return acc.finish()
}

// Appends the node's completed pieces and leaves its last one open in [acc].current, so the
// caller can pack its own next content alongside — the handoff the old return-a-list shape
// made via pieces.last(). The block-shape classification (fence-wrapped kinds, lists,
// headings) is shared with the truncation pipeline — see Blocks.kt
private fun chunkBlockInto(
    node: Node,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: ChunkAccumulator,
) {
    when {
        node.isFenceWrappedBlock() -> {
            chunkRenderedCode(node, renderBlock(node, depth, options), maxLength, acc)
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
            val rendered = renderBlock(node, depth, options)
            if (rendered.length <= maxLength) {
                acc.appendRendered(rendered)
            } else {
                flattenToPlainText(node, maxLength, acc)
            }
        }
    }
}

/**
 * The chunking pipeline's shared output state: completed pieces plus the in-progress chunk,
 * flowing through every seam so an oversized block's last piece can stay open for the
 * caller's next content to pack alongside.
 *
 * [verbatimTail] distinguishes the two content kinds that meet here. Rendered structure
 * flushes trimmed — its trailing whitespace is block-separator junk. Flattened escaped
 * text flushes raw and is never blank-skipped — its trailing whitespace is content (a
 * code line's trailing space, say) that the losslessness guarantee forbids dropping;
 * trimming flushes used to drop exactly those characters at the flatten-to-flush boundary.
 */
private class ChunkAccumulator {
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

    /** Flushes the in-progress chunk, trimmed unless it ends with flattened text. */
    fun flush() {
        if (current.isEmpty()) return
        pieces += if (verbatimTail) current.toString() else current.toString().trimEnd()
        current.setLength(0)
        verbatimTail = false
    }

    /**
     * Flushes unconditionally raw: the inline seam glues nodes without separators, so even
     * a rendered tail's trailing whitespace is text there (the space before an emphasis
     * marker, say).
     */
    fun flushRaw() {
        if (current.isEmpty()) return
        pieces += current.toString()
        current.setLength(0)
        verbatimTail = false
    }

    /**
     * The final flush: a blank in-progress chunk of rendered structure is nothing-content
     * and is skipped, a flattened one is text and is kept however blank it looks.
     */
    fun finish(): List<String> {
        if (current.isNotEmpty() && (verbatimTail || current.isNotBlank())) flush()
        return pieces
    }
}

// Last-resort seam: fully escaped text is valid MarkdownV2 at any atomic cut, so
// unit-boundary chunking loses no characters (structure degrades, text never does);
// the isBlank guard keeps the nothing-content skip, the text itself flattens untrimmed
private fun flattenToPlainText(
    node: Node,
    maxLength: Int,
    acc: ChunkAccumulator,
) {
    val text = plainText(listOf(node))
    if (text.isBlank()) return
    appendVerbatimChunks(text, maxLength, acc)
}

// Every piece but the tail completes; the tail stays open in [acc] as verbatim content
private fun appendVerbatimChunks(
    text: String,
    maxLength: Int,
    acc: ChunkAccumulator,
) {
    val escaped = escapeChunks(text, maxLength)
    escaped.subList(0, escaped.size - 1).forEach(acc.pieces::add)
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
        flattenToPlainText(node, maxLength, acc)
        return
    }

    val pieces = mutableListOf<String>()
    val current = StringBuilder(open)
    for (line in middle) {
        if (current.length + 1 + line.length + 1 + close.length <= maxLength) {
            current.append('\n').append(line)
        } else {
            if (current.length > open.length) {
                pieces += current.toString() + "\n" + close
                current.setLength(0)
                current.append(open)
            }
            if (line.length <= lineBudget) {
                current.append('\n').append(line)
            } else {
                splitEscapedUnits(line, lineBudget).forEach { segment ->
                    pieces += "$open\n$segment\n$close"
                }
            }
        }
    }
    if (current.length > open.length) pieces += current.toString() + "\n" + close
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
    forEachListItem(list, depth, options) { item, prefix, body ->
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
                // flattening path must preserve (a trailing code-line space, say)
                val text = plainText(listOf(item))
                if (text.isNotBlank()) flattenWithLead(prefix, text, maxLength, acc)
            }
        }
        true
    }
}

/**
 * Quote children are packed whole, each line "> "-prefixed; "> " alone on its own line
 * separates two quote paragraphs within a chunk. A child larger than the limit alone
 * flattens to escaped text prefixed line by line.
 */
private fun chunkQuote(
    quote: Node,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: ChunkAccumulator,
) {
    var node = quote.firstChild
    while (node != null) {
        val rendered = renderBlock(node, depth + 1, options).trim()
        if (rendered.isNotBlank()) {
            val prefixed = prefixQuote(rendered)
            val separator = if (acc.current.isEmpty()) "" else "\n>\n"
            if (acc.current.length + separator.length + prefixed.length <= maxLength) {
                acc.appendRendered(separator + prefixed)
            } else {
                acc.flush()
                if (prefixed.length <= maxLength) {
                    acc.appendRendered(prefixed)
                } else {
                    // Untrimmed, same trade as the list path above
                    val text = plainText(listOf(node))
                    if (text.isNotBlank()) chunkQuotedText(text, maxLength, acc)
                }
            }
        }
        node = node.next
    }
}

// Escaped text chunked line-wise with "> " prefixes; a line longer than the budget splits at
// escape-unit boundaries. With a maxLength too small for "> " + one escape unit the prefix
// is dropped — validity and text losslessness take precedence over the quote formatting
private fun chunkQuotedText(
    text: String,
    maxLength: Int,
    acc: ChunkAccumulator,
) {
    val prefixFits = maxLength >= 4
    val budget = if (prefixFits) maxLength - 2 else maxLength
    for (line in text.lines()) {
        val escapedLine = escapeText(line)
        val segments =
            if (escapedLine.length > budget) splitEscapedUnits(escapedLine, budget) else listOf(escapedLine)
        for (segment in segments) {
            val prefixed =
                when {
                    !prefixFits -> segment
                    segment.isEmpty() -> ">"
                    else -> "> $segment"
                }
            if (acc.current.isEmpty()) {
                acc.appendVerbatim(prefixed)
            } else if (acc.current.length + 1 + prefixed.length <= maxLength) {
                acc.appendVerbatim("\n" + prefixed)
            } else {
                acc.flush()
                acc.appendVerbatim(prefixed)
            }
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
 * stays glued to whichever node flattens next.
 */
private fun chunkInlineGroup(
    nodes: List<Node>,
    maxLength: Int,
    options: RenderOptions,
    acc: ChunkAccumulator,
) {
    val lead = acc.current.length
    for (node in nodes) {
        val rendered = renderInlineGroup(listOf(node), options)
        if (acc.current.length + rendered.length <= maxLength) {
            acc.appendRendered(rendered)
        } else {
            if (acc.current.length > lead) {
                acc.flushRaw()
            }
            if (acc.current.isEmpty() && rendered.length <= maxLength) {
                acc.appendRendered(rendered)
            } else {
                // isNotBlank keeps the nothing-content skip; a mid-group node's edge
                // spaces (" c" after an emphasis, say) are content and stay untrimmed
                val text = plainText(listOf(node))
                if (text.isNotBlank()) flattenWithLead(acc.current.toString(), text, maxLength, acc)
            }
        }
    }
}

/**
 * Last-resort flattening of an oversized item or inline node to escaped text: [lead] (a
 * list marker, a heading prefix, or whatever content still sits in [acc].current) stays glued
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
    // it would drop a character the flattening paths promise to preserve
    acc.pieces += (if (keepLead) lead else "") + escaped.first()
    for (i in 1 until escaped.size - 1) acc.pieces += escaped[i]
    acc.appendVerbatim(escaped.last())
}
