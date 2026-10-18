package io.github.kamiiroawase.markdownv2

import org.commonmark.Extension
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Document
import org.commonmark.node.Node
import org.commonmark.parser.Parser

/**
 * CommonMark (incl. GFM tables and strikethrough) to Telegram MarkdownV2 converter
 * with structure-preserving truncation.
 *
 * Thread safety: every member is safe to call concurrently from multiple threads. The
 * object holds no mutable state, and [defaultParser] — a commonmark parser — keeps all
 * parse-time state inside each parse call (a fresh document parser per invocation), so
 * a shared instance never carries state across calls. [RenderOptions] carries the same
 * guarantee as an immutable value object; only user-supplied collaborators (a custom
 * [parser], a custom display-width measure capturing mutable state) bring their own
 * thread-safety obligations.
 *
 * This file hosts only the public API and top-level flow; the implementation is split by
 * responsibility: full-document rendering in [Visitor](Visitor.kt), the block-level kernel
 * shared by the truncation and chunking pipelines (Blocks.kt), over-length truncation
 * (Truncation.kt), lossless chunking (Chunking.kt), de-formatting rendered output back to
 * plain text (Deformat.kt), escaping (Escape.kt), plain-text extraction (PlainText.kt),
 * table degradation (TableRenderer.kt), and customizable rendering knobs (RenderOptions.kt).
 */
public object MarkdownV2 {
    /**
     * Hard limit for message text length imposed by the Telegram Bot API.
     */
    public const val MAX_MESSAGE_LENGTH: Int = 4096

    /**
     * GFM extensions used by [defaultParser] (tables, strikethrough), plus the
     * task-list, autolink and footnotes extensions LLM output carries often — reusable
     * as a base when building a custom parser:
     * `Parser.builder().extensions(defaultExtensions + myExtension)`.
     */
    public val defaultExtensions: List<Extension> =
        listOf(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            IterativeTaskListItemsExtension,
            IterativeAutolinkExtension,
            FootnotesExtension.create(),
        )

    /**
     * Default parser: CommonMark plus [defaultExtensions].
     */
    public val defaultParser: Parser = Parser.builder().extensions(defaultExtensions).build()

    /**
     * Converts [content] from CommonMark to Telegram MarkdownV2 using [parser].
     *
     * With a positive [maxLength], content longer than it is truncated while staying
     * valid MarkdownV2: code fences are closed, unclosed emphasis/links are completed,
     * escape sequences and surrogate pairs are never split. [maxLength] of 0 or less
     * (the default) disables truncation — the full document renders regardless of
     * length; pass [MAX_MESSAGE_LENGTH] to clip to the Telegram message limit.
     * Constructs Telegram cannot render (tables, raw HTML) are degraded to fenced code
     * blocks; a blockquote inside a list item degrades to its content — the Bot API
     * accepts an unescaped '>' only at a line start, never behind a list marker or
     * continuation indent; underscore-family emphasis whose markers would glue onto
     * another '_' marker (two italics side by side, an italic opening straight after
     * one closes, an italic nested directly in an underline) degrades — the parser
     * re-pairs touching '_'/'__' markers into entities it cannot close; link entities
     * Telegram rejects (empty URL, or a label that renders to nothing) degrade to the
     * label text or the bare escaped URL. Rendering knobs that have no single right
     * answer (table cell cap, character display width) live in [options].
     */
    public fun render(
        content: String,
        maxLength: Int = 0,
        parser: Parser = defaultParser,
        options: RenderOptions = RenderOptions(),
    ): String {
        // The Parser interface types parse() as Node?: the default parser never returns
        // null for non-null input, but a custom implementation may — such a document
        // renders to nothing
        val document = parser.parse(content) ?: return ""
        return render(document, maxLength, options)
    }

    /**
     * Renders an already-parsed [document] (any block node) to Telegram MarkdownV2.
     *
     * With a positive [maxLength], output longer than it is truncated while staying
     * valid MarkdownV2; [maxLength] of 0 or less (the default) disables truncation.
     * Use with a custom [Parser] when the default extensions are not enough; unknown
     * block/inline node types contributed by third-party extensions are traversed as
     * plain content. A non-Document node renders alone — its siblings in the parsed tree
     * stay out of the call, truncated or not, the same boundary the [renderChunked]
     * single-block overload draws. Rendering knobs that have no single right answer
     * (table cell cap, character display width) live in [options].
     */
    public fun render(
        document: Node,
        maxLength: Int = 0,
        options: RenderOptions = RenderOptions(),
    ): String {
        val converted = convert(document, options)
        // maxLength <= 0 (the default) disables truncation; a positive value caps the output
        if (maxLength <= 0 || converted.length <= maxLength) {
            return converted
        }

        // A Document truncates from its child block chain; a single block node truncates
        // on its own via truncateBlock — its siblings belong to the caller's document, the
        // same boundary renderChunked's single-block overload draws — and either way the
        // block's own structure (list markers, table fences and the like) survives the cut.
        // The budget reserves one character for the trailing ellipsis appended below
        val truncated =
            if (document is Document) {
                renderBlocks(document.firstChild, maxLength - 1, 0, retriable = true, options)
            } else {
                truncateBlock(document, maxLength - 1, options)
            }

        if (truncated.isEmpty()) {
            return renderPlainText(document, maxLength)
        }

        return truncated.trimEnd() + "…"
    }

    /**
     * Escapes all MarkdownV2 special characters in [text] so the result renders as literal text.
     */
    public fun escape(text: String): String = escapeText(text)

    /**
     * Converts [rendered] MarkdownV2 — a [render] result or one [renderChunked] piece — back
     * into the plain text Telegram would display: the last-resort fallback when the Bot API
     * rejects a message (400 can't parse entities); send the result without parse_mode.
     *
     * Emphasis markers vanish (`*bold*`, `_italic_`, `__underline__`, `~strike~`) — runs of
     * glued markers included, so `_a__b_` (the render of two italics side by side) resolves
     * to plain `ab` — code spans and fenced blocks keep their content — a code block inside
     * a quote de-formats to its content with the per-line quote markers stripped — quote
     * `>` prefixes drop wherever they sit (at a line start, behind a list marker or a
     * continuation indent — list structure itself stays — and on the continuation line of
     * a label or code span soft-broken inside a quote) — escapes
     * resolve per context, and `[label](url)` becomes `label (url)` so no link target is
     * lost (an empty label or URL degrades to the non-empty piece).
     * The input is expected to be this library's output; arbitrary strings degrade
     * best-effort but losslessly — unterminated entities keep their content and never-closed
     * markers return literally — and the call never throws.
     */
    public fun toPlainText(rendered: String): String = deformat(rendered)

    /**
     * Converts [content] from CommonMark to Telegram MarkdownV2, splitting over-length
     * output into a list of chunks of at most [maxLength] characters each — send them as
     * consecutive messages to deliver arbitrarily long content without losing anything.
     *
     * Blocks are packed whole while they fit; a block longer than [maxLength] on its own
     * splits along its natural seams (code lines, list items, quote children, paragraph
     * lines, inline nodes); a seam that still exceeds the limit flattens to escaped plain
     * text, preserving every character. Each chunk is independently valid MarkdownV2 —
     * entities are completed at chunk boundaries, never split mid-marker, and list
     * numbering continues across chunks. [maxLength] defaults to [MAX_MESSAGE_LENGTH];
     * 1 or less returns the full render as a single chunk (chunking needs room for at
     * least one escaped character — two chars), and content that renders to nothing
     * yields an empty list.
     */
    public fun renderChunked(
        content: String,
        maxLength: Int = MAX_MESSAGE_LENGTH,
        parser: Parser = defaultParser,
        options: RenderOptions = RenderOptions(),
    ): List<String> {
        // See the render(content) overload: only a custom parser can return null here
        val document = parser.parse(content) ?: return emptyList()
        return renderChunked(document, maxLength, options)
    }

    /**
     * Renders an already-parsed [document] (any block node) to Telegram MarkdownV2 chunks,
     * splitting over-length output into a list of at most [maxLength] characters per chunk.
     *
     * Chunking semantics match the [renderChunked] string overload: blocks are packed whole
     * where they fit, oversized blocks split along their natural seams, every chunk is
     * independently valid MarkdownV2, and no character is dropped — save whitespace that
     * could never display anyway: pending blank lines at the top of a split code block
     * drop when the seam fires before real content joins them, and an all-whitespace
     * flattened piece drops because the server rejects blank messages. [maxLength] of 1 or
     * less
     * returns the full render as a single chunk (chunking needs room for at least one
     * escaped character); unknown block/inline node types from third-party extensions
     * degrade to plain content.
     */
    public fun renderChunked(
        document: Node,
        maxLength: Int = MAX_MESSAGE_LENGTH,
        options: RenderOptions = RenderOptions(),
    ): List<String> {
        // A positive chunk size below 2 cannot hold even one escaped special character, so
        // 1 joins 0 and the negatives on the no-chunking path rather than failing the call
        if (maxLength <= 1) return listOf(render(document, 0, options))
        // A Document chunks from its child block chain; a single block node passed in chunks
        // on its own — its siblings belong to the caller's document, not to this call
        return if (document is Document) {
            chunkBlocks(document.firstChild, maxLength, 0, options)
        } else {
            chunkBlock(document, maxLength, 0, options)
        }
    }

    /**
     * The decorated twin of [renderChunked]: every chunk carries a [decoration] — page
     * counters ("Part 2/5"), source links, bot signatures — while staying within
     * [maxLength]. The header and footer receive the chunk's zero-based index and the
     * total chunk count, so decorations that vary with the count itself re-chunk
     * correctly: the engine reserves the widest decoration seen and re-splits until
     * every decorated piece fits (see [decorateChunks]; one or two rounds in practice).
     *
     * The decoration strings insert verbatim — they are the caller's own markup, so
     * special characters are the caller's to escape ([escape]), while a deliberate link
     * entity goes through unescaped. A single chunk keeps its decoration (index 0, total
     * 1); content that renders to nothing yields an empty list undecorated.
     *
     * @throws IllegalArgumentException when the decorations leave no room for content
     * under [maxLength] (the undecorated overloads stay lenient here — this one is
     * explicit about an impossible request)
     */
    public fun renderChunked(
        content: String,
        maxLength: Int = MAX_MESSAGE_LENGTH,
        parser: Parser = defaultParser,
        options: RenderOptions = RenderOptions(),
        decoration: ChunkDecoration,
    ): List<String> {
        // See the render(content) overload: only a custom parser can return null here
        val document = parser.parse(content) ?: return emptyList()
        return renderChunked(document, maxLength, options, decoration)
    }

    /** The decorated [renderChunked] for an already-parsed document; see the string overload. */
    public fun renderChunked(
        document: Node,
        maxLength: Int = MAX_MESSAGE_LENGTH,
        options: RenderOptions = RenderOptions(),
        decoration: ChunkDecoration,
    ): List<String> =
        decorateChunks(
            maxLength,
            minLimit = 2,
            chunk = { limit ->
                if (document is Document) {
                    chunkBlocks(document.firstChild, limit, 0, options)
                } else {
                    chunkBlock(document, limit, 0, options)
                }
            },
            decoration = decoration,
            // A quote piece's leading '>' must stay line-initial; a header not ending in
            // a newline would push it mid-line, so the engine separates them — the one
            // grammar shape a decoration could otherwise break
            separatorNeeded = { head, piece -> head.isNotEmpty() && !head.endsWith("\n") && piece.startsWith(">") },
        )

    /**
     * Last-resort fallback: extract plain text from the document, then escape and truncate it
     * char by char. Truncating the fully rendered result directly would split code fences /
     * entity markers into invalid MarkdownV2, while fully escaped plain text is always valid.
     * When the whole plain text fits, nothing was truncated — return it as-is, without an
     * ellipsis.
     */
    private fun renderPlainText(
        document: Node,
        maxLength: Int,
    ): String {
        val plain = StringBuilder()
        appendPlainText(plain, document)
        val text = plain.toString().trimEnd()
        val escaped = escapeText(text)
        // isNotEmpty: content existed but yielded no representable text — keep at least
        // the ellipsis rather than producing an empty message
        if (escaped.isNotEmpty() && escaped.length <= maxLength) return escaped
        val sb = StringBuilder()
        appendEscapedTruncated(sb, text, maxLength - 1)
        return sb.append('…').toString()
    }

    // trim() removes the trailing block separator the visitor emits after the last block
    private fun convert(
        document: Node,
        options: RenderOptions,
    ): String {
        val visitor = Visitor(options = options)
        document.accept(visitor)
        return visitor.output().trim()
    }
}
