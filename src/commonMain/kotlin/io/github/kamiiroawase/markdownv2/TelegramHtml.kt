package io.github.kamiiroawase.markdownv2

import org.commonmark.node.Document
import org.commonmark.node.Node
import org.commonmark.parser.Parser

/**
 * CommonMark (incl. GFM tables and strikethrough) to Telegram HTML converter with
 * structure-preserving truncation — the second output format of this library, alongside
 * [MarkdownV2]. Telegram itself documents HTML as the more robust of its two parse
 * modes, and its tag set covers what MarkdownV2 cannot express: spoilers
 * (`<tg-spoiler>`) and custom emoji (`<tg-emoji>`) from inline input HTML survive here
 * instead of degrading to literal text.
 *
 * The two entry objects share their semantics deliberately: the same input parses with
 * [MarkdownV2.defaultParser], the same [RenderOptions] knobs apply, truncation and
 * chunking keep structure the same way (blocks whole where they fit, natural seams
 * otherwise, escaped plain text as the last resort), and every output piece is
 * independently sendable with `parse_mode = HTML`. Differences the formats force:
 * escaping is uniform everywhere in HTML (`&`, `<`, `>` in text, `"` additionally in
 * attribute values — no code-entity variant), a block quote is a wrapper tag rather
 * than a line-prefix grammar (so quotes inside list items keep their formatting where
 * the MarkdownV2 render must degrade them), headings keep their hashes unescaped, and
 * the ordered-list marker needs no escaped dot.
 *
 * Thread safety: every member is safe to call concurrently from multiple threads — the
 * object holds no mutable state and every render builds its own visitor state, the same
 * guarantee [MarkdownV2] documents for itself.
 */
public object TelegramHtml {
    /**
     * Converts [content] from CommonMark to Telegram HTML using [parser].
     *
     * With a positive [maxLength], content longer than it is truncated while staying
     * valid Telegram HTML: tags stay balanced, and escape units ("&amp;" and friends)
     * and surrogate pairs are never split. [maxLength] of 0 or less (the default)
     * disables truncation — the full document renders regardless of length; pass
     * [MarkdownV2.MAX_MESSAGE_LENGTH] to clip to the Telegram message limit. Constructs
     * Telegram cannot render degrade the same way they do for MarkdownV2: tables, raw
     * HTML blocks and other unsupported shapes become escaped text in a `<pre>`, a link
     * with an empty label or URL degrades to the non-empty piece (the bare URL when the
     * label rendered nothing, the label text when the URL is empty — there is no URL to
     * show), and an entity whose content renders to nothing drops its tags. Rendering
     * knobs live in [options].
     */
    public fun render(
        content: String,
        maxLength: Int = 0,
        parser: Parser = MarkdownV2.defaultParser,
        options: RenderOptions = RenderOptions(),
    ): String {
        // The Parser interface types parse() as Node?: the default parser never returns
        // null for non-null input, but a custom implementation may — such a document
        // renders to nothing
        val document = parser.parse(content) ?: return ""
        return render(document, maxLength, options)
    }

    /**
     * Renders an already-parsed [document] (any block node) to Telegram HTML.
     *
     * With a positive [maxLength], output longer than it is truncated while staying
     * valid Telegram HTML; [maxLength] of 0 or less (the default) disables truncation.
     * Use with a custom [Parser] when the default extensions are not enough; unknown
     * block/inline node types contributed by third-party extensions traverse as plain
     * content. A non-Document node renders alone — its siblings in the parsed tree stay
     * out of the call, truncated or not, the same boundary the [renderChunked]
     * single-block overload draws.
     */
    public fun render(
        document: Node,
        maxLength: Int = 0,
        options: RenderOptions = RenderOptions(),
    ): String {
        val converted = convertHtml(document, options)
        // maxLength <= 0 (the default) disables truncation; a positive value caps the output
        if (maxLength <= 0 || converted.length <= maxLength) {
            return converted
        }

        // A Document truncates from its child block chain; a single block node truncates
        // on its own via truncateHtmlBlock — its siblings belong to the caller's document,
        // the same boundary renderChunked's single-block overload draws — and either way
        // the block's own structure (list markers, pre fences and the like) survives the
        // cut. The budget reserves one character for the trailing ellipsis appended below
        val truncated =
            if (document is Document) {
                renderHtmlBlocks(document.firstChild, maxLength - 1, 0, retriable = true, options)
            } else {
                truncateHtmlBlock(document, maxLength - 1, options)
            }

        if (truncated.isEmpty()) {
            return renderHtmlPlainText(document, maxLength)
        }

        return truncated.trimEnd() + "…"
    }

    /**
     * Escapes all HTML special characters in [text] (`&`, `<`, `>`) so the result renders
     * as literal text when you assemble Telegram HTML by hand.
     */
    public fun escape(text: String): String = escapeHtmlText(text)

    /**
     * Converts [content] from CommonMark to Telegram HTML, splitting over-length output
     * into a list of chunks of at most [maxLength] characters each — send them as
     * consecutive messages to deliver arbitrarily long content without losing anything.
     *
     * Chunking semantics match the MarkdownV2 twin: blocks are packed whole where they
     * fit, oversized blocks split along their natural seams, every chunk is
     * independently valid HTML (tags balance within a piece), and no character is
     * dropped. [maxLength] defaults to [MarkdownV2.MAX_MESSAGE_LENGTH]; below
     * [MIN_CHUNK_LENGTH] (one escape unit — the five characters of "&amp;") chunking
     * cannot guarantee both validity and progress, and the full render returns as a
     * single chunk; content that renders to nothing yields an empty list.
     */
    public fun renderChunked(
        content: String,
        maxLength: Int = MarkdownV2.MAX_MESSAGE_LENGTH,
        parser: Parser = MarkdownV2.defaultParser,
        options: RenderOptions = RenderOptions(),
    ): List<String> {
        // See the render(content) overload: only a custom parser can return null here
        val document = parser.parse(content) ?: return emptyList()
        return renderChunked(document, maxLength, options)
    }

    /**
     * Renders an already-parsed [document] (any block node) to Telegram HTML chunks,
     * splitting over-length output into a list of at most [maxLength] characters per chunk.
     *
     * Chunking semantics match the [renderChunked] string overload: blocks are packed
     * whole where they fit, oversized blocks split along their natural seams, every chunk
     * is independently valid Telegram HTML, and no character is dropped — save whitespace
     * that could never display anyway: pending blank lines at the top of a split code
     * block drop when the seam fires before real content joins them, and an all-whitespace
     * flattened piece drops because the server rejects blank messages. [maxLength]
     * below [MIN_CHUNK_LENGTH] returns the full render as a single chunk; unknown
     * block/inline node types from third-party extensions degrade to plain content.
     */
    public fun renderChunked(
        document: Node,
        maxLength: Int = MarkdownV2.MAX_MESSAGE_LENGTH,
        options: RenderOptions = RenderOptions(),
    ): List<String> {
        // A positive chunk size below one escape unit cannot hold even a "&amp;", so it
        // joins 0 and the negatives on the no-chunking path rather than failing the call
        if (maxLength < MIN_CHUNK_LENGTH) return listOf(render(document, 0, options))
        // A Document chunks from its child block chain; a single block node passed in
        // chunks on its own — its siblings belong to the caller's document
        return if (document is Document) {
            chunkHtmlBlocks(document.firstChild, maxLength, 0, options)
        } else {
            chunkHtmlBlock(document, maxLength, 0, options)
        }
    }

    /**
     * The decorated twin of [renderChunked]: every chunk carries a [decoration] — page
     * counters ("Part 2/5"), source links, bot signatures — while staying within
     * [maxLength]. The header and footer receive the chunk's zero-based index and the
     * total chunk count; decorations that vary with the count itself re-chunk correctly
     * (see [decorateChunks]). The decoration strings insert verbatim — special characters
     * are the caller's to escape ([escape]), a deliberate tag goes through unescaped.
     *
     * @throws IllegalArgumentException when the decorations leave no room for content
     * under [maxLength] (the undecorated overloads stay lenient here)
     */
    public fun renderChunked(
        content: String,
        maxLength: Int = MarkdownV2.MAX_MESSAGE_LENGTH,
        parser: Parser = MarkdownV2.defaultParser,
        options: RenderOptions = RenderOptions(),
        decoration: ChunkDecoration,
    ): List<String> {
        val document = parser.parse(content) ?: return emptyList()
        return renderChunked(document, maxLength, options, decoration)
    }

    /** The decorated [renderChunked] for an already-parsed document; see the string overload. */
    public fun renderChunked(
        document: Node,
        maxLength: Int = MarkdownV2.MAX_MESSAGE_LENGTH,
        options: RenderOptions = RenderOptions(),
        decoration: ChunkDecoration,
    ): List<String> =
        decorateChunks(
            maxLength,
            minLimit = MIN_CHUNK_LENGTH,
            chunk = { limit ->
                if (document is Document) {
                    chunkHtmlBlocks(document.firstChild, limit, 0, options)
                } else {
                    chunkHtmlBlock(document, limit, 0, options)
                }
            },
            decoration = decoration,
        )

    /**
     * The smallest [maxLength] the HTML chunker accepts before falling back to the full
     * render as a single chunk: the length of one escape unit ("&amp;"), the atomic
     * minimum any valid cut must be able to hold.
     */
    public const val MIN_CHUNK_LENGTH: Int = MAX_HTML_UNIT

    /**
     * Last-resort fallback: extract plain text from the document, then escape and truncate
     * it char by char — fully escaped plain text is always valid HTML. When the whole
     * plain text fits, nothing was truncated — return it as-is, without an ellipsis.
     */
    private fun renderHtmlPlainText(
        document: Node,
        maxLength: Int,
    ): String {
        val plain = StringBuilder()
        appendPlainText(plain, document)
        val text = plain.toString().trimEnd()
        val escaped = escapeHtmlText(text)
        // isNotEmpty: content existed but yielded no representable text — keep at least
        // the ellipsis rather than producing an empty message
        if (escaped.isNotEmpty() && escaped.length <= maxLength) return escaped
        val sb = StringBuilder()
        appendHtmlEscapedTruncated(sb, text, maxLength - 1)
        return sb.append('…').toString()
    }

    // trim() removes the trailing block separator the visitor emits after the last block
    private fun convertHtml(
        document: Node,
        options: RenderOptions,
    ): String {
        val visitor = HtmlVisitor(options = options)
        document.accept(visitor)
        return visitor.output().trim()
    }
}
