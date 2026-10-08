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

// How an HTML code entity emits its backtick delimiter. LOUD opens and closes with one.
// MERGED is a code-family tag nested inside an already-open code entity: the nested span
// renders as part of the outer entity — no delimiter, content stays code-escaped (nested
// code is exactly single code). SUPPRESSED is a tag whose delimiter would glue onto a
// just-closed entity's backtick (`<code>a</code><code>b</code>`, `` `md`<code>x</code> ``):
// `` is an empty entity Telegram rejects, ``` re-parses as a pre fence — so the span
// degrades to escaped plain text with no delimiter at all. LOUD and MERGED entries own a
// htmlCodeDepth unit; a SUPPRESSED one does not (its content escapes as text)
private enum class CodeDelimiter {
    LOUD,
    MERGED,
    SUPPRESSED,
}

// marker: the MarkdownV2 closer; html: pushed by an HTML tag rather than by this
// visitor's own emphasis visits — only html-owned markers may be matched and popped by
// a closing tag, so a Markdown emphasis' own entry can never be stolen from under it.
// openPosition: for a LOUD code entry, the output position right after its opening
// backtick (everything the entity emitted sits at or after it; -1 otherwise)
private class OpenEmphasisMarker(
    val marker: String,
    val html: Boolean,
    val codeDelimiter: CodeDelimiter = CodeDelimiter.LOUD,
    override val seq: Int,
    val openPosition: Int = -1,
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

// Whether [output] ends with a backtick that is not the tail of an escape sequence:
// content backticks always escape (\`), so an unescaped trailing one is a just-closed
// entity's delimiter. Parity settles escaped tails: escapes pair every backslash with
// the character after it, so an odd number of backslashes before the backtick means
// the last one belongs to an escape. Shared with the chunking node seam
internal fun endsWithUnescapedBacktick(output: CharSequence): Boolean {
    if (output.isEmpty() || output.last() != '`') return false
    var backslashes = 0
    var index = output.length - 2
    while (index >= 0 && output[index] == '\\') {
        backslashes++
        index--
    }
    return backslashes % 2 == 0
}

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
 *
 * [precededByBacktick] tells the visitor that the output it produces will sit directly
 * behind an unescaped backtick of the caller's already-emitted content (the chunking
 * node seam): a code entity opening first in such a render would glue its delimiter
 * onto that backtick and takes the suppressed degradation instead.
 */
internal class Visitor(
    private val depth: Int = 0,
    private val options: RenderOptions = RenderOptions(),
    private val precededByBacktick: Boolean = false,
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
     * that keeps them clear of trailing block separators — except while an HTML code
     * entity is open: its content's trailing spaces and tabs are literal and stay; only
     * trailing newlines are cleared (the boundary-completion path completeOpenedAfter
     * trims nothing). A rewind that completed nothing else returns the rewound text
     * as-is.
     */
    fun output(): String {
        if (openEntities.isEmpty()) return sb.toString()
        val closers = mutableListOf<String>()
        while (openEntities.isNotEmpty()) {
            when (val entity = openEntities.removeLast()) {
                is OpenEmphasisMarker -> {
                    when {
                        entity.codeDelimiter != CodeDelimiter.LOUD -> {
                            // MERGED and SUPPRESSED code entries never emit their
                            // backtick — completing them must not either, or the glue
                            // they avoided at open time reappears at the completion point
                        }

                        entity.marker == "`" && sb.substring(entity.openPosition).trimEnd('\n').isEmpty() -> {
                            // An empty code entity (an unclosed <code> with no content,
                            // or one whose only content dropped out — a lone comment):
                            // emitting the closer would glue `` against the opener, an
                            // empty entity Telegram rejects — rewind the opener instead;
                            // the tags carried no visible text, so nothing is lost. The
                            // depth unit goes back too, so the trim below stops honoring
                            // a code entity that no longer exists. Trailing newlines are
                            // block separators appended after the children (never code
                            // the entity displays), so they do not count as content —
                            // unlike the matched-close and boundary paths, which run
                            // mid-children and compare positions exactly
                            htmlCodeDepth--
                            sb.setLength(entity.openPosition - 1)
                        }

                        else -> {
                            closers += entity.marker
                        }
                    }
                }

                is OpenAnchor -> {
                    when {
                        entity.url.isEmpty() || entity.url == NESTED_LITERAL_ANCHOR -> {}

                        regionIsBlank(entity.labelStart) -> {
                            // A blank label means nothing was emitted after its [ —
                            // anything opened inside would have output a marker or a
                            // literal tag — so this anchor is the latest open entry and
                            // rewinding the bracket discards only whitespace
                            // (regionIsBlank covers the whitespace-only label too)
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
        if (closers.isEmpty()) return sb.toString()
        // While an HTML code entity is open, trailing spaces and tabs are the entity's
        // literal content and must survive; only trailing newlines are cleared — they
        // are block separators (or a dangling <br>), never code the entity displays
        val body = if (htmlCodeDepth > 0) sb.toString().trimEnd('\n') else sb.toString().trimEnd()
        return body + closers.joinToString("")
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
        val start = sb.length
        visitChildren(paragraph)
        // A paragraph that rendered no visible text is no-content: one whose every
        // child dropped out (a lone inline comment, an empty-alt empty-URL image), or
        // one whose render is whitespace only (a hand-built Text(" ") child — the
        // parser never forms a paragraph from blank lines). The region rewinds and no
        // separator follows — the same blank-render skip the truncation (renderBlocks)
        // and chunking (chunkBlocks) paths make on the rendered shape, where the
        // separator (or the whitespace itself) would double the blank line between its
        // neighbors. Rewinding discards whitespace only: an entity marker would have
        // emitted a non-whitespace character, so nothing visible is lost
        if (regionIsBlank(start)) {
            sb.setLength(start)
        } else {
            sb.append("\n\n")
        }
    }

    // MarkdownV2 has no heading entity: the level is spelled out as escaped hashes
    // (headingPrefixOf, shared with the truncation and chunking pipelines)
    override fun visit(heading: Heading) {
        sb.append(headingPrefixOf(heading))
        visitChildren(heading)
        sb.append("\n\n")
    }

    override fun visit(blockQuote: BlockQuote) {
        // Per child: a nested quote renders with its own glued marker run and this level's
        // marker glues in front of it, every other child's raw lines (code content
        // included) take the spaced "> " — see prefixQuoteLevel, which pins the Telegram
        // grammar (nesting runs glue; "> >" is unescaped content). The "\n>\n" separator
        // keeps the blank line between the quote's paragraphs as a bare marker. An empty
        // quote keeps a single ">"
        val pieces = mutableListOf<String>()
        var child = blockQuote.firstChild
        while (child != null) {
            val rendered = renderQuoteChild(child)
            if (rendered.isNotBlank()) {
                pieces += prefixQuoteLevel(child is BlockQuote, rendered)
            }
            child = child.next
        }
        sb.append(if (pieces.isEmpty()) ">" else pieces.joinToString("\n>\n"))
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
        // The info string is untrusted: keep only letters/digits/-/+/# so it can neither
        // forge the fence (backticks, newlines) nor inject entities — '#' completes the
        // c#/f# tags and, like the rest of the set, renders literally inside the pre
        // entity; 32 chars is plenty
        val language =
            fencedCodeBlock.info
                .orEmpty()
                .trim()
                .filter { it.isLetterOrDigit() || it == '-' || it == '+' || it == '#' }
                .take(32)
        completeOpenCodeEntities()
        sb.append("```").append(language).append('\n')
        sb.append(escapeCode(fencedCodeBlock.literal.orEmpty().trimEnd('\n')))
        sb.append("\n```\n\n")
    }

    override fun visit(indentedCodeBlock: IndentedCodeBlock) {
        completeOpenCodeEntities()
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
        } else if (code.literal.isEmpty()) {
            // An empty span's delimiters would glue into an empty code entity; the
            // parser never produces one, this covers hand-built ASTs
        } else if (atCodeGlue()) {
            // The span's opener would glue onto a just-closed entity's backtick — the
            // same reserved-character run the HTML tag path suppresses: degrade to
            // escaped plain text, content preserved, formatting dropped
            sb.append(escapeText(code.literal))
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
        openEntities.addLast(OpenEmphasisMarker("_", html = false, seq = seq))
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
        openEntities.addLast(OpenEmphasisMarker("*", html = false, seq = seq))
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
                openEntities.addLast(OpenEmphasisMarker("~", html = false, seq = seq))
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
                    // Only LOUD and MERGED code entries own a depth unit (see
                    // CodeDelimiter); completing a SUPPRESSED one must not drop the
                    // count below the entities really open around it
                    if (entity.marker == "`" && entity.codeDelimiter != CodeDelimiter.SUPPRESSED) htmlCodeDepth--
                    when {
                        entity.codeDelimiter != CodeDelimiter.LOUD -> {}

                        entity.marker == "`" && sb.length == entity.openPosition -> {
                            // See output(): an empty code entity rewinds its opener
                            // instead of gluing `` at the completion point
                            sb.setLength(entity.openPosition - 1)
                        }

                        else -> {
                            sb.append(entity.marker)
                        }
                    }
                }

                is OpenAnchor -> {
                    when {
                        entity.url.isEmpty() || entity.url == NESTED_LITERAL_ANCHOR -> {}

                        regionIsBlank(entity.labelStart) -> {
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

    /**
     * Completes every open inline code entity (and everything opened inside it) before a
     * fence-wrapped block emits its ```` ``` ````: left open, the entity would swallow the
     * fence markers as its content and its closer would glue onto the closing fence
     * (a four-backtick run Telegram cannot parse). Code entities cannot meaningfully span
     * blocks anyway; emphasis and anchors left open keep the documented complete-at-output
     * behavior. The closers land before the trailing block separator — appended after it
     * they would glue onto the fence instead, so the separator is lifted and re-emitted
     * (the same trade the output-time completion's trim makes).
     */
    private fun completeOpenCodeEntities() {
        val outermostCode =
            openEntities.firstOrNull { it is OpenEmphasisMarker && it.marker == "`" } as? OpenEmphasisMarker
                ?: return
        var separatorLength = 0
        while (separatorLength < sb.length && sb[sb.length - 1 - separatorLength] == '\n') separatorLength++
        sb.setLength(sb.length - separatorLength)
        completeOpenedAfter(outermostCode.seq - 1)
        repeat(separatorLength) { sb.append('\n') }
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

        completeOpenCodeEntities()
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
        if (regionIsBlank(labelStart)) {
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
        if (regionIsBlank(labelStart)) {
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

        completeOpenCodeEntities()
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
                // An empty code pair (<code></code>, or one whose only content dropped
                // out — a lone comment) rewinds its opener: emitting the closer would
                // glue `` — an empty entity Telegram rejects; the tags carried no
                // visible text, so nothing is lost (see output() for the same trade at
                // completion points)
                if (marker == "`" && top.codeDelimiter == CodeDelimiter.LOUD && sb.length == top.openPosition) {
                    openEntities.removeLast()
                    htmlCodeDepth--
                    sb.setLength(top.openPosition - 1)
                    return ""
                }
                openEntities.removeLast()
                // LOUD and MERGED entries own a depth unit; a SUPPRESSED one never took
                // one (and a non-code marker has none to give back either)
                if (marker == "`" && top.codeDelimiter != CodeDelimiter.SUPPRESSED) htmlCodeDepth--
                return if (top.codeDelimiter == CodeDelimiter.LOUD) marker else ""
            }
            return escapeText(tag)
        }
        // A code-family tag never emits a second backtick in a row: nested inside an
        // open code entity it merges into it (nested code renders exactly as single
        // code), and directly behind a closed entity's backtick the delimiter would
        // glue into a reserved-character run — the span degrades to escaped plain text
        val delimiter =
            if (marker == "`" && htmlCodeDepth > 0) {
                CodeDelimiter.MERGED
            } else if (marker == "`" && atCodeGlue()) {
                CodeDelimiter.SUPPRESSED
            } else {
                CodeDelimiter.LOUD
            }
        // For a LOUD code entry, the opener backtick lands at sb.length once the caller
        // appends this method's return — openPosition is the position right after it
        openEntities.addLast(
            OpenEmphasisMarker(
                marker,
                html = true,
                delimiter,
                nextSeq++,
                openPosition = if (marker == "`" && delimiter == CodeDelimiter.LOUD) sb.length + 1 else -1,
            ),
        )
        if (delimiter != CodeDelimiter.SUPPRESSED && marker == "`") htmlCodeDepth++
        return if (delimiter == CodeDelimiter.LOUD) marker else ""
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
            if (regionIsBlank(top.labelStart)) {
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
        return (0xD800 + (offset shr 10)).toChar().toString() + (0xDC00 + (offset and 0x3FF)).toChar()
    }

    private fun linkEntityOpen(): Boolean = openEntities.any { it is OpenAnchor } || openMarkdownLinks > 0

    // Whether the output region starting at [start] renders no visible text: empty or
    // whitespace only. The label callers check it immediately after the label's children,
    // so the region runs exactly to the end of the current output; any entity marker a
    // label had opened would have emitted a non-whitespace character, so a blank label
    // region guarantees nothing was left open inside it. visit(paragraph) asks the same
    // question of a paragraph's region for its no-content skip
    private fun regionIsBlank(start: Int): Boolean {
        for (index in start until sb.length) {
            if (!sb[index].isWhitespace()) return false
        }
        return true
    }

    // Whether the output ends with a backtick that is not the tail of an escape
    // sequence. Content backticks always escape (\`), so at code-depth zero an
    // unescaped trailing one is necessarily a just-closed entity's delimiter — the
    // glue a new code opener must not touch. Parity settles escaped tails: escapes
    // pair every backslash with the character after it, so an odd number of
    // backslashes before the backtick means the last one belongs to an escape
    private fun atCodeGlue(): Boolean = if (sb.isEmpty()) precededByBacktick else endsWithUnescapedBacktick(sb)

    private fun enterInline(): Boolean {
        if (inlineDepth >= MAX_RENDER_DEPTH) return false
        inlineDepth++
        return true
    }

    private fun exitInline() {
        inlineDepth--
    }

    // Continuation lines are indented to the marker width via applyPrefix (shared with
    // the truncation and chunking pipelines). A blank-bodied item is no-content and skips,
    // its marker consumed — the same walk forEachListItem makes, so the full render,
    // truncation and chunking agree on which items exist and how they number
    private fun renderList(list: Node) {
        val start = sb.length
        val marker = listMarkerOf(list)
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) {
                val prefix = marker()
                val body = renderChild(item)
                if (body.isNotBlank()) {
                    sb.append(applyPrefix(prefix, body)).append('\n')
                }
            }
            item = item.next
        }
        // The final '\n' completes the block separator the last emitted item's own '\n'
        // started; a list whose every item is no-content (a lone "- " marker) renders to
        // nothing and separates nothing — the same trade visit(paragraph) makes for a
        // nothing-render
        if (sb.length > start) sb.append('\n')
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

    // The quote-child variant: the node renders through its own full block shape —
    // visit(node), not visitChildren(node) — so a nested blockQuote child arrives with
    // its own marker run for the caller to glue onto (see visit(blockQuote)). Depth
    // capping and trailing-newline trimming match renderChild
    private fun renderQuoteChild(node: Node): String =
        if (depth + 1 >= MAX_RENDER_DEPTH) {
            val plain = StringBuilder()
            appendPlainText(plain, node)
            plain.toString().trimEnd('\n')
        } else {
            val visitor = Visitor(depth + 1, options)
            node.accept(visitor)
            visitor.output().trimEnd('\n')
        }
}
