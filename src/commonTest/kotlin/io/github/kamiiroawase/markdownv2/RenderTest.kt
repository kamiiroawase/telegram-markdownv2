package io.github.kamiiroawase.markdownv2

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
 * Rendering behavior — `MarkdownV2.render` without truncation: escaping rules, every
 * block kind, inline-HTML mapping, links/images, table degradation (display widths,
 * caps) and the linear-scan regressions. Behavior-level tests: feed input, assert the
 * exact rendered output. Tests suffixed `ViaAst` build the AST by hand to reach shapes
 * the CommonMark parser cannot produce from text (null literals, foreign children, deep
 * chains, lone surrogates). HTML-parsing tests are guarded by [htmlParsingSupported]
 * because of the upstream JS/Wasm parser defect. Test data is ASCII wherever possible;
 * non-ASCII appears only where the behavior under test is itself Unicode-specific
 * (display width, surrogate pairs).
 */
class RenderTest {
    @Test
    fun shortContentRenderedWithoutTruncation() {
        assertEquals("*hello* world", MarkdownV2.render("**hello** world"))
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
    fun nestedHtmlCodeTagsMergeIntoOneEntity() {
        // Nested code-family tags must not emit glued backticks: ``x`` reads as an empty
        // code entity Telegram rejects, and a third level (```x```) re-parses as a pre
        // fence — the nested span renders as part of the outer entity instead (nested
        // code displays exactly as single code)
        assertEquals("a `xy` b", MarkdownV2.render("a <code>x<code>y</code></code> b"))
        assertEquals("a `xy` b", MarkdownV2.render("a <code><code>x</code>y</code> b"))
        assertEquals("a `xy` b", MarkdownV2.render("a <code><kbd>x</kbd>y</code> b"))
        assertEquals("a `xy` b", MarkdownV2.render("a <code><code><code>x</code></code>y</code> b"))
        // Unclosed inner tags complete merged as well
        assertEquals("a `x`", MarkdownV2.render("a <code><code>x"))
    }

    @Test
    fun unclosedNestedCodeTagsCompleteMergedAtEmphasisBoundary() {
        // The emphasis boundary completes the unclosed pair latest-opened first; a merged
        // entry completes with no backtick, so no glued `` appears at the boundary
        // (pre-fix: _a``xy``_)
        assertEquals("_a`xy`_", MarkdownV2.render("*a<code><code>xy*"))
    }

    @Test
    fun adjacentCodeEntitiesDegradeInsteadOfGluing() {
        // A backtick delimiter gluing onto a just-closed entity's backtick is a
        // reserved-character run Telegram cannot parse (`a``b`): the second span — HTML
        // tag or Markdown code span alike — degrades to escaped plain text, content
        // kept, formatting dropped
        assertEquals("a `x`y b", MarkdownV2.render("a <code>x</code><code>y</code> b"))
        assertEquals("a `x`y b", MarkdownV2.render("a `x`<code>y</code> b"))
        assertEquals("a `x`y b", MarkdownV2.render("a <code>x</code>`y` b"))
        // Unclosed glued tag: completion emits no delimiter either
        assertEquals("a `x`y", MarkdownV2.render("a <code>x</code><code>y"))
    }

    @Test
    fun escapedBacktickBeforeCodeTagStillOpensEntity() {
        // The backtick ending the text is escaped (\` — a literal), not a delimiter:
        // the following entity still opens — the glue check must read the escape
        // parity, not the raw last character
        assertEquals("a \\``y`", MarkdownV2.render("a \\`<code>y</code>"))
    }

    @Test
    fun emptyHtmlCodePairVanishes() {
        // A pair with no visible content (empty, or a lone comment) emits no delimiters:
        // its opener and closer would glue into `` — an empty code entity Telegram
        // rejects. Whitespace content is real content and keeps the entity
        assertEquals("a b", MarkdownV2.render("a <code></code>b"))
        assertEquals("a b", MarkdownV2.render("a <kbd></kbd>b"))
        assertEquals("a b", MarkdownV2.render("a <code><!-- c --></code>b"))
        assertEquals("a b", MarkdownV2.render("a <code><code></code></code>b"))
        assertEquals("a ` ` b", MarkdownV2.render("a <code> </code> b"))
    }

    @Test
    fun unclosedEmptyCodeEntityVanishes() {
        // The same trade at the completion points: a dangling tag that emitted nothing
        // rewinds its opener instead of gluing the closer onto it — at output time
        // (trailing newlines are block separators, not content) and at an emphasis
        // boundary alike
        assertEquals("a", MarkdownV2.render("a <kbd>"))
        assertEquals("_f_", MarkdownV2.render("*f*<code>"))
        assertEquals("_a_", MarkdownV2.render("*a<code>*"))
    }

    @Test
    fun unclosedCodeEntityClosesBeforeFencedBlock() {
        // Left open across a block boundary the entity would swallow the fence markers
        // and its closer would glue onto them (a four-backtick run); it completes
        // before the fence instead, ahead of the block separator. An empty one simply
        // vanishes (see unclosedEmptyCodeEntityVanishes)
        assertEquals("a `x`\n\n```c\ny\n```", MarkdownV2.render("a <kbd>x\n\n```c\ny\n```"))
        assertEquals("a\n\n```c\nx\n```", MarkdownV2.render("a<kbd>\n\n```c\nx\n```"))
        assertEquals("a\n\n```\n| h   |\n| --- |\n| c   |\n```", MarkdownV2.render("a<kbd>\n\n| h |\n| - |\n| c |"))
    }

    @Test
    fun unclosedCodeEntityClosesBeforeHtmlBlock() {
        if (!htmlParsingSupported) return

        assertEquals("a `x`\n\n```\n<div>\ny\n</div>\n```", MarkdownV2.render("a <kbd>x\n\n<div>\ny\n</div>"))
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
    fun hardLineBreakRendersAsNewline() {
        assertEquals("a\nb", MarkdownV2.render("a  \nb"))
    }

    @Test
    fun tableCellKeepsCodeText() {
        val content = "| a |\n| --- |\n| `x` |"
        assertEquals("```\n| a   |\n| --- |\n| x   |\n```", MarkdownV2.render(content))
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

        // Telegram links cannot nest: the anchor nested in emphasis inside the Markdown
        // link's label escapes literally (both its tags), the outer link stays the only
        // link entity — distinct from the emphasis-free shapes covered by the sibling
        // anchor-in-link tests
        assertEquals(
            """[_<a href\="i"\>b</a\>_](u)""",
            MarkdownV2.render("[*<a href=\"i\">b</a>*](u)"),
        )
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
        assertEquals(1, result.count { it == 'x' }, "the single text char was lost or duplicated")
    }

    @Test
    fun tableLongCellTruncatesButKeepsHeader() {
        val longCell = "w".repeat(5000)
        val result = MarkdownV2.render("| a | b |\n| --- | --- |\n| $longCell | x |")
        assertTrue(result.length <= maxMessageLength)
        assertTrue(result.startsWith("```"), "table did not degrade to a code block: ${result.take(20)}")
        assertTrue(result.contains("| a"), "header row was lost: ${result.take(60)}")
        assertEquals(0, Regex("```").findAll(result).count() % 2)
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
    fun quotedGreaterThanSignInHrefValueStaysInTheUrl() {
        if (!htmlParsingSupported) return

        // Per CommonMark/HTML5 a quoted attribute value may contain '>'; the tag scan must
        // not close on it (pre-fix the whole anchor degraded to literal escaped text)
        assertEquals("[t](a>b)", MarkdownV2.render("<a href=\"a>b\">t</a>"))
        assertEquals("[t](a>b)", MarkdownV2.render("<a href='a>b'>t</a>"))
    }

    @Test
    fun quotedGreaterThanSignInNonHrefAttributeStillParses() {
        if (!htmlParsingSupported) return

        // The '>' lives inside a quoted value: the tag still opens, and a '/' inside a
        // quoted value still does not read as self-closing
        assertEquals("*t*", MarkdownV2.render("<b data-x=\"a>b\">t</b>"))
        assertEquals("_t_", MarkdownV2.render("<i title=\"a/\">t</i>"))
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
        assertEquals("[x](&#0;)", MarkdownV2.render("<a href=\"&#0;\">x</a>")) // NUL — invalid message text
        assertEquals("[x](&#x0;)", MarkdownV2.render("<a href=\"&#x0;\">x</a>")) // hex NUL
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
    fun renderNodeOverloadPassesOptionsThrough() {
        val document = MarkdownV2.defaultParser.parse("| a very long cell |\n| --- |\n| b |")!!
        val capped = MarkdownV2.render(document, options = RenderOptions(maxCellWidth = 5))
        assertTrue(capped.contains("a ve…"), "maxCellWidth was not applied: $capped")
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
        // The continuation indent matches the marker's display width: "1\. " renders
        // three columns wide, so the nested bullet indents three spaces
        assertEquals("1\\. a\n\n   • b", MarkdownV2.render("1. a\n   - b"))
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
    fun whitespaceOnlyLabelDegradesToBareUrl() {
        // A whitespace-only label renders no visible text, so it degrades like an empty
        // one — on every path (pre-fix the closed paths emitted "[ ](u)"-shaped entities
        // while the output-time completion of unclosed anchors already treated them as
        // empty)
        assertEquals("u", MarkdownV2.render("[ ](u)"))
        assertEquals("u", MarkdownV2.render("![ ](u)"))
        if (!htmlParsingSupported) return

        // Leading text keeps the anchor inline — a bare tag line parses as an HTML block
        assertEquals("xu", MarkdownV2.render("x<a href=\"u\"> </a>"))
    }

    @Test
    fun unclosedHtmlEmphasisInsideLinkLabelClosesBeforeLink() {
        if (!htmlParsingSupported) return

        // Left open past ](url) the emphasis would cross the link entity into invalid
        // output, and htmlCodeDepth would leak code-escaping onto the text after the link
        assertEquals("[*x*](u)", MarkdownV2.render("[<b>x](u)"))
        assertEquals("[`x`](u) tail\\_more", MarkdownV2.render("[<code>x](u) tail_more"))
    }

    @Test
    fun unclosedAnchorInsideLinkLabelDoesNotSuppressLaterLinks() {
        if (!htmlParsingSupported) return

        // The nested-literal sentinel left by the unclosed inner anchor used to degrade
        // every following link in the document to plain text
        assertEquals("[x <a\\>y](u) and [z](t)", MarkdownV2.render("[x <a>y](u) and [z](t)"))
    }

    @Test
    fun emptyAnchorLabelDegradesToBareUrl() {
        if (!htmlParsingSupported) return

        // The HTML-anchor counterpart of emptyLinkTextDegradesToBareUrl, closed and
        // unclosed alike: the label rewinds, the destination survives as escaped text.
        // Leading text keeps the tags inline — a bare tag line parses as an HTML block
        assertEquals("u", MarkdownV2.render("<a href=\"u\"></a>"))
        assertEquals("xu", MarkdownV2.render("x<a href=\"u\">"))
    }

    @Test
    fun markdownCodeInsideHtmlCodeEntityEscapesBackticks() {
        if (!htmlParsingSupported) return

        // Raw backticks here would pair with the entity's own delimiters and garble the
        // code span boundaries
        assertEquals("`a \\`b\\` c`", MarkdownV2.render("<kbd>a `b` c</kbd>"))
    }

    @Test
    fun unclosedHtmlCodeEntityKeepsTrailingWhitespace() {
        if (!htmlParsingSupported) return

        // The comment tag renders to nothing, so the code entity's content runs to the
        // paragraph tail — "x " with its trailing space. Output-time completion used to
        // trimEnd before the closer and eat that space as block-separator junk. Spaces
        // and tabs are the entity's literal content; only trailing newlines (the
        // paragraph's block separator) are cleared — unclosedHtmlCodeCompletesWithCodeEscaping
        // pins the newline half of that rule
        assertEquals("`x `", MarkdownV2.render("<code>x <!-- c -->"))
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
    fun htmlCloserMatchingMarkdownEmphasisMarkerStaysLiteral() {
        // Pre-fix the HTML closer matched the marker the Markdown emphasis visit itself
        // had pushed, stole it off the stack, and the visit's own removeLast() threw
        // NoSuchElementException ("ArrayDeque is empty") on `_x</i>_`-shaped input
        assertEquals("_x</i\\>_", MarkdownV2.render("_x</i>_"))
        assertEquals("*x</b\\>*", MarkdownV2.render("**x</b>**"))
        assertEquals("~x</s\\>~", MarkdownV2.render("~~x</s>~~"))
    }

    @Test
    fun anchorOpenedInsideEmphasisClosesAtEmphasisBoundary() {
        // Pre-fix the anchor completed at output time and its ](url) crossed the
        // emphasis closer into the label (`_[x_](u)`-shaped) — an entity nesting Telegram
        // rejects; now it closes inside the emphasis, properly nested
        if (!htmlParsingSupported) return

        assertEquals("_[x](u)_", MarkdownV2.render("*<a href=\"u\">x*"))
        assertEquals("_u_", MarkdownV2.render("*<a href=\"u\">*"))
    }

    @Test
    fun unclosedAnchorAndEmphasisCompleteInnermostFirst() {
        // Interleaved unclosed entities complete by opening order at output time: the
        // anchor opened after the bold closes first, and vice versa — pre-fix output()
        // always emitted emphasis closers before link closers, crossing `<b><a href>`-shaped
        // stacks into `*[x*](u)`
        if (!htmlParsingSupported) return

        assertEquals("*[x](u)*", MarkdownV2.render("<b><a href=\"u\">x"))
        assertEquals("[*x*](u)", MarkdownV2.render("<a href=\"u\"><b>x"))
    }

    @Test
    fun closingTagCrossingStillOpenAnchorGoesLiteral() {
        // </b> and </a> match only the innermost open entity: a closer crossing a
        // still-open anchor (or an anchor closer crossing a still-open tag) used to pop
        // across stacks and emit crossed entities like `[*x](u)*`; now the tag renders
        // literally and the crossed entity completes later, innermost first
        if (!htmlParsingSupported) return

        assertEquals("[*x</a\\>*](u)", MarkdownV2.render("<a href=\"u\"><b>x</a>"))
        assertEquals("*[x</b\\>](u)*", MarkdownV2.render("<b><a href=\"u\">x</b>"))
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
        // The unclosed quote makes no well-formed tag: the literal escapes
        val paragraph = Paragraph()
        paragraph.appendChild(HtmlInline("<a href=\"x>"))
        assertEquals("<a href\\=\"x\\>", MarkdownV2.render(paragraph))
    }

    @Test
    fun quotedGreaterThanTagWellFormedEmptyLabelDegradesToUrlViaAst() {
        // Per CommonMark the quoted '>' keeps the tag well-formed, so the anchor path
        // runs; an anchor whose label renders to nothing degrades to the bare escaped
        // URL (pre-fix the tag regex rejected the quoted '>' and escaped the whole tag)
        val paragraph = Paragraph()
        paragraph.appendChild(HtmlInline("<a href=\"x>y\">"))
        assertEquals("x\\>y", MarkdownV2.render(paragraph))
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
        // Pins current behavior, knowingly: the empty pre entity is syntactically valid
        // MarkdownV2, but like every empty entity it is a Telegram-rejection candidate —
        // a known limitation left unchanged here
        assertEquals("```\n\n```", MarkdownV2.render("```\n```"))
    }

    @Test
    fun fencedCodeBlockWithNullLiteralsViaAst() {
        // Same empty-entity trade-off as emptyFencedCodeBlockRendersClosed
        assertEquals("```\n\n```", MarkdownV2.render(FencedCodeBlock()))
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
    fun tagScanLinearOnHugeUnclosedTag() {
        // The manual tag scan walks the string once with no engine recursion; a lazy regex
        // loop would overflow the stack at this scale, a backtracking one would hang
        val inline = HtmlInline("<b " + "x".repeat(100_000))
        val paragraph = Paragraph()
        paragraph.appendChild(inline)
        val result = MarkdownV2.render(paragraph, 200_000)
        assertTrue(result.length > 100_000, "literal text should pass through: ${result.length}")
        assertTrue(
            removeEscapes(result).startsWith("<b " + "x".repeat(100_000)),
            "the unmatched tag must pass through as literal text",
        )
    }

    @Test
    fun tagScanLinearOnAlternatingSlashesInAttributes() {
        // Slashes interleave with attribute characters up to the closing '>'; the scan
        // stays a single pass and the trailing 'x' keeps the tag open (not self-closing)
        val paragraph = Paragraph()
        paragraph.appendChild(HtmlInline("<b " + "/x".repeat(50_000) + ">"))
        paragraph.appendChild(Text("y"))
        // Content after the tag keeps the bold entity non-empty — no pinning of the empty
        // `**` entity, which Telegram would reject
        assertEquals("*y*", MarkdownV2.render(paragraph))
    }

    @Test
    fun tagScanLinearOnHugeHrefValue() {
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
    fun tagScanLinearOnHugeWhitespaceAroundEquals() {
        // Whitespace between name, = and value is skipped in one linear pass: 50k-scale gaps match linearly
        val paragraph = Paragraph()
        paragraph.appendChild(HtmlInline("<a href" + " ".repeat(50_000) + "=\"u\">"))
        paragraph.appendChild(Text("t"))
        paragraph.appendChild(HtmlInline("</a>"))
        assertEquals("[t](u)", MarkdownV2.render(paragraph, 200_000))
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
    fun anchorUnquotedHrefTrailingSlashIsNotSelfClosing() {
        if (!htmlParsingSupported) return

        // HTML5: an unquoted attribute value ends only at whitespace or >, so the slash
        // belongs to the URL — the anchor links, and the URL keeps its trailing slash
        assertEquals("[x](http://e.com/)", MarkdownV2.render("<a href=http://e.com/>x</a>"))
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
    fun astEmptyListItemSkipsLikeThePipelines() {
        // A blank-bodied item is no-content: the full render skips it exactly as the
        // truncation and chunking walks do (forEachListItem), the marker consumed so
        // ordered numbering keeps the source numbers
        val list = BulletList()
        list.appendChild(ListItem())
        assertEquals("", MarkdownV2.render(list))
        assertEquals("• a\n• b", MarkdownV2.render("- a\n-\n- b"))
        assertEquals("1\\. a\n3\\. b", MarkdownV2.render("1. a\n2.\n3. b"))
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
    fun ellipsisWiderThanMaxCellWidthIsRejected() {
        // A measure that renders the ellipsis wider than the cap would push every
        // truncated cell past maxCellWidth — rejected at construction, the same trade
        // as the render-time negative-width check
        assertFailsWith<IllegalArgumentException> {
            RenderOptions(maxCellWidth = 1, displayWidthOf = { _ -> 2 })
        }
    }

    @Test
    fun tinyMaxCellWidthTruncatesToBareEllipsis() {
        // With the default measure the ellipsis is width 1, so cap 1 is constructible and
        // truncates straight to the bare marker
        val result = MarkdownV2.render("| ああああ |\n| --- |", options = RenderOptions(maxCellWidth = 1))
        assertTrue(result.contains("…"), "cell not truncated: $result")
        assertFalse(result.contains("あ"), "wide cell leaked past the cap: $result")
    }

    @Test
    fun negativeDisplayWidthIsRejected() {
        // A negative width would silently break cell truncation (the cut budget grows
        // instead of shrinking) — fail fast. The ellipsis is measured at construction,
        // so an everywhere-negative measure fails before any rendering; a measure that
        // only goes negative on cell content still fails at the first measured code point
        assertFailsWith<IllegalArgumentException> {
            RenderOptions(displayWidthOf = { _ -> -1 })
        }
        val options = RenderOptions(displayWidthOf = { codePoint -> if (codePoint == 0x2026) 1 else -1 })
        assertFailsWith<IllegalArgumentException> {
            MarkdownV2.render("| a | b |\n| --- | --- |\n| c | d |", options = options)
        }
    }
}
