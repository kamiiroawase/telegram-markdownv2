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
        // Mid-line '>' is not a quote marker and stays (as-is, unescaped garbage tolerated)
        assertEquals("a > b", MarkdownV2.toPlainText("a > b"))
    }

    @Test
    fun plainTextLabelSpanningLinesInQuoteStripsContinuationPrefixes() {
        // The render of a quote whose link label soft-breaks onto the next line:
        // prefixQuote marks every line of the body, the label's continuation line
        // included — its marker belongs to the quote, not the label (pre-fix "> b"
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
        // applyPrefix prefixes every line of a list item's body — the marker on the
        // first line, the marker-width indent on the rest — so a quote inside an item
        // carries its '>' markers behind list structure. They strip there too; the list
        // structure stays (pre-fix "> " leaked into the plain text of both lines of
        // every list-wrapped quote)
        assertEquals("• a\n  b", MarkdownV2.toPlainText(MarkdownV2.render("- > a\n  > b")))
        assertEquals("1. a\n   b", MarkdownV2.toPlainText(MarkdownV2.render("1. > a\n   > b")))
        // Two quote levels behind the bullet strip both units per line
        assertEquals("• a\n  b", MarkdownV2.toPlainText(MarkdownV2.render("- > > a\n  > > b")))
        // A list inside a quote: the markers strip around the kept bullet
        assertEquals("• a", MarkdownV2.toPlainText(MarkdownV2.render("> - > a")))
    }

    @Test
    fun plainTextLabelAndCodeSpanContinuationsBehindListStructure() {
        // The continuation-line scan behind list context: the indent stays, the quote
        // markers strip — the list-wrapped shape of the spanning-entity tests above
        assertEquals("• a\n  b (u)", MarkdownV2.toPlainText(MarkdownV2.render("- > [a\n  > b](u)")))
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
}
