package com.github.kamiiroawase.markdownv2

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
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
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    var node = first
    while (node != null) {
        val rendered = renderBlock(node, depth, options)
        if (rendered.isNotBlank()) {
            val separator = if (current.isEmpty()) "" else "\n\n"
            if (current.length + separator.length + rendered.length <= maxLength) {
                current.append(separator).append(rendered)
            } else {
                if (current.isNotEmpty()) {
                    chunks += current.toString().trimEnd()
                    current.setLength(0)
                }
                val pieces = chunkBlock(node, maxLength, depth, options)
                if (pieces.isNotEmpty()) {
                    pieces.subList(0, pieces.size - 1).forEach(chunks::add)
                    current.append(pieces.last())
                }
            }
        }
        node = node.next
    }
    if (current.isNotBlank()) chunks += current.toString().trimEnd()
    return chunks
}

internal fun chunkBlock(
    node: Node,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
): List<String> =
    when (node) {
        is FencedCodeBlock, is IndentedCodeBlock, is TableBlock, is HtmlBlock -> {
            chunkRenderedCode(node, renderBlock(node, depth, options), maxLength)
        }

        is BulletList -> {
            chunkList(node, maxLength, depth, options) { "• " }
        }

        is OrderedList -> {
            var number = node.markerStartNumber ?: 1
            chunkList(node, maxLength, depth, options) { "${number++}\\. " }
        }

        is BlockQuote -> {
            chunkQuote(node, maxLength, depth, options)
        }

        is Heading -> {
            chunkParagraph(node, maxLength, options, escapeText("#".repeat(node.level)) + " ")
        }

        is Paragraph -> {
            chunkParagraph(node, maxLength, options, "")
        }

        else -> {
            val rendered = renderBlock(node, depth, options)
            if (rendered.length <= maxLength) listOf(rendered) else plainTextChunks(node, maxLength)
        }
    }

// Last-resort seam: fully escaped text is valid MarkdownV2 at any atomic cut, so
// unit-boundary chunking loses no characters (structure degrades, text never does)
private fun plainTextChunks(
    node: Node,
    maxLength: Int,
): List<String> {
    val text = plainText(listOf(node)).trim()
    if (text.isEmpty()) return emptyList()
    return escapeChunks(text, maxLength)
}

/**
 * The rendered output of the four fence-wrapped block kinds is always "open\n…\nclose":
 * lines are distributed across chunks, every chunk keeping both fences so it stays valid.
 * A single line longer than the per-line budget splits at escape-unit boundaries into
 * one fenced chunk per segment.
 */
private fun chunkRenderedCode(
    node: Node,
    rendered: String,
    maxLength: Int,
): List<String> {
    // The four fence-wrapped kinds render to nothing when their content drops out
    // entirely (comment-only or empty HtmlBlock, row-less TableBlock): nothing to chunk —
    // "".lines() is a single line and the subList below needs at least two
    if (rendered.isEmpty()) return emptyList()
    val lines = rendered.lines()
    val open = lines.first()
    val close = lines.last()
    val middle = lines.subList(1, lines.size - 1)
    // 2 = newline after open and before close; 2 more = one two-char escape unit per line
    val lineBudget = maxLength - open.length - close.length - 2
    if (lineBudget < 2) return plainTextChunks(node, maxLength)

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
    return pieces
}

/**
 * Items are packed whole; numbering runs across chunks (a chunk containing items 4–6 starts
 * at "4\\."). An item larger than the limit alone flattens to escaped text, the marker kept
 * on its first piece only.
 */
private fun chunkList(
    list: Node,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    marker: () -> String,
): List<String> {
    val pieces = mutableListOf<String>()
    val current = StringBuilder()
    var item = list.firstChild
    while (item != null) {
        if (item is ListItem) {
            val prefix = marker()
            val body = renderBlock(item, depth + 1, options)
            if (body.isNotBlank()) {
                val full = applyPrefix(prefix, body)
                val separator = if (current.isEmpty()) "" else "\n"
                if (current.length + separator.length + full.length <= maxLength) {
                    current.append(separator).append(full)
                } else {
                    if (current.isNotEmpty()) {
                        pieces += current.toString().trimEnd()
                        current.setLength(0)
                    }
                    if (full.length <= maxLength) {
                        current.append(full)
                    } else {
                        val text = plainText(listOf(item)).trim()
                        if (text.isNotEmpty()) {
                            val marked = prefix.length < maxLength
                            val escaped = escapeChunks(text, if (marked) maxLength - prefix.length else maxLength)
                            pieces += ((if (marked) prefix else "") + escaped.first()).trim()
                            for (i in 1 until escaped.size - 1) pieces += escaped[i].trim()
                            if (escaped.size > 1) current.append(escaped.last().trim())
                        }
                    }
                }
            }
        }
        item = item.next
    }
    if (current.isNotBlank()) pieces += current.toString().trimEnd()
    return pieces
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
): List<String> {
    val pieces = mutableListOf<String>()
    val current = StringBuilder()
    var node = quote.firstChild
    while (node != null) {
        val rendered = renderBlock(node, depth + 1, options).trim()
        if (rendered.isNotBlank()) {
            val prefixed = prefixQuote(rendered)
            val separator = if (current.isEmpty()) "" else "\n>\n"
            if (current.length + separator.length + prefixed.length <= maxLength) {
                current.append(separator).append(prefixed)
            } else {
                if (current.isNotEmpty()) {
                    pieces += current.toString().trimEnd()
                    current.setLength(0)
                }
                if (prefixed.length <= maxLength) {
                    current.append(prefixed)
                } else {
                    val text = plainText(listOf(node)).trim()
                    if (text.isNotEmpty()) chunkQuotedText(text, maxLength, pieces, current)
                }
            }
        }
        node = node.next
    }
    if (current.isNotBlank()) pieces += current.toString().trimEnd()
    return pieces
}

// Escaped text chunked line-wise with "> " prefixes; a line longer than the budget splits at
// escape-unit boundaries. With a maxLength too small for "> " + one escape unit the prefix
// is dropped — validity and text losslessness take precedence over the quote formatting
private fun chunkQuotedText(
    text: String,
    maxLength: Int,
    pieces: MutableList<String>,
    current: StringBuilder,
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
            if (current.isEmpty()) {
                current.append(prefixed)
            } else if (current.length + 1 + prefixed.length <= maxLength) {
                current.append('\n').append(prefixed)
            } else {
                pieces += current.toString().trimEnd()
                current.setLength(0)
                current.append(prefixed)
            }
        }
    }
}

/**
 * Paragraphs (and headings, whose hashes prefix the first piece) split at line breaks
 * first — every piece is a standalone run of lines. A line group larger than the limit
 * goes one level deeper ([chunkInlineGroup]).
 */
private fun chunkParagraph(
    node: Node,
    maxLength: Int,
    options: RenderOptions,
    prefix: String,
): List<String> {
    val pieces = mutableListOf<String>()
    val current = StringBuilder(prefix)
    for (group in lineGroups(node)) {
        if (group.isEmpty()) continue
        val rendered = renderInlineGroup(group, options)
        val separator = if (current.isEmpty()) "" else "\n"
        if (current.length + separator.length + rendered.length <= maxLength) {
            current.append(separator).append(rendered)
        } else {
            if (current.isNotEmpty()) {
                pieces += current.toString().trimEnd()
                current.setLength(0)
            }
            if (rendered.length <= maxLength) {
                current.append(rendered)
            } else {
                chunkInlineGroup(group, maxLength, options, pieces, current)
            }
        }
    }
    if (current.isNotBlank()) pieces += current.toString().trimEnd()
    return pieces
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
 * paths in Visitor).
 */
private fun chunkInlineGroup(
    nodes: List<Node>,
    maxLength: Int,
    options: RenderOptions,
    pieces: MutableList<String>,
    current: StringBuilder,
) {
    for (node in nodes) {
        val rendered = renderInlineGroup(listOf(node), options)
        if (current.length + rendered.length <= maxLength) {
            current.append(rendered)
        } else {
            if (current.isNotEmpty()) {
                // Full trim: inline continuation pieces carry no meaningful edge whitespace
                pieces += current.toString().trim()
                current.setLength(0)
            }
            if (rendered.length <= maxLength) {
                current.append(rendered)
            } else {
                val text = plainText(listOf(node)).trim()
                if (text.isNotEmpty()) {
                    val escaped = escapeChunks(text, maxLength)
                    escaped.subList(0, escaped.size - 1).forEach { pieces += it.trim() }
                    current.append(escaped.last())
                }
            }
        }
    }
}
