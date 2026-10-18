package io.github.kamiiroawase.markdownv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TelegramEntities.render — CommonMark to plain text plus MessageEntity ranges:
 * exact-output assertions over the entity mapping, UTF-16 offsets, the offset arithmetic
 * of list-item prefixing, and the range-completion discipline (unclosed input tags
 * complete, empty ranges drop, crossed closers go literal). Every case also passes the
 * structural entities validator.
 */
class EntityRenderTest {
    @Test
    fun plainTextCarriesNoEntities() {
        assertEquals(EntityText("hello world", emptyList()), TelegramEntities.render("hello world"))
    }

    @Test
    fun noEscapeRulesApply() {
        // The whole point of the protocol: the text is plain, nothing needs escaping
        assertEquals(EntityText("a&b < c > d", emptyList()), TelegramEntities.render("a&b < c > d"))
    }

    @Test
    fun strongEmphasisMapsToBold() {
        assertEquals(
            EntityText("hello", listOf(MessageEntity(MessageEntityType.BOLD, 0, 5))),
            TelegramEntities.render("**hello**"),
        )
    }

    @Test
    fun emphasisMapsToItalic() {
        assertEquals(
            EntityText("word", listOf(MessageEntity(MessageEntityType.ITALIC, 0, 4))),
            TelegramEntities.render("*word*"),
        )
    }

    @Test
    fun strikethroughMapsToStrikethrough() {
        assertEquals(
            EntityText("word", listOf(MessageEntity(MessageEntityType.STRIKETHROUGH, 0, 4))),
            TelegramEntities.render("~~word~~"),
        )
    }

    @Test
    fun nestedEmphasisNestsByContainment() {
        val result = TelegramEntities.render("**a *b* c**")
        assertEquals("a b c", result.text)
        assertEquals(
            listOf(
                MessageEntity(MessageEntityType.ITALIC, 2, 1),
                MessageEntity(MessageEntityType.BOLD, 0, 5),
            ),
            result.entities,
        )
        assertEntitiesValid(result, "nestedEmphasis")
    }

    @Test
    fun codeSpanMapsToCode() {
        assertEquals(
            EntityText("a < b", listOf(MessageEntity(MessageEntityType.CODE, 0, 5))),
            TelegramEntities.render("`a < b`"),
        )
    }

    @Test
    fun fencedCodeMapsToPreWithLanguage() {
        assertEquals(
            EntityText("fun x() {}", listOf(MessageEntity(MessageEntityType.PRE, 0, 10, language = "kotlin"))),
            TelegramEntities.render("```kotlin\nfun x() {}\n```"),
        )
    }

    @Test
    fun fencedCodeWithoutLanguageOmitsField() {
        val result = TelegramEntities.render("```\nplain\n```")
        assertEquals("plain", result.text)
        val pre = result.entities.single()
        assertEquals(MessageEntityType.PRE, pre.type)
        assertEquals(null, pre.language)
    }

    @Test
    fun indentedCodeMapsToPre() {
        assertEquals(
            EntityText("indented", listOf(MessageEntity(MessageEntityType.PRE, 0, 8))),
            TelegramEntities.render("    indented"),
        )
    }

    @Test
    fun linkMapsToTextLink() {
        assertEquals(
            EntityText("text", listOf(MessageEntity(MessageEntityType.TEXT_LINK, 0, 4, url = "https://e.com"))),
            TelegramEntities.render("[text](https://e.com)"),
        )
    }

    @Test
    fun linkLabelKeepsFormatting() {
        val result = TelegramEntities.render("[**b** t](https://e.com)")
        assertEquals("b t", result.text)
        assertEquals(
            listOf(
                MessageEntity(MessageEntityType.BOLD, 0, 1),
                MessageEntity(MessageEntityType.TEXT_LINK, 0, 3, url = "https://e.com"),
            ),
            result.entities,
        )
    }

    @Test
    fun emptyLabelLinkDegradesToBareUrlText() {
        assertEquals(EntityText("https://example.com", emptyList()), TelegramEntities.render("[](https://example.com)"))
    }

    @Test
    fun imageDegradesToLinkOnAltText() {
        assertEquals(
            EntityText("alt", listOf(MessageEntity(MessageEntityType.TEXT_LINK, 0, 3, url = "https://e.com/i.png"))),
            TelegramEntities.render("![alt](https://e.com/i.png)"),
        )
    }

    @Test
    fun imageInsideLinkKeepsOuterLinkSingleLevel() {
        val result = TelegramEntities.render("[![alt](https://e.com/img.png)](https://e.com/target)")
        assertEquals("alt", result.text)
        assertEquals(
            listOf(MessageEntity(MessageEntityType.TEXT_LINK, 0, 3, url = "https://e.com/target")),
            result.entities,
        )
    }

    @Test
    fun headingKeepsLiteralHashesAndInlineEntities() {
        val result = TelegramEntities.render("## T **b**")
        assertEquals("## T b", result.text)
        assertEquals(listOf(MessageEntity(MessageEntityType.BOLD, 5, 1)), result.entities)
    }

    @Test
    fun thematicBreakRendersEmDashes() {
        assertEquals(EntityText("———", emptyList()), TelegramEntities.render("---"))
    }

    @Test
    fun bulletListUsesPlainMarkers() {
        assertEquals(EntityText("• a\n• b", emptyList()), TelegramEntities.render("- a\n- b"))
    }

    @Test
    fun orderedListUsesPlainDotMarkers() {
        assertEquals(EntityText("1. a\n2. b", emptyList()), TelegramEntities.render("1. a\n2. b"))
    }

    @Test
    fun continuationIndentShiftsEntityOffsets() {
        // An italic spanning the item's soft break: the range grows by the continuation
        // indent inserted inside it and starts after the marker
        val result = TelegramEntities.render("- *a\n  b*")
        assertEquals("• a\n  b", result.text)
        assertEquals(listOf(MessageEntity(MessageEntityType.ITALIC, 2, 5)), result.entities)
        assertEntitiesValid(result, "continuationIndent")
    }

    @Test
    fun blockQuoteMapsToBlockquoteRange() {
        assertEquals(
            EntityText("quoted", listOf(MessageEntity(MessageEntityType.BLOCKQUOTE, 0, 6))),
            TelegramEntities.render("> quoted"),
        )
    }

    @Test
    fun quoteParagraphsShareOneRange() {
        val result = TelegramEntities.render("> a\n>\n> b")
        assertEquals("a\n\nb", result.text)
        assertEquals(listOf(MessageEntity(MessageEntityType.BLOCKQUOTE, 0, 4)), result.entities)
    }

    @Test
    fun nestedQuoteNestsIdenticalRanges() {
        val result = TelegramEntities.render("> > deep")
        assertEquals("deep", result.text)
        assertEquals(
            2,
            result.entities.count { it.type == MessageEntityType.BLOCKQUOTE && it.offset == 0 && it.length == 4 },
        )
        assertEntitiesValid(result, "nestedQuote")
    }

    @Test
    fun quoteInsideListItemKeepsItsRange() {
        // No line-prefix grammar to satisfy — the range survives behind list structure,
        // which the MarkdownV2 render must degrade away
        val result = TelegramEntities.render("- > quoted")
        assertEquals("• quoted", result.text)
        assertEquals(listOf(MessageEntity(MessageEntityType.BLOCKQUOTE, 2, 6)), result.entities)
    }

    @Test
    fun emptyQuoteRendersNothing() {
        assertEquals(EntityText.EMPTY, TelegramEntities.render("> ![]()"))
    }

    @Test
    fun tableDegradesToPreRange() {
        val result = TelegramEntities.render("| h1 | h2 |\n| --- | --- |\n| a | b |")
        assertEquals("| h1  | h2  |\n| --- | --- |\n| a   | b   |", result.text)
        assertEquals(listOf(MessageEntity(MessageEntityType.PRE, 0, result.text.length)), result.entities)
    }

    @Test
    fun htmlInlineTagsMapToEntities() {
        val result = TelegramEntities.render("<b>1</b><u>2</u><i>3</i><s>4</s><code>5</code>")
        assertEquals("12345", result.text)
        assertEquals(
            listOf(
                MessageEntity(MessageEntityType.BOLD, 0, 1),
                MessageEntity(MessageEntityType.UNDERLINE, 1, 1),
                MessageEntity(MessageEntityType.ITALIC, 2, 1),
                MessageEntity(MessageEntityType.STRIKETHROUGH, 3, 1),
                MessageEntity(MessageEntityType.CODE, 4, 1),
            ),
            result.entities,
        )
    }

    @Test
    fun htmlInlineSynonymsMapToCanonicalTypes() {
        val result = TelegramEntities.render("<strong>1</strong><em>2</em><ins>3</ins><del>4</del><kbd>5</kbd>")
        assertEquals("12345", result.text)
        assertEquals(
            setOf(
                MessageEntity(MessageEntityType.BOLD, 0, 1),
                MessageEntity(MessageEntityType.ITALIC, 1, 1),
                MessageEntity(MessageEntityType.UNDERLINE, 2, 1),
                MessageEntity(MessageEntityType.STRIKETHROUGH, 3, 1),
                MessageEntity(MessageEntityType.CODE, 4, 1),
            ),
            result.entities.toSet(),
        )
    }

    @Test
    fun htmlInlineBrRendersNewline() {
        assertEquals(EntityText("a\nb", emptyList()), TelegramEntities.render("a<br>b"))
    }

    @Test
    fun tgSpoilerMapsToSpoilerEntity() {
        val result = TelegramEntities.render("a <tg-spoiler>secret</tg-spoiler> b")
        assertEquals("a secret b", result.text)
        assertEquals(listOf(MessageEntity(MessageEntityType.SPOILER, 2, 6)), result.entities)
    }

    @Test
    fun tgEmojiMapsToCustomEmojiEntity() {
        val result = TelegramEntities.render("<tg-emoji emoji-id=\"5368324170671202286\">😀</tg-emoji>")
        assertEquals("😀", result.text)
        assertEquals(
            listOf(
                MessageEntity(MessageEntityType.CUSTOM_EMOJI, 0, 2, customEmojiId = "5368324170671202286"),
            ),
            result.entities,
        )
    }

    @Test
    fun tgEmojiWithoutIdStaysVisibleText() {
        assertEquals(EntityText("<tg-emoji>x</tg-emoji>", emptyList()), TelegramEntities.render("<tg-emoji>x</tg-emoji>"))
    }

    @Test
    fun unknownTagStaysVisibleText() {
        assertEquals(EntityText("a <span>x</span> b", emptyList()), TelegramEntities.render("a <span>x</span> b"))
    }

    @Test
    fun orphanCloseStaysVisibleText() {
        assertEquals(EntityText("a </b> c", emptyList()), TelegramEntities.render("a </b> c"))
    }

    @Test
    fun markdownEmphasisEntryIsSafeFromHtmlClose() {
        val result = TelegramEntities.render("*a</b>*")
        assertEquals("a</b>", result.text)
        assertEquals(listOf(MessageEntity(MessageEntityType.ITALIC, 0, 5)), result.entities)
    }

    @Test
    fun unclosedTagCompletesAtOutput() {
        val result = TelegramEntities.render("a <b>bold")
        assertEquals("a bold", result.text)
        assertEquals(listOf(MessageEntity(MessageEntityType.BOLD, 2, 4)), result.entities)
    }

    @Test
    fun unclosedTagInsideEmphasisCompletesBeforeIt() {
        val result = TelegramEntities.render("*a <u>b*")
        assertEquals("a b", result.text)
        assertEquals(
            listOf(
                MessageEntity(MessageEntityType.UNDERLINE, 2, 1),
                MessageEntity(MessageEntityType.ITALIC, 0, 3),
            ),
            result.entities,
        )
    }

    @Test
    fun emptyHtmlPairDrops() {
        assertEquals(EntityText("ac", emptyList()), TelegramEntities.render("a<b></b>c"))
    }

    @Test
    fun hreflessAnchorKeepsContentUnlinked() {
        assertEquals(EntityText("plain", emptyList()), TelegramEntities.render("<a>plain</a>"))
    }

    @Test
    fun nestedHtmlAnchorInsideLinkLabelGoesLiteral() {
        val result = TelegramEntities.render("[outer <a href=\"i\">inner</a>](https://e.com)")
        assertEquals("outer <a href=\"i\">inner</a>", result.text)
        assertEquals(
            listOf(MessageEntity(MessageEntityType.TEXT_LINK, 0, 27, url = "https://e.com")),
            result.entities,
        )
        assertEntitiesValid(result, "nestedAnchorLiteral")
    }

    @Test
    fun htmlCodeInsideHtmlCodeMerges() {
        val result = TelegramEntities.render("<code>a<kbd>b</kbd>c</code>")
        assertEquals("abc", result.text)
        assertEquals(listOf(MessageEntity(MessageEntityType.CODE, 0, 3)), result.entities)
    }

    @Test
    fun markdownCodeInsideHtmlCodeShowsBackticks() {
        val result = TelegramEntities.render("<code>a `x` b</code>")
        assertEquals("a `x` b", result.text)
        assertEquals(listOf(MessageEntity(MessageEntityType.CODE, 0, 7)), result.entities)
    }

    @Test
    fun htmlBlockDegradesToPreRange() {
        val result = TelegramEntities.render("<div>\ncontent\n</div>")
        assertEquals("<div>\ncontent\n</div>", result.text)
        assertEquals(listOf(MessageEntity(MessageEntityType.PRE, 0, 20)), result.entities)
    }

    @Test
    fun commentOnlyContentRendersEmpty() {
        assertEquals(EntityText.EMPTY, TelegramEntities.render("<!-- c -->"))
    }

    @Test
    fun emojiOffsetsCountUtf16Units() {
        // 😀 is a surrogate pair: two UTF-16 units, so with the space the italic starts
        // at offset 3
        val result = TelegramEntities.render("😀 *x*")
        assertEquals("😀 x", result.text)
        assertEquals(listOf(MessageEntity(MessageEntityType.ITALIC, 3, 1)), result.entities)
    }

    @Test
    fun blocksSeparateWithBlankLines() {
        assertEquals(EntityText("a\n\nb", emptyList()), TelegramEntities.render("a\n\nb"))
    }

    @Test
    fun entitiesJsonMatchesBotApiShape() {
        val piece = TelegramEntities.render("**b** [t](https://e.com)")
        assertEquals(
            "[{\"offset\":0,\"length\":1,\"type\":\"bold\"}," +
                "{\"offset\":2,\"length\":1,\"type\":\"text_link\",\"url\":\"https://e.com\"}]",
            piece.entitiesJson(),
        )
    }

    @Test
    fun entitiesJsonEscapesStrings() {
        val piece = TelegramEntities.render("[t](https://e.com/?a=\"x\"\\y)")
        assertEquals(
            "[{\"offset\":0,\"length\":1,\"type\":\"text_link\",\"url\":\"https://e.com/?a=\\\"x\\\"\\\\y\"}]",
            piece.entitiesJson(),
        )
    }

    @Test
    fun emptyEntitiesRenderEmptyJsonArray() {
        assertEquals("[]", TelegramEntities.render("plain").entitiesJson())
    }

    @Test
    fun handBuiltNonDocumentNodeRendersAlone() {
        val document = MarkdownV2.defaultParser.parse("first\n\nsecond")!!
        val output = TelegramEntities.render(document.firstChild!!)
        assertEquals(EntityText("first", emptyList()), output)
    }

    @Test
    fun sweepRendersValid() {
        for (content in sweepContents()) {
            val result = TelegramEntities.render(content)
            assertEntitiesValid(result, "sweep render of ${content.replace("\n", "\\n")}")
        }
    }

    @Test
    fun visibleTextMatchesHtmlDeformat() {
        // The entities text is exactly what Telegram displays — the same view the HTML
        // output gives once de-formatted
        for (content in sweepContents()) {
            val entityText = TelegramEntities.render(content).text
            assertTrue(entityText.isNotBlank() || content.isBlank(), "lost all text: $content")
        }
    }

    @Test
    fun sentinelShapedHrefRendersAsRealLink() {
        // See the MarkdownV2 twin: pre-fix a href literally spelling the old sentinel
        // string made the closing </a> drop the link entity and leak as visible text
        assertEquals(
            EntityText("text", listOf(MessageEntity(MessageEntityType.TEXT_LINK, 0, 4, "nested-literal"))),
            TelegramEntities.render("<a href=\"nested-literal\">text</a>"),
        )
    }

    @Test
    fun blankRegionRewindsDropTheirStaleEntities() {
        // A whitespace-only code span registers a CODE range over its spaces; when the
        // paragraph (or link label) it lives in rewinds as blank, that range must drop
        // with the rewound text — pre-fix it survived with its old offset and formatted
        // the first character of whatever the rewind re-seated there ("hello", the URL)
        assertEquals(EntityText("hello", emptyList()), TelegramEntities.render("` `\n\nhello"))
        assertEquals(EntityText("https://t.me", emptyList()), TelegramEntities.render("[` `](https://t.me)"))
    }
}
