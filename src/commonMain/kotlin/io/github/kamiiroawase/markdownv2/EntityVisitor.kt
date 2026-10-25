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

// One entry per open formatting range — Markdown emphasis (this visitor's own visits),
// HTML tags, links and block quotes alike: every range records where its text starts,
// and closing turns it into a MessageEntity computed against the live length. A range
// whose content stays empty closes into nothing (Telegram drops zero-length entities,
// the same trade the HTML render's empty-pair rewind makes)
private data class OpenRange(
    val type: MessageEntityType,
    val start: Int,
    // TEXT_LINK only: the URL, "" for a href-less anchor (no entity on close)
    val url: String,
    // PRE only
    val language: String?,
    // CUSTOM_EMOJI only
    val customEmojiId: String?,
    val seq: Int,
    // Pushed by an input HTML tag rather than by this visitor's own visits — only
    // html-owned entries may be matched and popped by a closing tag
    val html: Boolean,
    // A no-output degradation (nested same-type tag, a tag inside an open code range):
    // no entity on close, and a code-typed one still returns the depth unit its push
    // took
    val merged: Boolean = false,
    // TEXT_LINK only: the anchor literalized because an outer link was open (tag text goes
    // literal) — a type-level mark, not a URL value, so a real href can never collide with
    // it (Visitor.kt and HtmlVisitor.kt carry the same mark)
    val nested: Boolean = false,
)

/**
 * Renders the full AST to the entities protocol: plain display text plus formatting
 * ranges over it. The text carries no markup and needs no escaping — the whole class of
 * parse failures the string formats defend against cannot arise — so this visitor only
 * does bookkeeping: append text, track range starts, compute lengths on close.
 *
 * Unclosed ranges complete at the nearest container boundary (an emphasis, strikethrough
 * or link/image label that closes over them) or at output time, latest-opened first, so
 * ranges always nest by containment. Code-family ranges force-close at every block start
 * (a code entity may contain no other entities), and text_link ranges force-close before
 * PRE blocks too (a pre entity may not sit inside a link entity) — the same containment
 * discipline the HTML visitor enforces.
 */
internal class EntityVisitor(
    private val depth: Int = 0,
    private val options: RenderOptions = RenderOptions(),
) : AbstractVisitor() {
    private val sb = StringBuilder()

    // Closed ranges, in close order (nested ranges before their containers)
    private val entities = mutableListOf<MessageEntity>()

    private val openRanges = ArrayDeque<OpenRange>()

    private var nextSeq = 0

    // Depth of open Markdown link entities (Link and Image both render as text_link);
    // HTML anchors are tracked in [openRanges]. Telegram links cannot nest, so while any
    // link entity is open, link-like constructs degrade (see visit(link), visit(image)
    // and renderAnchorTag)
    private var openMarkdownLinks = 0

    // Nesting depth of open code ranges: keeps Markdown code spans and code-family HTML
    // tags from nesting a second code level inside the first (nested code renders
    // exactly as single code)
    private var codeDepth = 0

    // Nesting depth of inline emphasis (emphasis recurses within the same visitor; deep
    // nesting flattens)
    private var inlineDepth = 0

    /**
     * Returns the rendered text with entities, completing any unclosed ranges
     * latest-opened first. A range completed here may have covered the trailing block
     * separator (the MarkdownV2/HTML twins trim before appending closers; ranges have
     * no closer text), so entities clamp to the trimmed length. Only the trailing side
     * trims: a leading trim would shift the text under offsets computed before it, and
     * the twins return their buffers as-is.
     */
    fun output(): EntityText {
        val text = outputUntrimmed().text.trimEnd()
        return EntityText(text, entities.toList().clampedTo(text.length))
    }

    /**
     * The inline-group twin of [output]: everything completes the same way, but nothing
     * trims. An inline group carries no block separators, so the trailing whitespace the
     * block flavor trims is content here — the space before the next node's text at a
     * chunking seam, which the losslessness guarantee forbids dropping (the group renders
     * whole in the full render, whitespace included). Block-level trailing whitespace
     * still trims where it belongs, at the accumulator's flush — the same place the
     * string formats trim theirs.
     */
    fun outputUntrimmed(): EntityText {
        while (openRanges.isNotEmpty()) {
            closeRange(openRanges.removeLast())
        }
        return EntityText(sb.toString(), entities.toList())
    }

    // Turns the range into its entity, measured against the live length. A zero-length
    // range drops: Telegram ignores-or-rejects empty entities, and an empty range
    // formats nothing. A href-less anchor (the empty url) and a nested mark close
    // silently; a real anchor whose label stayed blank rewinds and degrades to the bare
    // URL text. Every code-typed range that took a codeDepth unit at open (merged ones
    // included, see renderHtmlInlineTag) gives it back here.
    //
    // [pendingSeparator] tells the blank-label degradation that this close runs at a
    // block boundary, where the next block is about to emit and the separator between
    // it and the bare URL must survive (see the branch); a synchronous close sits
    // mid-paragraph, the enclosing paragraph appends its own separator once the URL
    // text exists, and no separator re-emits here
    private fun closeRange(
        range: OpenRange,
        pendingSeparator: Boolean = false,
    ) {
        if (range.type == MessageEntityType.CODE) codeDepth--
        when {
            range.merged -> {}

            range.nested -> {}

            range.type == MessageEntityType.TEXT_LINK && range.url.isEmpty() -> {}

            range.type == MessageEntityType.CUSTOM_EMOJI && regionIsBlank(range.start) -> {
                // Telegram requires the fallback: one that renders to no visible text
                // degrades to the bare custom-emoji URL, the trade visit(link) and the
                // MarkdownV2/HTML twins make of every blank label — all three formats
                // show the same text
                degradeToBareUrl(range, TG_EMOJI_URL_PREFIX + range.customEmojiId.orEmpty(), pendingSeparator)
            }

            range.type == MessageEntityType.CUSTOM_EMOJI -> {
                // Telegram requires the fallback to be exactly one regular emoji (see
                // isSingleEmojiFallback): anything else is an entity the server silently
                // ignores, so the range never becomes an entity — the fallback text ships
                // as plain content with whatever inner ranges it registered (formatting
                // survives, the exact degrade the MarkdownV2 and HTML twins make of the
                // same shape). A code range inside defeats the text-based check (a span
                // whose literal IS one emoji formats the cluster while looking single),
                // so any CODE registered in the region bars the entity too — the degrade
                // the MarkdownV2 twin's marker-carrying label region makes of the shape.
                // A valid fallback's range wraps the cluster and any leading whitespace
                // (the label as rendered; the MarkdownV2 twin's `![ 😀]` label keeps its
                // space inside the entity too), the trailing separator staying outside —
                // the shape the MarkdownV2 twin ships (its completion lifts the separator
                // before the closer emits)
                val codeInside = entities.any { it.type == MessageEntityType.CODE && it.offset >= range.start }
                if (!codeInside && isSingleEmojiFallback(sb.substring(range.start))) {
                    var end = sb.length
                    while (end > range.start && sb[end - 1].isWhitespace()) end--
                    val length = end - range.start
                    if (length > 0) {
                        entities +=
                            MessageEntity(
                                range.type,
                                range.start,
                                length,
                                null,
                                null,
                                range.customEmojiId,
                            )
                    }
                }
            }

            range.type == MessageEntityType.TEXT_LINK && regionIsBlank(range.start) -> {
                degradeToBareUrl(range, range.url, pendingSeparator)
            }

            else -> {
                val length = sb.length - range.start
                if (length > 0) {
                    entities +=
                        MessageEntity(
                            range.type,
                            range.start,
                            length,
                            range.url.takeIf { range.type == MessageEntityType.TEXT_LINK },
                            range.language,
                            range.customEmojiId,
                        )
                }
            }
        }
    }

    // The blank-label degradation both URL-carrying range families share: a blank label
    // means nothing was emitted after the range opened — anything opened inside would
    // have emitted text — so the range is the latest open entry and rewinding discards
    // only whitespace. Ranges closed inside the blank label left entities behind (a
    // whitespace-only code span registers CODE over its spaces); their offsets sit in
    // the rewound region, so they drop with it — kept, they would format whichever text
    // the rewind re-seats at those offsets (the URL, or the next paragraph). The blank
    // region may also have swallowed a pending block separator (the paragraph's
    // "\n\n"); the URL would glue straight onto the next block's text without it, so
    // at a block-boundary completion ([pendingSeparator]) the trailing newline run
    // lifts and re-emits around the URL — the same lift the HTML twin's
    // completeThroughSeparator makes. A synchronous close re-emits nothing: the
    // enclosing paragraph appends its own separator after the URL (its blank check
    // runs once the URL text exists), and re-emitting here doubled it; the label's own
    // trailing newline (a soft break) drops with the rewind, the trade both string
    // twins make of the blank label. The one boundary shape with no newline to lift —
    // a range whose paragraph blank-skipped, so the region is empty and the rewind a
    // no-op — takes a fresh separator from the boundary itself
    private fun degradeToBareUrl(
        range: OpenRange,
        url: String,
        pendingSeparator: Boolean,
    ) {
        var separatorLength = 0
        val regionLength = sb.length - range.start
        while (separatorLength < regionLength && sb[sb.length - 1 - separatorLength] == '\n') {
            separatorLength++
        }
        rewindTo(range.start)
        sb.append(url)
        if (pendingSeparator) {
            if (separatorLength > 0) {
                repeat(separatorLength) { sb.append('\n') }
            } else if (regionLength == 0) {
                sb.append("\n\n")
            }
        }
    }

    override fun visit(text: Text) {
        sb.append(text.literal)
    }

    override fun visit(document: Document) {
        visitChildren(document)
    }

    override fun visit(paragraph: Paragraph) {
        // A code range opened in an earlier block cannot span this one: Telegram rejects
        // code entities containing other entities, and any range this paragraph opens
        // would sit inside it (see completeOpenCodeRanges)
        completeOpenCodeRanges()
        val start = sb.length
        visitChildren(paragraph)
        // A paragraph that rendered no visible text is no-content: the region rewinds
        // and no separator follows — the same blank-render skip every pipeline makes.
        // The rewind also drops entities registered inside the region (a whitespace-only
        // code span's CODE, say) — see rewindTo
        if (regionIsBlank(start)) {
            rewindTo(start)
        } else {
            sb.append("\n\n")
        }
    }

    // Rolls the text back to [start], dropping every entity registered inside the rewound
    // region (offsets at or past the rewind point; text positions only grow, so anything
    // closed before the region opened sits entirely before it) — kept, those entities
    // would format whatever text the rewind re-seats at their offsets. A range opened
    // BEFORE the rewind and closed inside the region (only HTML-opened ranges can: a
    // Markdown emphasis or link closes within its own container, before any blank-region
    // rewind its container makes) survives the offset filter with a stale end — its
    // length was measured against text the rewind replaces — so it shrinks to the rewind
    // point, at the very position its closer last saw. Open ranges deliberately survive
    // the rewind: one pushed by an input-HTML opener emitted no text, and completing
    // later over whatever text re-seats at its start is exactly how the string twins
    // treat an unclosed tag (their openers stay visible and complete at output time), so
    // all three formats format the same trailing text — see
    // unclosedAnchorInBlankParagraphCompletesAcrossFormats. A survivor that opened
    // inside the rewound region carries a start beyond the rewind point: kept as-is, its
    // close would measure the range from inside whatever text re-seats there (an entity
    // strictly nested inside a later PRE, or a format range missing the re-seated text's
    // first characters) where the twins complete from the region's first character — so
    // its start converges to the rewind point
    private fun rewindTo(start: Int) {
        // A rewind never extends: setLength past the live length would pad the gap with
        // NUL characters. The shape arises when a range opened inside a blank paragraph
        // survives the paragraph's own rewind — its start then sits beyond whatever text
        // re-seated after it. Nothing to drop in that shape either (no entity can sit at
        // or past the live length), so returning as-is lets any later append land at the
        // live end — the blank-label URL degradation in closeRange relies on exactly that
        if (start >= sb.length) return
        entities.removeAll { it.offset >= start }
        for (index in entities.indices) {
            val entity = entities[index]
            if (entity.offset + entity.length > start) {
                entities[index] = entity.copy(length = start - entity.offset)
            }
        }
        for (index in openRanges.indices) {
            val range = openRanges[index]
            if (range.start > start) openRanges[index] = range.copy(start = start)
        }
        sb.setLength(start)
    }

    // No heading entity exists in any output mode: the level spells out as literal
    // hashes, the same trade the string formats make
    override fun visit(heading: Heading) {
        completeOpenCodeRanges()
        sb.append(plainHeadingPrefixOf(heading))
        visitChildren(heading)
        sb.append("\n\n")
    }

    // A quote is one blockquote range over its children's text — no line-prefix
    // grammar, no wrapper tags, so quotes inside list items keep their formatting (the
    // MarkdownV2 render must degrade them there). An all-blank quote renders nothing:
    // an empty range is no quote at all.
    //
    // Known deviation, pinned as designed: a quote nested inside a quote emits a
    // blockquote range over child ranges that include the inner quote's own blockquote —
    // Bot API formatting rules say blockquote entities can't nest. The server repairs
    // this silently (the inner range drops at send time), every range stays in bounds,
    // and deduplicating across the visitor and the truncation/chunking re-assembly
    // paths would trade that cosmetic overlap for three divergent code paths
    override fun visit(blockQuote: BlockQuote) {
        // Code, link and custom-emoji ranges from an earlier block complete first — a
        // blockquote range inside any of them is the nesting Telegram rejects (see
        // completeOpenCodeLinkAndEmojiRanges)
        completeOpenCodeLinkAndEmojiRanges()
        val start = sb.length
        var appended = false
        var child = blockQuote.firstChild
        while (child != null) {
            val rendered = renderQuoteChild(child)
            if (rendered.text.isNotBlank()) {
                appendEntityText(sb, entities, if (appended) "\n\n" else "", rendered)
                appended = true
            }
            child = child.next
        }
        if (!appended) return
        val length = sb.length - start
        if (length > 0) {
            // An expandable quote (the <blockquote expandable> spelling the parse rewrite
            // marks, see isExpandableQuote) carries its own entity type
            entities += MessageEntity(blockquoteTypeOf(blockQuote), start, length)
        }
        sb.append("\n\n")
    }

    override fun visit(bulletList: BulletList) {
        renderList(bulletList)
    }

    override fun visit(orderedList: OrderedList) {
        renderList(orderedList)
    }

    // No thematic break entity exists; em dashes carry no special meaning in plain text
    override fun visit(thematicBreak: ThematicBreak) {
        completeOpenCodeRanges()
        sb.append("———\n\n")
    }

    override fun visit(fencedCodeBlock: FencedCodeBlock) {
        val language =
            htmlLanguageOf(fencedCodeBlock)
                .ifEmpty { null }
        val literal = fencedCodeBlock.literal.orEmpty().trimEnd('\n')
        completeOpenCodeLinkAndEmojiRanges()
        // An empty body renders to nothing at all — no pre range and no block separator
        // either: a lone separator would leave a blank line where the code block dropped
        // out (the HTML twin's emptyFenceRendersNothing). The completion above still
        // runs — an unclosed range must not reach across the block even when the block
        // itself drops out
        if (literal.isEmpty()) return
        val start = sb.length
        sb.append(literal)
        entities += MessageEntity(MessageEntityType.PRE, start, sb.length - start, language = language)
        sb.append("\n\n")
    }

    override fun visit(indentedCodeBlock: IndentedCodeBlock) {
        val literal = indentedCodeBlock.literal.trimEnd('\n')
        completeOpenCodeLinkAndEmojiRanges()
        // See visit(fencedCodeBlock): an empty body renders nothing
        if (literal.isEmpty()) return
        val start = sb.length
        sb.append(literal)
        entities += MessageEntity(MessageEntityType.PRE, start, sb.length - start)
        sb.append("\n\n")
    }

    override fun visit(code: Code) {
        if (code.literal.isEmpty()) {
            // An empty span would be an empty entity; the parser never produces one,
            // this covers hand-built ASTs
            return
        }
        if (codeDepth > 0) {
            // Inside an open code range the span's own entity would nest a second code
            // level; keep the backticks as visible text instead — exactly how both
            // string formats render the shape
            sb.append('`').append(code.literal).append('`')
        } else {
            // Inside a custom-emoji fallback the CODE entity registers freely: the
            // emoji range closes into no entity over code-formatted text (see
            // closeRange's codeInside check), so the span's formatting survives the
            // fallback's own degradation — the MarkdownV2 twin keeps its ` markers
            // in exactly the same shape
            val start = sb.length
            sb.append(code.literal)
            entities += MessageEntity(MessageEntityType.CODE, start, sb.length - start)
        }
    }

    override fun visit(emphasis: Emphasis) {
        if (!enterInline()) {
            appendPlainText(sb, emphasis)
            return
        }
        if (codeDepth > 0) {
            // Telegram forbids entities nested inside code ("Pre and Code can't contain
            // nested entities"): the range never opens, the content renders as plain code
            // text — the same degradation the MarkdownV2 twin's silent emphasis entries
            // make (the string twins drop the tags outright)
            visitChildren(emphasis)
            exitInline()
            return
        }
        if (formatRangeOpen(MessageEntityType.ITALIC)) {
            // Same-type entities cannot nest (see formatRangeOpen) — hand-built ASTs
            // only, the parser re-pairs same-type emphasis into siblings — the content
            // joins the outer range's text, the display identical. Unclosed ranges
            // opened inside still complete HERE, at the inner emphasis's boundary —
            // the same line the MarkdownV2 twin's silent-entry degradation draws (its
            // flat markers force the completion; the ranges now stop at the boundary
            // alike)
            val seq = nextSeq++
            visitChildren(emphasis)
            completeOpenedAfter(seq)
            exitInline()
            return
        }
        val seq = nextSeq++
        openRanges.addLast(OpenRange(MessageEntityType.ITALIC, sb.length, "", null, null, seq, html = false))
        visitChildren(emphasis)
        completeOpenedAfter(seq)
        closeRange(openRanges.removeLast())
        exitInline()
    }

    override fun visit(strongEmphasis: StrongEmphasis) {
        if (!enterInline()) {
            appendPlainText(sb, strongEmphasis)
            return
        }
        if (codeDepth > 0) {
            // See visit(emphasis): the range never opens inside an open code range
            visitChildren(strongEmphasis)
            exitInline()
            return
        }
        if (formatRangeOpen(MessageEntityType.BOLD)) {
            // See visit(emphasis): same-type nesting degrades to the outer range, with
            // the inner boundary completing what opened inside
            val seq = nextSeq++
            visitChildren(strongEmphasis)
            completeOpenedAfter(seq)
            exitInline()
            return
        }
        val seq = nextSeq++
        openRanges.addLast(OpenRange(MessageEntityType.BOLD, sb.length, "", null, null, seq, html = false))
        visitChildren(strongEmphasis)
        completeOpenedAfter(seq)
        closeRange(openRanges.removeLast())
        exitInline()
    }

    override fun visit(customNode: CustomNode) {
        when (customNode) {
            is Strikethrough -> {
                if (!enterInline()) {
                    appendPlainText(sb, customNode)
                    return
                }
                if (codeDepth > 0) {
                    // See visit(emphasis): the range never opens inside an open code range
                    visitChildren(customNode)
                    exitInline()
                    return
                }
                if (formatRangeOpen(MessageEntityType.STRIKETHROUGH)) {
                    // See visit(emphasis): same-type nesting degrades to the outer
                    // range, with the inner boundary completing what opened inside
                    val seq = nextSeq++
                    visitChildren(customNode)
                    completeOpenedAfter(seq)
                    exitInline()
                    return
                }
                val seq = nextSeq++
                openRanges.addLast(
                    OpenRange(MessageEntityType.STRIKETHROUGH, sb.length, "", null, null, seq, html = false),
                )
                visitChildren(customNode)
                completeOpenedAfter(seq)
                closeRange(openRanges.removeLast())
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
                // traversal, losing the marker from the text
                sb.append("[^${customNode.label}]")
            }

            else -> {
                super.visit(customNode)
            }
        }
    }

    /**
     * Completes unclosed ranges opened strictly after [seq] — latest-opened first —
     * before the entry owning [seq] closes itself. Runs when an inline container —
     * emphasis, strikethrough, a Markdown link or image label — closes with input HTML
     * tags still open inside it (missing closing tags, or upstream parsing defects).
     * [pendingSeparator] rides through to closeRange's blank-label degradation (true
     * only from the block-boundary completions, where a pending block separator sits in
     * the region tail).
     */
    private fun completeOpenedAfter(
        seq: Int,
        pendingSeparator: Boolean = false,
    ) {
        while (openRanges.isNotEmpty() && openRanges.last().seq > seq) {
            closeRange(openRanges.removeLast(), pendingSeparator)
        }
    }

    // Completes every open code range (and everything opened inside it) before a block
    // renders its own structure: left open, the code range would span the block's
    // entities — a nesting Telegram rejects ("Pre and Code can't contain nested
    // entities"). Other ranges may span block boundaries (the containment both string
    // formats ship for emphasis), so only the code family force-closes here. The close
    // runs at a block boundary with the previous block's separator pending — the lift
    // below keeps the range off the separator's "\n\n" (see completeThroughSeparator)
    private fun completeOpenCodeRanges() {
        val outermostCode =
            openRanges.firstOrNull { it.type == MessageEntityType.CODE } ?: return
        completeThroughSeparator(outermostCode.seq - 1)
    }

    // The block-entity twin of [completeOpenCodeRanges]: completes code, text_link and
    // custom_emoji ranges alike (everything opened inside the earliest of them) before a
    // block that emits — or whose children emit — its own block-level entity: a pre or
    // blockquote range may not sit inside a link entity ("Cannot be nested inside other
    // entities") nor inside a custom emoji ("all other entities can't contain each
    // other"), and a Markdown link never spans blocks, so an unclosed HTML anchor must
    // not reach across one either. Formatting ranges keep the documented
    // complete-at-output behavior (the pinned bold-over-pre containment). The close
    // runs at a block boundary with the previous block's separator pending — the lift
    // below keeps the range off the separator's "\n\n" (see completeThroughSeparator)
    private fun completeOpenCodeLinkAndEmojiRanges() {
        val boundary =
            openRanges
                .filter {
                    it.type == MessageEntityType.CODE ||
                        it.type == MessageEntityType.TEXT_LINK ||
                        it.type == MessageEntityType.CUSTOM_EMOJI
                }.minOfOrNull { it.seq }
                ?: return
        completeThroughSeparator(boundary - 1)
    }

    // Completes everything opened at or after [boundarySeq], lifting the trailing block
    // separator first and re-emitting it after the closers — the twin of the string
    // formats' completeThroughSeparator: a range closed at a block boundary measures
    // itself against the content before the separator, never over the separator's own
    // "\n\n" (the MarkdownV2 twin's closer lands after the lifted separator, the HTML
    // twin's tag pair before it; pre-fix CODE and TEXT_LINK ranges swallowed the blank
    // line and formatted it). The blank-label degradation re-emits a fresh separator
    // only when nothing was lifted to restore (pendingSeparator rides on the lifted
    // count, not an unconditional true — both would double the separator)
    private fun completeThroughSeparator(boundarySeq: Int) {
        var separatorLength = 0
        while (separatorLength < sb.length && sb[sb.length - 1 - separatorLength] == '\n') {
            separatorLength++
        }
        sb.setLength(sb.length - separatorLength)
        completeOpenedAfter(boundarySeq, pendingSeparator = separatorLength == 0)
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
                // wraps — the same visible shape the unextended parse shows. Code, link
                // and custom-emoji ranges from an earlier block complete first — the
                // label and the definition's blocks must not sit inside them (the
                // definition's own children re-check at their own boundaries; this also
                // keeps a child blockquote from landing inside an unclosed anchor)
                completeOpenCodeLinkAndEmojiRanges()
                val start = sb.length
                sb.append("[^${customBlock.label}]: ")
                val bodyStart = sb.length
                visitChildren(customBlock)
                // A definition whose children all render to nothing (no body text, or a
                // comment-only/blank paragraph) drops entirely, label included — the
                // same blank-body guard the MarkdownV2 twin's body.isNotEmpty() makes.
                // Kept, the bare label fused with the next block's first line: the
                // children emitted no separator of their own, and this visitor walks
                // straight into the sibling (a blank region guarantees nothing opened
                // inside it, so the rewind strands no range)
                if (regionIsBlank(bodyStart)) rewindTo(start)
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

        completeOpenCodeLinkAndEmojiRanges()
        val start = sb.length
        sb.append(lines.joinToString("\n"))
        entities += MessageEntity(MessageEntityType.PRE, start, sb.length - start)
        sb.append("\n\n")
    }

    override fun visit(link: Link) {
        val destination = link.destination.orEmpty()
        // Telegram rejects text_link entities with an empty URL, and links cannot nest
        // (a nested Link cannot come out of CommonMark parsing; the guard covers
        // hand-built ASTs) — degrade to the link text alone in both cases. A link entity
        // inside an open code range is equally illegal ("Pre and Code can't contain
        // nested entities"): the label text stays, the link drops
        if (destination.isEmpty() || linkEntityOpen() || codeDepth > 0) {
            visitChildren(link)
            return
        }
        val entrySeq = nextSeq++
        openMarkdownLinks++
        openRanges.addLast(
            OpenRange(MessageEntityType.TEXT_LINK, sb.length, destination, null, null, entrySeq, html = false),
        )
        visitChildren(link)
        openMarkdownLinks--
        // Telegram also rejects link entities with empty text: a label that renders to
        // no visible text degrades to the bare URL (see closeRange). Rewinding is safe —
        // inside a label every pushed range was accompanied by output
        completeOpenedAfter(entrySeq)
        closeRange(openRanges.removeLast())
    }

    // Telegram message text has no images in any mode: degrade to a plain link on the
    // alt text; an alt that renders to nothing degrades to the bare URL (see
    // visit(link)). Nested inside another link only the alt text survives. The one
    // carve-out is the Bot API's own custom emoji spelling — an image whose destination
    // reads tg://emoji?id=… — which renders as a custom_emoji range, the same entity the
    // tg-emoji tag path emits (see renderEmojiTag); the guards match it: inside a link or
    // a code range the image takes the ordinary degradation
    override fun visit(image: Image) {
        val destination = image.destination.orEmpty()
        if (destination.isNotEmpty() && !linkEntityOpen() && codeDepth == 0) {
            val emojiId = tgEmojiUrlIdOf(destination)
            if (emojiId != null) {
                val entrySeq = nextSeq++
                openRanges.addLast(
                    OpenRange(MessageEntityType.CUSTOM_EMOJI, sb.length, "", null, emojiId, entrySeq, html = false),
                )
                visitChildren(image)
                // A fallback that rendered no text closes into a zero-length entity,
                // which drops — Telegram requires the fallback (see closeRange)
                completeOpenedAfter(entrySeq)
                closeRange(openRanges.removeLast())
                return
            }
        }
        // See visit(link): empty URL, nesting and open code ranges all degrade to the
        // alt text alone
        if (destination.isEmpty() || linkEntityOpen() || codeDepth > 0) {
            visitChildren(image)
            return
        }
        val entrySeq = nextSeq++
        openMarkdownLinks++
        openRanges.addLast(
            OpenRange(MessageEntityType.TEXT_LINK, sb.length, destination, null, null, entrySeq, html = false),
        )
        visitChildren(image)
        openMarkdownLinks--
        completeOpenedAfter(entrySeq)
        closeRange(openRanges.removeLast())
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

        // Block-level input HTML cannot pass through in any mode: it degrades to its
        // source text in a pre range — the same trade tables make
        completeOpenCodeLinkAndEmojiRanges()
        val start = sb.length
        sb.append(literal)
        entities += MessageEntity(MessageEntityType.PRE, start, sb.length - start)
        sb.append("\n\n")
    }

    override fun visit(htmlInline: HtmlInline) {
        renderHtmlInlineTag(htmlInline.literal)
    }

    /**
     * A closing tag matches its range only as the innermost open HTML entry, otherwise
     * its text stays visible literally — an orphan `</b>` closing nothing formats
     * nothing, and showing it as text is what both string formats do. Anchors behave
     * the same; an `<a>` without href pushes the empty-url range and its closing only
     * pops, emitting no entity.
     */
    private fun renderHtmlInlineTag(literal: String) {
        val tag = literal.trim()
        if (tag.startsWith("<!") || tag.startsWith("<?")) {
            return
        }

        val parts =
            parseHtmlTag(tag) ?: run {
                sb.append(tag)
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
                "b", "strong" -> MessageEntityType.BOLD
                "i", "em" -> MessageEntityType.ITALIC
                "u", "ins" -> MessageEntityType.UNDERLINE
                "s", "del", "strike" -> MessageEntityType.STRIKETHROUGH
                "code", "kbd", "samp", "tt" -> MessageEntityType.CODE
                "tg-spoiler" -> MessageEntityType.SPOILER
                "tg-emoji" -> return renderEmojiTag(tag, parts)
                else -> null
            }
        if (mapped == null || parts.selfClosing) {
            // Unknown names (and self-closing emphasis forms) stay visible text
            sb.append(tag)
            return
        }
        if (parts.closing) {
            val top = openRanges.lastOrNull()
            if (top != null && top.html && top.type == mapped) {
                openRanges.removeLast()
                // closeRange handles both plain entries and merged ones (which emit no
                // entity but still give their code-depth unit back)
                closeRange(top)
                return
            }
            // Zero-output entries never block a closer on their own (see the HTML
            // twin's probe): a merged degradation emitted nothing, so a legitimate
            // closer reaches the emitting range beneath — a </code> behind an
            // unclosed in-code <b> used to escape literally, surfacing as visible
            // text and stranding the block's remainder inside the code range. The
            // entries pop only when a real match sits beneath
            var probe = openRanges.size - 1
            while (probe >= 0 && rangeEmitsNothing(openRanges[probe])) probe--
            val beneath = if (probe >= 0) openRanges[probe] else null
            if (beneath != null && beneath.html && beneath.type == mapped) {
                completeOpenedAfter(beneath.seq)
                closeRange(openRanges.removeLast())
                return
            }
            sb.append(tag)
            return
        }
        if (mapped == MessageEntityType.CODE && codeDepth > 0) {
            // A code-family tag never nests a second code level: inside an open code
            // range it merges into it, the tags dropping while the content continues
            // code-formatted
            openRanges.addLast(
                OpenRange(MessageEntityType.CODE, sb.length, "", null, null, nextSeq++, html = true, merged = true),
            )
            codeDepth++
            return
        }
        if (codeDepth > 0) {
            // Any other formatting tag inside an open code range would nest an entity
            // Telegram rejects ("Pre and Code can't contain nested entities"): the tag
            // drops — merged keeps the closer matched and emission-free, the content
            // continues as plain code text (the MarkdownV2 twin's silent entries)
            openRanges.addLast(
                OpenRange(mapped, sb.length, "", null, null, nextSeq++, html = true, merged = true),
            )
            return
        }
        if (formatRangeOpen(mapped)) {
            // Same-type entities cannot nest (see formatRangeOpen): the tag drops —
            // merged keeps the closer matched and emission-free — and the content
            // renders inside the outer range, whose formatting already covers it (the
            // display is identical; the MarkdownV2 twin's flat markers flatten the same
            // input shape into side-by-side entities)
            openRanges.addLast(
                OpenRange(mapped, sb.length, "", null, null, nextSeq++, html = true, merged = true),
            )
            return
        }
        openRanges.addLast(OpenRange(mapped, sb.length, "", null, null, nextSeq++, html = true))
        if (mapped == MessageEntityType.CODE) codeDepth++
    }

    // <tg-emoji> maps to the custom_emoji entity — one of the constructs only the
    // entities protocol expresses natively. The id must decode to a positive int64 value
    // (custom emoji ids are numeric); anything else degrades to visible text like every
    // unknown tag
    private fun renderEmojiTag(
        tag: String,
        parts: TagParts,
    ) {
        // The closing tag carries no attributes to validate — match it before the id
        // check, or a legal </tg-emoji> would read as id-less and go literal
        if (parts.closing) {
            if (!parts.selfClosing) {
                val entry = openRanges.lastOrNull { it.html && it.type == MessageEntityType.CUSTOM_EMOJI }
                if (entry != null) {
                    // Whatever opened inside the fallback completes before the emoji
                    // does — the same boundary the image spelling draws (see
                    // visit(image)): left open it would cross the </tg-emoji> boundary
                    // into crossed ranges, or the closer would go literal over a nested
                    // entry (merged entries match too: a tag degraded inside an open
                    // code range or link closes as silently as it opened)
                    completeOpenedAfter(entry.seq)
                    closeRange(openRanges.removeLast())
                    return
                }
            }
            sb.append(tag)
            return
        }
        // A self-closing <tg-emoji/> opens nothing: like the self-closing emphasis forms
        // in renderHtmlInlineTag it stays visible text — the HTML twin checks the same
        // pair (no id or self-closing) before ever opening
        if (parts.selfClosing) {
            sb.append(tag)
            return
        }
        // The id check runs before the code-depth merge: an id-less or invalid tag is
        // visible text whichever context it sits in — inside an open code range the merge
        // would otherwise swallow it silently, diverging from both the out-of-code shape
        // and the unknown-tag literalization code makes
        val id =
            tgEmojiIdOf(parts.attrs) ?: run {
                sb.append(tag)
                return
            }
        // Links cannot nest and a custom emoji counts as link-open (see linkEntityOpen):
        // an inner tag — inside a link or another custom emoji — degrades, a merged entry
        // keeping its closer silent while the fallback renders as plain text, the same
        // degradation the image path makes of an inner emoji and the HTML twin makes of
        // the tag
        if (linkEntityOpen()) {
            openRanges.addLast(
                OpenRange(
                    MessageEntityType.CUSTOM_EMOJI,
                    sb.length,
                    "",
                    null,
                    id,
                    nextSeq++,
                    html = true,
                    merged = true,
                ),
            )
            return
        }
        if (codeDepth > 0) {
            // A custom emoji entity can't nest inside code: the tags drop, the emoji char
            // renders as plain code text (merged keeps the closer silent)
            openRanges.addLast(
                OpenRange(
                    MessageEntityType.CUSTOM_EMOJI,
                    sb.length,
                    "",
                    null,
                    id,
                    nextSeq++,
                    html = true,
                    merged = true,
                ),
            )
            return
        }
        openRanges.addLast(
            OpenRange(MessageEntityType.CUSTOM_EMOJI, sb.length, "", null, id, nextSeq++, html = true),
        )
    }

    private fun renderAnchorTag(
        tag: String,
        parts: TagParts,
    ) {
        if (parts.selfClosing) {
            sb.append(tag)
            return
        }
        if (parts.closing) {
            // See renderHtmlInlineTag: </a> matches only the innermost open range — and
            // only an HTML-owned one, never a Markdown link's own range
            val top = openRanges.lastOrNull()
            if (top != null && top.html && top.type == MessageEntityType.TEXT_LINK) {
                openRanges.removeLast()
                when {
                    top.nested -> {
                        sb.append(tag)
                    }

                    top.url.isEmpty() -> {}

                    else -> {
                        closeRange(top)
                    }
                }
                return
            }
            // See renderHtmlInlineTag's probe: merged ranges emitted nothing, so a
            // legitimate </a> reaches the anchor beneath an unclosed in-code <b> (the
            // HTML twin's </a> probe alike)
            var probe = openRanges.size - 1
            while (probe >= 0 && rangeEmitsNothing(openRanges[probe])) probe--
            val beneath = if (probe >= 0) openRanges[probe] else null
            if (beneath != null && beneath.html && beneath.type == MessageEntityType.TEXT_LINK) {
                completeOpenedAfter(beneath.seq)
                openRanges.removeLast()
                when {
                    beneath.nested -> {
                        sb.append(tag)
                    }

                    beneath.url.isEmpty() -> {}

                    else -> {
                        closeRange(beneath)
                    }
                }
                return
            }
            sb.append(tag)
            return
        }
        // Telegram links cannot nest: while any link entity is open (Markdown link,
        // image link or an outer anchor), an inner anchor and its closing tag stay
        // visible literally
        if (linkEntityOpen()) {
            openRanges.addLast(
                OpenRange(
                    MessageEntityType.TEXT_LINK,
                    sb.length,
                    "",
                    null,
                    null,
                    nextSeq++,
                    html = true,
                    nested = true,
                ),
            )
            sb.append(tag)
            return
        }
        // A link entity can't nest inside an open code range: the tags drop silently —
        // the empty-url sentinel shape, whose closer emits nothing — and the label text
        // continues as plain code text
        if (codeDepth > 0) {
            openRanges.addLast(
                OpenRange(
                    MessageEntityType.TEXT_LINK,
                    sb.length,
                    "",
                    null,
                    null,
                    nextSeq++,
                    html = true,
                ),
            )
            return
        }
        // href="" is treated like a missing href: the empty URL degrades to plain text
        val href = extractHref(parts.attrs)
        openRanges.addLast(
            OpenRange(
                MessageEntityType.TEXT_LINK,
                sb.length,
                href.orEmpty(),
                null,
                null,
                nextSeq++,
                html = true,
            ),
        )
    }

    // A link entity is open when a text_link range is (or a Markdown link), or a
    // custom-emoji range: the HTML twin's linkEntityOpen counts its tg-emoji tags the
    // same way (merged entries included), so links cannot nest inside a custom emoji
    // here either and the twin formats agree
    private fun linkEntityOpen(): Boolean =
        openRanges.any {
            it.type == MessageEntityType.TEXT_LINK || it.type == MessageEntityType.CUSTOM_EMOJI
        } || openMarkdownLinks > 0

    // Zero-output ranges (the HTML twin's emitsNothing): merged degradations and
    // href-less sentinel anchors — an in-code <a> degrades to the sentinel, which
    // emitted nothing and must not block a legitimate closer
    private fun rangeEmitsNothing(range: OpenRange): Boolean =
        range.merged ||
            (range.type == MessageEntityType.TEXT_LINK && !range.nested && range.url.isEmpty())

    // Whether a formatting range of [type] is open: Telegram bars same-type entities
    // from nesting inside each other, and the MarkdownV2 twin's flat markers cannot
    // even express the shape — so a same-type tag (or, for hand-built ASTs, a same-type
    // Markdown emphasis) degrades and the content joins the outer range, whose
    // formatting already covers it. Merged entries are no-output degradations, not
    // entities, and do not count
    private fun formatRangeOpen(type: MessageEntityType): Boolean = openRanges.any { it.type == type && !it.merged }

    // Whether the output region starting at [start] renders no visible text: empty or
    // whitespace only. The label callers check it immediately after the label's
    // children, so the region runs exactly to the end of the current output; any text a
    // label had opened would have emitted a character, so a blank label region
    // guarantees nothing was left open inside it
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

    // Continuation lines are indented to the marker width via applyEntityPrefix (the
    // offset-shifting twin of applyPrefix, shared with the truncation and chunking
    // pipelines). A blank-bodied item is no-content and skips, its marker consumed — the
    // same walk forEachEntityListItem makes. Code, link and custom-emoji ranges from an
    // earlier block complete first: items render through child visitors whose own
    // blockquote or pre ranges must not land inside a range this visitor still holds
    // open (the HTML twin's renderList completes the same set)
    private fun renderList(list: Node) {
        completeOpenCodeLinkAndEmojiRanges()
        val start = sb.length
        val marker = plainListMarkerOf(list)
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) {
                val prefix = marker()
                val body = renderChild(item)
                if (body.text.isNotBlank()) {
                    appendEntityText(sb, entities, "", applyEntityPrefix(prefix, body))
                    sb.append('\n')
                }
            }
            item = item.next
        }
        if (sb.length > start) sb.append('\n')
    }

    // Renders a nested block (quote body, list item) in a child visitor; at the depth
    // cap the subtree flattens to plain text (see MAX_RENDER_DEPTH). Trailing newlines
    // are trimmed here — callers prepend their own prefixes and separators
    private fun renderChild(node: Node): EntityText =
        if (depth + 1 >= MAX_RENDER_DEPTH) {
            val plain = StringBuilder()
            appendPlainText(plain, node)
            trimTrailingNewlines(EntityText(plain.toString(), emptyList()))
        } else {
            val visitor = EntityVisitor(depth + 1, options)
            visitor.visitChildren(node)
            trimTrailingNewlines(visitor.output())
        }

    // The quote-child variant: the node renders through its own full block shape —
    // visit(node), not visitChildren(node) — so a nested blockQuote child arrives with
    // its own blockquote entity for the caller to nest. Depth capping and
    // trailing-newline trimming match renderChild
    private fun renderQuoteChild(node: Node): EntityText =
        if (depth + 1 >= MAX_RENDER_DEPTH) {
            val plain = StringBuilder()
            appendPlainText(plain, node)
            trimTrailingNewlines(EntityText(plain.toString(), emptyList()))
        } else {
            val visitor = EntityVisitor(depth + 1, options)
            node.accept(visitor)
            trimTrailingNewlines(visitor.output())
        }
}
