package io.github.kamiiroawase.markdownv2

import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak

/**
 * Iterative plain-text extraction (explicit stack, no recursion that grows with node nesting
 * depth), shared by table cells, inline-group truncation fallbacks and deep-structure
 * flattening. Block-level siblings of a flattened subtree separate with the same blank
 * line the rendered formats place between blocks ("\n\n"), and line-level siblings —
 * list items, table rows and cells — with a single newline: without those separators the
 * truncation and chunking fallback paths welded adjacent blocks' words together
 * ("alphabeta"), corrupting the text the losslessness guarantee promises to keep. A
 * link or image renders as `label (url)` — mirroring deformat's link handling
 * so the flattening paths never silently drop a link target — and one whose children
 * contribute no visible text falls back to its destination, mirroring the renderer's
 * empty-label degradation to the bare escaped URL (without it the truncation/chunking
 * fallbacks would drop such content entirely). Two carve-outs keep the flattened text at the
 * text Telegram actually displays: a label that IS the destination (an autolink, or an
 * `[u](u)` spelling) never gains the suffix — the URL is already the visible text, and the
 * suffix would duplicate it — and the custom emoji image spelling (`tg://emoji?id=…`)
 * never carries the ` (url)` suffix beside a non-blank fallback (the fallback IS the
 * visible text; the internal URL never surfaces there, the line deformat draws too),
 * while a blank fallback still degrades to the bare destination like every other blank
 * label (the renderer's own empty-fallback trade).
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
            is PlainBlockSeparator -> {
                // Only a boundary with output on its left separates anything, and the
                // trailing newline run normalizes away first: a code-block literal ends
                // with its own newline, and a no-content sibling (a comment-only block)
                // leaves two adjacent boundaries — re-emitting the separator over
                // whatever run is already there would stack blank lines the rendered
                // formats never show
                if (sb.isNotEmpty()) {
                    var length = sb.length
                    while (length > 0 && sb[length - 1] == '\n') length--
                    sb.setLength(length)
                    sb.append(current.separator)
                }
            }

            is UrlFallback -> {
                openLinks--
                // The label region as appended: the children were processed before this
                // marker popped, so everything from the push position is their text
                val label = sb.substring(current.start)
                if (label.isBlank()) {
                    sb.setLength(current.start)
                    sb.append(current.url)
                } else if (current.suffixWithUrl && openLinks == 0 && label != current.url) {
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
                pushBlockChildrenReversed(current as Node, stack)
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
                    stack.addLast(UrlFallback(destination, sb.length, suffixWithUrl = true))
                    openLinks++
                }
                pushChildrenReversed(current, stack)
            }

            is Image -> {
                val destination = current.destination
                // The Bot API's custom emoji spelling (tg://emoji?id=…, see
                // tgEmojiUrlIdOf): the renderer emits the fallback as a custom_emoji range
                // and never displays the destination beside it, so the plain view never
                // carries the " (url)" suffix either — but a fallback that renders no
                // visible text still degrades to the bare destination, the exact trade
                // visit(image) makes there (the rewind drops the whole custom-emoji shape
                // and the URL is the only text left). Any other destination — an invalid
                // id the renderer itself treats as an ordinary image included — keeps
                // the ordinary image handling
                if (!destination.isNullOrEmpty() && tgEmojiUrlIdOf(destination) == null) {
                    stack.addLast(UrlFallback(destination, sb.length, suffixWithUrl = true))
                    openLinks++
                } else if (!destination.isNullOrEmpty()) {
                    stack.addLast(UrlFallback(destination, sb.length, suffixWithUrl = false))
                    openLinks++
                }
                pushChildrenReversed(current, stack)
            }

            else -> {
                pushBlockChildrenReversed(current as Node, stack)
            }
        }
    }
}

// Popped right after a Link/Image's own children (pushed beneath them): when they added no
// visible text, the extraction rewinds to the pre-link position and falls back to the URL.
// suffixWithUrl: whether a non-blank label gains the " (url)" suffix — links and ordinary
// images do; the custom emoji spelling does not (the fallback alone is the visible text)
private class UrlFallback(
    val url: String,
    val start: Int,
    val suffixWithUrl: Boolean,
)

// Popped between two adjacent block-level (or line-level) children a container pushed —
// the separator of the boundary (see appendPlainText's header); carries its own string
// because block boundaries and line boundaries take different ones
private class PlainBlockSeparator(
    val separator: String,
)

// Multi-node convenience over [appendPlainText] for the truncation and chunking fallback
// paths, which flatten whole nodes or node groups; the nodes are an inline group's atoms,
// so no boundary separates them (the space between them is their own leading/trailing
// whitespace, content the losslessness guarantee keeps)
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

// The container variant: a separator sentinel rides between adjacent children whose
// boundary is block-level or line-level, popping after the left child and before the
// right one. Inline-only boundaries (emphasis siblings inside a paragraph, say) keep the
// old separator-free join — the whitespace between inline atoms is their own content.
// Unknown custom nodes from third-party extensions classify as neither and join
// separator-free, the pre-separator behavior, so extension content never gains
// Telegram-shaped separators by accident
private fun pushBlockChildrenReversed(
    node: Node,
    stack: ArrayDeque<Any>,
) {
    val children = mutableListOf<Node>()
    var child = node.firstChild
    while (child != null) {
        children += child
        child = child.next
    }
    for (index in children.indices.reversed()) {
        stack.addLast(children[index])
        if (index > 0) {
            val separator = plainSeparatorBetween(children[index - 1], children[index])
            if (separator != null) stack.addLast(PlainBlockSeparator(separator))
        }
    }
}

// Block boundaries take the blank line the rendered formats place between blocks; line
// boundaries (list items, table rows and cells) take the single newline those structures
// render their lines with — the closest a structure-less plain view comes to their shape.
// Either side being block-level makes the boundary block-level: a paragraph behind a list
// item must not take the item's line join
private fun plainSeparatorBetween(
    left: Node,
    right: Node,
): String? =
    when {
        isPlainBlockBoundary(left) || isPlainBlockBoundary(right) -> "\n\n"
        isPlainLineBoundary(left) || isPlainLineBoundary(right) -> "\n"
        else -> null
    }

private fun isPlainBlockBoundary(node: Node): Boolean =
    node is Paragraph ||
        node is Heading ||
        node is BlockQuote ||
        node is BulletList ||
        node is OrderedList ||
        node is ThematicBreak ||
        node is FencedCodeBlock ||
        node is IndentedCodeBlock ||
        node is HtmlBlock ||
        node is TableBlock ||
        node is FootnoteDefinition

private fun isPlainLineBoundary(node: Node): Boolean =
    node is ListItem || node is TableRow || node is TableCell || node is TableHead || node is TableBody
