package io.github.kamiiroawase.markdownv2

import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BulletList
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.parser.PostProcessor
import org.nibor.autolink.LinkExtractor
import org.nibor.autolink.LinkSpan
import org.nibor.autolink.LinkType

/**
 * Iterative stand-ins for the official task-list and autolink extensions, installed by
 * [MarkdownV2.defaultExtensions]. The upstream extensions do their post-processing with
 * recursive node visitors, which stack-overflows on deeply nested input — measured on
 * mingwX64 at a 1500-level quote chain, while this library's own contract is that
 * pathological input flattens or degrades, never crashes. These versions walk the same
 * tree with an explicit stack and produce the same nodes the renderers already handle
 * ([TaskListItemMarker], core [Link]), matching the upstream behavior point by point:
 * a task marker binds only at the head of a list item's first paragraph and needs
 * whitespace after the bracket pair (upper-case X counts as checked); autolink covers
 * URL, www and email spans (email mailto:-prefixed, www http://-prefixed — a www
 * autolink is an http autolink per GFM §6.9, and the destination without the scheme
 * resolves nowhere) everywhere except inside a link label, where the outer link must
 * stay single-level.
 */
internal object IterativeTaskListItemsExtension : Parser.ParserExtension {
    override fun extend(parserBuilder: Parser.Builder) {
        parserBuilder.postProcessor(IterativeTaskListPostProcessor)
    }
}

internal object IterativeAutolinkExtension : Parser.ParserExtension {
    override fun extend(parserBuilder: Parser.Builder) {
        parserBuilder.postProcessor(IterativeAutolinkPostProcessor)
    }
}

private object IterativeTaskListPostProcessor : PostProcessor {
    override fun process(node: Node): Node {
        val stack = ArrayDeque<Node>()
        stack.addLast(node)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current is BulletList || current is OrderedList) {
                var item = current.firstChild
                while (item != null) {
                    if (item is ListItem) {
                        markTaskMarker(item)
                    }
                    item = item.next
                }
            }
            pushChildrenForWalk(current, stack)
        }
        return node
    }

    // GitHub semantics: the marker binds only at the head of the item — the first block
    // is a paragraph whose first inline is text starting with "[ ]"/"[x]"/"[X]" followed
    // by a space or tab. The prefix strips into a TaskListItemMarker inserted ahead of
    // the paragraph (the same AST shape the upstream extension emits)
    private fun markTaskMarker(item: ListItem) {
        val paragraph = item.firstChild as? Paragraph ?: return
        val text = paragraph.firstChild as? Text ?: return
        val literal = text.literal
        if (literal.length < 4) return
        val marker = literal.take(3)
        val checked =
            when {
                marker == "[x]" || marker == "[X]" -> true

                // Upstream's bracket class is [xX\s]: any single whitespace inside the
                // brackets is an unchecked box, not just the plain space — "- [\t] x"
                // parses as a task item there, so it must here too. Java's \s is ASCII
                // only, so the check is too: an em space stays literal text, exactly
                // what upstream does with it (Kotlin's isWhitespace() would admit it)
                marker[0] == '[' && marker[2] == ']' && isAsciiWhitespace(marker[1]) -> false

                else -> return
            }
        val after = literal[3]
        // Upstream's \s+ is the full ASCII whitespace class: a vertical tab or form feed
        // after the bracket pair still binds the marker ("[x]\u000Bhello" is a task item
        // there), so the check is the same isAsciiWhitespace the greedy skip below runs —
        // not just the space/tab pair (a newline or carriage return cannot reach a Text
        // literal: the parser splits lines before it ever forms one)
        if (!isAsciiWhitespace(after)) return
        // Upstream's \s+ is greedy: every whitespace run after the bracket pair belongs
        // to the marker and group(2) starts at the first non-whitespace character, so
        // "- [x]␣␣␣hello" keeps no extra leading spaces (substring(4) used to leave
        // them in the literal, drifting from the pinned upstream parity)
        var contentStart = 4
        while (contentStart < literal.length && isAsciiWhitespace(literal[contentStart])) contentStart++
        text.literal = literal.substring(contentStart)
        paragraph.insertBefore(TaskListItemMarker(checked))
    }
}

// The ASCII \s class of upstream's regexes: space, tab, newline, vertical tab, form
// feed, carriage return — nothing Unicode
private fun isAsciiWhitespace(char: Char): Boolean =
    char == ' ' || char == '\t' || char == '\n' || char == '\u000B' || char == '\u000C' || char == '\r'

private object IterativeAutolinkPostProcessor : PostProcessor {
    private val extractor =
        LinkExtractor
            .builder()
            .linkTypes(setOf(LinkType.URL, LinkType.WWW, LinkType.EMAIL))
            .build()

    override fun process(node: Node): Node {
        val stack = ArrayDeque<Node>()
        stack.addLast(node)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current is Link) {
                // Inside a link label no second level may open — the outer link stays
                // single-level (the upstream visitor skips the same subtree)
                continue
            }
            if (current is Text) {
                autolinkText(current)
            }
            pushChildrenForWalk(current, stack)
        }
        return node
    }

    // extractSpans covers the whole literal with alternating plain and link spans, so
    // the text node splices into the piece sequence and unlinks — no prefix splitting
    // to track by hand
    private fun autolinkText(text: Text) {
        val literal = text.literal
        val spans = extractor.extractSpans(literal).toList()
        if (spans.none { it is LinkSpan }) return

        var anchor: Node = text
        for (span in spans) {
            val content = literal.substring(span.beginIndex, span.endIndex)
            val piece: Node =
                when (span) {
                    is LinkSpan -> {
                        val destination =
                            when (span.type) {
                                LinkType.EMAIL -> "mailto:$content"
                                LinkType.WWW -> "http://$content"
                                else -> content
                            }
                        Link(destination, null).apply { appendChild(Text(content)) }
                    }

                    else -> {
                        if (content.isEmpty()) continue
                        Text(content)
                    }
                }
            anchor.insertAfter(piece)
            anchor = piece
        }
        text.unlink()
    }
}

// Collects the children before walking them: the autolink pass splices nodes into the
// sibling chain while iterating, and a pre-collected reference list stays stable
private fun pushChildrenForWalk(
    node: Node,
    stack: ArrayDeque<Node>,
) {
    val children = mutableListOf<Node>()
    var child = node.firstChild
    while (child != null) {
        children += child
        child = child.next
    }
    children.asReversed().forEach(stack::addLast)
}
