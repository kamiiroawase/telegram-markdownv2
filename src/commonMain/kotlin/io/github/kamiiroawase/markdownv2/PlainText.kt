package io.github.kamiiroawase.markdownv2

import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text

/**
 * Iterative plain-text extraction (explicit stack, no recursion that grows with node nesting
 * depth), shared by table cells, inline-group truncation fallbacks and deep-structure
 * flattening. A link or image renders as `label (url)` — mirroring deformat's link handling
 * so the flattening paths never silently drop a link target — and one whose children
 * contribute no visible text falls back to its destination, mirroring the renderer's
 * empty-label degradation to the bare escaped URL (without it the truncation/chunking
 * fallbacks would drop such content entirely). Two carve-outs keep the flattened text at the
 * text Telegram actually displays: a label that IS the destination (an autolink, or an
 * `[u](u)` spelling) never gains the suffix — the URL is already the visible text, and the
 * suffix would duplicate it — and the custom emoji image spelling (`tg://emoji?id=…`)
 * never surfaces its internal URL at all (deformat draws both of those lines too).
 */
internal fun appendPlainText(
    sb: StringBuilder,
    node: Node,
) {
    val stack = ArrayDeque<Any>()
    stack.addLast(node)
    // Depth of open link/image scopes: CommonMark cannot nest them, so this stays 0/1 in
    // practice — hand-built ASTs may nest, and there only the outermost target survives,
    // matching the renderer's nested-link degradation to label text
    var openLinks = 0
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        when (current) {
            is UrlFallback -> {
                openLinks--
                // The label region as appended: the children were processed before this
                // marker popped, so everything from the push position is their text
                val label = sb.substring(current.start)
                if (label.isBlank()) {
                    sb.setLength(current.start)
                    sb.append(current.url)
                } else if (openLinks == 0 && label != current.url) {
                    // A label that IS the destination (an autolink, or an [u](u) spelling)
                    // already shows the URL as its visible text — the suffix would
                    // duplicate it (deformat's link handling draws the same line)
                    sb.append(" (").append(current.url).append(')')
                }
            }

            is Text -> {
                sb.append(current.literal)
            }

            is Code -> {
                sb.append(current.literal)
            }

            is SoftLineBreak, is HardLineBreak -> {
                sb.append(' ')
            }

            is HtmlInline -> {
                // Comments, declarations and processing instructions carry no visible
                // text — the same "<!"/"<?" prefixes the renderer drops
                // (renderHtmlInline); every other tag stays literal
                val literal = current.literal.trimStart()
                if (!literal.startsWith("<!") && !literal.startsWith("<?")) {
                    sb.append(current.literal)
                }
            }

            is TaskListItemMarker -> {
                // The checkbox marker is visible text in every output format — the
                // fallback paths must not drop it from an item's plain view
                sb.append(if (current.isChecked) "[x] " else "[ ] ")
            }

            is FootnoteReference -> {
                sb.append("[^${current.label}]")
            }

            is FootnoteDefinition -> {
                sb.append("[^${current.label}]: ")
                pushChildrenReversed(current as Node, stack)
            }

            is FencedCodeBlock -> {
                sb.append(current.literal.orEmpty())
            }

            is IndentedCodeBlock -> {
                sb.append(current.literal)
            }

            is HtmlBlock -> {
                // Same leaf-with-literal shape as the code blocks: without this branch the
                // plain-text fallbacks (truncation, chunking, deep flattening) would drop
                // the block's content entirely. Comment/declaration/PI blocks skip like
                // visit(htmlBlock) does, so the fallback cannot resurrect markup the
                // renderer itself never emits
                val literal = current.literal.orEmpty().trim()
                if (literal.isNotEmpty() && !literal.startsWith("<!") && !literal.startsWith("<?")) {
                    sb.append(current.literal.orEmpty())
                }
            }

            is Link -> {
                val destination = current.destination
                if (!destination.isNullOrEmpty()) {
                    stack.addLast(UrlFallback(destination, sb.length))
                    openLinks++
                }
                pushChildrenReversed(current, stack)
            }

            is Image -> {
                val destination = current.destination
                // The Bot API's custom emoji spelling (tg://emoji?id=…, see
                // tgEmojiUrlIdOf): the renderer emits the fallback as a custom_emoji range
                // and never displays the destination, so the plain view must not surface
                // the internal URL either — no " (url)" suffix, and a blank fallback
                // renders nothing at all (the renderer's empty-entity degradation; the
                // deformat twin emits the label alone too). Any other destination — an
                // invalid id the renderer itself treats as an ordinary image included —
                // keeps the ordinary image handling
                if (!destination.isNullOrEmpty() && tgEmojiUrlIdOf(destination) == null) {
                    stack.addLast(UrlFallback(destination, sb.length))
                    openLinks++
                }
                pushChildrenReversed(current, stack)
            }

            else -> {
                pushChildrenReversed(current as Node, stack)
            }
        }
    }
}

// Popped right after a Link/Image's own children (pushed beneath them): when they added no
// visible text, the extraction rewinds to the pre-link position and falls back to the URL
private class UrlFallback(
    val url: String,
    val start: Int,
)

// Multi-node convenience over [appendPlainText] for the truncation and chunking fallback
// paths, which flatten whole nodes or node groups
internal fun plainText(nodes: List<Node>): String {
    val sb = StringBuilder()
    nodes.forEach { appendPlainText(sb, it) }
    return sb.toString()
}

private fun pushChildrenReversed(
    node: Node,
    stack: ArrayDeque<Any>,
) {
    val children = mutableListOf<Node>()
    var child = node.firstChild
    while (child != null) {
        children += child
        child = child.next
    }
    // Push children reversed so they pop off the stack in document order
    children.asReversed().forEach(stack::addLast)
}
