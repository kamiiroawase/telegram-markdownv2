package io.github.kamiiroawase.markdownv2

import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak

/**
 * Structure-preserving truncation of over-length content into the entities protocol:
 * blocks that fit are kept whole, blocks that do not are shrunk by type (whole lines /
 * whole items), and the entity ranges clamp to whatever text survived — a range fully
 * beyond the cut drops, one straddling it shortens. A block that renders to nothing
 * skips exactly as the chunking side skips it. When no structural content fits at all,
 * the caller falls back to plain text with no entities.
 *
 * Quotes keep their blockquote range behind list structure (no line-prefix grammar to
 * satisfy) and need no re-shrink retry: nothing adds per-line cost outside the budget
 * except the list-item continuation indent, which keeps the MarkdownV2/HTML retry loop.
 */
internal fun renderEntityBlocks(
    first: Node?,
    budget: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): EntityText {
    if (budget <= 0) return EntityText.EMPTY

    val parts = EntityBuilder()
    var node = first
    // Set by a TaskListItemMarker block: its checkbox text is the start of the item's
    // first line, not a block of its own — the next block glues directly onto it (the
    // full render emits the marker inline, see EntityVisitor.visit(TaskListItemMarker)).
    // The glue separator is a space, not "": this visitor's output() trims the marker
    // render's own trailing space away, where the string formats keep it — the space
    // restores the one the full render's marker emission carries
    var glueNext = false
    while (node != null) {
        // The budget check precedes the render: once parts fill the budget no block can
        // be placed, and rendering the next one (arbitrarily large) just to discard it is
        // wasted work. Blank skips never touch parts
        val separator =
            when {
                parts.isEmpty() -> ""
                glueNext -> " "
                else -> "\n\n"
            }
        val remaining = budget - parts.length - separator.length
        if (remaining <= 0) break

        val block = renderEntityBlock(node, depth, options)
        if (block.text.isBlank()) {
            glueNext = node is TaskListItemMarker
            node = node.next
            continue
        }
        if (block.text.length <= remaining) {
            parts.append(separator, block)
            glueNext = node is TaskListItemMarker
            node = node.next
        } else {
            val shrunk = shrinkEntity(node, remaining, depth, retriable, options)
            if (shrunk.text.isNotEmpty()) {
                parts.append(separator, shrunk)
            }
            break
        }
    }
    return parts.build()
}

/**
 * Truncates one block node on its own — the non-Document entry of the entities render.
 * Mirrors renderEntityBlocks' loop body for a lone block but never walks node.next: the
 * node's siblings belong to the caller's document.
 */
internal fun truncateEntityBlock(
    node: Node,
    budget: Int,
    options: RenderOptions,
): EntityText {
    if (budget <= 0) return EntityText.EMPTY
    val block = renderEntityBlock(node, 0, options)
    if (block.text.length <= budget) return block
    return shrinkEntity(node, budget, 0, retriable = true, options)
}

private fun shrinkEntity(
    node: Node,
    budget: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): EntityText =
    when {
        node.isFenceWrappedBlock() -> {
            shrinkEntityFence(node, budget, depth, options)
        }

        node is BulletList || node is OrderedList -> {
            shrinkEntityList(node, budget, depth, retriable, options)
        }

        node is BlockQuote -> {
            shrinkEntityQuote(node, budget, depth, retriable, options)
        }

        node is FootnoteDefinition -> {
            // See the MarkdownV2 twin: the label prefix glues onto the shrunken child
            // chain, entities riding along with the blocks
            val prefix = "[^${node.label}]: "
            if (prefix.length >= budget) {
                EntityText.EMPTY
            } else {
                val parts = EntityBuilder()
                parts.appendVerbatim(prefix)
                parts.append("", renderEntityBlocks(node.firstChild, budget - prefix.length, depth, retriable, options))
                parts.build()
            }
        }

        node is Heading -> {
            shrinkEntityParagraph(node, budget, options, plainHeadingPrefixOf(node))
        }

        node is Paragraph -> {
            shrinkEntityParagraph(node, budget, options, "")
        }

        else -> {
            EntityText.EMPTY
        }
    }

// The four fence-wrapped kinds render to one PRE range over their content: content lines
// survive whole while they fit, and the range covers exactly the kept text (the language
// rides along on the entity). Entities mode has no wrapper text to budget for — the
// whole budget serves content
private fun shrinkEntityFence(
    node: Node,
    budget: Int,
    depth: Int,
    options: RenderOptions,
): EntityText {
    val rendered = renderEntityBlock(node, depth, options)
    if (rendered.text.isEmpty()) return EntityText.EMPTY

    val sb = StringBuilder()
    // A blank first line appends nothing yet still counts as placed: comparing against
    // an empty builder cannot tell "no line yet" from "an empty line just glued", and
    // the newline joining the NEXT line would be lost
    var hasLine = false
    for (line in rendered.text.split('\n')) {
        val joiner = if (hasLine) "\n" else ""
        if (sb.length + joiner.length + line.length > budget) break
        sb.append(joiner).append(line)
        hasLine = true
    }
    return preEntityOf(sb.toString(), node)
}

// Wraps [text] as a PRE range, carrying the fence's sanitized language when [node] is a
// fenced code block that names one
internal fun preEntityOf(
    text: String,
    node: Node,
): EntityText {
    if (text.isEmpty()) return EntityText.EMPTY
    val language = (node as? FencedCodeBlock)?.let(::htmlLanguageOf)?.ifEmpty { null }
    return EntityText(text, listOf(MessageEntity(MessageEntityType.PRE, 0, text.length, language = language)))
}

// Truncation stops at the first item that does not fit — later items are dropped whole,
// keeping the surviving prefix's numbering contiguous
private fun shrinkEntityList(
    list: Node,
    budget: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): EntityText {
    val parts = EntityBuilder()
    forEachEntityListItem(list, depth, options) { item, prefix, body ->
        val separator = if (parts.isEmpty()) "" else "\n"
        val remaining = budget - parts.length - separator.length
        val content = fitEntityListItem(item, body, prefix, remaining, depth, retriable, options)
        if (content == null) {
            false
        } else {
            parts.append(separator, content)
            true
        }
    }
    return parts.build()
}

/**
 * Fits the item's pre-rendered [body] into [remaining] by its actual length after
 * prefixing; returns null when it does not fit. The continuation-indent overhead added
 * by applyEntityPrefix is outside renderEntityBlocks' budget, so a shrunk result may
 * still overflow it — dropping lines is not a viable fallback: ranges closed on a
 * continuation line would be lost with the line. Instead re-shrink with the budget
 * tightened by the overflow; the loop converges (a single line always fits).
 *
 * Re-shrinking is allowed only at the top level ([retriable]); a nested level that does
 * not fit degrades outright to plain text with no entities.
 */
private fun fitEntityListItem(
    item: ListItem,
    body: EntityText,
    prefix: String,
    remaining: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): EntityText? {
    if (prefix.length >= remaining) return null
    val full = applyEntityPrefix(prefix, body)
    if (full.text.length <= remaining) return full
    if (!retriable) return plainEntityListItem(item, prefix, remaining)

    var budget = remaining - prefix.length
    while (budget > 0) {
        val shrunk = trimTrailingNewlines(renderEntityBlocks(item.firstChild, budget, depth + 1, retriable = false, options))
        if (shrunk.text.isEmpty()) return null
        val prefixed = applyEntityPrefix(prefix, shrunk)
        if (prefixed.text.length <= remaining) return prefixed
        budget -= prefixed.text.length - remaining
    }
    return null
}

// Fallback for a nested (non-retriable) item that does not fit: keep the marker, keep as
// much of the item's plain text as the budget allows — formatting drops, text stays
private fun plainEntityListItem(
    item: ListItem,
    prefix: String,
    remaining: Int,
): EntityText {
    val plain = StringBuilder()
    appendPlainText(plain, item)
    val sb = StringBuilder(prefix)
    appendPlainTruncated(sb, plain.toString().trim(), remaining - prefix.length)
    return EntityText(sb.toString(), emptyList())
}

// The quote's surviving children pack under one blockquote range covering exactly the
// packed text; the wrapper costs no characters, so the arithmetic is exact the first
// time — no re-shrink retry
private fun shrinkEntityQuote(
    quote: BlockQuote,
    budget: Int,
    depth: Int,
    retriable: Boolean,
    options: RenderOptions,
): EntityText {
    val parts = EntityBuilder()
    var node = quote.firstChild
    while (node != null) {
        val separator = if (parts.isEmpty()) "" else "\n\n"
        val remaining = budget - parts.length - separator.length
        if (remaining <= 0) break

        val rendered = renderEntityBlock(node, depth + 1, options)
        if (rendered.text.isBlank()) {
            node = node.next
            continue
        }
        if (rendered.text.length <= remaining) {
            parts.append(separator, rendered)
            node = node.next
        } else {
            val shrunk = shrinkEntity(node, remaining, depth + 1, retriable = false, options)
            // A nested level too small to shrink at all degrades to plain text — the
            // same trade plainEntityListItem makes; returning empty dropped content
            // that still fit
            val kept =
                if (shrunk.text.isEmpty()) {
                    val plain = StringBuilder()
                    appendPlainText(plain, node)
                    val sb = StringBuilder()
                    appendPlainTruncated(sb, plain.toString().trim(), remaining)
                    EntityText(sb.toString(), emptyList())
                } else {
                    shrunk
                }
            if (kept.text.isNotEmpty()) {
                parts.append(separator, kept)
            }
            break
        }
    }
    val text = parts.build()
    if (text.text.isEmpty()) return EntityText.EMPTY
    // The wrapper appends after the nested ranges — the close order the EntityText
    // contract documents (nested ranges before the ranges containing them), the same
    // order visit(blockQuote) and the chunking re-assembly emit
    return EntityText(
        text.text,
        text.entities + MessageEntity(blockquoteTypeOf(quote), 0, text.text.length),
    )
}

private fun shrinkEntityParagraph(
    node: Node,
    budget: Int,
    options: RenderOptions,
    prefix: String,
): EntityText {
    if (prefix.length >= budget) return EntityText.EMPTY

    val parts = EntityBuilder()
    parts.appendVerbatim(prefix)
    val group = mutableListOf<Node>()
    var child = node.firstChild
    while (child != null) {
        if (child is SoftLineBreak || child is HardLineBreak) {
            val fits = appendEntityGroup(parts, group, budget, options)
            group.clear()
            if (!fits) break
            if (parts.length > 0 && parts.length + 1 <= budget) {
                parts.appendVerbatim("\n")
            }
        } else {
            group += child
        }
        child = child.next
    }
    appendEntityGroup(parts, group, budget, options)
    return parts.build()
}

// Appends the group's rendered line when it fits within [budget]; otherwise fills the
// remainder with plain text of the same group, its formatting dropped — a partially cut
// range could point past the text, plain text with no entities never does
private fun appendEntityGroup(
    parts: EntityBuilder,
    group: List<Node>,
    budget: Int,
    options: RenderOptions,
): Boolean {
    if (group.isEmpty()) return true

    val line = renderEntityInlineGroup(group, options)
    if (parts.length + line.text.length <= budget) {
        parts.append("", line)
        return true
    }

    val remaining = budget - parts.length
    if (remaining > 0) {
        val sb = StringBuilder()
        appendPlainTruncated(sb, plainText(group), remaining)
        parts.appendVerbatim(sb.toString())
    }
    return false
}
