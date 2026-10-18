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
 * Lossless chunking of over-length content into several entities-protocol messages:
 * blocks are packed whole while they fit; a block larger than [maxLength] on its own is
 * split along its natural seams (code lines, list items, quote children, paragraph line
 * groups, then inline nodes); a seam still larger than the limit flattens to plain text,
 * which keeps every character. Each piece is an [EntityText] with offsets relative to
 * its own text — structural formatting (a quote's blockquote range, a code block's pre
 * range) re-establishes per piece the way the string formats re-close their wrappers,
 * while an inline range straddling a seam clamps into the piece holding its start and
 * does not continue (the MarkdownV2/HTML twins close their entities at seams too).
 */
internal fun chunkEntityBlocks(
    first: Node?,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
): List<EntityText> {
    val acc = EntityChunkAccumulator()
    var node = first
    while (node != null) {
        val rendered = renderEntityBlock(node, depth, options)
        if (rendered.text.isNotBlank()) {
            val separator = if (acc.current.isEmpty()) "" else "\n\n"
            if (acc.current.length + separator.length + rendered.text.length <= maxLength) {
                acc.appendRendered(separator, rendered)
            } else {
                acc.flush()
                chunkEntityBlockInto(node, rendered, maxLength, depth, options, acc)
            }
        }
        node = node.next
    }
    return acc.finish()
}

internal fun chunkEntityBlock(
    node: Node,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
): List<EntityText> {
    val acc = EntityChunkAccumulator()
    chunkEntityBlockInto(node, renderEntityBlock(node, depth, options), maxLength, depth, options, acc)
    return acc.finish()
}

// Appends the node's completed pieces and leaves its last one open in the accumulator,
// so the caller can pack its own next content alongside. [rendered] is the node's own
// renderEntityBlock output, supplied by the caller so the block renders exactly once
private fun chunkEntityBlockInto(
    node: Node,
    rendered: EntityText,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: EntityChunkAccumulator,
) {
    when {
        node.isFenceWrappedBlock() -> {
            chunkEntityFence(node, rendered, maxLength, depth, options, acc)
        }

        node is BulletList || node is OrderedList -> {
            chunkEntityList(node, maxLength, depth, options, acc)
        }

        node is BlockQuote -> {
            chunkEntityQuote(node, maxLength, depth, options, acc)
        }

        node is Heading -> {
            chunkEntityParagraph(node, maxLength, options, plainHeadingPrefixOf(node), acc)
        }

        node is Paragraph -> {
            chunkEntityParagraph(node, maxLength, options, "", acc)
        }

        else -> {
            if (rendered.text.length <= maxLength) {
                acc.appendRendered("", rendered)
            } else {
                flattenEntityToPlainText(node, maxLength, depth, options, acc)
            }
        }
    }
}

/**
 * The chunking pipeline's shared output state, the EntityText twin of ChunkAccumulator:
 * completed pieces plus the in-progress render, flowing through every seam so an
 * oversized block's last piece stays open for the caller's next content to pack
 * alongside. [verbatimTail] distinguishes rendered structure (flushes trimmed) from
 * flattened plain text (flushes raw and is never blank-skipped — its edge whitespace is
 * content the losslessness guarantee forbids dropping).
 */
internal class EntityChunkAccumulator {
    val pieces = mutableListOf<EntityText>()
    val current = EntityBuilder()
    var verbatimTail = false

    val length: Int
        get() = current.length

    fun isEmpty(): Boolean = current.isEmpty()

    fun appendRendered(
        separator: String,
        piece: EntityText,
    ) {
        current.append(separator, piece)
        verbatimTail = false
    }

    fun appendVerbatim(text: String) {
        current.appendVerbatim(text)
        verbatimTail = true
    }

    fun flush() {
        if (current.isEmpty()) return
        val piece =
            if (verbatimTail) {
                current.build()
            } else {
                trimTrailing(current.build())
            }
        // A whitespace-only piece drops — the server trims message text and rejects
        // blank text with a 400, so it could never be sent (see finish)
        if (piece.text.isNotBlank()) pieces += piece
        current.sb.setLength(0)
        current.entities.clear()
        verbatimTail = false
    }

    /**
     * Flushes unconditionally raw: the inline seam glues nodes without separators, so
     * even a rendered tail's trailing whitespace is text there. A whitespace-only flush
     * drops all the same — a blank chunk can never be sent (see flush).
     */
    fun flushRaw() {
        if (current.isEmpty()) return
        val piece = current.build()
        if (piece.text.isNotBlank()) pieces += piece
        current.sb.setLength(0)
        current.entities.clear()
        verbatimTail = false
    }

    fun finish(): List<EntityText> {
        // See ChunkAccumulator.finish: a blank final chunk — rendered nothing-content or
        // flattened all-whitespace text — can never be sent, so it drops
        if (!current.isEmpty() && current.sb.isNotBlank()) flush()
        return pieces
    }

    // Whitespace-tail trimming with the range clamp that keeps entities inside the text
    private fun trimTrailing(piece: EntityText): EntityText {
        val trimmed = piece.text.trimEnd()
        if (trimmed.length == piece.text.length) return piece
        return EntityText(trimmed, piece.entities.clampedTo(trimmed.length))
    }
}

// Last-resort seam: plain text is valid at any surrogate-safe cut, so unit-boundary
// chunking loses no characters (formatting drops, text never does — the lone exception
// being a whitespace-only piece, which cannot be sent at all; see appendPlainChunks)
private fun flattenEntityToPlainText(
    node: Node,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: EntityChunkAccumulator,
) {
    val text = flattenTextOfEntity(node, depth, options)
    if (text.isBlank()) return
    appendPlainChunks(text, maxLength, acc)
}

// Every piece but the tail completes; the tail stays open as verbatim content. A
// whitespace-only piece can never be sent — the server trims message text and rejects
// blank text with a 400 — so it drops instead of shipping an unsendable chunk; this is
// the one place chunking departs from no-character-loss, and it takes a whitespace run
// at least maxLength long to reach
private fun appendPlainChunks(
    text: String,
    maxLength: Int,
    acc: EntityChunkAccumulator,
) {
    val chunks = plainChunks(text, maxLength)
    chunks.subList(0, chunks.size - 1).forEach {
        if (!it.isBlank()) acc.pieces += EntityText(it, emptyList())
    }
    acc.appendVerbatim(chunks.last())
}

/**
 * A fence-wrapped block is one PRE range over its content: content lines distribute
 * across chunks, every chunk carrying its own range (with the language riding along) —
 * no wrapper text exists to budget for, so the whole limit serves content, and a single
 * line longer than the limit splits at surrogate boundaries into one chunk per segment.
 */
private fun chunkEntityFence(
    node: Node,
    rendered: EntityText,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: EntityChunkAccumulator,
) {
    if (rendered.text.isEmpty()) return

    val pieces = mutableListOf<EntityText>()
    val current = StringBuilder()
    // See shrinkEntityFence: a blank first line appends nothing yet still counts as
    // placed, or the newline joining the next line would be lost. The split guard keys
    // on real content (isNotBlank — pending blank lines at the top of a split fence
    // drop, the MarkdownV2/HTML twins' documented whitespace exception; output() only
    // trims the trailing side, so a fence whose content starts with blank lines DOES
    // start blank, and an unguarded flush used to ship it as an all-whitespace chunk
    // the server rejects with a 400)
    var hasLine = false
    for (line in rendered.text.split('\n')) {
        val joiner = if (hasLine) "\n" else ""
        if (current.length + joiner.length + line.length <= maxLength) {
            current.append(joiner).append(line)
            hasLine = true
        } else {
            if (current.isNotEmpty()) {
                if (current.isNotBlank()) pieces += preEntityOf(current.toString(), node)
                current.setLength(0)
                hasLine = false
            }
            if (line.length <= maxLength) {
                current.append(line)
                hasLine = true
            } else {
                // See appendPlainChunks: a whitespace-only segment can never be sent, so
                // it drops instead of shipping an unsendable chunk
                plainChunks(line, maxLength).forEach { segment ->
                    if (segment.isNotBlank()) pieces += preEntityOf(segment, node)
                }
            }
        }
    }
    if (current.isNotBlank()) pieces += preEntityOf(current.toString(), node)
    if (pieces.isEmpty()) return
    pieces.subList(0, pieces.size - 1).forEach(acc.pieces::add)
    acc.appendRendered("", pieces.last())
}

/**
 * Items are packed whole; numbering runs across chunks. An item larger than the limit
 * alone flattens to plain text, the marker kept on its first piece only.
 */
private fun chunkEntityList(
    list: Node,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: EntityChunkAccumulator,
) {
    forEachEntityListItem(list, depth, options) { item, prefix, body ->
        val full = applyEntityPrefix(prefix, body)
        val separator = if (acc.isEmpty()) "" else "\n"
        if (acc.length + separator.length + full.text.length <= maxLength) {
            acc.appendRendered(separator, full)
        } else {
            acc.flush()
            if (full.text.length <= maxLength) {
                acc.appendRendered("", full)
            } else {
                val text = flattenTextOfEntity(item, depth + 1, options)
                if (text.isNotBlank()) flattenEntityWithLead(prefix, text, maxLength, acc)
            }
        }
        true
    }
}

/**
 * Quote children pack under one blockquote range that opens in mid-chunk — a range is
 * just offsets, so prior document content and the quote's own text share the in-progress
 * piece, the range covering only the quote span. When the span must split, the range
 * closes before the flush and the continuation opens a fresh one on its own chunk (the
 * same trade the "> " prefixes and <blockquote> wrappers of the string formats make). An
 * oversized child chunks on its own, each piece carrying its own full-span range. A
 * quote no child of which yields content renders nothing: an empty range is no quote.
 */
private fun chunkEntityQuote(
    quote: Node,
    maxLength: Int,
    depth: Int,
    options: RenderOptions,
    acc: EntityChunkAccumulator,
) {
    // Offset in acc.current where the currently open quote span started; -1 when none
    var quoteStart = -1

    fun closeQuoteSpan() {
        if (quoteStart >= 0) {
            val length = acc.length - quoteStart
            if (length > 0) {
                acc.current.entities += MessageEntity(MessageEntityType.BLOCKQUOTE, quoteStart, length)
            }
            quoteStart = -1
        }
    }

    var node = quote.firstChild
    while (node != null) {
        val rendered = renderEntityBlock(node, depth + 1, options)
        if (rendered.text.isNotBlank()) {
            val separator = if (acc.isEmpty()) "" else "\n\n"
            if (acc.length + separator.length + rendered.text.length <= maxLength) {
                if (quoteStart < 0) quoteStart = acc.length + separator.length
                acc.appendRendered(separator, rendered)
            } else {
                closeQuoteSpan()
                acc.flush()
                if (rendered.text.length <= maxLength) {
                    quoteStart = 0
                    acc.appendRendered("", rendered)
                } else {
                    val temp = EntityChunkAccumulator()
                    chunkEntityBlockInto(node, rendered, maxLength, depth + 1, options, temp)
                    temp.finish().forEach { piece ->
                        acc.pieces += blockquoteEntityOf(piece)
                    }
                }
            }
        }
        node = node.next
    }
    closeQuoteSpan()
}

// Wraps a piece's text in one blockquote range over the whole piece, keeping the piece's
// own (nested) entities — appended after them, the close order the EntityText contract
// documents (nested ranges before the ranges containing them)
private fun blockquoteEntityOf(piece: EntityText): EntityText =
    if (piece.text.isEmpty()) {
        piece
    } else {
        EntityText(
            piece.text,
            piece.entities + MessageEntity(MessageEntityType.BLOCKQUOTE, 0, piece.text.length),
        )
    }

/**
 * Paragraphs (and headings, whose hashes prefix the first piece) split at line breaks
 * first. The heading prefix glues directly onto the first line group and never becomes a
 * piece of its own. A line group larger than the limit goes one level deeper
 * ([chunkEntityInlineGroup]).
 */
private fun chunkEntityParagraph(
    node: Node,
    maxLength: Int,
    options: RenderOptions,
    prefix: String,
    acc: EntityChunkAccumulator,
) {
    acc.appendRendered("", EntityText(prefix, emptyList()))
    var bare = prefix.length
    for (group in lineGroups(node)) {
        if (group.isEmpty()) continue
        val rendered = renderEntityInlineGroup(group, options)
        if (rendered.text.isEmpty()) continue
        val separator = if (acc.length == bare) "" else "\n"
        if (acc.length + separator.length + rendered.text.length <= maxLength) {
            acc.appendRendered(separator, rendered)
        } else {
            if (acc.length > bare) {
                acc.flush()
                bare = 0
            }
            if (acc.length + rendered.text.length <= maxLength) {
                acc.appendRendered("", rendered)
            } else {
                chunkEntityInlineGroup(group, maxLength, options, acc)
                bare = 0
            }
        }
    }
    // The lead never glued onto any content — an empty heading. Within the limit it
    // flushes as rendered structure; an over-limit lead flattens at unit boundaries
    if (bare > 0 && acc.length == bare && prefix.length > maxLength) {
        val chunks = plainChunks(prefix.trimEnd(), maxLength)
        chunks.subList(0, chunks.size - 1).forEach { acc.pieces += EntityText(it, emptyList()) }
        acc.current.sb.setLength(0)
        acc.current.entities.clear()
        acc.appendVerbatim(chunks.last())
    }
}

// Soft/hard breaks delimit line groups; consecutive breaks cannot occur in CommonMark
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
 * A line group larger than the message: pack whole inline nodes; a single node larger
 * than the message flattens to plain text. Each node renders through its own visitor, so
 * a boundary between nodes never splits a range — and ranges need no delimiter-glue
 * state across the seam: plain concatenated pieces are always valid.
 */
private fun chunkEntityInlineGroup(
    nodes: List<Node>,
    maxLength: Int,
    options: RenderOptions,
    acc: EntityChunkAccumulator,
) {
    // Length of the un-glued lead current starts with (a heading prefix, or 0 when it
    // starts empty); 0 from the moment any real content packs. Tracked by flag, not by
    // length — a length comparison cannot tell "the lead" from "content exactly as long
    // as the lead was", because the lead-drop branch deliberately reuses the lead's room
    var lead = acc.length
    for (node in nodes) {
        val rendered = renderEntityInlineGroup(listOf(node), options)
        if (acc.length + rendered.text.length <= maxLength) {
            acc.appendRendered("", rendered)
            // An empty render (a lone comment) leaves current exactly the lead — the
            // marker's bare-piece protection must survive it
            if (rendered.text.isNotEmpty()) lead = 0
        } else {
            if (lead > 0 && rendered.text.length <= maxLength) {
                // current is exactly the un-glued lead and the node fits once the lead
                // is dropped: drop it rather than flatten the node to plain text
                acc.current.sb.setLength(0)
                acc.current.entities.clear()
                acc.verbatimTail = false
                lead = 0
            } else if (lead == 0 && !acc.isEmpty()) {
                // current holds real content (the lead, if any, glued at its front):
                // it ships before the node continues on fresh ground
                acc.flushRaw()
            }
            if (rendered.text.length <= maxLength && acc.isEmpty()) {
                acc.appendRendered("", rendered)
            } else {
                val text = plainText(listOf(node))
                if (text.isNotBlank()) flattenEntityWithLead(acc.current.build().text, text, maxLength, acc)
            }
            lead = 0
        }
    }
}

/**
 * Last-resort flattening of an oversized item or inline node to plain text: [lead] stays
 * glued to the first piece while maxLength leaves room for it plus one unit; otherwise
 * the lead drops — validity and text losslessness take precedence over the marker.
 */
private fun flattenEntityWithLead(
    lead: String,
    text: String,
    maxLength: Int,
    acc: EntityChunkAccumulator,
) {
    val limit = maxLength - lead.length
    val keepLead = limit >= MIN_PLAIN_UNIT
    val chunks = plainChunks(text, if (keepLead) limit else maxLength)
    acc.current.sb.setLength(0)
    acc.current.entities.clear()
    acc.verbatimTail = false
    if (chunks.size == 1) {
        acc.appendVerbatim((if (keepLead) lead else "") + chunks.first())
        return
    }
    // See appendPlainChunks: blank pieces drop — the tail merges with whatever follows
    // inside the accumulator, so only the completed pieces need the check (a blank first
    // piece implies a blank lead: real leads are markers, never blank)
    val firstPiece = (if (keepLead) lead else "") + chunks.first()
    if (!firstPiece.isBlank()) acc.pieces += EntityText(firstPiece, emptyList())
    for (i in 1 until chunks.size - 1) {
        if (!chunks[i].isBlank()) acc.pieces += EntityText(chunks[i], emptyList())
    }
    acc.appendVerbatim(chunks.last())
}
