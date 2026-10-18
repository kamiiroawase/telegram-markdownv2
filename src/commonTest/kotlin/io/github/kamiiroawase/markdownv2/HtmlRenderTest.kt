package io.github.kamiiroawase.markdownv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun nestedQuoteFlattensIntoOneWrapper() {
        // Bot API formatting rules say blockquote entities can't nest: the inner level
        // flattens into the outer wrapper (the entities twin's identical ranges degrade
        // to the same single quote at send time)
        assertEquals(
            "<blockquote>deep</blockquote>",
            TelegramHtml.render("> > deep"),
        )
    }

    @Test
    fun deepNestedQuoteWithSiblingsFlattensInOrder() {
        // A quote inside a quote with a sibling paragraph: the inner quote's children
        // join the outer level in document order, one wrapper for them all
        assertEquals(
            "<blockquote>in\n\nsib</blockquote>",
            TelegramHtml.render("> > in\n>\n> sib"),
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
    fun tgEmojiWithZeroIdStaysLiteral() {
        // Custom emoji ids are positive int64 values — "0" fails the server-side parse
        assertEquals(
            "&lt;tg-emoji emoji-id=\"0\"&gt;x&lt;/tg-emoji&gt;",
            TelegramHtml.render("<tg-emoji emoji-id=\"0\">x</tg-emoji>"),
        )
    }

    @Test
    fun tgEmojiWithOverflowingIdStaysLiteral() {
        assertEquals(
            "&lt;tg-emoji emoji-id=\"99999999999999999999\"&gt;x&lt;/tg-emoji&gt;",
            TelegramHtml.render("<tg-emoji emoji-id=\"99999999999999999999\">x</tg-emoji>"),
        )
    }

    @Test
    fun tgEmojiWithUnicodeDigitIdStaysLiteral() {
        // toLongOrNull parses ASCII digits only, matching the server's int64 parse
        assertEquals(
            "&lt;tg-emoji emoji-id=\"٥٣٦\"&gt;x&lt;/tg-emoji&gt;",
            TelegramHtml.render("<tg-emoji emoji-id=\"٥٣٦\">x</tg-emoji>"),
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

    // "Pre and Code can't contain nested entities" — every formatting construct inside
    // an open code entity drops its tags and keeps its content as plain code text,
    // the same silent degradation the MarkdownV2 twin makes

    @Test
    fun markdownEmphasisInsideHtmlCodeDropsItsTags() {
        assertEquals("<code>a b c</code>", TelegramHtml.render("<code>a *b* c</code>"))
        assertEquals("<code>a b c</code>", TelegramHtml.render("<code>a **b** c</code>"))
    }

    @Test
    fun htmlFormattingTagsInsideHtmlCodeDropSilently() {
        assertEquals("<code>a b c</code>", TelegramHtml.render("<code>a <b>b</b> c</code>"))
        assertEquals("<code>a b c</code>", TelegramHtml.render("<code>a <i>b</i> c</code>"))
        assertEquals("<code>a b c</code>", TelegramHtml.render("<code>a <u>b</u> c</code>"))
        assertEquals("<code>a b c</code>", TelegramHtml.render("<code>a <s>b</s> c</code>"))
        assertEquals("<code>a b c</code>", TelegramHtml.render("<code>a <tg-spoiler>b</tg-spoiler> c</code>"))
    }

    @Test
    fun linksInsideHtmlCodeDegradeToLabelText() {
        assertEquals("<code>a b c</code>", TelegramHtml.render("<code>a [b](u) c</code>"))
        assertEquals("<code>x</code>", TelegramHtml.render("<code><a href=\"u\">x</a></code>"))
    }

    @Test
    fun tgEmojiInsideHtmlCodeDropsItsTags() {
        assertEquals("<code>x</code>", TelegramHtml.render("<code><tg-emoji emoji-id=\"1\">x</tg-emoji></code>"))
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

    @Test
    fun sentinelShapedHrefRendersAsRealLink() {
        // The nested-anchor literalization is a type-level mark, not a URL string: a
        // href that literally spells the old sentinel is an ordinary link. Pre-fix the
        // closing </a> mistook it for the mark and left an unclosed <a> behind — output
        // Telegram rejects ("Unclosed tag")
        val output = TelegramHtml.render("<a href=\"nested-literal\">text</a>")
        assertEquals("<a href=\"nested-literal\">text</a>", output)
        assertTelegramHtmlParseable(output, "sentinelShapedHref")
    }

    @Test
    fun deformatDecodesAnchorHrefsExactlyOnce() {
        // CommonMark keeps a link destination literally (no entity decoding), so this
        // href really contains "&amp;": the render escapes it once more and the
        // de-format must decode exactly that one layer back — pre-fix the whole-string
        // decode ran over the already-decoded URL a second time and displayed "&"
        val rendered = TelegramHtml.render("[t](http://x?a=&amp;b=2)")
        assertEquals("<a href=\"http://x?a=&amp;amp;b=2\">t</a>", rendered)
        assertEquals("t (http://x?a=&amp;b=2)", deformatHtml(rendered))
    }

    @Test
    fun deformatKeepsMalformedTagBrackets() {
        // Pre-fix both angle brackets vanished ("a <1a> b" de-formatted to "a 1a b") —
        // the degradation must stay lossless like the unterminated-< case
        assertEquals("a <1a> b", deformatHtml("a <1a> b"))
    }

    // ---------- depth-cap flattening escapes ----------

    // Past MAX_RENDER_DEPTH a subtree flattens to plain text; pre-fix that text landed
    // in the HTML output unescaped, so a deep-enough structure containing '&'/'<b>'
    // produced output the Bot API rejects. Every fallback site escapes now — these
    // sweep the list-item, quote-child and inline-emphasis paths
    @Test
    fun depthCapListFlattenEscapesHtml() {
        val content =
            (0 until 106).joinToString("\n") { "  ".repeat(it) + "- L$it" } +
                "\n" + "  ".repeat(106) + "- text & <b>bold</b>\n"
        val output = TelegramHtml.render(content)
        assertTrue(output.contains("text &amp; &lt;b&gt;bold&lt;/b&gt;"), "raw markup leaked: ${output.takeLast(80)}")
        assertFalse(output.contains("<b>bold"), "unescaped tag survived: ${output.takeLast(80)}")
        assertTelegramHtmlParseable(output, "depthCapListFlatten")
    }

    @Test
    fun depthCapQuoteFlattenEscapesHtml() {
        val output = TelegramHtml.render(">".repeat(110) + " text & <b>bold</b>")
        assertTrue(output.contains("text &amp; &lt;b&gt;bold&lt;/b&gt;"), "raw markup leaked: ${output.takeLast(80)}")
        assertFalse(output.contains("<b>bold"), "unescaped tag survived: ${output.takeLast(80)}")
        assertTelegramHtmlParseable(output, "depthCapQuoteFlatten")
    }

    @Test
    fun depthCapInlineEmphasisFlattenEscapesHtml() {
        // 105 levels of Markdown-nested emphasis: past the inline cap the inner levels
        // flatten through the same escaped fallback
        val content = "*".repeat(210) + "text & <b>bold</b>" + "*".repeat(210)
        val output = TelegramHtml.render(content)
        assertTrue(output.contains("text &amp; &lt;b&gt;bold&lt;/b&gt;"), "raw markup leaked: ${output.takeLast(80)}")
        assertFalse(output.contains("<b>bold"), "unescaped tag survived: ${output.takeLast(80)}")
        assertTelegramHtmlParseable(output, "depthCapInlineEmphasisFlatten")
    }

    // ---------- block tags complete a still-open inline <code> (fourth audit round) ----------

    @Test
    fun blockQuoteCompletesOpenInlineCodeTag() {
        // Pre-fix the wrapper nested inside the open code entity — a nesting Telegram
        // rejects ("Pre and Code can't contain nested entities"); only the four
        // fence-wrapped kinds completed the tag
        val output = TelegramHtml.render("a <code>b\n> q")
        assertEquals("a <code>b</code>\n\n<blockquote>q</blockquote>", output)
        assertTelegramHtmlParseable(output, "blockquoteCompletesCode")
    }

    @Test
    fun listItemCompletesOpenInlineCodeTag() {
        // The list path missed the completion too: a blockquote or pre inside a list item
        // landed inside the code entity through the item's rendered body
        val output = TelegramHtml.render("a <code>b\n\n- item\n\n  ```\n  c\n  ```")
        assertEquals("a <code>b</code>\n\n• item\n\n  <pre>c</pre>", output)
        assertTelegramHtmlParseable(output, "listItemCompletesCode")
    }

    @Test
    fun headingCompletesOpenInlineCodeTag() {
        val output = TelegramHtml.render("x <code>y\n\n# H")
        assertEquals("x <code>y</code>\n\n# H", output)
        assertTelegramHtmlParseable(output, "headingCompletesCode")
    }

    // ---------- <pre> completes a still-open anchor ----------

    @Test
    fun preCompletesOpenAnchor() {
        // Pre-fix the <pre> landed inside the unclosed <a> — "Cannot be nested inside
        // other entities" per the entities documentation; a Markdown link never spans
        // blocks, so the HTML anchor must not either
        val output = TelegramHtml.render("x <a href=\"https://e.com/u\">y\n\n```\ncode\n```")
        assertEquals("x <a href=\"https://e.com/u\">y</a>\n\n<pre>code</pre>", output)
        assertTelegramHtmlParseable(output, "preCompletesAnchor")
    }

    @Test
    fun emphasisOpenedInsideAnchorCompletesWithIt() {
        // Everything opened inside the completed anchor closes with it — a stranded <b>
        // would cross the </a> boundary into crossed tags
        val output = TelegramHtml.render("x <a href=\"https://e.com/u\"><b>y\n\n```\nc\n```")
        assertEquals("x <a href=\"https://e.com/u\"><b>y</b></a>\n\n<pre>c</pre>", output)
        assertTelegramHtmlParseable(output, "emphasisCompletesWithAnchor")
    }

    // ---------- custom emoji via the tg://emoji image syntax ----------

    @Test
    fun customEmojiImageSyntaxRendersAsTgEmojiTag() {
        // The Bot API's MarkdownV2 custom emoji spelling renders as this format's native
        // form, mirroring the <tg-emoji> tag path
        val output = TelegramHtml.render("![👍](tg://emoji?id=5368324170671202286)")
        assertEquals("<tg-emoji emoji-id=\"5368324170671202286\">👍</tg-emoji>", output)
        assertTelegramHtmlParseable(output, "customEmojiImage")
    }

    @Test
    fun customEmojiImageSyntaxInsideLinkDegradesToAlt() {
        // Telegram links cannot nest: inside a link only the alt text survives
        assertEquals(
            "<a href=\"https://e.com/\">x 👍 y</a>",
            TelegramHtml.render("[x ![👍](tg://emoji?id=5368324170671202286) y](https://e.com/)"),
        )
    }

    @Test
    fun customEmojiEmptyFallbackDropsThePair() {
        // An empty tg-emoji pair is an empty entity — Telegram requires the fallback, so
        // the pair rewinds (the entities twin's zero-length range drops alike)
        assertEquals("", TelegramHtml.render("![](tg://emoji?id=5368324170671202286)"))
    }

    // ---------- fence language stays inside the attribute grammar ----------

    @Test
    fun nonAsciiFenceLanguageDegradesToBarePre() {
        // The class attribute's grammar is the ASCII word set: a non-ASCII language drops
        // to a bare <pre> rather than an attribute the parse mode may reject (the
        // MarkdownV2 twin keeps Unicode languages on its fence line — no grammar there)
        val output = TelegramHtml.render("```日本語\ncode\n```")
        assertEquals("<pre>code</pre>", output)
        assertTelegramHtmlParseable(output, "nonAsciiFenceLanguage")
    }

    @Test
    fun asciiFenceLanguageStillCarries() {
        assertEquals(
            "<pre><code class=\"language-kotlin\">val x = 1</code></pre>",
            TelegramHtml.render("```kotlin\nval x = 1\n```"),
        )
    }

    // ---------- custom-emoji fallbacks with unclosed tags inside ----------

    @Test
    fun customEmojiFallbackClosesUnclosedAnchorsInsteadOfCrashing() {
        // An unclosed anchor inside the fallback used to pop under an unchecked cast —
        // a ClassCastException crashing the whole render. It now completes before the
        // emoji closes, literalized exactly the way the MarkdownV2 twin renders it
        // (links cannot nest inside a custom emoji)
        assertEquals(
            "<tg-emoji emoji-id=\"5\">&lt;a href=\"u\"&gt;x</tg-emoji> tail",
            TelegramHtml.render("""![<a href="u">x](tg://emoji?id=5) tail"""),
        )
        assertEquals(
            "<tg-emoji emoji-id=\"5\">&lt;a&gt;x</tg-emoji> tail",
            TelegramHtml.render("""![<a>x](tg://emoji?id=5) tail"""),
        )
    }

    @Test
    fun customEmojiFallbackClosesInlineTagsTightly() {
        // The unclosed <b> completes before </tg-emoji>, so the entity ends at the
        // image — the same tight close a balanced fallback gets, not one swallowing the
        // text that follows
        assertEquals(
            "<tg-emoji emoji-id=\"7\"><b>x</b></tg-emoji> tail",
            TelegramHtml.render("![<b>x](tg://emoji?id=7) tail"),
        )
        assertEquals(
            "<tg-emoji emoji-id=\"7\"><b>x</b></tg-emoji> tail",
            TelegramHtml.render("![<b>x</b>](tg://emoji?id=7) tail"),
        )
    }

    @Test
    fun anchorInsideCustomEmojiFallbackLiteralizes() {
        // The tag path twin: an <a> opened inside a <tg-emoji> fallback literalizes —
        // links cannot nest inside a custom emoji, the same degradation the MarkdownV2
        // twin makes (its emoji entries are anchors, so its linkEntityOpen reads true)
        assertEquals(
            "<tg-emoji emoji-id=\"5\">&lt;a href=\"u\"&gt;x&lt;/a&gt;</tg-emoji>",
            TelegramHtml.render("""<tg-emoji emoji-id="5"><a href="u">x</a></tg-emoji>"""),
        )
    }

// ---------- the sixth audit round: empty entities, sentinel closers, emoji symmetry ----------

    @Test
    fun emptyFenceRendersNothing() {
        // An empty body would render the empty <pre></pre> pair — Telegram rejects
        // empty entities, and the entities twin drops the range (its length > 0 gate),
        // so the block renders to nothing, in quotes and list items alike
        assertEquals("", TelegramHtml.render("```\n```"))
        assertEquals("", TelegramHtml.render("> ```\n> ```"))
        assertEquals("", TelegramHtml.render("- ```\n  ```"))
    }

    @Test
    fun whitespaceOnlyEntityRewindsAtOutputTime() {
        // The output-time trimEnd strips the trailing separator whitespace an unclosed
        // tag's blank content sits behind, which the position-exact emptiness check
        // could not see — the pair completed into a literal empty entity
        // (<b></b>). Whitespace-only content reads as empty there and the opener
        // rewinds; mid-render the whitespace still counts (it sits between words)
        assertEquals("a", TelegramHtml.render("a <b>\n\n<!-- c -->"))
        assertEquals("x[^1]\n\n[^1]: note &amp;", TelegramHtml.render("x[^1]\n\n[^1]: note & <b>"))
        assertEquals("a<b> </b>c", TelegramHtml.render("a<b> </b>c"))
    }

    @Test
    fun legitimateCloserPopsZeroOutputSentinels() {
        // A merged tag or a href-less sentinel anchor emitted nothing, so a legitimate
        // closer reaches the emitting entry beneath them: a </code> behind an unclosed
        // in-code <a> or <b> used to escape literally, surfacing as visible text and
        // stranding the block's remainder inside the code entity
        assertEquals("<code>x</code>", TelegramHtml.render("<code><a href=\"v\">x</code>"))
        assertEquals("<code>x</code> tail", TelegramHtml.render("<code><b>x</code> tail"))
    }

    @Test
    fun nestedTgEmojiTagDegradesToItsFallbackText() {
        // A custom emoji counts as link-open (see linkEntityOpen), so an inner tag
        // drops and its fallback renders as plain text — the same degradation the
        // image path makes of an inner emoji (the tags used to nest)
        assertEquals(
            "<tg-emoji emoji-id=\"1\">abc</tg-emoji>",
            TelegramHtml.render("<tg-emoji emoji-id=\"1\">a<tg-emoji emoji-id=\"2\">b</tg-emoji>c</tg-emoji>"),
        )
    }

    @Test
    fun blankEmojiFallbackDegradesToTheBareUrl() {
        // Telegram requires the fallback: one that renders to no visible text degrades
        // to the bare escaped URL — the same trade the anchor paths make of a blank
        // label (the pair used to render as an entity over whitespace)
        assertEquals("tg://emoji?id=5368324170671202286", TelegramHtml.render("![ ](tg://emoji?id=5368324170671202286)"))
    }

    @Test
    fun fenceInfoFirstWordIsTheLanguage() {
        // CommonMark §4.5: the info string's first word is the language, the rest is
        // metadata — the words used to glue into the bogus language-rustignore
        assertEquals(
            "<pre><code class=\"language-rust\">code</code></pre>",
            TelegramHtml.render("```rust ignore\ncode\n```"),
        )
    }
}
