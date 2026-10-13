package io.github.kamiiroawase.markdownv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// tdlib's parse_markdown_v2 pairs "_" by context: "_" closes an italic only when the
// next character is not "_", and "__" is one underline unit. Two underscore-family
// markers emitted back to back therefore re-pair into a nesting the parser cannot close
// ("Can't find end of the entity" 400) — an italic closer followed by any marker, or two
// openers with no content between. The renderer degrades at every emission point: the
// gluing entity drops its markers and keeps its content (see Visitor.atUnderscoreGlue).
// The one legal adjacency — an underline CLOSER on the left — stays rendered.
class UnderscoreGlueTest {
    @Test
    fun siblingTagsKeepTheFirstEntityAndMergeTheContent() {
        // The second entity degrades: its content glues into the first's span, which
        // renders both stretches in the first's style
        assertEquals("_a_b", MarkdownV2.render("<i>a</i><i>b</i>"))
        assertEquals("_a_b", MarkdownV2.render("<i>a</i><u>b</u>"))
        // An underline closer on the left is the legal adjacency — both stay rendered
        assertEquals("__a___b_", MarkdownV2.render("<u>a</u><i>b</i>"))
        assertEquals("__a____b__", MarkdownV2.render("<u>a</u><u>b</u>"))
        // Bold and strikethrough pair unconditionally — never degraded
        assertEquals("*a**b*", MarkdownV2.render("<b>a</b><b>b</b>"))
        assertEquals("~a~~b~", MarkdownV2.render("<s>a</s><s>b</s>"))
        // Markdown emphasis after an HTML closer degrades the same way
        assertEquals("_a_b", MarkdownV2.render("<i>a</i>*b*"))
        assertEquals("x _a_b y", MarkdownV2.render("x <i>a</i><i>b</i> y"))
    }

    @Test
    fun nestedUnderscoreFamilyDegradesTheInnerOpener() {
        // Two openers with no content between re-read as "___": the inner degrades
        assertEquals("__x__", MarkdownV2.render("<u><i>x</i></u>"))
        assertEquals("_x_", MarkdownV2.render("<i><u>x</u></i>"))
        // Contentful openers but glued closers ("<u>a<i>b</i></u>" renders "__a_b___",
        // which the parser leaves unterminated): the outer's closer rewinds its opener
        assertEquals("a_b_", MarkdownV2.render("<u>a<i>b</i></u>"))
        assertEquals("a_b_", MarkdownV2.render("<i>a<i>b</i></i>"))
        // Underline closers pair unconditionally — nested underlines stay rendered
        assertEquals("__a__b____", MarkdownV2.render("<u>a<u>b</u></u>"))
        // Plain separation never triggers anything
        assertEquals("__a _b_ c__", MarkdownV2.render("<u>a <i>b</i> c</u>"))
    }

    @Test
    fun emptyUnderscoreEntitiesRewind() {
        // An empty pair would complete into "__" — which reads as an opened underline:
        // the opener rewinds instead, exactly like the empty code pair
        assertEquals("", MarkdownV2.render("<i></i>"))
        assertEquals("", MarkdownV2.render("<u></u>"))
        assertEquals("plain  text", MarkdownV2.render("plain <i></i> text"))
        assertEquals("_a_", MarkdownV2.render("<i>a<i></i></i>"))
    }

    @Test
    fun markdownEmphasisNeverGlues() {
        // CommonMark's intraword-underscore ban and the rule-of-3 keep pure Markdown
        // from producing glued emphasis: the literal runs escape, the renders stay legal
        assertEquals("_a\\_\\_b_", MarkdownV2.render("_a__b_"))
        assertEquals("*a\\_\\_b*", MarkdownV2.render("__a__b__"))
        assertEquals("*a\\*\\*\\*\\*b*", MarkdownV2.render("**a****b**"))
        assertEquals("_a\\*\\*b_", MarkdownV2.render("*a**b*"))
    }

    @Test
    fun unclosedAdjacentTagsCompleteWithoutGlue() {
        // Unclosed tags complete at output time; the completions must respect the glue
        // rule too (pre-fix "<i>a<i>b" completed into "_a_b__" — an unterminated
        // underline)
        assertEquals("a_b_", MarkdownV2.render("<i>a<i>b"))
        assertEquals("_a__b___", MarkdownV2.render("<i>a<u>b"))
        assertTelegramParseable(MarkdownV2.render("<i>a</i><i>b</i> <u>c</u><u>d</u>"), "mixed adjacency")
    }

    @Test
    fun glueShapesParseAcrossTruncationBudgets() {
        val contents =
            listOf(
                "<i>a</i><i>b</i>",
                "<i>a</i><u>b</u>",
                "<u>a</u><i>b</i>",
                "<u>a<i>b</i></u>",
                "<i><u>x</u></i>",
                "<i></i>",
                "<i>a<i>b</i></i>",
                "<u>a<u>b</u></u>",
                "x <i>a</i><i>b</i> y <u>c</u><u>d</u> z",
            )
        contents.forEach { content ->
            for (maxLength in 1..40) {
                assertTelegramParseable(
                    MarkdownV2.render(content, maxLength),
                    "render maxLength=$maxLength of $content",
                )
            }
        }
    }

    @Test
    fun glueShapesParseAcrossChunkSeams() {
        // The per-node chunk seam renders each inline node through a fresh visitor, so
        // an emphasis atom completing at one seam edge used to glue onto the next
        // atom's opener (pre-fix pieces like "__b" — an opened underline with no
        // closer). The seam now carries the underscore state in and degrades
        val contents =
            listOf(
                "<i>a</i><i>b</i> " + "w ".repeat(30),
                "<i>a</i><u>b</u> " + "w ".repeat(30),
                "<u>a</u><i>b</i> " + "w ".repeat(30),
                "x " + "<u>c</u><u>d</u> " + "w ".repeat(30),
            )
        contents.forEach { content ->
            for (maxLength in listOf(4, 6, 9, 13, 22, 60)) {
                MarkdownV2.renderChunked(content, maxLength).forEachIndexed { index, piece ->
                    assertTelegramParseable(piece, "chunk $index of $maxLength of ${content.take(20)}")
                }
            }
        }
    }

    @Test
    fun degradedOutputIsLosslessInPlainText() {
        // The degradation drops formatting, never text
        assertEquals("ab", MarkdownV2.toPlainText(MarkdownV2.render("<i>a</i><i>b</i>")))
        assertEquals("ab", MarkdownV2.toPlainText(MarkdownV2.render("<u>a<i>b</i></u>")))
        assertEquals("x", MarkdownV2.toPlainText(MarkdownV2.render("<i></i>x")))
        assertTrue(MarkdownV2.render("<i>a</i><i>b</i>").contains("a") && MarkdownV2.render("<i>a</i><i>b</i>").contains("b"))
    }
}
