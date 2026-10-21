package io.github.kamiiroawase.markdownv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plain-text de-formatting — `MarkdownV2.toPlainText`: the rejected-message fallback
 * that turns this library's rendered MarkdownV2 (a full render or one chunk) back into
 * the text Telegram would display.
 */
class PlainTextTest {
    @Test
    fun plainTextPassthroughKeepsUnmarkedText() {
        assertEquals("hello world 123", MarkdownV2.toPlainText("hello world 123"))
        assertEquals("• item", MarkdownV2.toPlainText("• item"))
        assertEquals("", MarkdownV2.toPlainText(""))
    }

    @Test
    fun plainTextResolvesTextEscapes() {
        assertEquals("a*b_c. #x", MarkdownV2.toPlainText(MarkdownV2.escape("a*b_c. #x")))
        // A lone backslash before a non-special character is not an escape and stays
        assertEquals("a\\b", MarkdownV2.toPlainText("a\\b"))
    }

    @Test
    fun plainTextStripsEmphasisMarkers() {
        assertEquals("a b c d u", MarkdownV2.toPlainText("_a *b* c_ ~d~ __u__"))
        assertEquals("a b c", MarkdownV2.toPlainText("__a _b_ c__"))
    }

    @Test
    fun plainTextResolvesAdjacentEmphasisEntities() {
        // The render of two italics side by side: the old char-greedy read took the glued
        // __ for one underline marker, matched nothing, and re-inserted every marker
        // literally instead of resolving the run close-then-open
        assertEquals("ab", MarkdownV2.toPlainText("_a__b_"))
        assertEquals("ab", MarkdownV2.toPlainText(MarkdownV2.render("*a*_b_")))
        // Italic glued to underline, and underline glued to italic
        assertEquals("ab", MarkdownV2.toPlainText("_a___b__"))
        assertEquals("ab", MarkdownV2.toPlainText("__a___b_"))
        // Underline wrapping italic (e.g. <u><i>x</i></u>) renders as a ___ run
        assertEquals("x", MarkdownV2.toPlainText("___x___"))
        // Inside a link label the same glued shapes resolve
        assertEquals("ab (u)", MarkdownV2.toPlainText("[_a__b_](u)"))
    }

    @Test
    fun plainTextKeepsCodeSpanContentAndUnescapes() {
        // Inside code entities only \` and \\ are escapes
        assertEquals("a `b` c", MarkdownV2.toPlainText("`a \\`b\\` c`"))
        assertEquals("code", MarkdownV2.toPlainText("`code`"))
    }

    @Test
    fun plainTextDropsFencesAndLanguage() {
        assertEquals("fun x()", MarkdownV2.toPlainText("```kotlin\nfun x()\n```"))
        assertEquals("L1\nL2", MarkdownV2.toPlainText("```\nL1\nL2\n```"))
    }

    @Test
    fun plainTextKeepsQuoteMarkersInsidePreBlocks() {
        assertEquals("> code", MarkdownV2.toPlainText("```\n> code\n```"))
    }

    @Test
    fun plainTextQuoteWrappedFenceStripsPerLinePrefixes() {
        // A fence behind a quote prefix opens the quote's code block: every line's marker
        // strips, leaving exactly the code (pre-fix the fence was read as code spans and
        // the block de-formed with stray blank lines)
        assertEquals("q", MarkdownV2.toPlainText("> ```\n> q\n> ```"))
        assertEquals("q", MarkdownV2.toPlainText("> > ```\n> > q\n> > ```"))
        // Hand-made shape: fence behind a quote prefix, unprefixed content lines
        assertEquals("q", MarkdownV2.toPlainText("> ```\nq\n```"))
        // And the round trip through the renderer's own quote-wrapped code shape
        assertEquals("code line", MarkdownV2.toPlainText(MarkdownV2.render("> ```\n> code line\n> ```")))
    }

    @Test
    fun plainTextQuoteWrappedCodeKeepsContentGreaterThanSigns() {
        // Only the wrapping quote's markers strip: a code line starting with its own '>'
        // keeps it, at one and two quote levels alike
        assertEquals("> xml", MarkdownV2.toPlainText("> ```\n> > xml\n> ```"))
        assertEquals("> xml", MarkdownV2.toPlainText(MarkdownV2.render("> ```\n> > xml\n> ```")))
        assertEquals("> xml", MarkdownV2.toPlainText("> > ```\n> > > xml\n> > ```"))
    }

    @Test
    fun plainTextStripsQuotePrefixes() {
        assertEquals("a\n\nb", MarkdownV2.toPlainText("> a\n>\n> > b"))
        // The renderer's own nested-quote shape: glued markers strip per unit, the same
        // as the spaced hand-made form above
        assertEquals("a\n\nb", MarkdownV2.toPlainText(MarkdownV2.render("> a\n>\n> > b")))
        assertEquals("glued", MarkdownV2.toPlainText(">> glued"))
        assertEquals("a\n\nb", MarkdownV2.toPlainText(">>a\n>>\n>>b"))
        // Mid-line '>' is not a quote marker and stays (as-is, unescaped garbage tolerated)
        assertEquals("a > b", MarkdownV2.toPlainText("a > b"))
    }

    @Test
    fun plainTextLabelSpanningLinesInQuoteStripsContinuationPrefixes() {
        // The render of a quote whose link label soft-breaks onto the next line:
        // the quote renderer marks every line of the body, the label's continuation
        // line included — its marker belongs to the quote, not the label (pre-fix "> b"
        // leaked into the plain text)
        assertEquals(
            "a\nb (http://x.com)",
            MarkdownV2.toPlainText(MarkdownV2.render("> [a\n> b](http://x.com)")),
        )
        // Two quote levels strip both units per continuation line
        assertEquals("a\nb (u)", MarkdownV2.toPlainText(MarkdownV2.render("> > [a\n> > b](u)")))
    }

    @Test
    fun plainTextCodeSpanSpanningLinesInQuoteStripsContinuationPrefixes() {
        // CommonMark normalizes a code span's line breaks to spaces, so a parser round
        // trip never produces this shape — it reaches deformat only as the render of a
        // hand-built Code node; hand-made input, the same shape the fence tests above use
        assertEquals("a\nb", MarkdownV2.toPlainText("> `a\n> b`"))
        // Only the wrapping quote's markers strip: a code line starting with its own '>'
        // keeps it — the same trade the fence path makes
        assertEquals("x\n>y", MarkdownV2.toPlainText("> `x\n> >y`"))
    }

    @Test
    fun plainTextLabelWithCodeSpanSpanningLinesInQuoteStripsPrefixes() {
        // A code span inside a label strips the continuation prefixes too: the label's
        // own '\n' handling and the span's agree on the depth
        assertEquals("a\nb (u)", MarkdownV2.toPlainText("> [`a\n> b`](u)"))
    }

    @Test
    fun plainTextStripsQuoteMarkersBehindListStructure() {
        // The render no longer places quote markers behind list structure (the Bot API
        // accepts '>' only at a line start, so a quote in a list item degrades to its
        // content) — but deformat still meets the shape in arbitrary input and must strip
        // it there: markers behind the first line's list marker or a continuation indent
        // alike, list structure kept (pre-fix "> " leaked into the plain text of both
        // lines of every list-wrapped quote)
        assertEquals("• a\n  b", MarkdownV2.toPlainText("• > a\n  > b"))
        assertEquals("1. a\n   b", MarkdownV2.toPlainText("1\\. > a\n   > b"))
        // Two quote levels behind the bullet strip both units per line
        assertEquals("• a\n  b", MarkdownV2.toPlainText("• >> a\n  >> b"))
        // A list inside a quote: the markers strip around the kept bullet — this one is
        // still the render's own shape (the quote is at the line start, only the inner
        // quote degrades)
        assertEquals("• a", MarkdownV2.toPlainText(MarkdownV2.render("> - > a")))
        // The degraded renders themselves: no markers to strip, the plain text is the
        // item body as rendered
        assertEquals("• a\n  b", MarkdownV2.toPlainText(MarkdownV2.render("- > a\n  > b")))
    }

    @Test
    fun plainTextLabelAndCodeSpanContinuationsBehindListStructure() {
        // The continuation-line scan behind list context: the indent stays, the quote
        // markers strip — the list-wrapped shape of the spanning-entity tests above,
        // hand-made the same way (the render degrades quotes in list items and no longer
        // produces it)
        assertEquals("• a\n  b (u)", MarkdownV2.toPlainText("• > [a\n  > b](u)"))
        // Hand-made code-span shape (the parser never spans code spans across lines —
        // the same hand-made posture as the quote-only test above)
        assertEquals("• x\n  y", MarkdownV2.toPlainText("• > `x\n  > y`"))
    }

    @Test
    fun plainTextFencesBehindListStructure() {
        // A fence opens behind the bullet/indent too: markers and language drop, the
        // bullet stays, and each content line keeps the indent the renderer put inside
        // the entity (the continuation alignment) — pre-fix the fence misparsed as code
        // spans, leaking backticks and markers
        assertEquals("•   l1\n  l2", MarkdownV2.toPlainText(MarkdownV2.render("- ```\n  l1\n  l2\n  ```")))
        assertEquals("•   code", MarkdownV2.toPlainText(MarkdownV2.render("- > ```\n  > code\n  > ```")))
    }

    @Test
    fun plainTextKeepsEscapedGreaterThanLiteral() {
        assertEquals("> not a quote", MarkdownV2.toPlainText("\\> not a quote"))
    }

    @Test
    fun plainTextLinkBecomesLabelAndUrl() {
        assertEquals("t (u)", MarkdownV2.toPlainText("[t](u)"))
        assertEquals("b (u)", MarkdownV2.toPlainText("[_b_](u)"))
        // URL escapes resolve; the bare ')' that closed the entity does not leak
        assertEquals("t (https://e.com/a)b)", MarkdownV2.toPlainText("[t](https://e.com/a\\)b)"))
    }

    @Test
    fun plainTextIncompleteLinkStaysLiteral() {
        assertEquals("[oops", MarkdownV2.toPlainText("[oops"))
        assertEquals("[a](b", MarkdownV2.toPlainText("[a](b"))
    }

    @Test
    fun plainTextEmptyLinkPiecesDegradeToTheNonEmptyPiece() {
        // No dangling parentheses: an empty side yields the other side alone
        assertEquals("", MarkdownV2.toPlainText("[]()"))
        assertEquals("x", MarkdownV2.toPlainText("[x]()"))
        assertEquals("u", MarkdownV2.toPlainText("[](u)"))
    }

    @Test
    fun plainTextNeverClosedMarkersReturnLiterally() {
        assertEquals("*a", MarkdownV2.toPlainText("*a"))
        assertEquals("a_", MarkdownV2.toPlainText("a_"))
        assertEquals("*_a", MarkdownV2.toPlainText("*_a"))
    }

    @Test
    fun plainTextRoundTripsARenderedDocument() {
        val content = "# Title\n\nsome *emph* and `code`\n\n> quote\n\n- item"
        assertEquals(
            "# Title\n\nsome emph and code\n\nquote\n\n• item",
            MarkdownV2.toPlainText(MarkdownV2.render(content)),
        )
    }

    @Test
    fun plainTextOfChunkedPiecesLeavesNoUnresolvedEscapes() {
        // The rejected-chunk fallback shape: every piece de-formats to text with no
        // entity markers or escape sequences left for a no-parse_mode send
        val content =
            """
            # Deep *dive*

            Some _emphasis_ with `code` and [a link](https://e.com/a(b)) plus \| chars\.

            > quoted *bold* text
            > second line

            - item one
            - item two

            ```
            fenced `code` block
            with \ backslash
            ```
            """.trimIndent()
        MarkdownV2.renderChunked(content, 60).forEach { piece ->
            val plain = MarkdownV2.toPlainText(piece)
            assertTrue(plain.isNotBlank(), "piece de-formed to nothing: $piece")
            "_*[]()~`>#+-=|{}.!\\".forEach { special ->
                assertFalse("\\$special" in plain, "unresolved escape \\$special in: $plain")
            }
        }
    }

    @Test
    fun spoilerMarkersVanishInPlainText() {
        // The spoiler render's || delimiters are entity markers, not content — they drop
        // like the emphasis markers do (a run of four, the adjacent-pair shape, resolves
        // close-then-open the same way _a__b_ does)
        assertEquals("sp sec end", MarkdownV2.toPlainText("sp ||sec|| end"))
        assertEquals("ab", MarkdownV2.toPlainText("||a||||b||"))
        assertEquals("|", MarkdownV2.toPlainText("\\|"))
    }

    @Test
    fun emptyFenceDeFormatsToNothing() {
        // The open fence's first line was already its close — an empty pre, and both
        // fence lines drop. Pre-fix the closing fence leaked through as content on the
        // garbage-input path (this library renders an empty fence with a blank middle
        // line, which always de-formed to nothing)
        assertEquals("", MarkdownV2.toPlainText("```\n```"))
        assertEquals("", MarkdownV2.toPlainText("```\n\n```"))
    }

    @Test
    fun plainTextCustomEmojiResolvesToTheBareFallback() {
        // The Bot API's own spelling: the display text is the fallback alone — the id
        // rides in the entity, never in the text. Pre-fix the '!' stayed literal and
        // the internal tg:// URL leaked into the fallback
        assertEquals("alt", MarkdownV2.toPlainText(MarkdownV2.render("![alt](tg://emoji?id=5)")))
        assertEquals("alt", MarkdownV2.toPlainText("![alt](tg://emoji?id=5)"))
        // Arbitrary input loses nothing: a non-emoji destination (or an invalid id — 0
        // is not a positive int64) keeps the ordinary '!' + link degradation
        assertEquals("!alt (https://e.com/i.png)", MarkdownV2.toPlainText("![alt](https://e.com/i.png)"))
        assertEquals("!alt (tg://emoji?id=0)", MarkdownV2.toPlainText("![alt](tg://emoji?id=0)"))
        // Incomplete shapes stay literal
        assertEquals("![oops", MarkdownV2.toPlainText("![oops"))
    }

    @Test
    fun plainTextAutolinkRenderStaysTheSingleUrl() {
        // The render of a bare autolink is [url](url): Telegram displays the label
        // alone, and the mechanical "label (url)" shape would duplicate it
        assertEquals(
            "go https://e.com. now",
            MarkdownV2.toPlainText(MarkdownV2.render("go https://e.com. now")),
        )
        // A genuine link whose label happens to equal its URL displays the same way
        assertEquals("https://e.com", MarkdownV2.toPlainText("[https://e.com](https://e.com)"))
    }

    @Test
    fun plainTextUnmatchedMarkerRunsReinsertInPushOrder() {
        // Markers of one run share an output position: the earlier-opened re-inserts
        // first — pins the linear reopenUnmatched rebuild's ordering (the old
        // per-marker inserts and the rebuild must agree here). A self-pairing run
        // ("~~", an empty strikethrough) consumes itself and vanishes, as ever
        assertEquals("*_a", MarkdownV2.toPlainText("*_a"))
        assertEquals("___x", MarkdownV2.toPlainText("___x"))
        assertEquals("a||b", MarkdownV2.toPlainText("a||b"))
        assertEquals("x", MarkdownV2.toPlainText("~~x"))
    }

    @Test
    fun lonePipesNeverPairUp() {
        // A lone '|' is no marker at all — only '||' is the spoiler marker — so two of
        // them never close each other (they used to cancel as an empty spoiler,
        // dropping both pipes from the plain text)
        assertEquals("a|b|c", MarkdownV2.toPlainText("a|b|c"))
    }

    @Test
    fun unterminatedCodeSpanKeepsItsOpeningDelimiter() {
        // The never-closed-markers-return-literally contract: an emphasis marker that
        // never closes re-inserts itself, and the code span's opening delimiter must not
        // vanish either (pre-fix "`abc" de-formed to "abc" and "[`abc" to "[abc" — one
        // character silently lost on malformed input, where the link degradation keeps
        // every bracket)
        assertEquals("`abc", MarkdownV2.toPlainText("`abc"))
        assertEquals("[`abc", MarkdownV2.toPlainText("[`abc"))
        assertEquals("a `b", MarkdownV2.toPlainText("a `b"))
        assertEquals("`", MarkdownV2.toPlainText("`"))
        assertEquals("`a\nb", MarkdownV2.toPlainText("`a\nb"))
        assertEquals("`a", MarkdownV2.toPlainText("> `a"))
        // A closed span still drops both delimiters
        assertEquals("code", MarkdownV2.toPlainText("`code`"))
    }

    // ---------- the tenth audit round: block separators and the custom emoji URL ----------

    @Test
    fun plainTextSeparatesBlockSiblings() {
        // appendPlainText's container branch used to join adjacent blocks with no
        // separator at all: the truncation and chunking fallback paths shipped
        // "alphabeta" — words welded across a block boundary. Block boundaries take
        // the blank line the rendered formats place between blocks, line-level
        // siblings (list items, table rows and cells) the single newline, and a
        // code-block literal's own trailing newline never doubles one
        fun plain(content: String): String {
            val doc = MarkdownV2.defaultParser.parse(content)!!
            return plainText(listOf(doc))
        }
        assertEquals("para one\n\npara two", plain("para one\n\npara two"))
        assertEquals("a\n\nb\nc", plain("- a\n\n  b\n- c"))
        assertEquals("first\nsecond", plain("- first\n- second"))
        assertEquals("code\n\nafter", plain("```\ncode\n```\n\nafter"))
        assertEquals("a\nb\nc\nd", plain("| a | b |\n| --- | --- |\n| c | d |"))
        // A no-content sibling between two boundaries collapses: two separators
        // normalize into one, exactly one blank line where the render shows one
        assertEquals("x\n\ny", plain("x\n\n<!-- nothing -->\n\ny"))
    }

    @Test
    fun blankCustomEmojiFallbackDegradesToTheBareUrlInPlainText() {
        // visit(image) degrades ![](tg://emoji?id=…) to the bare destination, and the
        // plain extraction feeds the MarkdownV2 pipeline's own truncation/chunking
        // fallbacks — pre-fix it rendered nothing there, a visible-text divergence
        // between the full render and its fallback on the same input
        fun plain(content: String): String {
            val doc = MarkdownV2.defaultParser.parse(content)!!
            return plainText(listOf(doc))
        }
        assertEquals("tg://emoji?id=5", plain("![](tg://emoji?id=5)"))
        // A non-blank fallback never surfaces the internal URL beside the text
        assertEquals("😀", plain("![😀](tg://emoji?id=5)"))
    }
}
