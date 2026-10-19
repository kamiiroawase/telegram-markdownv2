package io.github.kamiiroawase.markdownv2

import org.commonmark.node.Document
import org.commonmark.node.Node
import org.commonmark.parser.Parser

/**
 * CommonMark (incl. GFM tables and strikethrough) to the Bot API's entities protocol —
 * the third output format of this library, alongside [MarkdownV2] and [TelegramHtml]:
 * plain display text plus [MessageEntity] formatting ranges, sent with `entities` and
 * no parse_mode. This is the endgame for robustness: nothing in the output needs
 * parsing, so the "400 can't parse entities" class of failures cannot arise at all —
 * the plain-text fallback of the string formats becomes unnecessary rather than
 * automatic.
 *
 * The three entry objects share their semantics deliberately: the same input parses
 * with [MarkdownV2.defaultParser], the same [RenderOptions] knobs apply, truncation and
 * chunking keep structure the same way (blocks whole where they fit, natural seams
 * otherwise, plain text as the last resort — plain here meaning "no entities", never
 * "lost characters"). What the entities protocol adds: the visible text is identical to
 * what a Telegram client displays for the other formats' output, formatting rides on
 * ranges instead of markup (so quotes inside list items keep their blockquote range,
 * and `<tg-spoiler>`/`<tg-emoji>` map to native spoiler/custom_emoji entities), and
 * [EntityText.entitiesJson] emits the request-body array directly.
 *
 * Offsets and lengths are UTF-16 code units — the unit Telegram counts message length
 * in and Kotlin strings are made of, so an [EntityText] from this object drops into
 * `sendMessage(text = it.text, entities = ...)` with no conversion.
 *
 * Thread safety: every member is safe to call concurrently from multiple threads — the
 * object holds no mutable state and every render builds its own visitor state, the same
 * guarantee [MarkdownV2] documents for itself.
 */
public object TelegramEntities {
    /**
     * Converts [content] from CommonMark to plain text plus formatting entities using
     * [parser].
     *
     * With a positive [maxLength], content longer than it is truncated: entity ranges
     * clamp to the surviving text — a range fully beyond the cut drops, one straddling
     * it shortens to the cut — and the trailing ellipsis lands outside every range.
     * [maxLength] of 0 or less (the default) disables truncation; pass
     * [MarkdownV2.MAX_MESSAGE_LENGTH] to clip to the Telegram message limit. Constructs
     * Telegram cannot render degrade the same way they do for the string formats:
     * tables and raw HTML blocks become pre-formatted text — the one exception is a
     * block-level `<blockquote>`/`<blockquote expandable>` HTML block (open tag on its
     * own line), which maps to a real blockquote/expandable_blockquote range — a link
     * with an empty label degrades to the bare URL, and an entity whose content
     * renders to nothing drops.
     * Rendering knobs live in [options].
     */
    public fun render(
        content: String,
        maxLength: Int = 0,
        parser: Parser = MarkdownV2.defaultParser,
        options: RenderOptions = RenderOptions(),
    ): EntityText {
        // The Parser interface types parse() as Node?: the default parser never returns
        // null for non-null input, but a custom implementation may — such a document
        // renders to nothing
        val document = parseForRender(content, parser) ?: return EntityText.EMPTY
        return render(document, maxLength, options)
    }

    /**
     * Renders an already-parsed [document] (any block node) to plain text plus entities.
     *
     * With a positive [maxLength], output longer than it is truncated with entity ranges
     * clamped to the surviving text; [maxLength] of 0 or less (the default) disables
     * truncation. Use with a custom [Parser] when the default extensions are not enough;
     * unknown block/inline node types contributed by third-party extensions traverse as
     * plain content. A non-Document node renders alone — its siblings in the parsed tree
     * stay out of the call, truncated or not, the same boundary the [renderChunked]
     * single-block overload draws.
     */
    public fun render(
        document: Node,
        maxLength: Int = 0,
        options: RenderOptions = RenderOptions(),
    ): EntityText {
        val converted = convertEntity(document, options)
        if (maxLength <= 0 || converted.text.length <= maxLength) {
            return converted
        }

        // The budget reserves one character for the trailing ellipsis appended below;
        // the ellipsis lands after a trimEnd that no entity covers
        val truncated =
            if (document is Document) {
                renderEntityBlocks(document.firstChild, maxLength - 1, 0, retriable = true, options)
            } else {
                truncateEntityBlock(document, maxLength - 1, options)
            }

        if (truncated.text.isEmpty()) {
            return plainFallback(document, maxLength)
        }

        val trimmed = truncated.text.trimEnd()
        return EntityText(
            trimmed + "…",
            truncated.entities.clampedTo(trimmed.length),
        )
    }

    /**
     * Converts [content] from CommonMark to entities-protocol chunks of at most
     * [maxLength] characters of text each — send them as consecutive messages to
     * deliver arbitrarily long content without losing anything.
     *
     * Chunking semantics match the string formats: blocks are packed whole where they
     * fit, oversized blocks split along their natural seams, every chunk's entities
     * reference its own text (offsets re-based per chunk), and no character is dropped
     * beyond two exceptions: whitespace that could never display anyway, and a structural
     * marker (a list marker or heading hashes) at limits too small to hold it beside a
     * first content unit — body text itself never drops.
     * Structural formatting re-establishes per chunk — a split quote carries a
     * blockquote range on every piece — while an inline range straddling a seam clamps
     * into the piece holding its start (the string formats close their entities at
     * seams the same way). [maxLength] defaults to [MarkdownV2.MAX_MESSAGE_LENGTH];
     * below [MIN_CHUNK_LENGTH] (one surrogate pair) chunking cannot guarantee progress,
     * and the full render returns as a single chunk; content that renders to nothing
     * yields an empty list.
     */
    public fun renderChunked(
        content: String,
        maxLength: Int = MarkdownV2.MAX_MESSAGE_LENGTH,
        parser: Parser = MarkdownV2.defaultParser,
        options: RenderOptions = RenderOptions(),
    ): List<EntityText> {
        val document = parseForRender(content, parser) ?: return emptyList()
        return renderChunked(document, maxLength, options)
    }

    /**
     * Renders an already-parsed [document] (any block node) to entities-protocol chunks
     * of at most [maxLength] characters of text per chunk.
     *
     * Chunking semantics match the [renderChunked] string overload: blocks are packed
     * whole where they fit, oversized blocks split along their natural seams, every
     * chunk's entities reference its own text, and no character is dropped — save
     * whitespace that could never display anyway: an all-whitespace flattened piece drops
     * because the server rejects blank messages. A structural marker — a list marker or
     * heading hashes — is the one further casualty: it drops at limits too small to hold
     * the marker beside a first content unit, so the body text (which never drops) fits.
     * [maxLength]
     * below [MIN_CHUNK_LENGTH] returns the full render as a single chunk; unknown
     * block/inline node types from third-party extensions degrade to plain content.
     */
    public fun renderChunked(
        document: Node,
        maxLength: Int = MarkdownV2.MAX_MESSAGE_LENGTH,
        options: RenderOptions = RenderOptions(),
    ): List<EntityText> {
        // Plain text's only atomic unit is a surrogate pair — the same floor the
        // MarkdownV2 chunker works with
        if (maxLength < MIN_CHUNK_LENGTH) return listOf(render(document, 0, options))
        return if (document is Document) {
            chunkEntityBlocks(document.firstChild, maxLength, 0, options)
        } else {
            chunkEntityBlock(document, maxLength, 0, options)
        }
    }

    /**
     * The decorated twin of [renderChunked]: every chunk's text carries a [decoration] —
     * page counters ("Part 2/5"), source links, bot signatures — while staying within
     * [maxLength], with every entity range shifted past its chunk's header. The header
     * and footer receive the chunk's zero-based index and the total chunk count;
     * decorations that vary with the count itself re-chunk correctly (see
     * [decorateEntityChunks]). The decoration strings insert verbatim as plain text —
     * formatting on them is the caller's to add as extra entities.
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
    ): List<EntityText> {
        val document = parseForRender(content, parser) ?: return emptyList()
        return renderChunked(document, maxLength, options, decoration)
    }

    /** The decorated [renderChunked] for an already-parsed document; see the string overload. */
    public fun renderChunked(
        document: Node,
        maxLength: Int = MarkdownV2.MAX_MESSAGE_LENGTH,
        options: RenderOptions = RenderOptions(),
        decoration: ChunkDecoration,
    ): List<EntityText> =
        decorateEntityChunks(
            maxLength,
            minLimit = MIN_CHUNK_LENGTH,
            chunk = { limit ->
                if (document is Document) {
                    chunkEntityBlocks(document.firstChild, limit, 0, options)
                } else {
                    chunkEntityBlock(document, limit, 0, options)
                }
            },
            decoration = decoration,
        )

    /**
     * The smallest [maxLength] the entities chunker accepts before falling back to the
     * full render as a single chunk: one surrogate pair, the atomic minimum any valid
     * cut in plain text must be able to hold.
     */
    public const val MIN_CHUNK_LENGTH: Int = MIN_PLAIN_UNIT

    /**
     * Last-resort fallback: extract plain text from the document and truncate it char by
     * char — no entities, nothing Telegram could reject. When the whole plain text fits,
     * nothing was truncated — return it as-is, without an ellipsis.
     */
    private fun plainFallback(
        document: Node,
        maxLength: Int,
    ): EntityText {
        val plain = StringBuilder()
        appendPlainText(plain, document)
        val text = plain.toString().trimEnd()
        if (text.isNotEmpty() && text.length <= maxLength) return EntityText(text, emptyList())
        val sb = StringBuilder()
        appendPlainTruncated(sb, text, maxLength - 1)
        return EntityText(sb.append('…').toString(), emptyList())
    }

    // trim() removes the trailing block separator the visitor emits after the last block
    private fun convertEntity(
        document: Node,
        options: RenderOptions,
    ): EntityText {
        val visitor = EntityVisitor(options = options)
        document.accept(visitor)
        return visitor.output()
    }
}
