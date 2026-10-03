package io.github.kamiiroawase.markdownv2

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

// One entry per open inline entity, whichever kind pushed it — Markdown emphasis (this
// visitor's own visits), HTML emphasis tags, HTML anchors — because their closers must
// interleave by opening order at every completion point (a container boundary or output
// time); two separate stacks cannot express that order and emitted crossed entities
// Telegram rejects
private sealed interface OpenEntity {
    // Opening order: every completion point closes what was opened after the boundary
    // entry, latest-opened first, so completed entities stay properly nested
    val seq: Int
}

// marker: the MarkdownV2 closer; html: pushed by an HTML tag rather than by this
// visitor's own emphasis visits — only html-owned markers may be matched and popped by
// a closing tag, so a Markdown emphasis' own entry can never be stolen from under it
private class OpenEmphasisMarker(
    val marker: String,
    val html: Boolean,
    override val seq: Int,
) : OpenEntity

// An open HTML anchor: its URL ("" when href-less, the NESTED_LITERAL_ANCHOR sentinel
// while an outer link is open) plus the output positions that let an empty label degrade
// to the bare escaped URL (the same trade visit(link) makes)
private class OpenAnchor(
    val url: String,
    val openBracket: Int,
    val labelStart: Int,
    override val seq: Int,
) : OpenEntity

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
 * Renders the full AST to Telegram MarkdownV2. Unclosed HTML entities — emphasis tags and
 * anchors alike — complete at the nearest container boundary (an emphasis, strikethrough,
 * or link/image label that closes over them) or at output time, always latest-opened first
 * so the output stays properly nested; tables degrade to aligned text via [renderTableLines].
 */
internal class Visitor(
    private val depth: Int = 0,
    private val options: RenderOptions = RenderOptions(),
) : AbstractVisitor() {
    private val sb = StringBuilder()

    // Every open inline entity in opening order: this visitor's own Markdown emphasis,
    // HTML emphasis tags and HTML anchors alike (see OpenEntity)
    private val openEntities = ArrayDeque<OpenEntity>()

    private var nextSeq = 0

    // Depth of open Markdown link entities (Link and Image both render as links); HTML
    // anchors are tracked in [openEntities]. Telegram links cannot nest, so while any
    // link entity is open, link-like constructs degrade (see visit(link), visit(image)
    // and renderAnchor)
    private var openMarkdownLinks = 0

    // Nesting depth of open HTML code entities (<code>/<kbd>/<samp>/<tt> render as `
    // entities). Inside a code entity Telegram interprets only \` and \\, so Text children
    // switch to code escaping (see visit(text)); Markdown Code nodes always escape that way
    private var htmlCodeDepth = 0

    // Nesting depth of inline emphasis (emphasis recurses within the same visitor; deep
    // nesting flattens)
    private var inlineDepth = 0

    /**
     * Returns the rendered text, completing any unclosed entities latest-opened first
     * (by opening order, so the completed entities nest properly): markers emit their
     * closer, real anchors their ](url) — an anchor whose label rendered no visible text
     * rewinds and degrades to the bare escaped URL, the same trade visit(link) makes —
     * while sentinel and href-less anchors emit nothing. Closers append after a trimEnd
     * that keeps them clear of trailing block separators; a rewind that completed nothing
     * else returns the rewound text as-is.
     */
    fun output(): String {
        if (openEntities.isEmpty()) return sb.toString()
        val closers = mutableListOf<String>()
        while (openEntities.isNotEmpty()) {
            when (val entity = openEntities.removeLast()) {
                is OpenEmphasisMarker -> {
                    closers += entity.marker
                }

                is OpenAnchor -> {
                    when {
                        entity.url.isEmpty() || entity.url == NESTED_LITERAL_ANCHOR -> {}

                        labelIsBlank(entity.labelStart) -> {
                            // A blank label means nothing was emitted after its [ —
                            // anything opened inside would have output a marker or a
                            // literal tag — so this anchor is the latest open entry and
                            // rewinding the bracket discards only whitespace
                            // (labelIsBlank covers the whitespace-only label too)
                            sb.setLength(entity.openBracket)
                            sb.append(escapeText(entity.url))
                        }

                        else -> {
                            closers += "](${escapeUrl(entity.url)})"
                        }
                    }
                }
            }
        }
        return if (closers.isEmpty()) {
            sb.toString()
        } else {
            sb.toString().trimEnd() + closers.joinToString("")
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
    // (headingPrefixOf, shared with the truncation and chunking pipelines)
    override fun visit(heading: Heading) {
        sb.append(headingPrefixOf(heading))
        visitChildren(heading)
        sb.append("\n\n")
    }

    override fun visit(blockQuote: BlockQuote) {
        // prefixQuote is the shared quote-prefixing helper (Blocks.kt); blank lines keep
        // a bare ">": an empty line would terminate the quote entity
        sb.append(prefixQuote(renderChild(blockQuote)))
        sb.append("\n\n")
    }

    override fun visit(bulletList: BulletList) {
        renderList(bulletList)
    }

    override fun visit(orderedList: OrderedList) {
        renderList(orderedList)
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
        val seq = nextSeq++
        openEntities.addLast(OpenEmphasisMarker("_", html = false, seq))
        sb.append('_')
        visitChildren(emphasis)
        completeOpenedAfter(seq)
        sb.append('_')
        openEntities.removeLast()
        exitInline()
    }

    override fun visit(strongEmphasis: StrongEmphasis) {
        if (!enterInline()) {
            appendPlainText(sb, strongEmphasis)
            return
        }
        val seq = nextSeq++
        openEntities.addLast(OpenEmphasisMarker("*", html = false, seq))
        sb.append('*')
        visitChildren(strongEmphasis)
        completeOpenedAfter(seq)
        sb.append('*')
        openEntities.removeLast()
        exitInline()
    }

    override fun visit(customNode: CustomNode) {
        when (customNode) {
            is Strikethrough -> {
                if (!enterInline()) {
                    appendPlainText(sb, customNode)
                    return
                }
                val seq = nextSeq++
                openEntities.addLast(OpenEmphasisMarker("~", html = false, seq))
                sb.append('~')
                visitChildren(customNode)
                completeOpenedAfter(seq)
                sb.append('~')
                openEntities.removeLast()
                exitInline()
            }

            else -> {
                super.visit(customNode)
            }
        }
    }

    /**
     * Completes unclosed entities opened strictly after [seq] — latest-opened first —
     * before the entry owning [seq] closes itself. Runs when an inline container —
     * emphasis, strikethrough, a Markdown link or image label — closes with HTML
     * entities still open inside it (missing closing tags, or upstream parsing defects).
     * An anchor opened inside completes exactly like a matched `</a>` would: a real one
     * emits its ](url) — or rewinds, when its label rendered no visible text, the same
     * empty-label trade visit(link) makes — while sentinels and href-less anchors close
     * silently. Completing an unclosed HTML code entity ends its scope: Text after the
     * boundary escapes as plain text again.
     */
    private fun completeOpenedAfter(seq: Int) {
        while (openEntities.isNotEmpty() && openEntities.last().seq > seq) {
            when (val entity = openEntities.removeLast()) {
                is OpenEmphasisMarker -> {
                    if (entity.marker == "`") htmlCodeDepth--
                    sb.append(entity.marker)
                }

                is OpenAnchor -> {
                    when {
                        entity.url.isEmpty() || entity.url == NESTED_LITERAL_ANCHOR -> {}

                        labelIsBlank(entity.labelStart) -> {
                            // See output(): a blank label guarantees this anchor is the
                            // latest open entry, so the rewind discards only whitespace
                            sb.setLength(entity.openBracket)
                            sb.append(escapeText(entity.url))
                        }

                        else -> {
                            sb.append("](").append(escapeUrl(entity.url)).append(')')
                        }
                    }
                }
            }
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
        val entrySeq = nextSeq++
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
        // open on the stack
        if (labelIsBlank(labelStart)) {
            sb.setLength(openBracket)
            sb.append(escapeText(destination))
            return
        }
        // Whatever HTML opened inside the label closes before the link does: left open it
        // would cross the ](url) boundary into invalid entities, or leak htmlCodeDepth
        // escaping onto the text after the link. Anchors opened inside are all
        // literalized sentinels (the entry guard saw to that), so they close silently
        // here instead of lingering to degrade later links
        completeOpenedAfter(entrySeq)
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
        val entrySeq = nextSeq++
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
        completeOpenedAfter(entrySeq)
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
        // "<!--" is a "<!" — one prefix covers comments and declarations alike
        if (literal.isEmpty() || literal.startsWith("<!") || literal.startsWith("<?")) {
            return
        }

        sb.append("```\n").append(escapeCode(literal)).append("\n```\n\n")
    }

    override fun visit(htmlInline: HtmlInline) {
        sb.append(renderHtmlInline(htmlInline.literal))
    }

    /**
     * A closing tag emits its entity marker only when it matches the innermost open HTML
     * entity, otherwise it is escaped literally — an orphan `</b>` emitting a bare `*`
     * would create an unpaired entity Telegram rejects. Innermost-only matching also
     * keeps a closer from crossing a still-open anchor (or from popping a Markdown
     * emphasis' own stack entry, which used to crash on inputs like `_x</i>_`); the
     * crossed entity completes later instead, latest-opened first. Anchors behave the
     * same; an `<a>` without href pushes the empty sentinel and its closing only pops,
     * emitting no link.
     */
    private fun renderHtmlInline(literal: String): String {
        val tag = literal.trim()
        // "<!--" is a "<!" — one prefix covers comments and declarations alike
        if (tag.startsWith("<!") || tag.startsWith("<?")) {
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
            val top = openEntities.lastOrNull()
            if (top is OpenEmphasisMarker && top.html && top.marker == marker) {
                openEntities.removeLast()
                if (marker == "`") htmlCodeDepth--
                return marker
            }
            return escapeText(tag)
        }
        openEntities.addLast(OpenEmphasisMarker(marker, html = true, nextSeq++))
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
            // See renderHtmlInline: </a> matches only the innermost open entity — an
            // anchor closed while something opened inside it is still open goes literal
            // rather than emitting a crossed ](url)
            val top = openEntities.lastOrNull()
            if (top !is OpenAnchor) return escapeText(tag)
            openEntities.removeLast()
            if (top.url == NESTED_LITERAL_ANCHOR) return escapeText(tag)
            // "" is the href-less open tag: pop the stack, emit no link
            if (top.url.isEmpty()) return ""
            // Telegram rejects link entities with empty text: a label that rendered no
            // visible text (whitespace included) degrades to the bare escaped URL,
            // rewinding the bracket — safe for the same reason as in visit(link): a blank
            // label left nothing open
            if (labelIsBlank(top.labelStart)) {
                sb.setLength(top.openBracket)
                return escapeText(top.url)
            }
            return "](${escapeUrl(top.url)})"
        }
        // Telegram links cannot nest: while any link entity is open (Markdown link, image
        // link or an outer anchor), an inner anchor and its closing tag are escaped literally
        if (linkEntityOpen()) {
            openEntities.addLast(OpenAnchor(NESTED_LITERAL_ANCHOR, sb.length, sb.length, nextSeq++))
            return escapeText(tag)
        }
        // href="" is treated like a missing href: the empty URL degrades to plain text
        val href = extractHref(attrs)
        openEntities.addLast(OpenAnchor(href.orEmpty(), sb.length, sb.length + 1, nextSeq++))
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

    private fun linkEntityOpen(): Boolean = openEntities.any { it is OpenAnchor } || openMarkdownLinks > 0

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
    // visually attached to their marker; the marker factory (listMarkerOf) is shared with
    // the truncation and chunking pipelines
    private fun renderList(list: Node) {
        val marker = listMarkerOf(list)
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
