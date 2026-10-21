# telegram-markdownv2

[![Build](https://github.com/kamiiroawase/telegram-markdownv2/actions/workflows/build.yml/badge.svg)](https://github.com/kamiiroawase/telegram-markdownv2/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kamiiroawase/telegram-markdownv2.svg)](https://central.sonatype.com/artifact/io.github.kamiiroawase/telegram-markdownv2)
[![License: Unlicense](https://img.shields.io/badge/license-Unlicense-blue.svg)](LICENSE)

Kotlin Multiplatform library that converts **CommonMark (incl. GFM tables & strikethrough) into Telegram MarkdownV2, HTML or the entities protocol**, with structure-preserving truncation or lossless chunking for over-length content (Telegram caps messages at 4096 characters; chunks sent in order deliver all the content — no character is dropped, formatting straddling a seam closes per piece, every piece independently sendable). Built for LLM bot output — model replies are arbitrary Markdown of unpredictable structure, frequently containing constructs Telegram cannot render.

[中文版](README.md)

## Usage

Published on [Maven Central](https://central.sonatype.com/artifact/io.github.kamiiroawase/telegram-markdownv2); versions follow `v*` git tags. Targets Android (minSdk 23), JVM 11+, JS, Wasm, Linux (x64/Arm64), macOS (Arm64), Windows (mingwX64), iOS (device and simulator).

```kotlin
repositories { mavenCentral() }

dependencies {
    // KMP consumers reference the root coordinate from commonMain (Gradle module
    // metadata resolves the platform variant); pin a variant by suffix: -android /
    // -jvm / -js / -wasm-js / -linuxx64 / -linuxarm64 / -macosarm64 / -mingwx64 /
    // -iosarm64 / -iosx64 / -iossimulatorarm64
    implementation("io.github.kamiiroawase:telegram-markdownv2:3.1.0")
}
```

```kotlin
import io.github.kamiiroawase.markdownv2.ChunkDecoration
import io.github.kamiiroawase.markdownv2.MarkdownV2
import io.github.kamiiroawase.markdownv2.TelegramEntities
import io.github.kamiiroawase.markdownv2.TelegramHtml

// parse_mode = MarkdownV2
val text = MarkdownV2.render("**hello** world")                        // send as-is
val clipped = MarkdownV2.render(reply, MarkdownV2.MAX_MESSAGE_LENGTH)  // over-limit truncation, still valid MarkdownV2
val parts = MarkdownV2.renderChunked(reply)                            // lossless chunks, each <= 4096 and independently valid
val literal = MarkdownV2.escape(userInput)                             // escape dynamic text when hand-assembling MarkdownV2
val fallback = MarkdownV2.toPlainText(rejectedPart)                    // de-format a rejected piece, resend without parse_mode

// parse_mode = HTML — all three entries share the parser, RenderOptions and the
// truncation/chunking semantics
val html = TelegramHtml.render("**hello** <tg-spoiler>world</tg-spoiler>")   // tg-spoiler and tg-emoji pass through
val htmlParts = TelegramHtml.renderChunked(reply)                            // same lossless chunking, each piece valid HTML
val htmlLiteral = TelegramHtml.escape(userInput)                             // text escaping for & < >; the renderer also escapes " in attributes

// no parse_mode — the entities protocol: plain text plus formatting ranges; the
// whole class of parse failures cannot arise
val message = TelegramEntities.render(reply)          // EntityText(text = plain display text, entities = ranges)
sendMessage(text = message.text, entities = message.entities)   // for a raw REST body, entitiesJson() is the ready-made field value
val messageParts = TelegramEntities.renderChunked(reply)   // same lossless chunking, offsets re-based per piece

// Chunk decoration: a header/footer on every piece (page counters, signatures) —
// all three entry points carry the overload
val pages = MarkdownV2.renderChunked(
    reply,
    decoration = ChunkDecoration(header = { i, n -> "Part ${i + 1}/$n\n\n" }),
)
```

- All 19 escapable characters (Telegram's 18 official special characters plus the backslash) escaped; inline code / code blocks / link URLs follow their own escaping rules
- `<tg-spoiler>` maps to the native `||…||` spoiler markers, `<tg-emoji emoji-id="…">` and the Bot API's own custom emoji spelling `![fallback](tg://emoji?id=…)` (the image syntax) both map to `![fallback](tg://emoji?id=…)` — all three output formats support custom emoji and spoilers
- Unsupported constructs degrade gracefully: tables become aligned code blocks (cell cap and display-width measure configurable via `RenderOptions`; a literal `|` in cell text stands in as the fullwidth `｜` — the pipe layout admits no escaping, and a bare pipe would read as a column edge), HTML maps to entities or literal text, a blockquote inside a list item degrades to its content (the Bot API accepts an unescaped `>` only at a line start — one behind a list marker or continuation indent gets the whole message rejected with 400), and underscore-family emphasis that would glue onto a neighboring `_`/`__` marker degrades (the parser re-pairs touching markers into entities it cannot close)
- Truncation and chunking preserve structure: code fences close, unclosed entities complete, quote and list prefixes survive line by line, escape sequences and surrogate pairs never split; when nothing structural fits, the fallback is escaped plain text
- The input side enables task lists (`- [ ]`/`- [x]`, markers spelled back out as text), autolink (bare URLs/emails become links) and footnotes (references and definition labels stay visible) by default; pass a custom `Parser` built on top of `MarkdownV2.defaultExtensions` for more extensions (heading-anchor, ins, …) — unknown extension nodes traverse as plain content without crashing

### How the HTML side differs

- Escaping is uniform everywhere: `&`, `<`, `>` in text, `"` additionally in attribute values — none of MarkdownV2's context-narrowed rules
- A quote is a `<blockquote>` wrapper tag rather than a line-start `>` prefix, so **a quote inside a list item keeps its formatting** (the MarkdownV2 side must degrade it); an all-blank quote renders nothing (the MarkdownV2 side keeps a bare `>` marker)
- `<tg-spoiler>` and `<tg-emoji emoji-id="…">` from inline input HTML pass through as-is, and the custom emoji image spelling `![fallback](tg://emoji?id=…)` maps to `<tg-emoji>`; a fenced code block with a language maps to `<pre><code class="language-x">` (which Telegram syntax-highlights — the language is limited to ASCII; anything outside the attribute's grammar degrades to a language-less `<pre>`)
- Chunking needs room for one escape unit — 5 characters, an `&amp;` (`TelegramHtml.MIN_CHUNK_LENGTH`); below that the full render returns as a single chunk

### How the entities side differs

- The text is exactly what Telegram displays, with no escaping rules at all — the "400 can't parse entities" class of failures cannot happen, and the plain-text fallback becomes unnecessary rather than automatic
- Formatting rides on ranges instead of markup: a quote is a blockquote range (kept inside list items too), `<tg-spoiler>`/`<tg-emoji>` map to native spoiler/custom_emoji entities, and a fenced code block with a language maps to a pre range carrying it
- Offsets are UTF-16 code units the way the Bot API counts them — which Kotlin strings already are, so an EntityText drops into sendMessage(text, entities) as-is; entitiesJson() emits the request-body JSON array (hand-rolled, no dependency added)
- Truncation clamps ranges to the surviving text (a straddling range shortens, a fully-beyond one drops); chunking re-establishes structural formatting per piece (a split quote carries a blockquote range on every piece) while an inline range straddling a seam stays in the piece holding its start — the same seam discipline the string formats apply
- Chunking needs room for one surrogate pair — 2 characters (`TelegramEntities.MIN_CHUNK_LENGTH`)

## Integration (ktgram)

Wiring the three formats into [ktgram](https://github.com/vendelieu/telegram-bot)
(`eu.vendeli:telegram-bot`) is a matter of `options { parseMode = ... }` or
`entities(...)` on its actions (versions and DSL details per ktgram's docs):

```kotlin
import eu.vendeli.tgbot.api.message.sendMessage
import eu.vendeli.tgbot.types.component.ParseMode
import eu.vendeli.tgbot.types.msg.EntityType
import eu.vendeli.tgbot.types.msg.MessageEntity
import io.github.kamiiroawase.markdownv2.MessageEntity as TmMessageEntity
import io.github.kamiiroawase.markdownv2.MessageEntityType
import io.github.kamiiroawase.markdownv2.TelegramEntities
import io.github.kamiiroawase.markdownv2.TelegramHtml

// HTML: parse_mode = HTML
sendMessage(TelegramHtml.render(reply))
    .options { parseMode = ParseMode.HTML }
    .send(user, bot)

// entities: no parse_mode, ranges passed straight through (fields match the Bot API)
fun TmMessageEntity.toKtgram() = MessageEntity(
    type = when (type) {
        MessageEntityType.BOLD -> EntityType.Bold
        MessageEntityType.ITALIC -> EntityType.Italic
        MessageEntityType.UNDERLINE -> EntityType.Underline
        MessageEntityType.STRIKETHROUGH -> EntityType.Strikethrough
        MessageEntityType.SPOILER -> EntityType.Spoiler
        MessageEntityType.CODE -> EntityType.Code
        MessageEntityType.PRE -> EntityType.Pre
        MessageEntityType.TEXT_LINK -> EntityType.TextLink
        MessageEntityType.BLOCKQUOTE -> EntityType.Blockquote
        MessageEntityType.EXPANDABLE_BLOCKQUOTE -> EntityType.ExpandableBlockquote
        MessageEntityType.CUSTOM_EMOJI -> EntityType.CustomEmoji
    },
    offset = offset,
    length = length,
    url = url,
    language = language,
    customEmojiId = customEmojiId,
)

val formatted = TelegramEntities.render(reply)
sendMessage(formatted.text)
    .entities(formatted.entities.map { it.toKtgram() })
    .send(user, bot)
```

**The pagination-edit pattern**: combine chunking + decoration with `editMessageText`
to page through one message in place instead of sending a burst — send page one, keep
the returned `Message`, edit it on navigation:

```kotlin
val pages = TelegramEntities.renderChunked(
    reply,
    decoration = ChunkDecoration(footer = { i, n -> if (n > 1) "\n\nPart ${i + 1}/$n" else "" }),
)

var page = 0
val first = sendMessage(pages[0].text)
    .entities(pages[0].entities.map { it.toKtgram() })
    .sendReturning(user, bot)
    .getOrNull() ?: return

// in the pager button callback:
editMessageText(first.messageId, pages[++page].text)
    .entities(pages[page].entities.map { it.toKtgram() })
    .send(user, bot)
```

Any other framework works the same way: every Bot API client that can set `parse_mode`
or pass `entities` (ktor, a bare REST client, ...) consumes the three entry points'
output directly — `entitiesJson()` is the ready-made value for the request body's
`entities` field.

## Known limitations

- Parser differences vs commonmark-java: weaker HTML block detection (`<!-- -->`, `<!DOCTYPE>` treated as inline paragraph content); a closing HTML tag inside emphasis (the `</b>` in `*a<b>x</b>*`) is not recognized as inline HTML
- Quote/list/inline-emphasis nesting beyond 100 levels flattens to plain text (stack safety on pathological input); flattened consecutive blocks join with no separator, so deeply nested content can render as run-together words
- Pathologically deep nesting spends its time in the upstream parser: a multi-thousand-level nested list (~4MB input) takes seconds end to end, ~90% of it inside commonmark-kotlin parsing; services accepting untrusted input should cap input size themselves
- Over-length content renders fully first, then shrinks (two passes); the shrink retry loop is worst-case O(n²) — negligible at the default 4096 limit
- MarkdownV2 is not plain Markdown: `__` is underline, not bold; don't post-process rendered output as ordinary Markdown
- The upstream task-list and autolink extensions post-process with recursive tree walks that stack-overflow on deeply nested input (measured on mingwX64 at a 1500-level quote chain) — the default set installs this library's behavior-matched iterative stand-ins (internal implementations emitting the same node types); the official footnotes extension has no such recursion and is used as-is
- The HTML side still degrades block-level input HTML (`<div>` and friends) to escaped text inside a `<pre>` instead of passing it through; the one exception is the block-level `<blockquote>` / `<blockquote expandable>` (the open tag on its own line, the closing tag the block's last line, the content parsed as inline Markdown) — it maps to a real block quote, the three formats emitting `>` / `**>` line markers (the `**` on the first line only, per the official grammar), `<blockquote>` / `<blockquote expandable>` wrappers and `blockquote` / `expandable_blockquote` entities; nested quotes flatten by each format's existing rules, and the entities side keeps its known quote-in-quote overlap (below); the `render(document)` overloads rewrite nothing on a caller-supplied AST, so the spelling applies to the string entry points the library parses itself
- The entities side emits nested blockquote ranges for a quote inside a quote (`> > x`) — the Bot API formatting rules say blockquote entities can't nest, but the server repairs this silently at send time (the inner range drops) and the message delivers; consumers that strictly validate the raw entity array need to deduplicate themselves
- Chunking has three whitespace-only exceptions to no-character-loss: pending blank lines drop when a code-block seam fires before real content joins them (top or middle alike), a whitespace-only code line longer than the per-chunk budget drops whole, and an all-whitespace flattened piece drops because the server rejects blank messages — everything lost is whitespace Telegram would never display, or would reject the whole message for
- Chunking has one structural-marker exception on top: at a limit too small to hold a structural marker plus one content unit (a tiny custom maxLength), the list marker ("• ", "10. ") or heading hashes drop so the body text fits — body text itself never drops, the lead-drop trade all three formats make (noted in each entry point's renderChunked KDoc); an expandable quote's first-line "**" flag belongs to the same family — it rides the first piece whenever it fits and yields at the tiny limits (all three formats yield there alike)
- A code block inside a list item: the continuation indent (aligned to the list marker's width) lands inside the pre entity, so all three formats display the code lines with indentation noise and differ from each other (the MarkdownV2 side prefixes every line with marker-width spaces; the HTML/entities sides indent continuation lines only) — the pinned trade-off when list continuity and code-block validity can't both hold

## Development

```bash
git clone https://github.com/kamiiroawase/telegram-markdownv2.git
cd telegram-markdownv2
./gradlew build        # JDK 21+: all targets + host-runnable tests + format checks
./gradlew jvmTest      # one platform
./gradlew benchmark    # perf benchmark: typical messages ~0.1 ms, 1M characters ~10 ms; CI re-runs attach the output to every GitHub Release
```

The API reference (Dokka) deploys to [GitHub Pages](https://kamiiroawase.github.io/telegram-markdownv2/) on every main push.

776 tests — 775 behavior-level (input/output assertions) plus one seeded random-document property test (150 seeds, a generator over random combinations of emphasis/code/links/quotes/lists/task lists/tables/footnotes/autolinks/raw HTML/emoji asserting every invariant for all three formats), running on every platform; every HTML-side output passes a port of tdlib's HTML parser rules (`assertTelegramHtmlParseable`), the MarkdownV2 side does likewise (`assertTelegramParseable`), and the entities side passes structural validation (ranges in bounds, nesting by containment, close-order listing, surrogate pairs never split). Quality gates hang off `build`: Spotless formatting, `explicitApi()`, binary-compatibility-validator public-API snapshots (run `./gradlew apiDump` for intentional changes).

## License

[The Unlicense](LICENSE) — public domain.
