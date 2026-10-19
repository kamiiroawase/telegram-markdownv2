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
 * Lossless chunking of over-length content into several Telegram HTML messages: blocks
 * are packed whole while they fit; a block larger than [maxLength] on its own is split
 * along its natural seams (code lines, list items, quote children, paragraph line groups,
 * then inline nodes); a seam still larger than the limit flattens to escaped plain text,
 * which keeps every character. Each piece is independently valid Telegram HTML and at
 * most [maxLength] long — tags opened inside a piece close at its end (or the empty
 * entity drops entirely) and never leak into the next. The public guard keeps
 * [maxLength] at [MAX_HTML_UNIT] or above, which every escape-unit seam below relies on.
 */
internal fun chunkHtmlBlocks(
    first: Node?,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
): List<String> {
    val acc = ChunkAccumulator()
    var node = first
    while (node != null) {
        val rendered = renderHtmlBlock(node, depth, options)
        if (rendered.isNotBlank()) {
            val separator = if (acc.current.isEmpty()) "" else "\n\n"
            if (acc.current.length + separator.length + rendered.length <= maxLength) {
                acc.appendRendered(separator + rendered)
            } else {
                acc.flush()
                chunkHtmlBlockInto(node, rendered, maxLength, depth, options, acc)
            }
        }
        node = node.next
    }
    return acc.finish()
}

internal fun chunkHtmlBlock(
    node: Node,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
): List<String> {
    val acc = ChunkAccumulator()
    chunkHtmlBlockInto(node, renderHtmlBlock(node, depth, options), maxLength, depth, options, acc)
    return acc.finish()
}

// Appends the node's completed pieces and leaves its last one open in [acc].current, so
// the caller can pack its own next content alongside. [rendered] is the node's own
// renderHtmlBlock output, supplied by the caller (the packing fit check or the
// single-block entry), so the block renders exactly once. The block-shape classification
// (fence-wrapped kinds, lists, headings) is shared with the MarkdownV2 pipeline — see
// HtmlBlocks.kt
private fun chunkHtmlBlockInto(
    node: Node,
    rendered: String,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: ChunkAccumulator,
) {
    when {
        node.isFenceWrappedBlock() -> {
            chunkHtmlFence(node, rendered, maxLength, depth, options, acc)
        }

        node is BulletList || node is OrderedList -> {
            chunkHtmlList(node, maxLength, depth, options, acc)
        }

        node is BlockQuote -> {
            chunkHtmlQuote(node, maxLength, depth, options, acc)
        }

        node is Heading -> {
            chunkHtmlParagraph(node, maxLength, options, plainHeadingPrefixOf(node), acc)
        }

        node is Paragraph -> {
            chunkHtmlParagraph(node, maxLength, options, "", acc)
        }

        else -> {
            if (rendered.length <= maxLength) {
                acc.appendRendered(rendered)
            } else {
                flattenHtmlToPlainText(node, maxLength, depth, options, acc)
            }
        }
    }
}

// The flatten seam's text source: the node's plain text when it carries any, otherwise
// the de-formatted render — a thematic break renders a visible em-dash line but extracts
// to no plain text. Deriving the fallback from the render keeps the two in sync by
// construction; the de-format resolves the render's tags and entities losslessly, and
// never throws
private fun flattenTextOfHtml(
    node: Node,
    depth: Int,
    options: RenderOptions,
): String {
    val text = plainText(listOf(node))
    if (text.isNotBlank()) return text
    val rendered = renderHtmlBlock(node, depth, options)
    return if (rendered.isBlank()) text else deformatHtml(rendered)
}

// Last-resort seam: fully escaped text is valid HTML at any atomic cut, so unit-boundary
// chunking loses no characters (structure degrades, text never does); the isBlank guard
// keeps the nothing-content skip, the text itself flattens untrimmed
private fun flattenHtmlToPlainText(
    node: Node,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: ChunkAccumulator,
) {
    val text = flattenTextOfHtml(node, depth, options)
    if (text.isBlank()) return
    appendHtmlVerbatimChunks(text, maxLength, acc)
}

// Every piece but the tail completes; the tail stays open in [acc] as verbatim content.
// A whitespace-only piece drops instead of shipping — the server trims message text and
// rejects blank text with a 400 (the one lossless-chunking exception; see
// ChunkAccumulator.flush)
private fun appendHtmlVerbatimChunks(
    text: String,
    maxLength: Int,
    acc: ChunkAccumulator,
) {
    val escaped = htmlEscapeChunks(text, maxLength)
    escaped.subList(0, escaped.size - 1).forEach { piece ->
        if (!piece.isBlank()) acc.pieces += piece
    }
    acc.appendVerbatim(escaped.last())
}

/**
 * The rendered output of the four fence-wrapped block kinds is always open + content +
 * close with the tags of htmlFenceOf: content lines distribute across chunks, every chunk
 * keeping both tag strings so it stays valid. A single line longer than the per-line
 * budget splits at escape-unit boundaries into one fenced chunk per segment. The last
 * piece stays open in [acc] — rendered content, it ends with the closing tags — for the
 * caller to pack alongside.
 */
private fun chunkHtmlFence(
    node: Node,
    rendered: String,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: ChunkAccumulator,
) {
    // The four fence-wrapped kinds render to nothing when their content drops out
    // entirely (comment-only or empty HtmlBlock, row-less TableBlock): nothing to chunk
    if (rendered.isEmpty()) return
    val (open, close) = htmlFenceOf(node)
    val lines = rendered.substring(open.length, rendered.length - close.length).split('\n')
    // No newline overhead exists beyond the tags themselves (the first content line glues
    // onto the open tag), so the whole budget between the tags serves one line
    val lineBudget = maxLength - open.length - close.length
    if (lineBudget < MAX_HTML_UNIT) {
        flattenHtmlToPlainText(node, maxLength, depth, options, acc)
        return
    }

    val pieces = mutableListOf<String>()
    val current = StringBuilder(open)
    // See shrinkHtmlFence: a blank first line appends nothing yet still counts as placed,
    // or the newline joining the next line would be lost. Placing no line is different
    // from placing a blank one only until the split fires: there a marker that placed
    // nothing but blank lines drops them instead of shipping — a piece whose visible
    // text is blank is a message the server trims and rejects (400 "message text is
    // empty") — the one lossless-chunking exception, the same drop the verbatim and
    // flatten seams make; the MarkdownV2 twin's guard makes the same trade
    var hasLine = false
    for (line in lines) {
        val joiner = if (hasLine) "\n" else ""
        if (current.length + joiner.length + line.length + close.length <= maxLength) {
            current.append(joiner).append(line)
            hasLine = true
        } else {
            if (current.length > open.length && !isBlankBeyond(current, open.length)) {
                pieces += current.toString() + close
                current.setLength(0)
                current.append(open)
                hasLine = false
            } else {
                // Only pending blank lines ever sit here (the fit check placed real
                // content, and trailing newlines never reach this loop — the render
                // trims them): drop them and start the next piece unplaced
                current.setLength(open.length)
                hasLine = false
            }
            if (line.length <= lineBudget) {
                current.append(line)
                hasLine = true
            } else {
                splitHtmlEscapedUnits(line, lineBudget).forEach { segment ->
                    // A segment of a whitespace-only line is blank visible text — the
                    // same unsendable piece shape the seam drop above refuses, so the
                    // line drops whole
                    if (!segment.isBlank()) pieces += "$open$segment$close"
                }
            }
        }
    }
    // Same content guard as the split above: a marker never placed a real line, so the
    // tail cannot be an empty — or blank — tag pair either
    if (current.length > open.length && !isBlankBeyond(current, open.length)) pieces += current.toString() + close
    if (pieces.isEmpty()) return
    pieces.subList(0, pieces.size - 1).forEach(acc.pieces::add)
    acc.appendRendered(pieces.last())
}

/**
 * Items are packed whole; numbering runs across chunks (a chunk containing items 4–6
 * starts at "4. "). An item larger than the limit alone flattens to escaped text, the
 * marker kept on its first piece only. The walk itself (marker consumption, blank-body
 * skip) is forEachHtmlListItem, shared with the truncation side.
 */
private fun chunkHtmlList(
    list: Node,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: ChunkAccumulator,
) {
    forEachHtmlListItem(list, depth, options) { item, prefix, body ->
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
                val text = flattenTextOfHtml(item, depth + 1, options)
                if (text.isNotBlank()) flattenWithHtmlLead(prefix, text, maxLength, acc)
            }
        }
        true
    }
}

/**
 * Quote children pack into one <blockquote> wrapper while they fit; when the wrapper must
 * split, every piece closes and reopens it, so each chunk is independently valid (a
 * continuation chunk's wrapper carries the quote on — the same trade the per-line ">"
 * prefixes of the MarkdownV2 chunker make). An oversized child chunks on its own and its
 * pieces wrap individually. A quote no child of which yields content renders nothing —
 * the full HTML render emits no bare marker (an empty tag pair is no quote).
 */
private fun chunkHtmlQuote(
    quote: Node,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: ChunkAccumulator,
) {
    // Content children pre-rendered: the walk below needs them twice (packing and
    // oversized splits), and pre-collecting lets an all-blank quote return before
    // touching the accumulator. The chain is the flattened one (see
    // flattenedQuoteChildren) — a nested quote splices its children into this level
    // instead of wrapping a second <blockquote> inside the wrapper, the flattening the
    // full render makes; a quote the flattening left in place at its depth cap renders
    // as escaped plain text, the visitor's own cap fallback
    val children = mutableListOf<Pair<Node, String>>()
    for (child in flattenedQuoteChildren(quote, depth)) {
        val rendered =
            if (child is BlockQuote) {
                val plain = StringBuilder()
                appendPlainText(plain, child)
                escapeHtmlText(plain.toString().trimEnd('\n'))
            } else {
                renderHtmlBlock(child, depth + 1, options)
            }
        if (rendered.isNotBlank()) children += child to rendered
    }
    if (children.isEmpty()) return

    val (quoteOpenTag, quoteCloseTag) = htmlQuoteTagsOf(quote)
    val innerLimit = maxLength - quoteOpenTag.length - quoteCloseTag.length
    if (innerLimit < MAX_HTML_UNIT) {
        // Not even one escape unit fits wrapped — the quote formatting yields to
        // validity and text losslessness, the same trade chunkQuotedText makes when its
        // "> " prefix cannot fit
        val text = flattenTextOfHtml(quote, depth, options)
        if (text.isNotBlank()) appendHtmlVerbatimChunks(text, maxLength, acc)
        return
    }

    // Prior document content the quote's first piece continues — consumed here and
    // re-emitted as the first piece's head, the same lead handoff flattenWithHtmlLead
    // makes (the caller flushed or starts fresh around this call, so current is either
    // empty or exactly that content)
    val lead = acc.current.toString()
    acc.current.setLength(0)

    val pieces = mutableListOf<String>()
    var current = StringBuilder(lead)
    var quoteOpen = false
    for ((node, rendered) in children) {
        // joiner: the wrapper opens either at the chunk head or behind a "\n\n" from
        // prior content; a child already inside the wrapper separates from its sibling
        // with the quote's own paragraph join
        val joiner =
            when {
                quoteOpen -> "\n\n"
                current.isEmpty() -> ""
                else -> "\n\n"
            }
        val add = joiner + (if (quoteOpen) "" else quoteOpenTag) + rendered
        if (current.length + add.length + quoteCloseTag.length <= maxLength) {
            current.append(add)
            quoteOpen = true
        } else {
            if (quoteOpen) current.append(quoteCloseTag)
            if (current.isNotEmpty()) pieces += current.toString()
            current = StringBuilder()
            quoteOpen = false
            if (quoteOpenTag.length + rendered.length + quoteCloseTag.length <= maxLength) {
                current.append(quoteOpenTag).append(rendered)
                quoteOpen = true
            } else {
                // The child alone overflows even a fresh wrapper: chunk it at the inner
                // limit and wrap each of its pieces — the wrapper pair is itself valid
                // standing content, so every piece closes and reopens independently. A
                // depth-capped nested quote (a BlockQuote entry) flattens to verbatim
                // text instead: its own wrapper is the forbidden nesting, and
                // re-entering the quote seam on it would recurse once per remaining level
                val temp = ChunkAccumulator()
                if (node is BlockQuote) {
                    val text = flattenTextOfHtml(node, depth + 1, options)
                    if (text.isNotBlank()) appendHtmlVerbatimChunks(text, innerLimit, temp)
                } else {
                    chunkHtmlBlockInto(node, rendered, innerLimit, depth + 1, options, temp)
                }
                temp.finish().forEach { piece ->
                    pieces += quoteOpenTag + piece + quoteCloseTag
                }
            }
        }
    }
    if (quoteOpen) current.append(quoteCloseTag)
    if (current.isNotEmpty()) pieces += current.toString()

    pieces.subList(0, pieces.size - 1).forEach(acc.pieces::add)
    acc.appendRendered(pieces.last())
}

/**
 * Paragraphs (and headings, whose hashes prefix the first piece) split at line breaks
 * first — every piece is a standalone run of lines. The heading prefix glues directly
 * onto the first line group and is never emitted as a piece of its own. A line group
 * larger than the limit goes one level deeper ([chunkHtmlInlineGroup]).
 */
private fun chunkHtmlParagraph(
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
        val rendered = renderHtmlInlineGroup(group, options)
        // A group that renders to nothing glues nothing and separates nothing: appending
        // it would only stack separators between its neighbors
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
                // chunkHtmlInlineGroup consumes the still-un-glued prefix, if any
                chunkHtmlInlineGroup(group, maxLength, options, acc)
                bare = 0
            }
        }
    }
    // The lead never glued onto any content — an empty heading, or one whose every group
    // renders to nothing. Within the limit it flushes as rendered structure; an over-limit
    // lead would flush as an over-limit chunk, so it flattens at escape-unit boundaries
    // instead, the same shape the code-line seam takes
    if (bare > 0 && acc.current.length == bare && prefix.length > maxLength) {
        val pieces = splitHtmlEscapedUnits(prefix.trimEnd(), maxLength)
        pieces.subList(0, pieces.size - 1).forEach(acc.pieces::add)
        acc.current.setLength(0)
        acc.appendVerbatim(pieces.last())
    }
}

// Whether [builder] holds nothing but whitespace past [from] — the fence seams' test
// for "placed real content", which a length comparison alone cannot make (pending
// blank lines place length too). Shared with the MarkdownV2 fence seam
internal fun isBlankBeyond(
    builder: StringBuilder,
    from: Int,
): Boolean {
    for (index in from until builder.length) {
        if (!builder[index].isWhitespace()) return false
    }
    return true
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
 * the message flattens to escaped text. Each node renders with its tags completed by its
 * own visitor, so a boundary between nodes never splits a tag pair — the HTML twin of the
 * MarkdownV2 seam, with no delimiter-glue state to hand across (the backslash escapes and
 * underscore markers that force the seam flags there have no HTML counterpart: every
 * concatenated atom is balanced on its own).
 */
private fun chunkHtmlInlineGroup(
    nodes: List<Node>,
    maxLength: Int,
    options: RenderOptions,
    acc: ChunkAccumulator,
) {
    // Length of the un-glued lead current starts with (a heading prefix, or 0 when it
    // starts empty); 0 from the moment any real content packs. Tracked by flag, not by
    // length — a length comparison cannot tell "the lead" from "content exactly as long
    // as the lead was", because the lead-drop branch deliberately reuses the lead's room
    var lead = acc.current.length
    for (node in nodes) {
        val rendered = renderHtmlInlineGroup(listOf(node), options)
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
                // current holds real content (the lead, if any, glued at its front):
                // it ships before the node continues on fresh ground
                acc.flushRaw()
            }
            if (rendered.length <= maxLength && acc.current.isEmpty()) {
                acc.appendRendered(rendered)
            } else {
                // isNotBlank keeps the nothing-content skip; a mid-group node's edge
                // spaces are content and stay untrimmed
                val text = plainText(listOf(node))
                if (text.isNotBlank()) flattenWithHtmlLead(acc.current.toString(), text, maxLength, acc)
            }
            lead = 0
        }
    }
}

/**
 * Last-resort flattening of an oversized item or inline node to escaped text: [lead] (a
 * list marker or a heading prefix — never mid-paragraph content; the inline seam flushes
 * that first) stays
 * glued to the first piece while maxLength leaves room for it plus one escape unit — the
 * [MAX_HTML_UNIT] limit htmlEscapeChunks is promised; otherwise the lead is dropped,
 * because chunk validity and text losslessness take precedence over the marker. Consumes
 * [acc].current and leaves only the final piece in it, as verbatim content.
 */
private fun flattenWithHtmlLead(
    lead: String,
    text: String,
    maxLength: Int,
    acc: ChunkAccumulator,
) {
    val limit = maxLength - lead.length
    val keepLead = limit >= MAX_HTML_UNIT
    val escaped = htmlEscapeChunks(text, if (keepLead) limit else maxLength)
    acc.current.setLength(0)
    if (escaped.size == 1) {
        // The lone piece is also the tail: it stays open as verbatim content for the
        // caller to pack alongside
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
