package com.github.kamiiroawase.markdownv2

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
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
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak

// Maximum nesting depth for structural rendering: deeper quotes/lists/emphasis flatten to
// plain text, preventing pathological input (e.g. thousand-level quote chains) from
// exhausting the call stack
private const val MAX_RENDER_DEPTH = 100

// Stack sentinel: a nested anchor literalized while an outer link is open; its closing tag
// is treated literally as well
private const val NESTED_LITERAL_ANCHOR = "nested-literal"

// Linear-time by construction: the lazy [^>]*? has a single stopping point (>) and no
// nested quantifiers; the regexLinear* regression tests pin this down at 100k scale
private val HTML_TAG = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*?)(/?)>""")

// Entity decoding for URL attribute values: the five XML predefined entities plus decimal
// and hex character references. The full HTML5 named-entity table (~2k names) is
// deliberately out of scope for URLs; anything unrecognized stays literal. Scanned
// manually — no regex — so HTML_TAG remains the library's only regex
private val NAMED_ENTITIES =
    mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
    )

/**
 * Renders the full AST to Telegram MarkdownV2. Unclosed HTML emphasis/anchors are completed
 * LIFO at output time ([output]); tables degrade to aligned text via [renderTableLines].
 */
internal class Visitor(
    private val depth: Int = 0,
    private val options: RenderOptions = RenderOptions(),
) : AbstractVisitor() {
    private val sb = StringBuilder()

    private val openLinkUrls = ArrayDeque<String>()

    // Depth of open Markdown link entities (Link and Image both render as links); HTML
    // anchors are tracked separately in [openLinkUrls]. Telegram links cannot nest, so
    // while any link entity is open, link-like constructs degrade (see visit(link),
    // visit(image) and renderAnchor)
    private var openMarkdownLinks = 0

    private val openEmphasis = ArrayDeque<String>()

    // Nesting depth of open HTML code entities (<code>/<kbd>/<samp>/<tt> render as `
    // entities). Inside a code entity Telegram interprets only \` and \\, so Text children
    // switch to code escaping (see visit(text)); Markdown Code nodes always escape that way
    private var htmlCodeDepth = 0

    // Nesting depth of inline emphasis (emphasis recurses within the same visitor; deep
    // nesting flattens)
    private var inlineDepth = 0

    /**
     * Returns the rendered text, completing any unclosed entities. Emphasis closers come
     * before link closers: emphasis typically opens inside the link text (after `[`), so
     * closing it first keeps the nesting valid; the reverse shape (anchor opened inside
     * unclosed emphasis) yields crossed entities Telegram rejects — a documented
     * limitation. Emphasis closers are emitted innermost-first (reversed); links need no
     * reversal because at most one real link is ever open (nested anchors are literalized).
     * trimEnd keeps the appended closers clear of trailing block separators.
     */
    fun output(): String =
        if (openLinkUrls.isEmpty() && openEmphasis.isEmpty()) {
            sb.toString()
        } else {
            sb.toString().trimEnd() +
                openEmphasis.reversed().joinToString("") { it } +
                openLinkUrls.joinToString("") { url ->
                    if (url.isEmpty() || url == NESTED_LITERAL_ANCHOR) "" else "](${escapeUrl(url)})"
                }
        }

    override fun visit(text: Text) {
        // Inside an HTML code entity the full 19-char escaping would leak backslashes:
        // Telegram renders \x (x neither ` nor \) literally inside code entities
        sb.append(if (htmlCodeDepth > 0) escapeCode(text.literal) else escapeText(text.literal))
    }

    override fun visit(document: Document) {
        visitChildren(document)
    }

    override fun visit(paragraph: Paragraph) {
        visitChildren(paragraph)
        sb.append("\n\n")
    }

    // MarkdownV2 has no heading entity: the level is spelled out as escaped hashes
    override fun visit(heading: Heading) {
        sb.append(escapeText("#".repeat(heading.level))).append(' ')
        visitChildren(heading)
        sb.append("\n\n")
    }

    override fun visit(blockQuote: BlockQuote) {
        val body = renderChild(blockQuote)
        // Blank lines keep a bare ">": an empty line would terminate the quote entity
        sb.append(
            body.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" },
        )
        sb.append("\n\n")
    }

    override fun visit(bulletList: BulletList) {
        renderList(bulletList) { "• " }
    }

    override fun visit(orderedList: OrderedList) {
        var number = orderedList.markerStartNumber ?: 1
        renderList(orderedList) { "${number++}\\. " }
    }

    // MarkdownV2 has no thematic break entity; em dashes render cleanly without escaping,
    // unlike '-' which would need a backslash per dash
    override fun visit(thematicBreak: ThematicBreak) {
        sb.append(escapeText("———")).append("\n\n")
    }

    override fun visit(fencedCodeBlock: FencedCodeBlock) {
        // literal and info are String? in commonmark-kotlin, so orEmpty is required here
        // and in visit(htmlBlock); Text/Code/HtmlInline literals are declared non-null
        // upstream and are used directly
        //
        // The info string is untrusted: keep only letters/digits/-/+ so it can neither
        // forge the fence (backticks, newlines) nor inject entities; 32 chars is plenty
        val language =
            fencedCodeBlock.info
                .orEmpty()
                .trim()
                .filter { it.isLetterOrDigit() || it == '-' || it == '+' }
                .take(32)
        sb.append("```").append(language).append('\n')
        sb.append(escapeCode(fencedCodeBlock.literal.orEmpty().trimEnd('\n')))
        sb.append("\n```\n\n")
    }

    override fun visit(indentedCodeBlock: IndentedCodeBlock) {
        sb.append("```\n")
        sb.append(escapeCode(indentedCodeBlock.literal.trimEnd('\n')))
        sb.append("\n```\n\n")
    }

    override fun visit(code: Code) {
        sb.append('`').append(escapeCode(code.literal)).append('`')
    }

    override fun visit(emphasis: Emphasis) {
        if (!enterInline()) {
            appendPlainText(sb, emphasis)
            return
        }
        val baseDepth = openEmphasis.size
        openEmphasis.addLast("_")
        sb.append('_')
        visitChildren(emphasis)
        closeNestedTo(baseDepth)
        sb.append('_')
        openEmphasis.removeLast()
        exitInline()
    }

    override fun visit(strongEmphasis: StrongEmphasis) {
        if (!enterInline()) {
            appendPlainText(sb, strongEmphasis)
            return
        }
        val baseDepth = openEmphasis.size
        openEmphasis.addLast("*")
        sb.append('*')
        visitChildren(strongEmphasis)
        closeNestedTo(baseDepth)
        sb.append('*')
        openEmphasis.removeLast()
        exitInline()
    }

    override fun visit(customNode: CustomNode) {
        when (customNode) {
            is Strikethrough -> {
                if (!enterInline()) {
                    appendPlainText(sb, customNode)
                    return
                }
                val baseDepth = openEmphasis.size
                openEmphasis.addLast("~")
                sb.append('~')
                visitChildren(customNode)
                closeNestedTo(baseDepth)
                sb.append('~')
                openEmphasis.removeLast()
                exitInline()
            }

            else -> {
                super.visit(customNode)
            }
        }
    }

    /**
     * When an emphasis node closes, first complete any unclosed HTML emphasis inside it
     * (happens when the source lacks closing tags, or due to upstream parsing defects).
     * Its own marker is located by stack depth — same-kind markers can overlap (e.g. _
     * inside _), and matching by marker value would misalign; after completion the stack
     * should hold exactly its own marker.
     */
    private fun closeNestedTo(baseDepth: Int) {
        while (openEmphasis.size > baseDepth + 1) {
            val nested = openEmphasis.removeLastOrNull() ?: break
            // Completing an unclosed HTML code entity ends its scope: Text after the
            // emphasis boundary escapes as plain text again
            if (nested == "`") htmlCodeDepth--
            sb.append(nested)
        }
    }

    override fun visit(customBlock: CustomBlock) {
        if (customBlock is TableBlock) {
            visitTable(customBlock)
        } else {
            super.visit(customBlock)
        }
    }

    private fun visitTable(table: TableBlock) {
        val lines = renderTableLines(table, options)
        if (lines.isEmpty()) {
            return
        }

        sb.append("```\n")
        sb.append(escapeCode(lines.joinToString("\n")))
        sb.append("\n```\n\n")
    }

    override fun visit(link: Link) {
        val destination = link.destination.orEmpty()
        // Telegram rejects link entities with an empty URL, and links cannot nest (a nested
        // Link cannot come out of CommonMark parsing; the guard covers hand-built ASTs) —
        // degrade to the link text alone in both cases
        if (destination.isEmpty() || linkEntityOpen()) {
            visitChildren(link)
            return
        }
        val openBracket = sb.length
        sb.append('[')
        openMarkdownLinks++
        val labelStart = sb.length
        visitChildren(link)
        openMarkdownLinks--
        // Telegram also rejects link entities with empty text: a label that renders to
        // nothing (no children, or children like a lone HTML comment) degrades to the bare
        // escaped URL. Rewinding is safe — inside a label every pushed entity marker is
        // accompanied by output, so an empty label left nothing open on the stacks
        if (sb.length == labelStart) {
            sb.setLength(openBracket)
            sb.append(escapeText(destination))
            return
        }
        sb.append("](").append(escapeUrl(destination)).append(')')
    }

    // Telegram MarkdownV2 has no images in message text: degrade to a plain link on the
    // alt text; an alt that renders to nothing degrades to the bare escaped URL (see
    // visit(link)). Nested inside another link (the README badge shape
    // [![alt](img)](target)) only the alt text survives, keeping the outer link
    // single-level — Telegram links cannot nest
    override fun visit(image: Image) {
        val destination = image.destination.orEmpty()
        if (destination.isEmpty() || linkEntityOpen()) {
            visitChildren(image)
            return
        }
        val openBracket = sb.length
        sb.append('[')
        openMarkdownLinks++
        val labelStart = sb.length
        visitChildren(image)
        openMarkdownLinks--
        if (sb.length == labelStart) {
            sb.setLength(openBracket)
            sb.append(escapeText(destination))
            return
        }
        sb.append("](").append(escapeUrl(destination)).append(')')
    }

    // Telegram message text renders both break kinds as plain newlines
    override fun visit(softLineBreak: SoftLineBreak) {
        sb.append('\n')
    }

    override fun visit(hardLineBreak: HardLineBreak) {
        sb.append('\n')
    }

    override fun visit(htmlBlock: HtmlBlock) {
        val literal = htmlBlock.literal.orEmpty().trim()
        if (literal.isEmpty() || literal.startsWith("<!--") ||
            literal.startsWith("<!") || literal.startsWith("<?")
        ) {
            return
        }

        sb.append("```\n").append(escapeCode(literal)).append("\n```\n\n")
    }

    override fun visit(htmlInline: HtmlInline) {
        sb.append(renderHtmlInline(htmlInline.literal))
    }

    /**
     * A closing tag emits its entity marker only when it matches the open tag on top of
     * the stack, otherwise it is escaped literally — an orphan `</b>` emitting a bare `*`
     * would create an unpaired entity Telegram rejects. Anchors behave the same; an `<a>`
     * without href pushes the empty sentinel and its closing only pops, emitting no link.
     */
    private fun renderHtmlInline(literal: String): String {
        val tag = literal.trim()
        if (tag.startsWith("<!--") || tag.startsWith("<!") || tag.startsWith("<?")) {
            return ""
        }

        val match = HTML_TAG.matchEntire(tag) ?: return escapeText(tag)
        val closing = match.groupValues[1].isNotEmpty()
        val name = match.groupValues[2].lowercase()
        // The regex's trailing (/?) is attribute-blind: per HTML5 an unquoted attribute
        // value ends only at whitespace or >, so the slash of <a href=http://x/> belongs
        // to the value and the tag is not self-closing. A slash counts as self-closing
        // only when it follows the tag name (no attributes), whitespace, or a value's
        // closing quote; otherwise it is folded back into the attributes
        var attrs = match.groupValues[3]
        var selfClosing = match.groupValues[4].isNotEmpty()
        val last = attrs.lastOrNull()
        if (selfClosing && last != null && !last.isWhitespace() && last != '"' && last != '\'') {
            attrs += "/"
            selfClosing = false
        }

        if (name == "br") return "\n"
        if (name == "a") return renderAnchor(tag, closing, selfClosing, attrs)

        val marker =
            when (name) {
                "b", "strong" -> "*"
                "i", "em" -> "_"
                "s", "del", "strike" -> "~"
                "u", "ins" -> "__"
                "code", "kbd", "samp", "tt" -> "`"
                else -> return escapeText(tag)
            }
        if (selfClosing) return escapeText(tag)
        if (closing) {
            if (openEmphasis.lastOrNull() == marker) {
                openEmphasis.removeLastOrNull()
                if (marker == "`") htmlCodeDepth--
                return marker
            }
            return escapeText(tag)
        }
        openEmphasis.addLast(marker)
        if (marker == "`") htmlCodeDepth++
        return marker
    }

    private fun renderAnchor(
        tag: String,
        closing: Boolean,
        selfClosing: Boolean,
        attrs: String,
    ): String {
        if (selfClosing) return escapeText(tag)
        if (closing) {
            return when (val url = openLinkUrls.removeLastOrNull()) {
                null -> escapeText(tag)

                NESTED_LITERAL_ANCHOR -> escapeText(tag)

                // "" is the href-less open tag: pop the stack, emit no link
                "" -> ""

                else -> "](${escapeUrl(url)})"
            }
        }
        // Telegram links cannot nest: while any link entity is open (Markdown link, image
        // link or an outer anchor), an inner anchor and its closing tag are escaped literally
        if (linkEntityOpen()) {
            openLinkUrls.addLast(NESTED_LITERAL_ANCHOR)
            return escapeText(tag)
        }
        // href="" is treated like a missing href: the empty URL degrades to plain text
        val href = extractHref(attrs)
        openLinkUrls.addLast(href.orEmpty())
        return if (href == null) "" else "["
    }

    /**
     * Extracts the href attribute from a tag's attribute string by walking name/value pairs
     * left to right (linear, no regex). Quoted values are skipped as a whole, so an `href=`
     * appearing inside another attribute's value (`title="href=x"`) cannot be mistaken for
     * the real attribute; attribute names are compared case-insensitively (HTML5), and the
     * value may be double-quoted, single-quoted or unquoted. Returns null when the attribute
     * is absent or empty.
     */
    private fun extractHref(attrs: String): String? {
        var index = 0
        while (index < attrs.length) {
            while (index < attrs.length && attrs[index].isWhitespace()) index++
            if (index >= attrs.length) break
            val nameStart = index
            while (index < attrs.length && attrs[index] != '=' && !attrs[index].isWhitespace()) index++
            val name = attrs.substring(nameStart, index)
            while (index < attrs.length && attrs[index].isWhitespace()) index++
            var value: String? = null
            if (index < attrs.length && attrs[index] == '=') {
                index++
                while (index < attrs.length && attrs[index].isWhitespace()) index++
                value =
                    if (index < attrs.length && (attrs[index] == '"' || attrs[index] == '\'')) {
                        val quote = attrs[index]
                        index++
                        val valueStart = index
                        while (index < attrs.length && attrs[index] != quote) index++
                        attrs.substring(valueStart, index).also { if (index < attrs.length) index++ }
                    } else {
                        // HTML5 unquoted value: ends at whitespace (a '>' cannot occur — the
                        // tag regex already split it off)
                        val valueStart = index
                        while (index < attrs.length && !attrs[index].isWhitespace()) index++
                        attrs.substring(valueStart, index)
                    }
            }
            if (name.equals("href", ignoreCase = true)) {
                return value?.takeIf { it.isNotEmpty() }?.let(::decodeHtmlEntities)
            }
        }
        return null
    }

    /**
     * Decodes the supported HTML entities (`&amp;`, `&#38;`, `&#x26;`, …) in URL attribute
     * values; `&` without a well-formed entity stays literal. Browsers decode entities in
     * attributes before using the value, so an href extracted raw would link to the wrong
     * URL.
     */
    private fun decodeHtmlEntities(text: String): String {
        if ('&' !in text) return text
        val sb = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char != '&') {
                sb.append(char)
                index++
                continue
            }
            val end = text.indexOf(';', index + 1)
            // An entity body is 1-10 chars here: at least one char between & and ;, at most
            // &#x10FFFF; — anything longer cannot be a supported entity and stays literal
            if (end == -1 || end - index < 2 || end - index > 10) {
                sb.append(char)
                index++
                continue
            }
            val decoded = decodeEntity(text.substring(index + 1, end))
            if (decoded == null) {
                sb.append(char)
                index++
            } else {
                sb.append(decoded)
                index = end + 1
            }
        }
        return sb.toString()
    }

    private fun decodeEntity(body: String): String? =
        when {
            body.startsWith("#") -> decodeCharRef(body.substring(1))
            else -> NAMED_ENTITIES[body]
        }

    private fun decodeCharRef(digits: String): String? {
        val codePoint =
            when {
                digits.startsWith("x") || digits.startsWith("X") -> digits.substring(1).toIntOrNull(16)
                else -> digits.toIntOrNull()
            } ?: return null
        if (codePoint < 0 || codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF) return null
        if (codePoint <= 0xFFFF) return codePoint.toChar().toString()
        val offset = codePoint - 0x10000
        return Char(0xD800 + (offset shr 10)).toString() + Char(0xDC00 + (offset and 0x3FF))
    }

    private fun linkEntityOpen(): Boolean = openLinkUrls.isNotEmpty() || openMarkdownLinks > 0

    private fun enterInline(): Boolean {
        if (inlineDepth >= MAX_RENDER_DEPTH) return false
        inlineDepth++
        return true
    }

    private fun exitInline() {
        inlineDepth--
    }

    // Continuation lines are indented to the marker width so multi-line items stay
    // visually attached to their marker
    private fun renderList(
        list: Node,
        marker: () -> String,
    ) {
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) {
                val body = renderChild(item)
                val prefix = marker()
                val indent = " ".repeat(prefix.length)
                val lines = body.lines()

                sb.append(prefix).append(lines.first())
                for (i in 1 until lines.size) {
                    sb.append('\n')
                    if (lines[i].isNotEmpty()) {
                        sb.append(indent)
                    }
                    sb.append(lines[i])
                }
                sb.append('\n')
            }
            item = item.next
        }
        sb.append('\n')
    }

    // Renders a nested block (quote body, list item) in a child visitor; at the depth cap
    // the subtree flattens to plain text (see MAX_RENDER_DEPTH). Trailing newlines are
    // trimmed here — callers prepend their own prefixes and separators.
    private fun renderChild(node: Node): String =
        if (depth + 1 >= MAX_RENDER_DEPTH) {
            val plain = StringBuilder()
            appendPlainText(plain, node)
            plain.toString().trimEnd('\n')
        } else {
            val visitor = Visitor(depth + 1, options)
            visitor.visitChildren(node)
            visitor.output().trimEnd('\n')
        }
}
