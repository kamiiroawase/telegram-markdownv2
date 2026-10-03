package com.github.kamiiroawase.markdownv2

import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.node.BulletList
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
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
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavior-level tests: feed input, assert the exact rendered output — nothing about
 * internal structure. Tests suffixed `ViaAst` build the AST by hand to reach shapes the
 * CommonMark parser cannot produce from text (null literals, foreign children, deep
 * chains, lone surrogates). HTML-parsing tests are guarded by [htmlParsingSupported]
 * because of the upstream JS/Wasm parser defect. Test data is ASCII wherever possible;
 * non-ASCII appears only where the behavior under test is itself Unicode-specific
 * (display width, surrogate pairs).
 */
class MarkdownV2Test {
    private val maxMessageLength = 4096

    @Test
    fun shortContentRenderedWithoutTruncation() {
        assertEquals("*hello* world", MarkdownV2.render("**hello** world"))
    }

    @Test
    fun longContentTruncatedWithinLimit() {
        val content = "intro\n\n" + "long content ".repeat(2000)
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("intro"))
    }

    @Test
    fun zeroMaxLengthRendersFullContent() {
        val content = "word ".repeat(2000) // ~10k chars, well past the Telegram limit
        assertEquals("word ".repeat(2000).trim(), MarkdownV2.render(content, 0))
    }

    @Test
    fun defaultMaxLengthRendersFullContent() {
        val content = "word ".repeat(2000)
        assertEquals(MarkdownV2.render(content, 0), MarkdownV2.render(content))
    }

    @Test
    fun negativeMaxLengthRendersFullContent() {
        val content = "word ".repeat(2000)
        assertEquals(MarkdownV2.render(content, 0), MarkdownV2.render(content, -1))
    }

    @Test
    fun longParagraphTruncatesWholeLines() {
        val content = "intro\n\n" + (1..2000).joinToString("\n") { "line $it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.contains("line 1"))
        assertTrue(result.contains("\nline 2"), "soft line breaks were glued together")
        assertTrue(!result.contains("line 2000"))
    }

    @Test
    fun longCodeBlockStaysClosed() {
        val code = (1..1000).joinToString("\n") { "line $it" }
        val content = "intro\n\n```\n$code\n```"
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertEquals(0, Regex("```").findAll(result).count() % 2)
        assertTrue(result.contains("line 1"))
        assertTrue(!result.contains("line 1000"))
    }

    @Test
    fun longListKeepsWholeItems() {
        val content = (1..2000).joinToString("\n") { "- item $it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.contains("• item 1"))
        assertTrue(!result.contains("item 2000"))
        result.lines().dropLast(1).forEach { line ->
            assertTrue(line.startsWith("• "), "line is not a whole item: $line")
        }
    }

    @Test
    fun longOrderedListKeepsMarkerSpace() {
        val content = (1..2000).joinToString("\n") { "$it. item $it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.contains("1\\. item 1"), "marker space lost: $result.take(50)")
        assertTrue(!result.contains("item 2000"))
        result.lines().dropLast(1).forEach { line ->
            assertTrue(
                Regex("^\\d+\\\\\\. ").containsMatchIn(line),
                "marker space lost: $line",
            )
        }
    }

    @Test
    fun longBlockQuoteTruncatesWithPrefix() {
        val content = "> " + (1..2000).joinToString("\n") { "quote $it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.contains("quote 1"))
        result.lines().dropLast(1).forEach { line ->
            assertTrue(line.startsWith("> "), "quote prefix lost: $line")
        }
    }

    @Test
    fun longTableTruncatesAsClosedCodeBlock() {
        val header = "| col1 | col2 |\n| --- | --- |"
        val rows = (1..1000).joinToString("\n") { "| cell$it | data$it |" }
        val result = MarkdownV2.render("$header\n$rows", maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertEquals(0, Regex("```").findAll(result).count() % 2)
    }

    @Test
    fun overlongSingleLineFallsBackToEscapedPlainText() {
        val content = "special*char_content" + "x".repeat(5000)
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        val plain = removeEscapes(result.dropLast(1))
        assertEquals(content.take(plain.length), plain)
    }

    @Test
    fun htmlBoldAcrossLinesStaysBalanced() {
        val content = "prefix<b>bold\n" + "x".repeat(5000) + "\nsecond line</b>tail"
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        val body = removeEscapes(result.dropLast(1))
        assertEquals(0, body.count { it == '*' } % 2)
    }

    @Test
    fun customSmallLimitTruncatesWithinLimit() {
        val content = "**bold** head\n\n" + "body".repeat(500) + "\n\n```\ncode line\n```"
        val result = MarkdownV2.render(content, 200)
        assertTrue(result.length <= 200)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("*bold* head"))
    }

    @Test
    fun customLimitKeepsCodeBlockClosed() {
        val code = (1..100).joinToString("\n") { "line $it" }
        val result = MarkdownV2.render("```\n$code\n```", 300)
        assertTrue(result.length <= 300)
        assertEquals(0, Regex("```").findAll(result).count() % 2)
    }

    @Test
    fun fallbackTruncationDoesNotSplitEscape() {
        val content = "```kotlin\n\\user\\home\n```"
        // The whole plain text fits: returned as-is, no spurious ellipsis
        assertEquals("\\\\user\\\\home", MarkdownV2.render(content, 12))
        val cut = MarkdownV2.render(content, 11)
        assertTrue(cut.length <= 11)
        assertTrue(cut.endsWith("…"))
        assertFalse(cut.dropLast(1).endsWith("\\"), "truncation split an escape: $cut")
        // The fallback is escaped plain text: code-block content stays literal and the whole output remains valid MarkdownV2
        assertEquals("\\\\user\\\\ho…", cut)
    }

    @Test
    fun specialCharactersAreEscapedAsLiterals() {
        for (char in "_*[]()~`>#+-=|{}.!\\") {
            assertEquals("a\\${char}b", MarkdownV2.render("a${char}b"), "char: $char")
        }
    }

    @Test
    fun emphasisRendersAsUnderscores() {
        assertEquals("_italic_", MarkdownV2.render("*italic*"))
    }

    @Test
    fun strikethroughRendersAsTilde() {
        assertEquals("~gone~", MarkdownV2.render("~~gone~~"))
    }

    @Test
    fun inlineCodeEscapesBackslash() {
        assertEquals("`a\\\\b`", MarkdownV2.render("`a\\b`"))
    }

    @Test
    fun linksKeepSquareBracketSyntax() {
        assertEquals("[label](https://example.com/x)", MarkdownV2.render("[label](https://example.com/x)"))
    }

    @Test
    fun linkUrlClosingParenIsEscaped() {
        assertEquals(
            "[a](https://en.wikipedia.org/wiki/Foo_(bar\\))",
            MarkdownV2.render("[a](https://en.wikipedia.org/wiki/Foo_(bar))"),
        )
    }

    @Test
    fun imagesRenderLikeLinks() {
        assertEquals("[alt](https://example.com/i.png)", MarkdownV2.render("![alt](https://example.com/i.png)"))
    }

    @Test
    fun imageInsideLinkDegradesToAltText() {
        // Telegram links cannot nest: the README badge shape keeps the outer link, the
        // nested image degrades to its alt text
        assertEquals(
            "[badge](https://example.com)",
            MarkdownV2.render("[![badge](https://img.example/b.svg)](https://example.com)"),
        )
    }

    @Test
    fun imageInsideImageDegradesToAltText() {
        assertEquals("[alt](outer)", MarkdownV2.render("[![alt](inner)](outer)"))
    }

    @Test
    fun headingsEscapeHashesAndRenderInline() {
        assertEquals("\\#\\# Title _x_", MarkdownV2.render("## Title *x*"))
    }

    @Test
    fun thematicBreakRendersAsEmDashLine() {
        assertEquals("a\n\n———\n\nb", MarkdownV2.render("a\n\n---\n\nb"))
    }

    @Test
    fun nestedBulletListIsIndented() {
        assertEquals("• a\n\n  • b", MarkdownV2.render("- a\n  - b"))
    }

    @Test
    fun orderedListStartsFromMarkerNumber() {
        assertEquals("3\\. c\n4\\. d", MarkdownV2.render("3. c\n4. d"))
    }

    @Test
    fun fencedCodeLanguageIsSanitized() {
        assertEquals(
            "```c++v12script\ncode\n```",
            MarkdownV2.render("```c++ v1.2<script>\ncode\n```"),
        )
    }

    @Test
    fun indentedCodeRendersAsFencedBlock() {
        assertEquals("```\nindented\n```", MarkdownV2.render("    indented"))
    }

    @Test
    fun tableRendersAsAlignedCodeBlock() {
        val content = "| 左 | 中 | 右 |\n| :-- | :-: | --: |\n| a | b | c |"
        assertEquals(
            "```\n| 左  | 中  |  右 |\n| --- | --- | --- |\n| a   |  b  |   c |\n```",
            MarkdownV2.render(content),
        )
    }

    @Test
    fun htmlBlockRendersAsCodeBlock() {
        if (!htmlParsingSupported) return

        assertEquals("```\n<div>\nhello\n</div>\n```", MarkdownV2.render("<div>\nhello\n</div>"))
    }

    @Test
    fun htmlCommentBlockIsDropped() {
        if (!htmlParsingSupported) return

        assertEquals("", MarkdownV2.render("<!-- note -->"))
        assertEquals("text", MarkdownV2.render("<!-- note -->\n\ntext"))
    }

    @Test
    fun htmlInlineTagsMapToMarkdownEntities() {
        val content = "a<i>it</i>b<s>del</s>c<u>u</u>d<code>k</code>e<br>f<span>x</span>"
        assertEquals("a_it_b~del~c__u__d`k`e\nf<span\\>x</span\\>", MarkdownV2.render(content))
    }

    @Test
    fun htmlCodeContentUsesCodeEscaping() {
        // Inside a code entity Telegram interprets only \` and \\: HTML code-family tags must
        // escape their content like Markdown backtick code, not like plain text (pre-fix
        // <code>a_b*c</code> emitted `a\_b\*c`, which renders with stray backslashes)
        assertEquals("a `b_c*d` e", MarkdownV2.render("a <code>b_c*d</code> e"))
        assertEquals("a `b\\`c` e", MarkdownV2.render("a <code>b`c</code> e"))
        assertEquals("a `b\\\\c` e", MarkdownV2.render("a <code>b\\c</code> e"))
        assertEquals("a `b_c` e", MarkdownV2.render("a <kbd>b_c</kbd> e"))
        assertEquals("a `b_c` e", MarkdownV2.render("a <samp>b_c</samp> e"))
        assertEquals("a `b_c` e", MarkdownV2.render("a <tt>b_c</tt> e"))
    }

    @Test
    fun htmlCodeClosingRestoresTextEscaping() {
        assertEquals("a `b_c` d\\*e", MarkdownV2.render("a <code>b_c</code> d*e"))
    }

    @Test
    fun unclosedHtmlCodeCompletesWithCodeEscaping() {
        assertEquals("a `b_c`", MarkdownV2.render("a <code>b_c"))
    }

    @Test
    fun unclosedHtmlCodeInsideEmphasisEndsCodeScope() {
        // The unclosed <code> is completed at the emphasis boundary; Text after it must
        // escape as plain text again (pre-fix the trailing * stayed unescaped)
        assertEquals("_a`b_c`_ d\\*e", MarkdownV2.render("*a<code>b_c* d*e"))
    }

    @Test
    fun htmlAnchorMapsToLink() {
        if (!htmlParsingSupported) return

        assertEquals("[t](https://x.com/a(b\\))", MarkdownV2.render("<a href=\"https://x.com/a(b)\">t</a>"))
    }

    @Test
    fun softLineBreakRendersAsNewline() {
        assertEquals("line1\nline2", MarkdownV2.render("line1\nline2"))
    }

    @Test
    fun blockQuoteIsPrefixed() {
        assertEquals("> quote", MarkdownV2.render("> quote"))
    }

    @Test
    fun nestedBlockQuoteDoublesPrefix() {
        assertEquals("> > deep", MarkdownV2.render("> > deep"))
    }

    @Test
    fun blockQuoteKeepsPrefixOnBlankLines() {
        assertEquals("> line1\n>\n> line2", MarkdownV2.render("> line1\n>\n> line2"))
    }

    @Test
    fun emptyContentRendersEmpty() {
        assertEquals("", MarkdownV2.render(""))
        assertEquals("", MarkdownV2.render("   \n\n"))
    }

    @Test
    fun minimalLimitRendersEllipsisOnly() {
        assertEquals("…", MarkdownV2.render("abcdef", 1))
    }

    @Test
    fun longHeadingKeepsEscapedPrefixWhenTruncated() {
        val content = "# " + "x".repeat(5000)
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.startsWith("\\# "))
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun surrogatePairIsNotSplitWhenTruncated() {
        val result = MarkdownV2.render("😀".repeat(3000), 100)
        assertTrue(result.length <= 100)
        assertTrue(result.endsWith("…"))
        assertFalse(result.dropLast(1).last().code in 0xD800..0xDBFF, "surrogate pair was split")
    }

    @Test
    fun partialListItemKeepsMarkerPrefix() {
        val content = "- short\n- second line item\n  continued"
        val result = MarkdownV2.render(content, 15)
        assertTrue(result.length <= 15)
        assertTrue(result.endsWith("…"))
        assertEquals("• short", result.lines().first())
        assertTrue(result.lines()[1].startsWith("• "), "marker prefix lost: $result")
    }

    @Test
    fun tinyLimitTruncatesListContent() {
        assertEquals("• it…", MarkdownV2.render("- item", 5))
    }

    @Test
    fun hardLineBreakRendersAsNewline() {
        assertEquals("a\nb", MarkdownV2.render("a  \nb"))
    }

    @Test
    fun longHtmlBlockTruncatesAsClosedCodeBlock() {
        if (!htmlParsingSupported) return

        val content = "<div>\n" + "x".repeat(5000) + "\n</div>"
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertEquals(0, Regex("```").findAll(result).count() % 2)
    }

    @Test
    fun tableCellKeepsCodeText() {
        val content = "| a |\n| --- |\n| `x` |"
        assertEquals("```\n| a   |\n| --- |\n| x   |\n```", MarkdownV2.render(content))
    }

    @Test
    fun truncatedGroupWithInlineHtmlFallsBackToPlainText() {
        val content = "a<b>bold" + "x".repeat(5000) + "<!-- hidden -->"
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        val plain = removeEscapes(result.dropLast(1))
        assertTrue(plain.startsWith("a<b>"), "html tag should remain as plain text: ${plain.take(20)}")
        assertFalse(plain.contains("hidden"), "html comment should be dropped")
    }

    @Test
    fun fallbackTruncationAvoidsSplittingSurrogatePair() {
        assertEquals("😀😀😀", MarkdownV2.render("```js\n😀😀😀\n```", 8))
        assertEquals("😀😀…", MarkdownV2.render("```js\n😀😀😀\n```", 5))
    }

    @Test
    fun fallbackTruncationEscapesCharAfterLoneSurrogate() {
        // A lone high surrogate is not a pair: treated as a single char, the special char after it escapes normally
        // (the pre-fix surrogate branch swallowed the * alongside it, emitting a raw unescaped char)
        val paragraph = Paragraph()
        paragraph.appendChild(Text("a\uD800*" + "x".repeat(50)))
        assertEquals("a\uD800\\*xxxxx…", MarkdownV2.render(paragraph, 10))
    }

    @Test
    fun unshrinkableBlockFallsBackToPlainText() {
        assertEquals("x", MarkdownV2.render("---\n\nx", 3))
    }

    @Test
    fun unmatchedClosingHtmlTagRendersAsLiteral() {
        // An orphan closing tag escapes literally — never emit unpaired * / _ / ` entity markers
        assertEquals("x</b\\>y", MarkdownV2.render("x</b>y"))
        assertEquals("x</i\\>y", MarkdownV2.render("x</i>y"))
        assertEquals("x</code\\>y", MarkdownV2.render("x</code>y"))
    }

    @Test
    fun unclosedHtmlEmphasisInsideEmphasisClosesAtBoundary() {
        // Unclosed <b> inside Markdown emphasis: completed LIFO at the emphasis boundary,
        // output stays properly nested — stack misalignment must not produce an orphan _
        assertEquals("_a*b*_", MarkdownV2.render("*a<b>b*"))
        assertEquals("_it *bold</b tail*_ extra", MarkdownV2.render("*it <b>bold</b tail* extra"))
    }

    @Test
    fun selfClosingHtmlTagRendersAsLiteral() {
        assertEquals("x<b/\\>y", MarkdownV2.render("x<b/>y"))
    }

    @Test
    fun emptyLinkUrlDegradesToText() {
        // Telegram rejects link entities with an empty URL
        assertEquals("text", MarkdownV2.render("[text]()"))
        assertEquals("a*b*c", MarkdownV2.render("[a**b**c]()"))
    }

    @Test
    fun emptyImageUrlDegradesToAlt() {
        assertEquals("alt", MarkdownV2.render("![alt]()"))
    }

    @Test
    fun nestedAnchorInnerIsRenderedAsLiteral() {
        if (!htmlParsingSupported) return

        // Telegram links cannot nest: the inner anchor and its closing tag escape literally, the outer link stays valid
        assertEquals("[_b_](u)", MarkdownV2.render("[*b*](u)"))
    }

    @Test
    fun unclosedAnchorInsideLinkTextEscapesLiterally() {
        if (!htmlParsingSupported) return

        // The anchor must not become a second link entity inside the Markdown link's label
        assertEquals(
            """[<a href\="u1"\>x](u2)""",
            MarkdownV2.render("[<a href=\"u1\">x](u2)"),
        )
    }

    @Test
    fun closedAnchorInsideLinkTextEscapesLiterally() {
        if (!htmlParsingSupported) return

        // '<' and '/' are not MarkdownV2 special characters — only '=' and '>' escape
        assertEquals(
            """[<a href\="u1"\>x</a\>](u2)""",
            MarkdownV2.render("[<a href=\"u1\">x</a>](u2)"),
        )
    }

    @Test
    fun imageInsideAnchorDegradesToAltText() {
        if (!htmlParsingSupported) return

        assertEquals("[alt](u)", MarkdownV2.render("<a href=\"u\">![alt](i)</a>"))
    }

    @Test
    fun deepNestedQuoteDoesNotOverflow() {
        val content = ">".repeat(1500) + " " + "x".repeat(5000)
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun deepNestedListDoesNotOverflow() {
        val content = (0 until 500).joinToString("\n") { "  ".repeat(it) + "- item" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun deepInlineEmphasisDoesNotOverflow() {
        val content = "*".repeat(2000) + "x" + "*".repeat(2000)
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
    }

    @Test
    fun tableLongCellTruncatesButKeepsHeader() {
        val longCell = "w".repeat(5000)
        val result = MarkdownV2.render("| a | b |\n| --- | --- |\n| $longCell | x |")
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.contains("| a"), "header row was lost: ${result.take(60)}")
        assertEquals(0, Regex("```").findAll(result).count() % 2)
    }

    @Test
    fun quoteWithCodeBlockTruncatesBalanced() {
        val code = (1..500).joinToString("\n") { "> line $it" }
        val result = MarkdownV2.render("> ```\n$code\n> ```", 60)
        assertTrue(result.length <= 60)
        assertTrue(result.endsWith("…"))
        assertEquals(0, Regex("```").findAll(result).count() % 2, "unclosed fence in quote: $result")
    }

    @Test
    fun truncatedLooseListKeepsBlankLineAndIndentation() {
        val content = "- a\n\n  b\n" + (1..2000).joinToString("\n") { "- item $it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        val lines = result.lines()
        assertEquals("• a", lines[0])
        assertEquals("", lines[1])
        assertEquals("  b", lines[2])
    }

    @Test
    fun quoteWithFittingHeadThenHugeBodyKeepsBoth() {
        val content = "> short\n>\n> " + (1..2000).joinToString("\n") { "quote $it" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("> short\n> "), "head quote lost: ${result.take(30)}")
    }

    @Test
    fun htmlAnchorWithoutHrefRendersTextOnly() {
        if (!htmlParsingSupported) return

        assertEquals("t", MarkdownV2.render("<a>t</a>"))
    }

    @Test
    fun htmlAnchorUppercaseHrefAttrIsRecognized() {
        if (!htmlParsingSupported) return

        assertEquals("[x](u)", MarkdownV2.render("<A HREF=\"u\">x</A>"))
    }

    @Test
    fun htmlAnchorMixedCaseHrefAttrIsRecognized() {
        if (!htmlParsingSupported) return

        assertEquals("[x](u)", MarkdownV2.render("<a HrEf='u'>x</a>"))
    }

    @Test
    fun htmlAnchorUnquotedHrefValueIsRecognized() {
        if (!htmlParsingSupported) return

        assertEquals("[x](https://e.com/a)", MarkdownV2.render("<a href=https://e.com/a>x</a>"))
    }

    @Test
    fun htmlAnchorDataHrefAttrDoesNotShadowHref() {
        if (!htmlParsingSupported) return

        // The href= inside data-href= must not be mistaken for the real attribute
        assertEquals("[x](real)", MarkdownV2.render("<a data-href=\"fake\" href=\"real\">x</a>"))
    }

    @Test
    fun hrefInsideQuotedAttributeValueIsNotMistakenForHrefViaAst() {
        // href= inside another attribute's quoted value must not be mistaken for the real
        // attribute (pre-fix rendered [t](fake)); a tag without a usable href degrades to text
        val paragraph = Paragraph()
        paragraph.appendChild(HtmlInline("<a title=\"href=fake\" data-x=\"1\">"))
        paragraph.appendChild(Text("t"))
        paragraph.appendChild(HtmlInline("</a>"))
        assertEquals("t", MarkdownV2.render(paragraph))
    }

    @Test
    fun hrefAfterAttributeWithValueContainingHrefViaAst() {
        // The quoted value is skipped as a whole, so the real href after it is still found
        val paragraph = Paragraph()
        paragraph.appendChild(HtmlInline("<a title='see href=ignored page' href=\"real\">"))
        paragraph.appendChild(Text("t"))
        paragraph.appendChild(HtmlInline("</a>"))
        assertEquals("[t](real)", MarkdownV2.render(paragraph))
    }

    @Test
    fun htmlAnchorHrefNamedEntitiesAreDecoded() {
        if (!htmlParsingSupported) return

        assertEquals("[x](a&b)", MarkdownV2.render("<a href=\"a&amp;b\">x</a>"))
        assertEquals("[x](a<b)", MarkdownV2.render("<a href=\"a&lt;b\">x</a>"))
        assertEquals("[x](a>b)", MarkdownV2.render("<a href=\"a&gt;b\">x</a>"))
        assertEquals("[x](a\"b)", MarkdownV2.render("<a href=\"a&quot;b\">x</a>"))
        assertEquals("[x](a'b)", MarkdownV2.render("<a href=\"a&apos;b\">x</a>"))
    }

    @Test
    fun htmlAnchorHrefCharRefsAreDecoded() {
        if (!htmlParsingSupported) return

        assertEquals("[x](a&b)", MarkdownV2.render("<a href=\"a&#38;b\">x</a>"))
        assertEquals("[x](a&b)", MarkdownV2.render("<a href=\"a&#x26;b\">x</a>"))
        assertEquals("[x](a&b)", MarkdownV2.render("<a href=\"a&#X26;b\">x</a>"))
        assertEquals("[x](\uD83D\uDE00)", MarkdownV2.render("<a href=\"&#x1F600;\">x</a>"))
    }

    @Test
    fun htmlAnchorHrefMalformedEntitiesStayLiteral() {
        if (!htmlParsingSupported) return

        assertEquals("[x](a&b)", MarkdownV2.render("<a href=\"a&b\">x</a>")) // no semicolon
        assertEquals("[x](a&nope;b)", MarkdownV2.render("<a href=\"a&nope;b\">x</a>")) // unknown name
        assertEquals("[x](&;)", MarkdownV2.render("<a href=\"&;\">x</a>")) // empty body
        assertEquals("[x](a&toolongname;)", MarkdownV2.render("<a href=\"a&toolongname;\">x</a>")) // beyond the entity scan window
        assertEquals("[x](&#;)", MarkdownV2.render("<a href=\"&#;\">x</a>")) // empty reference
        assertEquals("[x](&#xZ;)", MarkdownV2.render("<a href=\"&#xZ;\">x</a>")) // bad hex digits
        assertEquals("[x](&#1114112;)", MarkdownV2.render("<a href=\"&#1114112;\">x</a>")) // beyond Unicode
        assertEquals("[x](&#xD800;)", MarkdownV2.render("<a href=\"&#xD800;\">x</a>")) // surrogate half
    }

    @Test
    fun tableEmojiCellCountsAsWide() {
        assertEquals(
            "```\n| 😀  |\n| --- |\n| a   |\n```",
            MarkdownV2.render("| 😀 |\n| --- |\n| a |"),
        )
    }

    @Test
    fun customParserExtensionsAreTraversedAsPlainContent() {
        val parser =
            Parser
                .builder()
                .extensions(MarkdownV2.defaultExtensions + FootnotesExtension.create())
                .build()
        val content = "Text[^1]\n\n[^1]: The note"
        val result = MarkdownV2.render(content, parser = parser)
        assertEquals("Text\n\nThe note", result)
    }

    @Test
    fun rendersPreParsedDocument() {
        val document = MarkdownV2.defaultParser.parse("# Title\n\nBody *text*") ?: error("parser returned null")
        assertEquals(MarkdownV2.render("# Title\n\nBody *text*"), MarkdownV2.render(document))
        assertEquals("\\# Title\n\nBody _text_", MarkdownV2.render(document))
        val heading = document.firstChild ?: error("no heading")
        assertEquals("\\# Title", MarkdownV2.render(heading))
    }

    @Test
    fun htmlBlockCommentsAreDroppedViaAst() {
        val block = HtmlBlock()
        block.literal = "<!-- hidden -->"
        assertEquals("", MarkdownV2.render(block))
    }

    @Test
    fun emptyTableBlockRendersNothingViaAst() {
        assertEquals("", MarkdownV2.render(TableBlock()))
    }

    @Test
    fun tableCellLineBreaksCollapseToSpacesViaAst() {
        val cell = TableCell()
        cell.appendChild(Text("a"))
        cell.appendChild(SoftLineBreak())
        cell.appendChild(HardLineBreak())
        cell.appendChild(Text("b"))
        val row = TableRow()
        row.appendChild(cell)
        val head = TableHead()
        head.appendChild(row)
        val table = TableBlock()
        table.appendChild(head)
        assertEquals("```\n| a  b |\n| ---- |\n```", MarkdownV2.render(table))
    }

    @Test
    fun escapePublicApiEscapesAllNineteenSpecialChars() {
        for (char in "_*[]()~`>#+-=|{}.!\\") {
            assertEquals("a\\${char}b", MarkdownV2.escape("a${char}b"), "char: $char")
        }
        assertEquals("", MarkdownV2.escape(""))
        assertEquals("😀中文", MarkdownV2.escape("😀中文"), "emoji and CJK must not be escaped")
    }

    @Test
    fun headingLevelSixEscapesAllHashes() {
        assertEquals("\\#\\#\\#\\#\\#\\# deep", MarkdownV2.render("###### deep"))
    }

    @Test
    fun headingKeepsInlineEntities() {
        assertEquals("\\# `c` [_e_](u)", MarkdownV2.render("# `c` [*e*](u)"))
    }

    @Test
    fun bulletListInsideBlockQuote() {
        assertEquals("> • a\n> • b", MarkdownV2.render("> - a\n> - b"))
    }

    @Test
    fun blockQuoteInsideListItem() {
        assertEquals("• > q", MarkdownV2.render("- > q"))
    }

    @Test
    fun orderedListWithNestedBullet() {
        assertEquals("1\\. a\n\n    • b", MarkdownV2.render("1. a\n   - b"))
    }

    @Test
    fun orderedListLargeStartNumber() {
        assertEquals("1000000\\. x", MarkdownV2.render("1000000. x"))
    }

    @Test
    fun codeBlockInsideListItem() {
        assertEquals("• a\n\n  ```\n  c\n  ```", MarkdownV2.render("- a\n\n  ```\n  c\n  ```"))
    }

    @Test
    fun linkWithFormattedLabelKeepsEntities() {
        assertEquals("[_b_](u)", MarkdownV2.render("[*b*](u)"))
    }

    @Test
    fun imageWithFormattedAltKeepsEntities() {
        assertEquals("[_b_](u)", MarkdownV2.render("![*b*](u)"))
    }

    @Test
    fun formattedImageInsideLinkKeepsAltEntities() {
        assertEquals("[_b_](t)", MarkdownV2.render("[![*b*](i)](t)"))
    }

    @Test
    fun emptyLinkTextDegradesToBareUrl() {
        // Telegram rejects link entities with empty text — keep the destination visible
        assertEquals("u", MarkdownV2.render("[](u)"))
        assertEquals("https://example\\.com", MarkdownV2.render("[](https://example.com)"))
        assertEquals("u", MarkdownV2.render("![](u)"))
    }

    @Test
    fun nullDestinationLinkRendersChildrenViaAst() {
        val link = Link(destination = null, title = null)
        link.appendChild(Text("t"))
        val paragraph = Paragraph()
        paragraph.appendChild(link)
        assertEquals("t", MarkdownV2.render(paragraph))
    }

    @Test
    fun linkUrlBackslashIsEscapedViaAst() {
        val link = Link(destination = "https://x.com/a\\b", title = null)
        link.appendChild(Text("a"))
        val paragraph = Paragraph()
        paragraph.appendChild(link)
        assertEquals("[a](https://x.com/a\\\\b)", MarkdownV2.render(paragraph))
    }

    @Test
    fun unclosedHtmlBoldIsCompletedAtParagraphEnd() {
        assertEquals("a*b*", MarkdownV2.render("a<b>b"))
    }

    @Test
    fun unclosedNestedHtmlEmphasisCompletesInOrder() {
        if (!htmlParsingSupported) return

        assertEquals("*_x_*", MarkdownV2.render("<b><i>x"))
    }

    @Test
    fun unclosedHtmlInsideEmphasisClosesNestedFirst() {
        // Same-kind markers can overlap (_ inside _); completion locates by stack depth, not by marker value
        assertEquals("_*_x_*_", MarkdownV2.render("*<b><i>x*"))
    }

    @Test
    fun interleavedClosedHtmlAfterEmphasisIsLiteral() {
        assertEquals("_a*b*_ c</b\\>y", MarkdownV2.render("*a<b>b* c</b>y"))
    }

    @Test
    fun closingTagNotMatchingStackTopIsLiteral() {
        if (!htmlParsingSupported) return

        assertEquals("_x</b\\>y_", MarkdownV2.render("<i>x</b>y</i>"))
    }

    @Test
    fun uppercaseHtmlTagsMapToEntities() {
        assertEquals("a*b*c", MarkdownV2.render("a<B>b</B>c"))
    }

    @Test
    fun htmlTagWithAttributesMapsToEntity() {
        if (!htmlParsingSupported) return

        assertEquals("*t*", MarkdownV2.render("<b class=\"x\">t</b>"))
    }

    @Test
    fun unclosedHtmlAnchorIsCompleted() {
        if (!htmlParsingSupported) return

        assertEquals("a [b](u)", MarkdownV2.render("a <a href=\"u\">b"))
    }

    @Test
    fun singleQuotedHrefMapsToLink() {
        if (!htmlParsingSupported) return

        assertEquals("[t](u)", MarkdownV2.render("<a href='u'>t</a>"))
    }

    @Test
    fun emptyHrefRendersTextOnly() {
        if (!htmlParsingSupported) return

        assertEquals("t", MarkdownV2.render("<a href=\"\">t</a>"))
    }

    @Test
    fun extraClosingAnchorIsLiteral() {
        if (!htmlParsingSupported) return

        assertEquals("[x](o)</a\\>", MarkdownV2.render("<a href=\"o\">x</a></a>"))
    }

    @Test
    fun anchorWithoutHrefInsideAnchorStaysLiteral() {
        if (!htmlParsingSupported) return

        assertEquals("[x<a\\>y</a\\>z](o)", MarkdownV2.render("<a href=\"o\">x<a>y</a>z</a>"))
    }

    @Test
    fun htmlBlockDeclarationsAreDroppedViaAst() {
        for (literal in listOf("<!DOCTYPE html>", "<?xml version=\"1.0\"?>")) {
            val block = HtmlBlock()
            block.literal = literal
            assertEquals("", MarkdownV2.render(block), "literal: $literal")
        }
        val empty = HtmlBlock()
        empty.literal = ""
        assertEquals("", MarkdownV2.render(empty))
    }

    @Test
    fun htmlInlineDeclarationsAreDroppedViaAst() {
        for (literal in listOf("<?php echo 1; ?>", "<!x>")) {
            val paragraph = Paragraph()
            paragraph.appendChild(HtmlInline(literal))
            assertEquals("", MarkdownV2.render(paragraph), "literal: $literal")
        }
    }

    @Test
    fun malformedHtmlInlineIsEscapedViaAst() {
        val paragraph = Paragraph()
        paragraph.appendChild(HtmlInline("<a href=\"x>y\">"))
        assertEquals("<a href\\=\"x\\>y\"\\>", MarkdownV2.render(paragraph))
    }

    @Test
    fun fencedLanguageIsCappedAt32Chars() {
        val lang = "a".repeat(40)
        assertEquals("```${lang.take(32)}\ncode\n```", MarkdownV2.render("```$lang\ncode\n```"))
    }

    @Test
    fun fencedCodeEscapesBackticks() {
        assertEquals("```\na\\`b\n```", MarkdownV2.render("```\na`b\n```"))
    }

    @Test
    fun emptyFencedCodeBlockRendersClosed() {
        assertEquals("```\n\n```", MarkdownV2.render("```\n```"))
    }

    @Test
    fun fencedCodeBlockWithNullLiteralsViaAst() {
        assertEquals("```\n\n```", MarkdownV2.render(FencedCodeBlock()))
    }

    @Test
    fun indentedCodeBlockTruncatesClosed() {
        val code = (1..100).joinToString("\n") { "    line $it" }
        val result = MarkdownV2.render(code, 300)
        assertTrue(result.length <= 300)
        assertTrue(result.endsWith("…"))
        assertEquals(0, Regex("```").findAll(result).count() % 2)
    }

    @Test
    fun indentedCodeFallsBackToPlainTextOnTinyLimit() {
        assertEquals("abc", MarkdownV2.render("    abc", 5))
    }

    @Test
    fun fencedCodeFallsBackToPlainTextWhenFenceDoesNotFit() {
        assertEquals("code", MarkdownV2.render("```\ncode\n```", 6))
    }

    @Test
    fun headingPrefixTooLongFallsBackToPlainText() {
        assertEquals("x", MarkdownV2.render("# x", 3))
    }

    @Test
    fun tinyLimitListFallsBackToPlainText() {
        // The plain-text fallback keeps item content only; the "-" marker is syntax and never part of plain text
        assertEquals("it…", MarkdownV2.render("- item", 3))
    }

    @Test
    fun unknownCustomBlockShrinksToEmptyViaFootnotes() {
        val parser =
            Parser
                .builder()
                .extensions(MarkdownV2.defaultExtensions + FootnotesExtension.create())
                .build()
        val content = "Text[^1]\n\n[^1]: " + "x".repeat(100)
        assertEquals("Text…", MarkdownV2.render(content, 20, parser))
    }

    @Test
    fun contentExactlyAtLimitIsReturnedUntouched() {
        assertEquals("ab", MarkdownV2.render("ab", 2))
    }

    @Test
    fun tableRaggedRowsArePadded() {
        assertEquals(
            "```\n| a   | b   |\n| --- | --- |\n| x   |     |\n```",
            MarkdownV2.render("| a | b |\n| --- | --- |\n| x |"),
        )
        // Cells beyond the header's column count are dropped at GFM parse time; the table stays one column
        assertEquals(
            "```\n| a   |\n| --- |\n| x   |\n```",
            MarkdownV2.render("| a |\n| --- |\n| x | y |"),
        )
    }

    @Test
    fun tableKoreanCellCountsAsWide() {
        assertEquals(
            "```\n| 한  |\n| --- |\n| a   |\n```",
            MarkdownV2.render("| 한 |\n| --- |\n| a |"),
        )
    }

    @Test
    fun tableCellAtExactWidthLimitIsNotTruncated() {
        val sixteen = "字".repeat(16) // 32 display width — exactly at the limit, not truncated
        val result = MarkdownV2.render("| $sixteen |\n| --- |\n| a |")
        assertTrue(result.contains(sixteen), "cell at the limit must stay intact: $result")
    }

    @Test
    fun tableCellBeyondWidthLimitIsTruncatedWithEllipsis() {
        val seventeen = "字".repeat(17)
        val result = MarkdownV2.render("| $seventeen |\n| --- |\n| a |")
        assertTrue(result.contains("字".repeat(15) + "…"), "cell not truncated: $result")
        assertFalse(result.contains(seventeen))
    }

    @Test
    fun tableBodyOnlyRendersFirstRowAsHeaderViaAst() {
        val cell1 = TableCell()
        cell1.appendChild(Text("a"))
        val cell2 = TableCell()
        cell2.appendChild(Text("b"))
        val row = TableRow()
        row.appendChild(cell1)
        row.appendChild(cell2)
        val body = TableBody()
        body.appendChild(row)
        val table = TableBlock()
        table.appendChild(body)
        assertEquals("```\n| a   | b   |\n| --- | --- |\n```", MarkdownV2.render(table))
    }

    @Test
    fun emptyDocumentRendersEmptyViaAst() {
        assertEquals("", MarkdownV2.render(Document()))
    }

    @Test
    fun regexLinearOnHugeUnclosedTag() {
        // HTML_TAG's lazy [^>]*? expands stepwise at O(n) worst case; catastrophic backtracking would hang this test
        val inline = HtmlInline("<b " + "x".repeat(100_000))
        val paragraph = Paragraph()
        paragraph.appendChild(inline)
        val result = MarkdownV2.render(paragraph, 200_000)
        assertTrue(result.length > 100_000, "literal text should pass through: ${result.length}")
    }

    @Test
    fun regexLinearOnAlternatingSlashesInAttributes() {
        // [^>]*? interacting with the optional /: alternating-slash attribute strings must still match linearly
        val inline = HtmlInline("<b " + "/x".repeat(50_000) + ">")
        val paragraph = Paragraph()
        paragraph.appendChild(inline)
        assertEquals("**", MarkdownV2.render(paragraph))
    }

    @Test
    fun regexLinearOnHugeHrefValue() {
        // The href attribute scan walks the value once with a single stopping point: 100k-scale values extract linearly
        val paragraph = Paragraph()
        paragraph.appendChild(HtmlInline("<a href=\"" + "x".repeat(100_000) + "\">"))
        paragraph.appendChild(Text("t"))
        paragraph.appendChild(HtmlInline("</a>"))
        val result = MarkdownV2.render(paragraph, 200_000)
        assertEquals("[t](", result.take(4))
        assertTrue(result.endsWith(")"))
        assertEquals(100_005, result.length)
    }

    @Test
    fun regexLinearOnHugeWhitespaceAroundEquals() {
        // Whitespace between name, = and value is skipped in one linear pass: 50k-scale gaps match linearly
        val paragraph = Paragraph()
        paragraph.appendChild(HtmlInline("<a href" + " ".repeat(50_000) + "=\"u\">"))
        paragraph.appendChild(Text("t"))
        paragraph.appendChild(HtmlInline("</a>"))
        assertEquals("[t](u)", MarkdownV2.render(paragraph, 200_000))
    }

    @Test
    fun quoteWithInnerListTruncates() {
        val content = "> " + (1..100).joinToString("\n> ") { "- item $it" }
        val result = MarkdownV2.render(content, 100)
        assertTrue(result.length <= 100)
        assertTrue(result.endsWith("…"))
        assertTrue(result.contains("> • item 1"))
        result.lines().dropLast(1).forEach { line ->
            assertTrue(line.startsWith("> "), "quote prefix lost: $line")
        }
    }

    @Test
    fun mixedDocumentTruncationKeepsLeadingBlocks() {
        val content =
            buildString {
                appendLine("# Title")
                appendLine()
                appendLine("Intro paragraph with *emphasis* and [link](https://example.com).")
                appendLine()
                appendLine((1..100).joinToString("\n") { "- item $it with filler" })
            }
        val result = MarkdownV2.render(content, 300)
        assertTrue(result.length <= 300)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("\\# Title"))
        assertTrue(result.contains("_emphasis_"))
        assertTrue(result.contains("[link](https://example.com)"))
        assertTrue(result.contains("• item 1"))
        assertFalse(result.contains("item 100"))
    }

    @Test
    fun htmlEmphasisTagAliasesMapToEntities() {
        val content =
            "a<strong>s</strong>b<em>e</em>c<del>d</del>e<strike>k</strike>f<ins>u</ins>" +
                "g<kbd>k</kbd>h<samp>s</samp>i<tt>t</tt>j"
        assertEquals("a*s*b_e_c~d~e~k~f__u__g`k`h`s`i`t`j", MarkdownV2.render(content))
    }

    @Test
    fun anchorSelfClosingIsLiteral() {
        if (!htmlParsingSupported) return

        assertEquals("<a href\\=\"u\"/\\>x", MarkdownV2.render("<a href=\"u\"/>x"))
    }

    @Test
    fun fencedLanguageKeepsHyphen() {
        assertEquals("```my-lang\nc\n```", MarkdownV2.render("```my-lang\nc\n```"))
    }

    @Test
    fun deepAstEmphasisFlattens() {
        // The parser pairs consecutive underscores into StrongEmphasis; deep Emphasis chains can only be built via the AST
        var node: Node = Text("x")
        repeat(150) { node = Emphasis("_").apply { appendChild(node) } }
        val paragraph = Paragraph()
        paragraph.appendChild(node)
        val result = MarkdownV2.render(paragraph)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.contains("x"))
    }

    @Test
    fun deepAstStrikethroughFlattens() {
        var node: Node = Text("x")
        repeat(150) { node = Strikethrough("~~").apply { appendChild(node) } }
        val paragraph = Paragraph()
        paragraph.appendChild(node)
        val result = MarkdownV2.render(paragraph)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.contains("x"))
    }

    @Test
    fun blockBudgetExhaustedBeforeSecondBlock() {
        assertEquals("aaaa…", MarkdownV2.render("aaaa\n\nb", 5))
    }

    @Test
    fun paragraphLineBreakSkippedWhenBudgetFull() {
        assertEquals("aaaa…", MarkdownV2.render("aaaa\nbb", 5))
    }

    @Test
    fun truncatedGroupIsNotAppendedTwice() {
        // When first-line truncation stops at a special-char boundary (budget 1 left), the pre-fix tail fallback
        // would truncate the same group a second time and append it, duplicating one character
        val paragraph = Paragraph()
        paragraph.appendChild(Text("aaa*"))
        paragraph.appendChild(SoftLineBreak())
        paragraph.appendChild(Text("end"))
        assertEquals("aaa…", MarkdownV2.render(paragraph, 5))
    }

    @Test
    fun hardLineBreakInsideTruncatedParagraph() {
        val content = "keep" + (1..2000).joinToString("  \n") { "row $it" }
        val result = MarkdownV2.render(content, 100)
        assertTrue(result.length <= 100)
        assertTrue(result.endsWith("…"))
        assertTrue(result.startsWith("keep"))
    }

    @Test
    fun quoteBudgetExhaustedBeforeInnerSecondBlock() {
        val content = "> " + "a".repeat(50) + "\n>\n> " + "b".repeat(50)
        val result = MarkdownV2.render(content, 54)
        assertTrue(result.length <= 54)
        assertTrue(result.startsWith("> " + "a".repeat(50)))
        assertTrue(result.endsWith("…"))
    }

    @Test
    fun astOrderedListWithoutStartNumberDefaultsToOne() {
        val paragraph = Paragraph()
        paragraph.appendChild(Text("x"))
        val item = ListItem()
        item.appendChild(paragraph)
        val list = OrderedList()
        list.appendChild(item)
        assertEquals("1\\. x", MarkdownV2.render(list))
    }

    @Test
    fun astOrderedListNullStartInTruncationDefaultsToOne() {
        val item =
            ListItem().apply {
                appendChild(Paragraph().apply { appendChild(Text("x".repeat(500))) })
            }
        val list = OrderedList()
        list.appendChild(item)
        val result = MarkdownV2.render(list, 100)
        assertTrue(result.length <= 100, "len=${result.length}")
        assertTrue(result.startsWith("1\\. "), "got: ${result.take(30)}")
    }

    @Test
    fun astTruncatedListSkipsNonListItemChildren() {
        val long = Paragraph().apply { appendChild(Text("x".repeat(500))) }
        val item = ListItem().apply { appendChild(Paragraph().apply { appendChild(Text("y")) }) }
        val list = BulletList()
        list.appendChild(long)
        list.appendChild(item)
        val result = MarkdownV2.render(list, 100)
        assertTrue(result.length <= 100)
        assertTrue(result.startsWith("• y"))
    }

    @Test
    fun astListSkipsNonListItemChildren() {
        val paragraph = Paragraph()
        paragraph.appendChild(Text("x"))
        val good = ListItem()
        good.appendChild(Paragraph().apply { appendChild(Text("y")) })
        val list = BulletList()
        list.appendChild(paragraph)
        list.appendChild(good)
        assertEquals("• y", MarkdownV2.render(list))
    }

    @Test
    fun astEmptyListItemRendersBareMarker() {
        val list = BulletList()
        list.appendChild(ListItem())
        assertEquals("•", MarkdownV2.render(list))
    }

    @Test
    fun astCodeBlocksWithNullLiteralFallBackToPlainText() {
        val document = Document()
        document.appendChild(FencedCodeBlock())
        document.appendChild(IndentedCodeBlock())
        val paragraph = Paragraph()
        paragraph.appendChild(Text("x"))
        document.appendChild(paragraph)
        assertEquals("x", MarkdownV2.render(document, 6))
    }

    @Test
    fun astHtmlBlockWithNullLiteralDrops() {
        assertEquals("", MarkdownV2.render(HtmlBlock()))
    }

    @Test
    fun astImageWithNullDestinationRendersAlt() {
        val image = Image(destination = null, title = null)
        image.appendChild(Text("alt"))
        val paragraph = Paragraph()
        paragraph.appendChild(image)
        assertEquals("alt", MarkdownV2.render(paragraph))
    }

    @Test
    fun astNestedLinkDegradesToInnerText() {
        // CommonMark parsing never nests links; a hand-built AST still must not emit
        // nested link entities
        val inner = Link(destination = "inner", title = null)
        inner.appendChild(Text("x"))
        val outer = Link(destination = "outer", title = null)
        outer.appendChild(inner)
        val paragraph = Paragraph()
        paragraph.appendChild(outer)
        assertEquals("[x](outer)", MarkdownV2.render(paragraph))
    }

    @Test
    fun unclosedHreflessAnchorIsFilteredInCompletion() {
        if (!htmlParsingSupported) return

        assertEquals("prefix t", MarkdownV2.render("prefix <a>t"))
    }

    @Test
    fun unclosedNestedAnchorIsFilteredInCompletion() {
        if (!htmlParsingSupported) return

        assertEquals("q [x<a\\>y](o)", MarkdownV2.render("q <a href=\"o\">x<a>y"))
    }

    @Test
    fun astTableSkipsForeignChildren() {
        val cell = TableCell()
        cell.appendChild(Text("a"))
        val row = TableRow()
        row.appendChild(cell)
        row.appendChild(Paragraph())
        val head = TableHead()
        head.appendChild(row)
        head.appendChild(Paragraph())
        val table = TableBlock()
        table.appendChild(head)
        table.appendChild(Paragraph())
        assertEquals("```\n| a   |\n| --- |\n```", MarkdownV2.render(table))
    }

    @Test
    fun astTableHeadOnlyRendersHeaderRow() {
        assertEquals("```\n| a   |\n| --- |\n```", MarkdownV2.render("| a |\n| --- |"))
    }

    @Test
    fun astTableCellTrailingLoneSurrogateCountsAsSingle() {
        val cell = TableCell()
        cell.appendChild(Text("a\uD800"))
        val row = TableRow()
        row.appendChild(cell)
        val head = TableHead()
        head.appendChild(row)
        val table = TableBlock()
        table.appendChild(head)
        assertEquals("```\n| a\uD800  |\n| --- |\n```", MarkdownV2.render(table))
    }

    @Test
    fun astTableCellLoneSurrogateBeforeWideCharCountsSeparately() {
        // A lone high surrogate before a wide char: widths count separately as 1+2 (pre-fix it was swallowed as an imaginary pair counting 2)
        val cell = TableCell()
        cell.appendChild(Text("\uD800中"))
        val row = TableRow()
        row.appendChild(cell)
        val head = TableHead()
        head.appendChild(row)
        val table = TableBlock()
        table.appendChild(head)
        assertEquals("```\n| \uD800中 |\n| --- |\n```", MarkdownV2.render(table))
    }

    @Test
    fun tableWideCharRangesAllCountAsDouble() {
        // Covers every wide range: Jamo, Kangxi radicals, kana, Ext-A, CJK, Yi, Hangul,
        // compatibility ideographs, vertical forms, fullwidth letters, fullwidth currency
        val wide = "\u1100\u2F00\u3041\u3400\u4E00\uA000\uAC00\uF900\uFE30\uFF21\uFFE0"
        assertEquals(
            "```\n| $wide |\n| ${"-".repeat(22)} |\n| a${" ".repeat(21)} |\n```",
            MarkdownV2.render("| $wide |\n| --- |\n| a |"),
        )
    }

    @Test
    fun astTruncatedParagraphOpeningWithSoftBreak() {
        val paragraph = Paragraph()
        paragraph.appendChild(SoftLineBreak())
        paragraph.appendChild(Text("x".repeat(500)))
        val result = MarkdownV2.render(paragraph, 100)
        assertTrue(result.length <= 100)
        assertTrue(result.endsWith("…"))
        assertFalse(result.startsWith("\n"))
    }

    @Test
    fun tableWideEmojiCellTruncatesByDisplayWidth() {
        val cell = "😀".repeat(20) // display width 40, beyond the 32 cap
        val result = MarkdownV2.render("| $cell |\n| --- |\n| a |")
        assertTrue(result.contains("😀".repeat(15) + "…"), "wide emoji cell not truncated: $result")
        assertFalse(result.contains("😀".repeat(16)))
    }

    @Test
    fun tableWideBmpEmojiRangesCountAsDoubleFirstHalf() {
        // One char per wide emoji range: watches, angle brackets, fast-forward, alarm clock,
        // hourglass, squares, umbrella, zodiac, wheelchair, anchor, zap, circles, sports,
        // snowman, Ophiuchus, no-entry
        val wide = "\u231A\u2329\u23E9\u23F0\u23F3\u25FD\u2614\u2648\u267F\u2693\u26A1\u26AA\u26BD\u26C4\u26CE\u26D4"
        assertEquals(
            "```\n| $wide |\n| ${"-".repeat(wide.length * 2)} |\n| a${" ".repeat(wide.length * 2 - 1)} |\n```",
            MarkdownV2.render("| $wide |\n| --- |\n| a |"),
        )
    }

    @Test
    fun tableWideBmpEmojiRangesCountAsDoubleSecondHalf() {
        // church, fountain, boat, tent, fuel pump, check mark, fists, sparkle, cross marks,
        // question/exclamation marks, plus signs, curly loop, heavy squares
        val wide = "\u26EA\u26F2\u26F5\u26FA\u26FD\u2705\u270A\u2728\u274C\u274E\u2753\u2757\u2795\u27B0\u27BF\u2B1B"
        assertEquals(
            "```\n| $wide |\n| ${"-".repeat(wide.length * 2)} |\n| a${" ".repeat(wide.length * 2 - 1)} |\n```",
            MarkdownV2.render("| $wide |\n| --- |\n| a |"),
        )
    }

    @Test
    fun tableWideBmpEmojiRangesCountAsDoubleRemainder() {
        // star, circled heavy circle
        val wide = "\u2B50\u2B55"
        assertEquals(
            "```\n| $wide |\n| ${"-".repeat(wide.length * 2)} |\n| a${" ".repeat(wide.length * 2 - 1)} |\n```",
            MarkdownV2.render("| $wide |\n| --- |\n| a |"),
        )
    }

    @Test
    fun tableZeroWidthCharsCostNoColumnsByDefault() {
        // Combining accent, VS16, ZWJ and the astral variation-selector supplement all cost
        // zero columns — every row keeps the same width as the first
        val rows = "| 中a |\n| --- |\n| 中\u0301a |\n| 中\uFE0Fa |\n| 中\u200Da |\n| 中\uDB40\uDD00a |"
        assertEquals(
            "```\n| 中a |\n| --- |\n| 中\u0301a |\n| 中\uFE0Fa |\n| 中\u200Da |\n| 中\uDB40\uDD00a |\n```",
            MarkdownV2.render(rows),
        )
    }

    @Test
    fun defaultDisplayWidthMeasuresZeroWidthChars() {
        listOf(
            0x0300, // combining acute
            0x200B, // zero-width space
            0x200D, // zero-width joiner
            0x2060, // word joiner
            0x20D0, // combining marks for symbols
            0xFE00, // variation selector 1
            0xFE0F, // variation selector 16
            0xFEFF, // zero-width no-break space
            0x1AB0, // combining diacritical marks extended (astral)
            0x1DC0, // combining diacritical marks supplement (astral)
            0xE0100, // variation selectors supplement (astral)
        ).forEach { codePoint ->
            assertEquals(0, defaultDisplayWidth(codePoint), "code point ${codePoint.toString(16)}")
        }
    }

    @Test
    fun defaultDisplayWidthMeasuresNarrowWideAndAstralChars() {
        assertEquals(1, defaultDisplayWidth('a'.code))
        assertEquals(1, defaultDisplayWidth(0x00D7)) // Ambiguous (×) — narrow by default
        assertEquals(2, defaultDisplayWidth(0x4E2D)) // CJK 中
        assertEquals(2, defaultDisplayWidth(0x1F600)) // astral emoji
        assertEquals(2, defaultDisplayWidth(0x20000)) // CJK extension B
        assertEquals(1, defaultDisplayWidth(0xD800)) // lone surrogate measures as its char value
    }

    @Test
    fun customDisplayWidthRealignsTable() {
        // × (U+00D7) is East Asian Width Ambiguous: narrow by default, wide in CJK fonts —
        // a custom measure can widen it while delegating the rest
        val options =
            RenderOptions(displayWidthOf = { codePoint ->
                if (codePoint == 0x00D7) 2 else defaultDisplayWidth(codePoint)
            })
        assertEquals(
            "```\n| ×× |\n| ---- |\n| a    |\n```",
            MarkdownV2.render("| ×× |\n| --- |\n| a |", options = options),
        )
    }

    @Test
    fun defaultWidthKeepsAmbiguousCharsNarrow() {
        assertEquals(
            "```\n| ××  |\n| --- |\n| a   |\n```",
            MarkdownV2.render("| ×× |\n| --- |\n| a |"),
        )
    }

    @Test
    fun customMaxCellWidthTruncatesCellsSooner() {
        val options = RenderOptions(maxCellWidth = 5)
        val result = MarkdownV2.render("| abcdefgh |\n| --- |", options = options)
        assertTrue(result.contains("abcd…"), "cell not cut to the custom cap: $result")
        assertFalse(result.contains("abcde"))
    }

    @Test
    fun nonPositiveMaxCellWidthIsRejected() {
        assertFailsWith<IllegalArgumentException> { RenderOptions(maxCellWidth = 0) }
    }

    @Test
    fun negativeDisplayWidthIsRejected() {
        // A negative width would silently break cell truncation (the cut budget grows
        // instead of shrinking) — fail fast at the first measured code point instead
        val options = RenderOptions(displayWidthOf = { _ -> -1 })
        assertFailsWith<IllegalArgumentException> {
            MarkdownV2.render("| a | b |\n| --- | --- |\n| c | d |", options = options)
        }
    }

    @Test
    fun quoteFitsAfterBlockwiseShrinkWithinBudget() {
        // The whole render exceeds the budget, but after block-wise shrinking both sub-blocks fit: the list exhausts normally and exits
        assertEquals("> ab\n> cd…", MarkdownV2.render("> ab\n>\n> cd", 10))
    }

    @Test
    fun truncatedListItemKeepsEmphasisClosedAcrossSoftBreak() {
        // applyPrefix's continuation indent used to push the closing _ into a dropped line, emitting • _abc… with an unclosed entity
        assertEquals("• abc def…", MarkdownV2.render("- *abc\ndef*", 12))
    }

    @Test
    fun truncatedQuoteKeepsEmphasisClosedAcrossSoftBreak() {
        assertEquals("> abc def…", MarkdownV2.render("> *abc\ndef*", 11))
    }

    @Test
    fun truncatedListItemKeepsLinkClosedAcrossSoftBreak() {
        assertEquals("• abc def…", MarkdownV2.render("- [abc\ndef](u)", 15))
    }

    @Test
    fun longListWithEmphasisAcrossLinesStaysBalanced() {
        val content = (1..300).joinToString("\n") { "- *a${it}\nb$it*" }
        val result = MarkdownV2.render(content, maxMessageLength)
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.endsWith("…"))
        assertEquals(0, result.count { it == '_' } % 2, "unclosed emphasis: ${result.take(200)}")
    }

    @Test
    fun orderedListIndentOverheadBeyondBudgetFallsBackToPlainText() {
        // When continuation-indent overhead exceeds the whole item budget, item shrinking cannot converge: plain-text fallback;
        // the whole plain text fits, returned as-is without an ellipsis
        assertEquals("a b c d", MarkdownV2.render("1. a\nb\nc\nd", 10))
    }

    @Test
    fun truncatedListStopsAtEmptyItem() {
        val content = "-\n- " + "z".repeat(50)
        assertEquals("z".repeat(9) + "…", MarkdownV2.render(content, 10))
    }

    @Test
    fun truncatedListWithUnfittableCodeItemFallsBackToPlainText() {
        val item = ListItem().apply { appendChild(FencedCodeBlock()) }
        val list = BulletList()
        list.appendChild(item)
        assertEquals("…", MarkdownV2.render(list, 8))
    }

    @Test
    fun quoteTailTooSmallForPrefixFallsBackToPlainText() {
        assertEquals("ab", MarkdownV2.render("> ab", 3))
    }

    @Test
    fun nestedListItemOverrunDegradesToPlainText() {
        // Nested lists do not re-shrink (prevents exponential re-rendering): an inner item that does not fit degrades to escaped plain text, still closed
        assertEquals("• • abc def…", MarkdownV2.render("- - *abc\ndef*", 12))
    }

    @Test
    fun nestedQuoteOverrunFallsBackToPlainText() {
        // A quote inside a list does not fit (indent overhead included): the block is abandoned, document-level plain-text fallback;
        // the whole plain text fits, returned as-is without an ellipsis
        assertEquals("abc def", MarkdownV2.render("- > *abc\ndef*", 13))
    }

    @Test
    fun renderChunkedShortContentSingleChunk() {
        assertEquals(listOf("*hello* world"), MarkdownV2.renderChunked("**hello** world"))
    }

    @Test
    fun renderChunkedSplitsAtBlockBoundaries() {
        assertEquals(listOf("aaa", "bbb"), MarkdownV2.renderChunked("aaa\n\nbbb", 3))
    }

    @Test
    fun renderChunkedSplitsParagraphLines() {
        assertEquals(listOf("aaaa\nbbbb", "cccc"), MarkdownV2.renderChunked("aaaa\nbbbb\ncccc", 9))
    }

    @Test
    fun renderChunkedSplitsInlineNodes() {
        assertEquals(listOf("aa", "_b_", "cc", "_d_"), MarkdownV2.renderChunked("aa *b* cc *d*", 4))
    }

    @Test
    fun renderChunkedSplitsCodeBlockLines() {
        assertEquals(
            listOf("```\naaaa\n```", "```\nbbbb\n```", "```\ncccc\n```"),
            MarkdownV2.renderChunked("```\naaaa\nbbbb\ncccc\n```", 12),
        )
    }

    @Test
    fun renderChunkedSplitsOversizedCodeLineIntoFencedSegments() {
        assertEquals(
            List(5) { "```\naaaa\n```" },
            MarkdownV2.renderChunked("```\n" + "a".repeat(20) + "\n```", 12),
        )
    }

    @Test
    fun renderChunkedListItemsKeepNumberingAcrossChunks() {
        assertEquals(
            listOf("1\\. aaaa", "2\\. bbbb", "3\\. cccc"),
            MarkdownV2.renderChunked("1. aaaa\n2. bbbb\n3. cccc", 10),
        )
    }

    @Test
    fun renderChunkedOversizedItemFlattensWithMarkerOnFirstPiece() {
        assertEquals(listOf("• aaaa", "bbbb"), MarkdownV2.renderChunked("- aaaabbbb", 6))
    }

    @Test
    fun renderChunkedSplitsQuoteChildren() {
        assertEquals(listOf("> aaaa", "> bbbb"), MarkdownV2.renderChunked("> aaaa\n>\n> bbbb", 8))
    }

    @Test
    fun renderChunkedOversizedQuoteChildSplitsLines() {
        assertEquals(listOf("> aaaaaa", "> aaaaaa"), MarkdownV2.renderChunked("> " + "a".repeat(12), 8))
    }

    @Test
    fun renderChunkedOversizedLineDegradesToEscapedUnits() {
        assertEquals(listOf("aaaa", "\\*bbb", "b"), MarkdownV2.renderChunked("aaaa*bbbb", 5))
    }

    @Test
    fun renderChunkedNeverSplitsEscapeUnitsAndLosesNoText() {
        // '.' is a special character that never forms an entity, so the whole paragraph
        // stays plain text and the chunks' concatenation must equal the full escape
        val content = "a.b".repeat(60)
        val chunks = MarkdownV2.renderChunked(content, 7)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 7)
            assertFalse(chunk.endsWith("\\"), "escape sequence split across chunks: $chunk")
        }
        assertEquals(MarkdownV2.escape(content), chunks.joinToString(""))
    }

    @Test
    fun renderChunkedNonPositiveMaxLengthSingleChunk() {
        val content = "word ".repeat(2000)
        assertEquals(listOf(MarkdownV2.render(content)), MarkdownV2.renderChunked(content, 0))
        assertEquals(listOf(MarkdownV2.render(content)), MarkdownV2.renderChunked(content, -5))
    }

    @Test
    fun renderChunkedMaxLengthOneSingleChunk() {
        // A positive size below 2 cannot hold even one escaped special character, so it
        // joins 0 and the negatives on the no-chunking path
        val content = "word ".repeat(2000)
        assertEquals(listOf(MarkdownV2.render(content)), MarkdownV2.renderChunked(content, 1))
    }

    @Test
    fun renderChunkedDefaultLimitSplitsHugeParagraph() {
        val content = "word ".repeat(2000)
        val chunks = MarkdownV2.renderChunked(content)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.length <= maxMessageLength) }
        assertEquals(content.count { it.isLetter() }, chunks.sumOf { chunk -> chunk.count { it.isLetter() } })
    }

    @Test
    fun renderChunkedEmptyContentYieldsNoChunks() {
        assertEquals(emptyList(), MarkdownV2.renderChunked(""))
    }

    @Test
    fun renderChunkedViaAst() {
        val document = MarkdownV2.defaultParser.parse("aaa\n\nbbb")!!
        assertEquals(listOf("aaa", "bbb"), MarkdownV2.renderChunked(document, 3))
        val paragraph = document.firstChild as Paragraph
        assertEquals(listOf("aaa"), MarkdownV2.renderChunked(paragraph, 3))
    }

    @Test
    fun renderChunkedEmptyRenderingBlocksYieldNoChunksViaAst() {
        val htmlComment = HtmlBlock()
        htmlComment.literal = "<!-- hidden -->"
        assertEquals(emptyList(), MarkdownV2.renderChunked(htmlComment, 30))
        assertEquals(emptyList(), MarkdownV2.renderChunked(TableBlock(), 30))
    }

    @Test
    fun renderChunkedTableDegradesToFencedChunks() {
        val chunks =
            MarkdownV2.renderChunked(
                "| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |",
                30,
            )
        assertEquals(4, chunks.size)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 30)
            assertTrue(chunk.startsWith("```"))
            assertTrue(chunk.endsWith("```"))
        }
    }

    @Test
    fun renderChunkedMixedDocumentAllChunksBounded() {
        val content =
            """
            # Title here

            > quoted line one
            > quoted line two

            - first item
            - second item

            ```
            code one
            code two
            ```
            """.trimIndent()
        val chunks = MarkdownV2.renderChunked(content, 30)
        assertTrue(chunks.size > 1)
        chunks.forEach { chunk ->
            assertTrue(chunk.isNotBlank())
            assertTrue(chunk.length <= 30, "over-long chunk: $chunk")
        }
    }

    private fun removeEscapes(text: String): String =
        buildString {
            var index = 0
            while (index < text.length) {
                if (text[index] == '\\' && index + 1 < text.length) {
                    append(text[index + 1])
                    index += 2
                } else {
                    append(text[index])
                    index++
                }
            }
        }
}
