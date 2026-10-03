package com.github.kamiiroawase.markdownv2

import org.commonmark.Extension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Document
import org.commonmark.node.Node
import org.commonmark.parser.Parser

/**
 * CommonMark (incl. GFM tables and strikethrough) to Telegram MarkdownV2 converter
 * with structure-preserving truncation.
 *
 * Platform warning — Kotlin/JS and Kotlin/Wasm: the upstream commonmark-kotlin parser
 * throws on these platforms for inputs containing an HTML block, an `<a href>` anchor,
 * or any document that opens with an HTML tag (its `Regex("]]>")` is invalid in the JS
 * RegExp engine). Wrap calls in try/catch with a plain-text fallback there, or pre-strip
 * HTML; mid-paragraph inline tags such as `<b>` or `<br>` are not affected.
 *
 * This file hosts only the public API and top-level flow; the implementation is split by
 * responsibility: full-document rendering in [Visitor](Visitor.kt), over-length truncation
 * (Truncation.kt), escaping (Escape.kt), plain-text extraction (PlainText.kt), table
 * degradation (TableRenderer.kt), and customizable rendering knobs (RenderOptions.kt).
 */
public object MarkdownV2 {
    /**
     * Hard limit for message text length imposed by the Telegram Bot API.
     */
    public const val MAX_MESSAGE_LENGTH: Int = 4096

    /**
     * GFM extensions used by [defaultParser] (tables, strikethrough), reusable as a base
     * when building a custom parser: `Parser.builder().extensions(defaultExtensions + myExtension)`.
     */
    public val defaultExtensions: List<Extension> =
        listOf(TablesExtension.create(), StrikethroughExtension.create())

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
     * blocks; link entities Telegram rejects (empty URL, or a label that renders to
     * nothing) degrade to the label text or the bare escaped URL. Rendering knobs that
     * have no single right answer (table cell cap, character display width) live in
     * [options].
     *
     * On Kotlin/JS and Kotlin/Wasm this call throws for inputs the upstream parser cannot
     * handle (HTML blocks, `<a href>` anchors, documents opening with an HTML tag — see
     * the object-level platform warning); catch and fall back to plain text there.
     */
    public fun render(
        content: String,
        maxLength: Int = 0,
        parser: Parser = defaultParser,
        options: RenderOptions = RenderOptions(),
    ): String {
        // parse() does not return null for non-null content in practice; this branch is
        // purely defensive
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
     * plain content. Rendering knobs that have no single right answer (table cell cap,
     * character display width) live in [options].
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

        // A Document starts from its child block chain; a single block node passed in
        // starts from itself, otherwise truncation would lose the block's own structure
        // (list markers, table fences and the like)
        val first = if (document is Document) document.firstChild else document
        // The budget reserves one character for the trailing ellipsis appended below
        val truncated = renderBlocks(first, maxLength - 1, 0, retriable = true, options)

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
     * Emphasis markers vanish (`*bold*`, `_italic_`, `__underline__`, `~strike~`), code spans
     * and fenced blocks keep their content, quote `>` prefixes drop, escapes resolve per
     * context, and `[label](url)` becomes `label (url)` so no link target is lost. The input
     * is expected to be this library's output; arbitrary strings degrade best-effort but
     * losslessly — unterminated entities keep their content and never-closed markers return
     * literally — and the call never throws.
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
     *
     * On Kotlin/JS and Kotlin/Wasm this call throws for inputs the upstream parser cannot
     * handle (HTML blocks, `<a href>` anchors, documents opening with an HTML tag — see
     * the object-level platform warning); catch and fall back to plain text there.
     */
    public fun renderChunked(
        content: String,
        maxLength: Int = MAX_MESSAGE_LENGTH,
        parser: Parser = defaultParser,
        options: RenderOptions = RenderOptions(),
    ): List<String> {
        val document = parser.parse(content) ?: return emptyList()
        return renderChunked(document, maxLength, options)
    }

    /**
     * Renders an already-parsed [document] (any block node) to Telegram MarkdownV2 chunks,
     * splitting over-length output into a list of at most [maxLength] characters per chunk.
     *
     * Chunking semantics match the [renderChunked] string overload: blocks are packed whole
     * where they fit, oversized blocks split along their natural seams, every chunk is
     * independently valid MarkdownV2, and no character is dropped. [maxLength] of 1 or less
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
