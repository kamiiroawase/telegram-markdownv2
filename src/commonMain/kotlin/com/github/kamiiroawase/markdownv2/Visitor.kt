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

// An open HTML anchor: its URL ("" when href-less, the NESTED_LITERAL_ANCHOR sentinel
// while an outer link is open) plus the output positions that let an empty label degrade
// to the bare escaped URL (the same trade visit(link) makes)
private class OpenAnchor(
    val url: String,
    val openBracket: Int,
    val labelStart: Int,
)

// A parsed inline-HTML tag: the lowercased name, the raw attribute string, whether the
// tag closes, and whether its trailing slash read as self-closing
private class TagParts(
    val closing: Boolean,
    val name: String,
    val attrs: String,
    val selfClosing: Boolean,
)

/**
 * Parses a complete inline-HTML tag (`<name attrs>`, `</name>`, `<name/>`) in one linear
 * left-to-right scan — no regex, so no backtracking and no engine recursion whatever the
 * length; the tagScanLinear* regression tests pin this down at 100k scale (a lazy regex
 * loop over an attribute alternation overflows the stack there). Quoted attribute values
 * follow HTML5/CommonMark and may contain `>` and `/`: neither closes the tag nor reads
 * as self-closing. A slash counts as self-closing only when it directly precedes the
 * closing `>` after the tag name, whitespace, or a value's closing quote — an unquoted
 * value swallows its trailing slash (HTML5). An unclosed quote, a missing `>`, or
 * content after the `>` makes the whole tag unmatched; the caller escapes the literal.
 */
private fun parseHtmlTag(tag: String): TagParts? {
    var index = 0
    if (index >= tag.length || tag[index] != '<') return null
    index++
    val closing = index < tag.length && tag[index] == '/'
    if (closing) index++
    val nameStart = index
    if (index >= tag.length || !tag[index].isAsciiLetter()) return null
    index++
    while (index < tag.length && tag[index].isAsciiLetterOrDigit()) index++
    val name = tag.substring(nameStart, index).lowercase()

    val attrsStart = index
    while (true) {
        if (index >= tag.length) return null
        when (val char = tag[index]) {
            '>' -> {
                var attrs = tag.substring(attrsStart, index)
                var selfClosing = false
                if (attrs.isNotEmpty() && attrs.last() == '/') {
                    val before = attrs.getOrNull(attrs.length - 2)
                    if (before == null || before.isWhitespace() || before == '"' || before == '\'') {
                        selfClosing = true
                        attrs = attrs.substring(0, attrs.length - 1)
                    }
                }
                // matchEntire semantics: the closing '>' must end the tag
                return if (index + 1 == tag.length) {
                    TagParts(closing, name, attrs, selfClosing)
                } else {
                    null
                }
            }

            '"', '\'' -> {
                val close = tag.indexOf(char, index + 1)
                if (close == -1) return null
                index = close + 1
            }

            else -> {
                index++
            }
        }
    }
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private fun Char.isAsciiLetterOrDigit(): Boolean = isAsciiLetter() || this in '0'..'9'

// Entity decoding for URL attribute values: the five XML predefined entities plus decimal
// and hex character references. The full HTML5 named-entity table (~2k names) is
// deliberately out of scope for URLs; anything unrecognized stays literal. Scanned
// manually — no regex, like every other scanner in this file
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

    private val openLinkUrls = ArrayDeque<OpenAnchor>()

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
    fun output(): String {
        // An anchor still open with an empty label degrades like visit(link): an empty
        // label means nothing but trailing block separators follows its [, so rewinding
        // the bracket is safe (labelIsBlank covers the whitespace-only label too)
        openLinkUrls
            .lastOrNull { anchor ->
                anchor.url.isNotEmpty() && anchor.url != NESTED_LITERAL_ANCHOR && labelIsBlank(anchor.labelStart)
            }?.let { emptyLabel ->
                sb.setLength(emptyLabel.openBracket)
                sb.append(escapeText(emptyLabel.url))
                openLinkUrls.remove(emptyLabel)
            }
        return if (openLinkUrls.isEmpty() && openEmphasis.isEmpty()) {
            sb.toString()
        } else {
            sb.toString().trimEnd() +
                openEmphasis.reversed().joinToString("") { it } +
                openLinkUrls.joinToString("") { anchor ->
                    if (anchor.url.isEmpty() || anchor.url == NESTED_LITERAL_ANCHOR) "" else "](${escapeUrl(anchor.url)})"
                }
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
        if (htmlCodeDepth > 0) {
            // Inside an HTML code entity the code span's own backticks would pair with
            // the entity's delimiters and garble the span boundaries — keep them, escaped
            // (\` renders as a literal backtick inside a code entity)
            sb.append("\\`").append(escapeCode(code.literal)).append("\\`")
        } else {
            sb.append('`').append(escapeCode(code.literal)).append('`')
        }
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
        closeNestedTo(baseDepth + 1)
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
        closeNestedTo(baseDepth + 1)
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
                closeNestedTo(baseDepth + 1)
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
     * Completes unclosed HTML emphasis down to [targetDepth] on the emphasis stack (the
     * caller's own marker, when it pushed one, stays). Runs when an inline container —
     * emphasis, strikethrough, a Markdown link or image label — closes with HTML tags
     * still open inside it (missing closing tags, or upstream parsing defects). Markers
     * are located by stack depth — same-kind markers can overlap (e.g. _ inside _), and
     * matching by marker value would misalign.
     */
    private fun closeNestedTo(targetDepth: Int) {
        while (openEmphasis.size > targetDepth) {
            val nested = openEmphasis.removeLastOrNull() ?: break
            // Completing an unclosed HTML code entity ends its scope: Text after the
            // boundary escapes as plain text again
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
        val emphasisDepth = openEmphasis.size
        val openBracket = sb.length
        sb.append('[')
        openMarkdownLinks++
        val labelStart = sb.length
        visitChildren(link)
        openMarkdownLinks--
        // Telegram also rejects link entities with empty text: a label that renders to
        // no visible text (no children, whitespace-only, or children like a lone HTML
        // comment) degrades to the bare escaped URL — the same blank test output() applies
        // to anchors left unclosed at output time. Rewinding is safe — inside a label every
        // pushed entity marker is accompanied by output, so a blank label left nothing
        // open on the stacks
        if (labelIsBlank(labelStart)) {
            sb.setLength(openBracket)
            sb.append(escapeText(destination))
            return
        }
        // Whatever HTML opened inside the label closes before the link does: left open it
        // would cross the ](url) boundary into invalid entities, or leak htmlCodeDepth
        // escaping onto the text after the link
        closeNestedTo(emphasisDepth)
        // An unclosed anchor inside the label leaves a nested-literal sentinel behind;
        // never popped, it would degrade every following link in the document to plain
        // text. Its open tag already rendered literally, so dropping the sentinel here is
        // lossless (the guard above emptied the stack on entry, and only sentinels can be
        // pushed inside a label)
        while (openLinkUrls.isNotEmpty()) openLinkUrls.removeLast()
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
        val emphasisDepth = openEmphasis.size
        val openBracket = sb.length
        sb.append('[')
        openMarkdownLinks++
        val labelStart = sb.length
        visitChildren(image)
        openMarkdownLinks--
        // See visit(link): a label with no visible text degrades to the bare escaped URL
        if (labelIsBlank(labelStart)) {
            sb.setLength(openBracket)
            sb.append(escapeText(destination))
            return
        }
        // See visit(link): HTML opened inside the alt text closes before the link does
        closeNestedTo(emphasisDepth)
        while (openLinkUrls.isNotEmpty()) openLinkUrls.removeLast()
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

        val parts = parseHtmlTag(tag) ?: return escapeText(tag)
        if (parts.name == "br") return "\n"
        if (parts.name == "a") return renderAnchor(tag, parts.closing, parts.selfClosing, parts.attrs)

        val marker =
            when (parts.name) {
                "b", "strong" -> "*"
                "i", "em" -> "_"
                "s", "del", "strike" -> "~"
                "u", "ins" -> "__"
                "code", "kbd", "samp", "tt" -> "`"
                else -> return escapeText(tag)
            }
        if (parts.selfClosing) return escapeText(tag)
        if (parts.closing) {
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
            val anchor = openLinkUrls.removeLastOrNull() ?: return escapeText(tag)
            if (anchor.url == NESTED_LITERAL_ANCHOR) return escapeText(tag)
            // "" is the href-less open tag: pop the stack, emit no link
            if (anchor.url.isEmpty()) return ""
            // Telegram rejects link entities with empty text: a label that rendered no
            // visible text (whitespace included) degrades to the bare escaped URL,
            // rewinding the bracket — safe for the same reason as in visit(link): a blank
            // label left nothing open
            if (labelIsBlank(anchor.labelStart)) {
                sb.setLength(anchor.openBracket)
                return escapeText(anchor.url)
            }
            return "](${escapeUrl(anchor.url)})"
        }
        // Telegram links cannot nest: while any link entity is open (Markdown link, image
        // link or an outer anchor), an inner anchor and its closing tag are escaped literally
        if (linkEntityOpen()) {
            openLinkUrls.addLast(OpenAnchor(NESTED_LITERAL_ANCHOR, sb.length, sb.length))
            return escapeText(tag)
        }
        // href="" is treated like a missing href: the empty URL degrades to plain text
        val href = extractHref(attrs)
        openLinkUrls.addLast(OpenAnchor(href.orEmpty(), sb.length, sb.length + 1))
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
        // 0 joins the rejects: the NUL it would decode to is not valid message text for
        // Telegram — the reference stays literal like every other malformed one
        if (codePoint <= 0 || codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF) return null
        if (codePoint <= 0xFFFF) return codePoint.toChar().toString()
        val offset = codePoint - 0x10000
        return Char(0xD800 + (offset shr 10)).toString() + Char(0xDC00 + (offset and 0x3FF))
    }

    private fun linkEntityOpen(): Boolean = openLinkUrls.isNotEmpty() || openMarkdownLinks > 0

    // Whether a link label region starting at [labelStart] renders no visible text: empty
    // or whitespace only. Every caller checks it immediately after the label's children,
    // so the region runs exactly to the end of the current output; any entity marker a
    // label had opened would have emitted a non-whitespace character, so a blank region
    // guarantees nothing was left open inside it
    private fun labelIsBlank(labelStart: Int): Boolean = sb.substring(labelStart).isBlank()

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
