package io.github.kamiiroawase.markdownv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TelegramHtml.render — CommonMark to Telegram HTML: exact-output assertions over the
 * inline and block mapping, the escaping rules (uniform `&`/`<`/`>` plus `"` in
 * attributes), and the tag-balancing discipline (unclosed input tags complete, empty
 * entities drop, crossed closers go literal). Every case also passes the tdlib HTML
 * parser port.
 */
class HtmlRenderTest {
    @Test
    fun plainTextPassesThrough() {
        assertEquals("hello world", TelegramHtml.render("hello world"))
    }

    @Test
    fun specialCharactersEscape() {
        assertEquals("a &amp; b &lt; c &gt; d", TelegramHtml.render("a & b < c > d"))
    }

    @Test
    fun quotesAndApostrophesNeedNoEscapingInText() {
        assertEquals("it's \"fine\"", TelegramHtml.render("it's \"fine\""))
    }

    @Test
    fun emphasisMapsToItalicTag() {
        assertEquals("<i>word</i>", TelegramHtml.render("*word*"))
    }

    @Test
    fun strongEmphasisMapsToBoldTag() {
        assertEquals("<b>word</b>", TelegramHtml.render("**word**"))
    }

    @Test
    fun underscoreStrongMapsToBoldTag() {
        // Unlike MarkdownV2 (where __ renders an underline), CommonMark reads __ as
        // strong emphasis — the input vocabulary decides, not the output format
        assertEquals("<b>word</b>", TelegramHtml.render("__word__"))
    }

    @Test
    fun strikethroughMapsToStrikeTag() {
        assertEquals("<s>word</s>", TelegramHtml.render("~~word~~"))
    }

    @Test
    fun nestedEmphasisNestsTags() {
        assertEquals("<b>a <i>b</i> c</b>", TelegramHtml.render("**a *b* c**"))
    }

    @Test
    fun codeSpanMapsToCodeTag() {
        assertEquals("<code>a &lt; b</code>", TelegramHtml.render("`a < b`"))
    }

    @Test
    fun fencedCodeWithLanguageCarriesClass() {
        assertEquals(
            "<pre><code class=\"language-kotlin\">fun x() {}</code></pre>",
            TelegramHtml.render("```kotlin\nfun x() {}\n```"),
        )
    }

    @Test
    fun fencedCodeWithoutLanguageUsesBarePre() {
        assertEquals(
            "<pre>plain &amp; simple</pre>",
            TelegramHtml.render("```\nplain & simple\n```"),
        )
    }

    @Test
    fun fencedCodeLanguageIsSanitized() {
        // The info string is untrusted: only letters/digits/-/+/#/# survive into the
        // class attribute, so it can neither leave the quoted value nor inject markup
        assertEquals(
            "<pre><code class=\"language-kotlinscript\">x</code></pre>",
            TelegramHtml.render("```kotlin\"><script>\nx\n```"),
        )
    }

    @Test
    fun indentedCodeUsesBarePre() {
        assertEquals(
            "<pre>indented code</pre>",
            TelegramHtml.render("    indented code"),
        )
    }

    @Test
    fun linkMapsToAnchor() {
        assertEquals(
            "<a href=\"https://example.com\">text</a>",
            TelegramHtml.render("[text](https://example.com)"),
        )
    }

    @Test
    fun linkUrlEscapesAttributeCharacters() {
        assertEquals(
            "<a href=\"https://e.com/?a=1&amp;b=2&quot;x\">text</a>",
            TelegramHtml.render("[text](https://e.com/?a=1&b=2\"x)"),
        )
    }

    @Test
    fun emptyLabelLinkDegradesToBareUrl() {
        assertEquals("https://example.com", TelegramHtml.render("[](https://example.com)"))
    }

    @Test
    fun emptyUrlLinkDegradesToLabel() {
        assertEquals("text", TelegramHtml.render("[text]()"))
    }

    @Test
    fun imageDegradesToLinkOnAltText() {
        assertEquals(
            "<a href=\"https://e.com/i.png\">alt</a>",
            TelegramHtml.render("![alt](https://e.com/i.png)"),
        )
    }

    @Test
    fun emptyAltImageDegradesToBareUrl() {
        assertEquals("https://e.com/i.png", TelegramHtml.render("![](https://e.com/i.png)"))
    }

    @Test
    fun imageInsideLinkKeepsOuterLinkSingleLevel() {
        assertEquals(
            "<a href=\"https://e.com/target\">alt</a>",
            TelegramHtml.render("[![alt](https://e.com/img.png)](https://e.com/target)"),
        )
    }

    @Test
    fun nestedHtmlAnchorInsideLinkLabelGoesLiteral() {
        assertEquals(
            "<a href=\"https://e.com\">outer &lt;a href=\"i\"&gt;inner&lt;/a&gt;</a>",
            TelegramHtml.render("[outer <a href=\"i\">inner</a>](https://e.com)"),
        )
    }

    @Test
    fun linkLabelKeepsFormatting() {
        assertEquals(
            "<a href=\"https://e.com\"><b>bold</b> text</a>",
            TelegramHtml.render("[**bold** text](https://e.com)"),
        )
    }

    @Test
    fun headingSpellsOutLiteralHashes() {
        assertEquals("## Title", TelegramHtml.render("## Title"))
        assertEquals("###### Deep", TelegramHtml.render("###### Deep"))
    }

    @Test
    fun headingKeepsInlineFormatting() {
        assertEquals("## a <b>b</b>", TelegramHtml.render("## a **b**"))
    }

    @Test
    fun thematicBreakRendersEmDashes() {
        assertEquals("———", TelegramHtml.render("---"))
    }

    @Test
    fun bulletListUsesBulletMarkers() {
        assertEquals("• a\n• b", TelegramHtml.render("- a\n- b"))
    }

    @Test
    fun orderedListUsesUnescapedDotMarkers() {
        assertEquals("1. a\n2. b", TelegramHtml.render("1. a\n2. b"))
    }

    @Test
    fun orderedListStartsAtItsOwnNumber() {
        assertEquals("3. a\n4. b", TelegramHtml.render("3. a\n4. b"))
    }

    @Test
    fun listContinuationLinesIndentToMarkerWidth() {
        assertEquals("• a\n  continued", TelegramHtml.render("- a\n  continued"))
    }

    @Test
    fun nestedListIndentsToParentMarker() {
        // The nested list follows the item's paragraph, so the blank block separator
        // lands between them (the same shape the MarkdownV2 render emits)
        assertEquals("• a\n\n  • b", TelegramHtml.render("- a\n  - b"))
    }

    @Test
    fun blockQuoteWrapsInTag() {
        assertEquals("<blockquote>quoted</blockquote>", TelegramHtml.render("> quoted"))
    }

    @Test
    fun quoteParagraphsJoinInsideOneWrapper() {
        assertEquals(
            "<blockquote>a\n\nb</blockquote>",
            TelegramHtml.render("> a\n>\n> b"),
        )
    }

    @Test
    fun nestedQuoteNestsWrappers() {
        assertEquals(
            "<blockquote><blockquote>deep</blockquote></blockquote>",
            TelegramHtml.render("> > deep"),
        )
    }

    @Test
    fun quoteInsideListItemKeepsItsFormatting() {
        // The capability the MarkdownV2 render cannot offer: an HTML blockquote is a
        // wrapper tag, not a line-prefix grammar, so it stays a quote behind list
        // structure (MarkdownV2 must degrade it to plain content there)
        assertEquals(
            "• <blockquote>quoted</blockquote>",
            TelegramHtml.render("- > quoted"),
        )
    }

    @Test
    fun emptyQuoteRendersNothing() {
        // The MarkdownV2 render keeps a bare ">" marker for an all-blank quote; an empty
        // tag pair is no quote at all, so the HTML render emits nothing
        assertEquals("", TelegramHtml.render("> ![]()"))
    }

    @Test
    fun tableDegradesToAlignedPre() {
        assertEquals(
            "<pre>| h1  | h2  |\n| --- | --- |\n| a   | b   |</pre>",
            TelegramHtml.render("| h1 | h2 |\n| --- | --- |\n| a | b |"),
        )
    }

    @Test
    fun tableContentEscapesInsidePre() {
        // Cell widths measure the raw text ("a & b" is 5 columns); the escaping happens
        // on the aligned lines, so "&amp;" renders wider than it measured ("<x>" pads to
        // 5 columns as "<x>  ", and the row join adds its own space before the pipe)
        assertEquals(
            "<pre>| a &amp; b |\n| ----- |\n| &lt;x&gt;   |</pre>",
            TelegramHtml.render("| a & b |\n| --- |\n| <x> |"),
        )
    }

    @Test
    fun htmlInlineBoldPassesThrough() {
        assertEquals("a <b>bold</b> c", TelegramHtml.render("a <b>bold</b> c"))
    }

    @Test
    fun htmlInlineSynonymsMapToCanonicalTags() {
        assertEquals(
            "<b>1</b><i>2</i><u>3</u><s>4</s><code>5</code>",
            TelegramHtml.render(
                "<strong>1</strong><em>2</em><ins>3</ins><del>4</del><kbd>5</kbd>",
            ),
        )
    }

    @Test
    fun htmlInlineBrRendersNewline() {
        assertEquals("a\nb", TelegramHtml.render("a<br>b"))
    }

    @Test
    fun htmlInlineUnknownTagStaysLiteral() {
        assertEquals(
            "a &lt;span&gt;x&lt;/span&gt; b",
            TelegramHtml.render("a <span>x</span> b"),
        )
    }

    @Test
    fun htmlInlineSelfClosingStaysLiteral() {
        assertEquals("a &lt;b/&gt; c", TelegramHtml.render("a <b/> c"))
    }

    @Test
    fun tgSpoilerPassesThrough() {
        // One of the constructs only the HTML parse mode can express
        assertEquals(
            "a <tg-spoiler>secret</tg-spoiler> b",
            TelegramHtml.render("a <tg-spoiler>secret</tg-spoiler> b"),
        )
    }

    @Test
    fun tgEmojiPassesThroughWithNumericId() {
        assertEquals(
            "<tg-emoji emoji-id=\"5368324170671202286\">😀</tg-emoji>",
            TelegramHtml.render("<tg-emoji emoji-id=\"5368324170671202286\">😀</tg-emoji>"),
        )
    }

    @Test
    fun tgEmojiWithoutIdStaysLiteral() {
        assertEquals(
            "&lt;tg-emoji&gt;x&lt;/tg-emoji&gt;",
            TelegramHtml.render("<tg-emoji>x</tg-emoji>"),
        )
    }

    @Test
    fun tgEmojiWithNonNumericIdStaysLiteral() {
        assertEquals(
            "&lt;tg-emoji emoji-id=\"abc\"&gt;x&lt;/tg-emoji&gt;",
            TelegramHtml.render("<tg-emoji emoji-id=\"abc\">x</tg-emoji>"),
        )
    }

    @Test
    fun unclosedInlineTagCompletesAtOutput() {
        assertEquals("a <b>bold</b>", TelegramHtml.render("a <b>bold"))
    }

    @Test
    fun unclosedAnchorCompletesAtOutput() {
        assertEquals(
            "go <a href=\"https://e.com\">now</a>",
            TelegramHtml.render("go <a href=\"https://e.com\">now"),
        )
    }

    @Test
    fun unclosedTagInsideEmphasisCompletesBeforeIt() {
        assertEquals("<i>a <u>b</u></i>", TelegramHtml.render("*a <u>b*"))
    }

    @Test
    fun orphanCloseStaysLiteral() {
        assertEquals("a &lt;/b&gt; c", TelegramHtml.render("a </b> c"))
    }

    @Test
    fun crossedCloseStaysLiteralUntilItsMatch() {
        assertEquals("<b>bold &lt;/i&gt;x</b>", TelegramHtml.render("<b>bold </i>x</b>"))
    }

    @Test
    fun markdownEmphasisEntryIsSafeFromHtmlClose() {
        // `</b>` must not pop the Markdown emphasis' own stack entry
        assertEquals("<i>a&lt;/b&gt;</i>", TelegramHtml.render("*a</b>*"))
    }

    @Test
    fun htmlTagNestsInsideMarkdownEmphasis() {
        assertEquals("<i>a <u>b</u></i>", TelegramHtml.render("*a <u>b</u>*"))
    }

    @Test
    fun hrefEntityReferencesDecodeBeforeReEscaping() {
        assertEquals(
            "<a href=\"https://e.com/?a=1&amp;b=2\">x</a>",
            TelegramHtml.render("<a href=\"https://e.com/?a=1&#38;b=2\">x</a>"),
        )
    }

    @Test
    fun hreflessAnchorKeepsContentUnlinked() {
        assertEquals("plain", TelegramHtml.render("<a>plain</a>"))
    }

    @Test
    fun emptyHtmlPairDrops() {
        assertEquals("ac", TelegramHtml.render("a<b></b>c"))
    }

    @Test
    fun emptyHtmlPairChainDrops() {
        assertEquals("ac", TelegramHtml.render("a<b><i></i></b>c"))
    }

    @Test
    fun htmlBlockDegradesToPre() {
        assertEquals(
            "<pre>&lt;div&gt;\ncontent\n&lt;/div&gt;</pre>",
            TelegramHtml.render("<div>\ncontent\n</div>"),
        )
    }

    @Test
    fun commentBlockDrops() {
        assertEquals("", TelegramHtml.render("<!-- c -->"))
    }

    @Test
    fun inlineCommentDrops() {
        assertEquals("a  b", TelegramHtml.render("a <!-- c --> b"))
    }

    @Test
    fun markdownCodeInsideHtmlCodeShowsBackticks() {
        // The code span's own <code> tags would nest a second code level; its backticks
        // stay as literal text instead — exactly how MarkdownV2 renders the shape
        assertEquals(
            "<code>a `x` b</code>",
            TelegramHtml.render("<code>a `x` b</code>"),
        )
    }

    @Test
    fun htmlCodeInsideHtmlCodeMerges() {
        assertEquals("<code>abc</code>", TelegramHtml.render("<code>a<kbd>b</kbd>c</code>"))
    }

    @Test
    fun codeClosesBeforeAFollowingPre() {
        // An inline <code> left open would swallow the block into its entity: the code
        // family force-closes before the <pre>, while an ordinary emphasis stays open
        // across the block boundary and completes at output (the same containment the
        // MarkdownV2 render ships for its emphasis entities)
        assertEquals(
            "<i>a<code>c</code>\n\n<pre>x</pre></i>",
            TelegramHtml.render("<i>a<code>c\n\n    x"),
        )
    }

    @Test
    fun entityReferenceInTextRoundTrips() {
        // The parser resolves entity references in text; the renderer re-escapes the
        // decoded character, so the visible output matches the source
        assertEquals("a &amp; b", TelegramHtml.render("a &amp; b"))
        assertEquals("a &amp; b", TelegramHtml.render("a &#38; b"))
    }

    @Test
    fun softAndHardBreaksRenderNewlines() {
        assertEquals("a\nb", TelegramHtml.render("a\nb"))
        assertEquals("a\nb", TelegramHtml.render("a\\\nb"))
    }

    @Test
    fun blocksSeparateWithBlankLines() {
        assertEquals("a\n\nb", TelegramHtml.render("a\n\nb"))
    }

    @Test
    fun escapeEscapesAllThreeCharacters() {
        assertEquals("a&lt;b&gt;&amp;c", TelegramHtml.escape("a<b>&c"))
    }

    @Test
    fun everyRenderOutputIsParseable() {
        val outputs =
            listOf(
                "hello **world** [link](https://e.com) `code` ~~strike~~",
                "> quote\n> more\n\n- item\n- > quoted item",
                "```python\nx = 1 < 2 & 3\n```",
                "| a & b | c |\n| --- | --- |\n| <x> | y |",
                "a <b>unclosed\n\npara <tg-spoiler>sp</tg-spoiler>",
                "<a href=\"https://e.com/?x=1&amp;y=2\">label</a>",
                "a <b>bold</b> c",
                "- > deep\n  > quote",
            )
        outputs.forEach { content ->
            val output = TelegramHtml.render(content)
            assertTelegramHtmlParseable(output, "render of ${content.replace("\n", "\\n")}")
        }
    }

    @Test
    fun sweepRendersParseable() {
        for (content in sweepContents()) {
            val output = TelegramHtml.render(content)
            assertTelegramHtmlParseable(output, "sweep render of ${content.replace("\n", "\\n")}")
            // The MarkdownV2 twin's outputs serve as a differential check: both parse
            // under their own grammar
            assertTelegramParseable(MarkdownV2.render(content), "mdv2 twin")
        }
    }

    @Test
    fun handBuiltNonDocumentNodeRendersAlone() {
        val document = MarkdownV2.defaultParser.parse("first\n\nsecond")!!
        val paragraph = document.firstChild!!
        val output = TelegramHtml.render(paragraph)
        assertEquals("first", output)
    }
}
