package io.github.kamiiroawase.markdownv2

import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.task.list.items.TaskListItemMarker
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

// One entry per open inline tag, whichever kind pushed it — Markdown emphasis (this
// visitor's own visits), HTML emphasis tags, HTML anchors — because their closers must
// interleave by opening order at every completion point (an inline container boundary or
// output time); two separate stacks cannot express that order and emitted crossed tags
// are exactly what Telegram rejects
private sealed interface OpenTag {
    // Opening order: every completion point closes what was opened after the boundary
    // entry, latest-opened first, so completed tags stay properly nested
    val seq: Int
}

// tag: the emitted element name ("b", "i", "u", "s", "code", "tg-spoiler", "tg-emoji");
// html: pushed by an HTML tag rather than by this visitor's own emphasis visits — only
// html-owned entries may be matched and popped by a closing tag, so a Markdown emphasis'
// own entry can never be stolen from under it. openStart/openLength: the emitted opener's
// position and length — an entity whose content renders to no visible text drops both its
// tags (Telegram rejects empty entities), rewinding the opener instead of emitting a
// closer. merged: a code-family tag nested inside an already-open inline <code> emitted
// nothing (nested code renders exactly as single code); its closer emits nothing either
private class OpenInlineTag(
    val tag: String,
    val html: Boolean,
    override val seq: Int,
    val openStart: Int = -1,
    val openLength: Int = 0,
    val merged: Boolean = false,
) : OpenTag

// An open anchor — a Markdown link, a Markdown image or an HTML <a>: the href ("" when
// href-less) plus the output positions that let an empty label degrade to the bare
// escaped URL (the same trade visit(link) makes). nested: the anchor literalized because
// an outer link was open — a type-level mark, not a URL value, so a real href can never
// collide with it (Visitor.kt and EntityVisitor.kt carry the same mark)
private class OpenAnchorTag(
    val url: String,
    val openStart: Int,
    val labelStart: Int,
    override val seq: Int,
    val nested: Boolean = false,
) : OpenTag

/**
 * Renders the full AST to Telegram HTML. Unclosed tags — emphasis and anchors alike —
 * complete at the nearest container boundary (an emphasis, strikethrough or link/image
 * label that closes over them) or at output time, always latest-opened first so the
 * output stays properly nested; code tags cannot span blocks ("Pre and Code can't
 * contain nested entities") and complete at every block start, while anchors
 * additionally complete before <pre>-emitting blocks (a pre entity may not sit inside
 * a link entity); block quotes wrap in <blockquote> tags (no line-prefix grammar to
 * satisfy, so — unlike the MarkdownV2 visitor — a quote inside a list item keeps its
 * formatting; nested quotes flatten into one wrapper — Bot API formatting rules say
 * blockquote entities can't nest); tables degrade to aligned text via
 * [renderTableLines].
 *
 * The output is valid Telegram HTML everywhere: text escapes `<`, `>` and `&` uniformly
 * (the escape set does not narrow inside code entities), attribute values additionally
 * escape the double quote, and every emitted piece carries balanced tags.
 */
internal class HtmlVisitor(
    private val depth: Int = 0,
    private val options: RenderOptions = RenderOptions(),
) : AbstractVisitor() {
    private val sb = StringBuilder()

    // Every open inline tag in opening order: this visitor's own Markdown emphasis, HTML
    // emphasis tags and HTML anchors alike (see OpenTag)
    private val openTags = ArrayDeque<OpenTag>()

    private var nextSeq = 0

    // Depth of open Markdown link entities (Link and Image both render as anchors); HTML
    // anchors are tracked in [openTags]. Telegram links cannot nest, so while any link
    // entity is open, link-like constructs degrade (see visit(link), visit(image) and
    // renderAnchorTag)
    private var openMarkdownLinks = 0

    // Nesting depth of open code entities — an inline <code>, the <code> of a fenced
    // <pre><code> or a merged code-family tag. Unlike MarkdownV2 this changes no escaping
    // (the HTML escape set is uniform); it only keeps Markdown code spans and code-family
    // HTML tags from nesting a second code level inside the first
    private var codeDepth = 0

    // Nesting depth of inline emphasis (emphasis recurses within the same visitor; deep
    // nesting flattens)
    private var inlineDepth = 0

    /**
     * Returns the rendered text, completing any unclosed tags latest-opened first (by
     * opening order, so the completed tags nest properly): inline tags emit their closer
     * — or rewind their opener when the entity's content rendered no visible text, since
     * Telegram rejects empty entities — while real anchors emit `</a>` and an anchor whose
     * label rendered no visible text rewinds and degrades to the bare escaped URL, the
     * same trade visit(link) makes; sentinel and href-less anchors emit nothing. Closers
     * append after a trimEnd that keeps them clear of trailing block separators.
     */
    fun output(): String {
        if (openTags.isEmpty()) return sb.toString()
        val closers = StringBuilder()
        while (openTags.isNotEmpty()) {
            when (val entity = openTags.removeLast()) {
                is OpenInlineTag -> closeInlineTag(entity, closers)
                is OpenAnchorTag -> closeAnchor(entity, closers, matched = false)
            }
        }
        if (closers.isEmpty()) return sb.toString()
        return sb.toString().trimEnd() + closers
    }

    // Emits the entry's closer into [sink] (output()'s closers builder or the live sb of
    // completeOpenedAfter) — or rewinds an entity whose content stayed empty, dropping
    // opener and closer both. A rewind may leave an outer entity empty in turn; the
    // caller's latest-first iteration reaches it next with the live position, so chains
    // of empty entities (`<b><i></i></b>`) unwind completely
    private fun closeInlineTag(
        entity: OpenInlineTag,
        sink: StringBuilder,
    ) {
        if (entity.merged) {
            // A merged entry emitted no opener — its closer emits nothing either. It owns
            // a depth unit exactly like the entry it merged into (see renderHtmlInlineTag)
            if (entity.tag == "code") codeDepth--
            return
        }
        if (entity.openStart >= 0 && sb.length == entity.openStart + entity.openLength) {
            // Nothing visible was emitted inside the entity — Telegram rejects empty
            // entities, so the opener rewinds and the closer never emits. The depth unit
            // goes back too: the entity no longer exists
            if (entity.tag == "code") codeDepth--
            sb.setLength(entity.openStart)
            return
        }
        if (entity.tag == "code") codeDepth--
        sink.append("</${entity.tag}>")
    }

    // The anchor twin of [closeInlineTag]: a real anchor closes (or degrades to its bare
    // escaped URL when the label stayed blank), a href-less anchor closes silently (its
    // opener emitted nothing), and a nested sentinel closes with the literal escaped
    // `</a>` at a matched close — its opener went literal, so the closer must too or the
    // pair reads asymmetrically — while staying silent at a completion point, where the
    // literal would land as stray visible text (the MarkdownV2 twin makes the same split)
    private fun closeAnchor(
        entity: OpenAnchorTag,
        sink: StringBuilder,
        matched: Boolean,
    ) {
        when {
            entity.nested -> {
                if (matched) sink.append(escapeHtmlText("</a>"))
            }

            entity.url.isEmpty() -> {}

            regionIsBlank(entity.labelStart) -> {
                // A blank label means nothing was emitted after the opener — anything
                // opened inside would have emitted a tag — so this anchor is the latest
                // open entry and rewinding discards only whitespace
                sb.setLength(entity.openStart)
                sb.append(escapeHtmlText(entity.url))
            }

            else -> {
                sink.append("</a>")
            }
        }
    }

    override fun visit(text: Text) {
        sb.append(escapeHtmlText(text.literal))
    }

    override fun visit(document: Document) {
        visitChildren(document)
    }

    override fun visit(paragraph: Paragraph) {
        // An inline <code> opened in an earlier block cannot span this one: Telegram
        // rejects code entities containing other entities, and any tag this paragraph
        // emits would sit inside it (see completeOpenInlineCodeTags)
        completeOpenInlineCodeTags()
        val start = sb.length
        visitChildren(paragraph)
        // A paragraph that rendered no visible text is no-content: one whose every child
        // dropped out (a lone inline comment, an empty-alt empty-URL image), or one whose
        // render is whitespace only. The region rewinds and no separator follows — the
        // same blank-render skip the truncation (renderHtmlBlocks) and chunking
        // (chunkHtmlBlocks) paths make on the rendered shape
        if (regionIsBlank(start)) {
            sb.setLength(start)
        } else {
            sb.append("\n\n")
        }
    }

    // Telegram HTML has no heading entity either: the level is spelled out as literal
    // hashes (no escaping needed — '#' carries no meaning in HTML text), the same trade
    // the MarkdownV2 render makes
    override fun visit(heading: Heading) {
        completeOpenInlineCodeTags()
        sb.append(plainHeadingPrefixOf(heading))
        visitChildren(heading)
        sb.append("\n\n")
    }

    // A quote wraps its children in <blockquote> tags — no line-prefix grammar to
    // satisfy, so quotes keep their formatting inside list items too (the inListItem
    // degradation of the MarkdownV2 visitor has no HTML counterpart). Bot API formatting
    // rules say blockquote entities can't nest: a quote child that is a quote itself
    // flattens into this level's wrapper (the entities twin's identical ranges degrade
    // to the same single quote at send time, and the MarkdownV2 twin never opens a
    // second level for glued markers). An all-blank quote renders nothing: an empty tag
    // pair is no quote at all (the MarkdownV2 render keeps a bare ">" marker there — the
    // marker is the quote's whole output)
    override fun visit(blockQuote: BlockQuote) {
        val pieces = mutableListOf<String>()
        appendQuoteChildren(blockQuote, pieces, 0)
        if (pieces.isEmpty()) return
        // A code entity from an earlier block completes first — the wrapper is a block
        // tag, and Telegram rejects block tags inside inline code
        completeOpenInlineCodeTags()
        sb.append("<blockquote>")
        sb.append(pieces.joinToString("\n\n"))
        sb.append("</blockquote>\n\n")
    }

    // Collects the quote's rendered children, flattening nested quotes into the same
    // level (see visit(blockQuote)). [quoteDepth] counts the flattened quote levels —
    // each consumed one depth unit as a wrapper in the old shape, and still must: a
    // pathological nesting chain flattens to escaped text at the cap exactly like every
    // other structural path (see MAX_RENDER_DEPTH)
    private fun appendQuoteChildren(
        blockQuote: BlockQuote,
        pieces: MutableList<String>,
        quoteDepth: Int,
    ) {
        var child = blockQuote.firstChild
        while (child != null) {
            if (child is BlockQuote) {
                if (depth + quoteDepth + 1 >= MAX_RENDER_DEPTH) {
                    val plain = StringBuilder()
                    appendPlainText(plain, child)
                    val rendered = escapeHtmlText(plain.toString().trimEnd('\n'))
                    if (rendered.isNotBlank()) {
                        pieces += rendered
                    }
                } else {
                    appendQuoteChildren(child, pieces, quoteDepth + 1)
                }
            } else {
                val rendered = renderQuoteChild(child)
                if (rendered.isNotBlank()) {
                    pieces += rendered
                }
            }
            child = child.next
        }
    }

    override fun visit(bulletList: BulletList) {
        renderList(bulletList)
    }

    override fun visit(orderedList: OrderedList) {
        renderList(orderedList)
    }

    // Telegram HTML has no thematic break entity; em dashes need no escaping in HTML
    // text, unlike MarkdownV2's '-' which would need a backslash per dash
    override fun visit(thematicBreak: ThematicBreak) {
        completeOpenInlineCodeTags()
        sb.append("———\n\n")
    }

    override fun visit(fencedCodeBlock: FencedCodeBlock) {
        val (open, close) = htmlFenceOf(fencedCodeBlock)
        completeOpenInlineCodeAndAnchorTags()
        sb.append(open)
        sb.append(escapeHtmlText(fencedCodeBlock.literal.orEmpty().trimEnd('\n')))
        sb.append(close)
        sb.append("\n\n")
    }

    override fun visit(indentedCodeBlock: IndentedCodeBlock) {
        completeOpenInlineCodeAndAnchorTags()
        sb.append("<pre>")
        sb.append(escapeHtmlText(indentedCodeBlock.literal.trimEnd('\n')))
        sb.append("</pre>\n\n")
    }

    override fun visit(code: Code) {
        if (code.literal.isEmpty()) {
            // An empty span would be an empty entity; the parser never produces one,
            // this covers hand-built ASTs
            return
        }
        if (codeDepth > 0) {
            // Inside an open code entity the span's own <code> tags would nest a second
            // code level; keep the backticks instead as literal text — they carry no
            // meaning in HTML and render exactly as the MarkdownV2 render shows them
            // inside a code entity
            sb.append('`').append(escapeHtmlText(code.literal)).append('`')
        } else {
            sb.append("<code>")
            sb.append(escapeHtmlText(code.literal))
            sb.append("</code>")
        }
    }

    override fun visit(emphasis: Emphasis) {
        if (codeDepth > 0) {
            // Telegram forbids entities nested inside code ("Pre and Code can't contain
            // nested entities"): the tags drop, the content renders as plain code text —
            // the same degradation the MarkdownV2 twin's silent emphasis entries make
            visitChildren(emphasis)
            return
        }
        if (!enterInline()) {
            appendEscapedHtmlPlainText(sb, emphasis)
            return
        }
        val seq = nextSeq++
        val openStart = sb.length
        sb.append("<i>")
        openTags.addLast(OpenInlineTag("i", html = false, seq = seq, openStart = openStart, openLength = 3))
        visitChildren(emphasis)
        completeOpenedAfter(seq)
        // completeOpenedAfter left our entry innermost by construction
        closeInlineTag(openTags.removeLast() as OpenInlineTag, sb)
        exitInline()
    }

    override fun visit(strongEmphasis: StrongEmphasis) {
        if (codeDepth > 0) {
            // See visit(emphasis): the tags drop inside an open code entity
            visitChildren(strongEmphasis)
            return
        }
        if (!enterInline()) {
            appendEscapedHtmlPlainText(sb, strongEmphasis)
            return
        }
        val seq = nextSeq++
        val openStart = sb.length
        sb.append("<b>")
        openTags.addLast(OpenInlineTag("b", html = false, seq = seq, openStart = openStart, openLength = 3))
        visitChildren(strongEmphasis)
        completeOpenedAfter(seq)
        closeInlineTag(openTags.removeLast() as OpenInlineTag, sb)
        exitInline()
    }

    override fun visit(customNode: CustomNode) {
        when (customNode) {
            is Strikethrough -> {
                if (codeDepth > 0) {
                    // See visit(emphasis): the tags drop inside an open code entity
                    visitChildren(customNode)
                    return
                }
                if (!enterInline()) {
                    appendEscapedHtmlPlainText(sb, customNode)
                    return
                }
                val seq = nextSeq++
                val openStart = sb.length
                sb.append("<s>")
                openTags.addLast(OpenInlineTag("s", html = false, seq = seq, openStart = openStart, openLength = 3))
                visitChildren(customNode)
                completeOpenedAfter(seq)
                closeInlineTag(openTags.removeLast() as OpenInlineTag, sb)
                exitInline()
            }

            is TaskListItemMarker -> {
                // No checkbox entity exists in any Telegram format: the marker spells
                // back out as literal text, the same shape the unextended parse shows
                sb.append(if (customNode.isChecked) "[x] " else "[ ] ")
            }

            is FootnoteReference -> {
                // No footnote entity either: the reference stays visible as its source
                // spelling — pre-promotion it vanished entirely under the unknown-node
                // traversal, losing the marker from the text. The label is arbitrary
                // non-whitespace source text, so it escapes like any other (the
                // MarkdownV2 twin's escapeText at the same spot)
                sb.append(escapeHtmlText("[^${customNode.label}]"))
            }

            else -> {
                super.visit(customNode)
            }
        }
    }

    /**
     * Completes unclosed tags opened strictly after [seq] — latest-opened first — before
     * the entry owning [seq] closes itself. Runs when an inline container — emphasis,
     * strikethrough, a Markdown link or image label — closes with HTML tags still open
     * inside it (missing closing tags, or upstream parsing defects). An anchor opened
     * inside completes exactly like a matched `</a>` would: a real one emits its closer —
     * or rewinds, when its label rendered no visible text, the same empty-label trade
     * visit(link) makes — while sentinels and href-less anchors close silently.
     */
    private fun completeOpenedAfter(seq: Int) {
        while (openTags.isNotEmpty() && openTags.last().seq > seq) {
            when (val entity = openTags.removeLast()) {
                is OpenInlineTag -> closeInlineTag(entity, sb)
                is OpenAnchorTag -> closeAnchor(entity, sb, matched = false)
            }
        }
    }

    /**
     * Completes every open inline code tag (and everything opened inside it) before a
     * block emits its own structure: left open, the block's tags or text would sit inside
     * an inline code entity — a nesting Telegram's parser rejects ("Pre and Code can't
     * contain nested entities"). Other inline tags may span the block boundary (the
     * MarkdownV2 render ships the same shape for its emphasis entities), so only the code
     * family force-closes here. The closers land before the trailing block separator —
     * appended after it they would sit inside the block — so the separator is lifted and
     * re-emitted.
     */
    private fun completeOpenInlineCodeTags() {
        val outermostCode =
            openTags.firstOrNull { it is OpenInlineTag && it.tag == "code" } as? OpenInlineTag
                ?: return
        completeThroughSeparator(outermostCode.seq - 1)
    }

    /**
     * The <pre>-emitting twin of [completeOpenInlineCodeTags]: completes code tags and
     * anchors alike (everything opened inside the earliest of them) before a `<pre>`
     * block emits. A pre entity may not sit inside a link entity — the entities
     * documentation's "Cannot be nested inside other entities" — and a Markdown link
     * never spans blocks, so an unclosed HTML anchor must not reach across one either.
     * Emphasis keeps the documented complete-at-output behavior (the pinned
     * bold-over-pre containment).
     */
    private fun completeOpenInlineCodeAndAnchorTags() {
        val boundary =
            openTags
                .filter { (it is OpenInlineTag && it.tag == "code") || it is OpenAnchorTag }
                .minOfOrNull { it.seq }
                ?: return
        completeThroughSeparator(boundary - 1)
    }

    // Completes everything opened at or after [boundarySeq], lifting the trailing block
    // separator first and re-emitting it after the closers — appended after it they would
    // sit inside the following block (see completeOpenInlineCodeTags)
    private fun completeThroughSeparator(boundarySeq: Int) {
        var separatorLength = 0
        while (separatorLength < sb.length && sb[sb.length - 1 - separatorLength] == '\n') separatorLength++
        sb.setLength(sb.length - separatorLength)
        completeOpenedAfter(boundarySeq)
        repeat(separatorLength) { sb.append('\n') }
    }

    override fun visit(customBlock: CustomBlock) {
        when (customBlock) {
            is TableBlock -> {
                visitTable(customBlock)
            }

            is FootnoteDefinition -> {
                // The definition keeps its place in the document (Telegram message text
                // is linear): label spelled back out, content rendered as the blocks it
                // wraps — the same visible shape the unextended parse shows. The label
                // escapes like the reference's does, and a code tag from an earlier
                // block completes first — the label and blocks are structure and block
                // content, neither may sit inside inline code
                completeOpenInlineCodeTags()
                sb.append(escapeHtmlText("[^${customBlock.label}]: "))
                visitChildren(customBlock)
            }

            else -> {
                super.visit(customBlock)
            }
        }
    }

    private fun visitTable(table: TableBlock) {
        val lines = renderTableLines(table, options)
        if (lines.isEmpty()) {
            return
        }

        completeOpenInlineCodeAndAnchorTags()
        sb.append("<pre>")
        sb.append(escapeHtmlText(lines.joinToString("\n")))
        sb.append("</pre>\n\n")
    }

    override fun visit(link: Link) {
        val destination = link.destination.orEmpty()
        // Telegram rejects link entities with an empty URL, and links cannot nest (a nested
        // Link cannot come out of CommonMark parsing; the guard covers hand-built ASTs) —
        // degrade to the link text alone in both cases. A link entity inside an open code
        // entity is equally illegal ("Pre and Code can't contain nested entities"): the
        // label text stays, the link drops
        if (destination.isEmpty() || linkEntityOpen() || codeDepth > 0) {
            visitChildren(link)
            return
        }
        val entrySeq = nextSeq++
        val openStart = sb.length
        openMarkdownLinks++
        sb.append("<a href=\"").append(escapeHtmlAttribute(destination)).append("\">")
        val labelStart = sb.length
        visitChildren(link)
        openMarkdownLinks--
        // Telegram also rejects link entities with empty text: a label that renders to no
        // visible text (no children, whitespace-only, or children like a lone HTML
        // comment) degrades to the bare escaped URL. Rewinding is safe — inside a label
        // every pushed tag was accompanied by output, so a blank label left nothing open
        if (regionIsBlank(labelStart)) {
            sb.setLength(openStart)
            sb.append(escapeHtmlText(destination))
            return
        }
        // Whatever HTML opened inside the label closes before the link does: left open it
        // would cross the </a> boundary into crossed tags, or leak codeDepth onto the text
        // after the link. Anchors opened inside are all literalized sentinels (the entry
        // guard saw to that), so they close silently here
        completeOpenedAfter(entrySeq)
        sb.append("</a>")
    }

    // Telegram HTML has no images in message text: degrade to a plain link on the alt
    // text; an alt that renders to nothing degrades to the bare escaped URL (see
    // visit(link)). Nested inside another link (the README badge shape
    // [![alt](img)](target)) only the alt text survives, keeping the outer link
    // single-level — Telegram links cannot nest. The one carve-out is the Bot API's own
    // custom emoji spelling — an image whose destination reads tg://emoji?id=… — which
    // renders as <tg-emoji>, this format's native custom emoji form, mirroring the tag
    // path (see renderEmojiTag); the guards match it: inside a link or a code entity the
    // image takes the ordinary degradation
    override fun visit(image: Image) {
        val destination = image.destination.orEmpty()
        if (destination.isNotEmpty() && !linkEntityOpen() && codeDepth == 0) {
            val emojiId = tgEmojiUrlIdOf(destination)
            if (emojiId != null) {
                val entrySeq = nextSeq++
                val openStart = sb.length
                val opener = "<tg-emoji emoji-id=\"$emojiId\">"
                sb.append(opener)
                openTags.addLast(
                    OpenInlineTag("tg-emoji", html = true, seq = entrySeq, openStart = openStart, openLength = opener.length),
                )
                visitChildren(image)
                // Whatever HTML opened inside the fallback closes before the emoji does:
                // left open it would cross the </tg-emoji> boundary into crossed tags —
                // or pop under the cast below, which used to crash the whole render on an
                // unclosed anchor. linkEntityOpen's tg-emoji arm degraded those anchors
                // to literals already, and completeOpenedAfter leaves our entry innermost
                // by construction
                completeOpenedAfter(entrySeq)
                // A fallback that rendered no visible text is an empty entity — the
                // rewind drops the pair (see closeInlineTag)
                closeInlineTag(openTags.removeLast() as OpenInlineTag, sb)
                return
            }
        }
        // See visit(link): empty URL, nesting and open code entities all degrade to the
        // alt text alone
        if (destination.isEmpty() || linkEntityOpen() || codeDepth > 0) {
            visitChildren(image)
            return
        }
        val entrySeq = nextSeq++
        val openStart = sb.length
        openMarkdownLinks++
        sb.append("<a href=\"").append(escapeHtmlAttribute(destination)).append("\">")
        val labelStart = sb.length
        visitChildren(image)
        openMarkdownLinks--
        // See visit(link): a label with no visible text degrades to the bare escaped URL
        if (regionIsBlank(labelStart)) {
            sb.setLength(openStart)
            sb.append(escapeHtmlText(destination))
            return
        }
        // See visit(link): HTML opened inside the alt text closes before the link does
        completeOpenedAfter(entrySeq)
        sb.append("</a>")
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

        // Block-level input HTML cannot pass through: anything outside Telegram's tiny
        // tag set draws a 400 from the Bot API, so the block degrades to escaped text in
        // a <pre> — the same trade tables make. (Inline input HTML maps tag by tag; see
        // renderHtmlInlineTag.)
        completeOpenInlineCodeAndAnchorTags()
        sb.append("<pre>").append(escapeHtmlText(literal)).append("</pre>\n\n")
    }

    override fun visit(htmlInline: HtmlInline) {
        renderHtmlInlineTag(htmlInline.literal)
    }

    /**
     * A closing tag emits its closer only when it matches the innermost open HTML entity,
     * otherwise it is escaped literally — an orphan `</b>` emitting a bare closer would
     * unbalance the output. Innermost-only matching also keeps a closer from crossing a
     * still-open anchor (or from popping a Markdown emphasis' own stack entry); the
     * crossed entity completes later instead, latest-opened first. Anchors behave the
     * same; an `<a>` without href pushes the empty sentinel and its closing only pops,
     * emitting no link.
     */
    private fun renderHtmlInlineTag(literal: String) {
        val tag = literal.trim()
        // "<!--" is a "<!" — one prefix covers comments and declarations alike
        if (tag.startsWith("<!") || tag.startsWith("<?")) {
            return
        }

        val parts =
            parseHtmlTag(tag) ?: run {
                sb.append(escapeHtmlText(tag))
                return
            }
        if (parts.name == "br") {
            sb.append('\n')
            return
        }
        if (parts.name == "a") {
            renderAnchorTag(tag, parts)
            return
        }

        val mapped =
            when (parts.name) {
                "b", "strong" -> "b"
                "i", "em" -> "i"
                "u", "ins" -> "u"
                "s", "del", "strike" -> "s"
                "code", "kbd", "samp", "tt" -> "code"
                "tg-spoiler" -> "tg-spoiler"
                "tg-emoji" -> return renderEmojiTag(tag, parts)
                else -> null
            }
        if (mapped == null || parts.selfClosing) {
            // Unknown names (and self-closing emphasis forms) stay literal text — the
            // same degradation the MarkdownV2 render makes
            sb.append(escapeHtmlText(tag))
            return
        }
        if (parts.closing) {
            val top = openTags.lastOrNull()
            if (top is OpenInlineTag && top.html && top.tag == mapped) {
                openTags.removeLast()
                closeInlineTag(top, sb)
            } else {
                sb.append(escapeHtmlText(tag))
            }
            return
        }
        if (mapped == "code" && codeDepth > 0) {
            // A code-family tag never nests a second code level: inside an open code
            // entity it merges into it (nested code renders exactly as single code), the
            // tags dropping while the content continues code-styled
            openTags.addLast(OpenInlineTag("code", html = true, seq = nextSeq++, merged = true))
            codeDepth++
            return
        }
        if (codeDepth > 0) {
            // Any other formatting tag inside an open code entity would nest an entity
            // Telegram rejects ("Pre and Code can't contain nested entities"): the tag
            // drops — merged keeps the closer matched and emission-free, the content
            // continues as plain code text (the MarkdownV2 twin's silent entries)
            openTags.addLast(OpenInlineTag(mapped, html = true, seq = nextSeq++, merged = true))
            return
        }
        val openStart = sb.length
        sb.append('<').append(mapped).append('>')
        openTags.addLast(OpenInlineTag(mapped, html = true, seq = seq(), openStart = openStart, openLength = mapped.length + 2))
        if (mapped == "code") codeDepth++
    }

    // <tg-emoji> passes through with its emoji-id attribute — one of the constructs only
    // the HTML parse mode can express. The id must decode to a positive int64 value
    // (custom emoji ids are numeric); anything else degrades to literal text like every
    // unknown tag
    private fun renderEmojiTag(
        tag: String,
        parts: TagParts,
    ) {
        // The closing tag carries no attributes to validate — match it before the id
        // check, or a legal </tg-emoji> would read as id-less and go literal
        if (parts.closing) {
            if (!parts.selfClosing) {
                val top = openTags.lastOrNull()
                if (top is OpenInlineTag && top.html && top.tag == "tg-emoji") {
                    openTags.removeLast()
                    closeInlineTag(top, sb)
                    return
                }
            }
            sb.append(escapeHtmlText(tag))
            return
        }
        if (codeDepth > 0) {
            // A custom emoji entity can't nest inside code either: the tags drop, the
            // emoji char renders as plain code text (merged keeps the closer silent)
            openTags.addLast(OpenInlineTag("tg-emoji", html = true, seq = seq(), merged = true))
            return
        }
        val id = tgEmojiIdOf(parts.attrs)
        if (id == null || parts.selfClosing) {
            sb.append(escapeHtmlText(tag))
            return
        }
        val opener = "<tg-emoji emoji-id=\"$id\">"
        val openStart = sb.length
        sb.append(opener)
        openTags.addLast(OpenInlineTag("tg-emoji", html = true, seq = seq(), openStart = openStart, openLength = opener.length))
    }

    private fun seq(): Int = nextSeq++

    private fun renderAnchorTag(
        tag: String,
        parts: TagParts,
    ) {
        if (parts.selfClosing) {
            sb.append(escapeHtmlText(tag))
            return
        }
        if (parts.closing) {
            // See renderHtmlInlineTag: </a> matches only the innermost open entity — an
            // anchor closed while something opened inside it is still open goes literal
            // rather than emitting a crossed closer
            val top = openTags.lastOrNull()
            if (top !is OpenAnchorTag) {
                sb.append(escapeHtmlText(tag))
                return
            }
            openTags.removeLast()
            closeAnchor(top, sb, matched = true)
            return
        }
        // Telegram links cannot nest: while any link entity is open (Markdown link, image
        // link or an outer anchor), an inner anchor and its closing tag are escaped literally
        if (linkEntityOpen()) {
            openTags.addLast(OpenAnchorTag("", sb.length, sb.length, nextSeq++, nested = true))
            sb.append(escapeHtmlText(tag))
            return
        }
        // A link entity can't nest inside an open code entity: the tags drop silently —
        // the href-less sentinel shape, whose closer emits nothing — and the label text
        // continues as plain code text
        if (codeDepth > 0) {
            openTags.addLast(OpenAnchorTag("", sb.length, sb.length, nextSeq++))
            return
        }
        // href="" is treated like a missing href: the empty URL degrades to plain text
        val href = extractHref(parts.attrs)
        if (href == null) {
            openTags.addLast(OpenAnchorTag("", sb.length, sb.length, nextSeq++))
            return
        }
        val openStart = sb.length
        sb.append("<a href=\"").append(escapeHtmlAttribute(href)).append("\">")
        openTags.addLast(OpenAnchorTag(href, openStart, sb.length, nextSeq++))
    }

    // A link entity is open when an anchor is (or a Markdown link), or a tg-emoji entry:
    // the MarkdownV2 twin's emoji entries ARE anchors (its tag path pushes OpenAnchor,
    // its image path bumps openMarkdownLinks), so its linkEntityOpen reads true across a
    // custom-emoji fallback — links cannot nest inside it there, and the twin formats
    // must agree. Merged entries (a tg-emoji degraded inside code) count too, the same
    // way the twin's code-depth emoji sentinel does
    private fun linkEntityOpen(): Boolean =
        openTags.any { it is OpenAnchorTag || (it is OpenInlineTag && it.tag == "tg-emoji") } || openMarkdownLinks > 0

    // Whether the output region starting at [start] renders no visible text: empty or
    // whitespace only. The label callers check it immediately after the label's children,
    // so the region runs exactly to the end of the current output; any tag a label had
    // opened would have emitted a non-whitespace character, so a blank label region
    // guarantees nothing was left open inside it. visit(paragraph) asks the same question
    // of a paragraph's region for its no-content skip
    private fun regionIsBlank(start: Int): Boolean {
        for (index in start until sb.length) {
            if (!sb[index].isWhitespace()) return false
        }
        return true
    }

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
    // its marker consumed — the same walk forEachHtmlListItem makes, so the full render,
    // truncation and chunking agree on which items exist and how they number. A code tag
    // from an earlier block completes first — the marker is structure, and a block quote
    // or pre inside an item would sit inside the code entity
    private fun renderList(list: Node) {
        completeOpenInlineCodeTags()
        val start = sb.length
        val marker = plainListMarkerOf(list)
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
        // nothing and separates nothing — the same trade visit(paragraph) makes
        if (sb.length > start) sb.append('\n')
    }

    // appendPlainText flattens with no output-format escaping (the entities protocol
    // needs none); the depth-cap fallbacks below land that text in HTML output, so it
    // escapes on the way in
    private fun appendEscapedHtmlPlainText(
        sb: StringBuilder,
        node: Node,
    ) {
        val plain = StringBuilder()
        appendPlainText(plain, node)
        sb.append(escapeHtmlText(plain.toString()))
    }

    // Renders a nested block (quote body, list item) in a child visitor; at the depth cap
    // the subtree flattens to plain text, escaped like every other text this visitor
    // emits (see MAX_RENDER_DEPTH). Trailing newlines are trimmed here — callers prepend
    // their own prefixes and separators
    private fun renderChild(node: Node): String =
        if (depth + 1 >= MAX_RENDER_DEPTH) {
            val plain = StringBuilder()
            appendPlainText(plain, node)
            escapeHtmlText(plain.toString().trimEnd('\n'))
        } else {
            val visitor = HtmlVisitor(depth + 1, options)
            visitor.visitChildren(node)
            visitor.output().trimEnd('\n')
        }

    // The quote-child variant: the node renders through its own full block shape —
    // visit(node), not visitChildren(node). BlockQuote children never arrive here —
    // visit(blockQuote) flattens nested quotes into its own level (Bot API formatting
    // rules say blockquote entities can't nest) — so every child this renders is a
    // non-quote block. Depth capping, escaping and trailing-newline trimming match
    // renderChild
    private fun renderQuoteChild(node: Node): String =
        if (depth + 1 >= MAX_RENDER_DEPTH) {
            val plain = StringBuilder()
            appendPlainText(plain, node)
            escapeHtmlText(plain.toString().trimEnd('\n'))
        } else {
            val visitor = HtmlVisitor(depth + 1, options)
            node.accept(visitor)
            visitor.output().trimEnd('\n')
        }
}
