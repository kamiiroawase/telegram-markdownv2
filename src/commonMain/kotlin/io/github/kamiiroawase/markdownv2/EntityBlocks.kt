package io.github.kamiiroawase.markdownv2

import org.commonmark.node.ListItem
import org.commonmark.node.Node

// The entities twin of Blocks.kt/HtmlBlocks.kt: the block-level kernel shared by the
// entities truncation (EntityTruncation.kt) and chunking (EntityChunking.kt) pipelines.
// The block-shape classification (isFenceWrappedBlock), applyPrefix, the plain list
// marker and the plain heading prefix are the very functions the other kernels export —
// a block's kind and an item's line shape do not depend on the output format; here live
// only the pieces that are EntityText-specific: composition with offset shifting, the
// offset-aware list-item prefixing, trimming and clamping.

/** Appends [piece]'s text behind [separator] into an accumulating render, shifting its entity offsets by the insertion position. */
internal fun appendEntityText(
    sb: StringBuilder,
    entities: MutableList<MessageEntity>,
    separator: String,
    piece: EntityText,
) {
    sb.append(separator)
    val base = sb.length
    sb.append(piece.text)
    piece.entities.forEach { entity ->
        entities +=
            MessageEntity(
                entity.type,
                entity.offset + base,
                entity.length,
                entity.url,
                entity.language,
                entity.customEmojiId,
            )
    }
}

/** The accumulating render the entities pipelines compose pieces into. */
internal class EntityBuilder {
    val sb = StringBuilder()
    val entities = mutableListOf<MessageEntity>()

    val length: Int
        get() = sb.length

    fun isEmpty(): Boolean = sb.isEmpty()

    fun append(
        separator: String,
        piece: EntityText,
    ) {
        appendEntityText(sb, entities, separator, piece)
    }

    fun appendVerbatim(text: String) {
        sb.append(text)
    }

    // toList: the builder keeps mutating its list after a build (the accumulator clears
    // it on flush) — a shared reference would empty the pieces already handed out
    fun build(): EntityText = EntityText(sb.toString(), entities.toList())
}

/**
 * Renders one block node through a fresh EntityVisitor; nesting-depth capping happens
 * inside the visitor itself (renderChild for structure, enterInline for inline emphasis).
 * Trailing block separators trim away — with entities clamped to the trimmed length, so
 * no range points past the text it claims to cover.
 */
internal fun renderEntityBlock(
    node: Node,
    depth: Int,
    options: RenderOptions,
): EntityText {
    val visitor = EntityVisitor(depth, options)
    node.accept(visitor)
    return trimTrailingNewlines(visitor.output())
}

// A fresh visitor per group: ranges left unclosed within the group complete by its own
// output(), never leaking into the next
internal fun renderEntityInlineGroup(
    nodes: List<Node>,
    options: RenderOptions,
): EntityText {
    val visitor = EntityVisitor(options = options)
    nodes.forEach { it.accept(visitor) }
    return visitor.output()
}

// Trims trailing newlines off a render's text, clamping entity ranges to the trimmed
// length: entities never cover the separators their blocks appended, so the clamp only
// ever shortens ranges that a rewinding degradation left hanging
internal fun trimTrailingNewlines(piece: EntityText): EntityText {
    val trimmed = piece.text.trimEnd('\n')
    if (trimmed.length == piece.text.length) return piece
    return EntityText(trimmed, piece.entities.clampedTo(trimmed.length))
}

/**
 * Clamps the entity list to a text cut at [limit] (a boundary the calling machinery
 * guarantees is surrogate-safe): a range fully beyond the cut drops, one straddling it
 * shortens to the cut — the formatting survives on the kept part, exactly the trade the
 * string formats make when they close an entity at a cut.
 */
internal fun List<MessageEntity>.clampedTo(limit: Int): List<MessageEntity> =
    mapNotNull { entity ->
        if (entity.offset >= limit) {
            null
        } else {
            val end = minOf(entity.offset + entity.length, limit)
            if (end == entity.offset) {
                null
            } else {
                MessageEntity(
                    entity.type,
                    entity.offset,
                    end - entity.offset,
                    entity.url,
                    entity.language,
                    entity.customEmojiId,
                )
            }
        }
    }

/**
 * The offset-shifting twin of [applyPrefix]: prefixes the item's first line with the
 * marker and indents continuation lines to the marker width, shifting the item's entity
 * ranges onto the prefixed text. Insertions sit at the head of line 0 (the marker) and
 * at the head of every non-empty continuation line (the indent); an entity START at an
 * insertion point moves past it (its first character is that line's content) while an
 * END at one does not (the range closed at the previous line's newline) — so starts and
 * ends shift by their own insertion counts, and a range crossing a line boundary grows
 * by the indents inside it.
 */
internal fun applyEntityPrefix(
    prefix: String,
    body: EntityText,
): EntityText {
    val text = applyPrefix(prefix, body.text)
    val indent = prefix.count { it != '\\' }

    val lines = body.text.lines()
    // Line start offsets in the un-prefixed text
    val starts = IntArray(lines.size)
    var acc = 0
    for (index in lines.indices) {
        starts[index] = acc
        acc += lines[index].length + 1
    }

    // Insertions a shifted point passes: the marker at 0 for every point, plus the
    // indent at each non-empty continuation line start the point lies at or beyond
    // ([inclusive] — the marker/indent precedes the content a START indexes into, while
    // an END at a line start closed before that line's indent)
    fun shift(
        point: Int,
        inclusive: Boolean,
    ): Int {
        var shifted = point + prefix.length
        for (index in 1 until lines.size) {
            if (lines[index].isNotEmpty()) {
                val atStart = if (inclusive) starts[index] <= point else starts[index] < point
                if (atStart) shifted += indent
            }
        }
        return shifted
    }

    val entities =
        body.entities.map { entity ->
            val start = shift(entity.offset, inclusive = true)
            val end = shift(entity.offset + entity.length, inclusive = false)
            MessageEntity(
                entity.type,
                start,
                end - start,
                entity.url,
                entity.language,
                entity.customEmojiId,
            )
        }
    return EntityText(text, entities)
}

/**
 * Walks a list's items the way every rendering path must: non-ListItem children skip,
 * the marker is taken — its number consumed — for every item including ones whose body
 * drops out, and a body that renders to nothing but whitespace is no-content and skips
 * too (EntityVisitor.renderList makes the same walk). [visit] receives each surviving
 * item with its marker and pre-rendered body; returning false stops the walk.
 */
internal fun forEachEntityListItem(
    list: Node,
    depth: Int,
    options: RenderOptions,
    visit: (item: ListItem, prefix: String, body: EntityText) -> Boolean,
) {
    val marker = plainListMarkerOf(list)
    var item = list.firstChild
    while (item != null) {
        if (item is ListItem) {
            val prefix = marker()
            val body = renderEntityBlock(item, depth + 1, options)
            if (body.text.isNotBlank() && !visit(item, prefix, body)) break
        }
        item = item.next
    }
}

// The plain-text unit floor of the entities pipeline: no escape sequences exist, so the
// only atomic unit is a surrogate pair (2 chars) — the same floor MarkdownV2 chunking
// works with
internal const val MIN_PLAIN_UNIT = 2

/**
 * Appends plain text truncated to [maxLength]; surrogate pairs advance as atomic units
 * and are never split.
 */
internal fun appendPlainTruncated(
    sb: StringBuilder,
    text: String,
    maxLength: Int,
) {
    var used = 0
    var index = 0
    while (index < text.length) {
        val unit = if (isSurrogatePairAt(text, index)) 2 else 1
        if (used + unit > maxLength) break
        sb.append(text, index, index + unit)
        used += unit
        index += unit
    }
}

/**
 * Cuts plain text into pieces of at most [limit] chars without splitting surrogate
 * pairs; [limit] must be at least [MIN_PLAIN_UNIT] and is checked. The concatenation of
 * all pieces equals [text].
 */
internal fun plainChunks(
    text: String,
    limit: Int,
): List<String> {
    require(limit >= MIN_PLAIN_UNIT) { "limit must be at least $MIN_PLAIN_UNIT: $limit" }
    if (text.isEmpty()) return listOf("")
    val chunks = mutableListOf<String>()
    val sb = StringBuilder()
    var index = 0
    while (index < text.length) {
        val unit = if (isSurrogatePairAt(text, index)) 2 else 1
        if (sb.length + unit > limit) {
            chunks += sb.toString()
            sb.clear()
        }
        sb.append(text, index, index + unit)
        index += unit
    }
    if (sb.isNotEmpty()) chunks += sb.toString()
    return chunks
}

// The flatten seam's text source: the node's plain text when it carries any, otherwise
// the render's own text — which IS plain in this pipeline, so no de-formatting step
// exists (a thematic break extracts to no plain text but renders a visible em-dash line)
internal fun flattenTextOfEntity(
    node: Node,
    depth: Int,
    options: RenderOptions,
): String {
    val text = plainText(listOf(node))
    if (text.isNotBlank()) return text
    return renderEntityBlock(node, depth, options).text
}
