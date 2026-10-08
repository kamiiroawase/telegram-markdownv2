# telegram-markdownv2

[![Build](https://github.com/kamiiroawase/telegram-markdownv2/actions/workflows/build.yml/badge.svg)](https://github.com/kamiiroawase/telegram-markdownv2/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kamiiroawase/telegram-markdownv2.svg)](https://central.sonatype.com/artifact/io.github.kamiiroawase/telegram-markdownv2)
[![License: Unlicense](https://img.shields.io/badge/license-Unlicense-blue.svg)](LICENSE)

Kotlin Multiplatform library that converts **CommonMark (incl. GFM tables & strikethrough) into Telegram MarkdownV2**, with structure-preserving truncation or lossless chunking for over-length content (Telegram caps messages at 4096 characters; chunks sent in order deliver all the content — no character is dropped, entities straddling a seam close per piece). Built for LLM bot output — model replies are arbitrary Markdown of unpredictable structure, frequently containing constructs Telegram cannot render.

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
    implementation("io.github.kamiiroawase:telegram-markdownv2:2.0.0")
}
```

```kotlin
import io.github.kamiiroawase.markdownv2.MarkdownV2

val text = MarkdownV2.render("**hello** world")                        // send with parse_mode = MarkdownV2
val clipped = MarkdownV2.render(reply, MarkdownV2.MAX_MESSAGE_LENGTH)  // over-limit truncation, still valid MarkdownV2
val parts = MarkdownV2.renderChunked(reply)                            // lossless chunks, each <= 4096 and independently valid
val literal = MarkdownV2.escape(userInput)                             // escape dynamic text when hand-assembling MarkdownV2
val fallback = MarkdownV2.toPlainText(rejectedPart)                    // de-format a rejected piece, resend without parse_mode
```

- All 19 escapable characters (Telegram's 18 official special characters plus the backslash) escaped; inline code / code blocks / link URLs follow their own escaping rules
- Unsupported constructs degrade gracefully: tables become aligned code blocks (cell cap and display-width measure configurable via `RenderOptions`), HTML maps to entities or literal text
- Truncation and chunking preserve structure: code fences close, unclosed entities complete, quote and list prefixes survive line by line, escape sequences and surrogate pairs never split; when nothing structural fits, the fallback is escaped plain text
- Pass a custom `Parser` built on top of `MarkdownV2.defaultExtensions` for more Markdown extensions (footnotes, autolink, …); unknown extension nodes traverse as plain content without crashing

> ⚠️ **Kotlin/JS and Kotlin/Wasm**: the upstream commonmark-kotlin parser cannot handle HTML on these platforms — inputs containing an HTML block, an `<a href>` anchor, or opening with an HTML tag make `render`/`renderChunked` throw. Wrap calls in try/catch with a plain-text fallback, or pre-strip HTML; mid-paragraph inline tags such as `<b>` or `<br>` are unaffected.

## Known limitations

- Parser differences vs commonmark-java: weaker HTML block detection (`<!-- -->`, `<!DOCTYPE>` treated as inline paragraph content); a closing HTML tag inside emphasis (the `</b>` in `*a<b>x</b>*`) is not recognized as inline HTML
- Quote/list/inline-emphasis nesting beyond 100 levels flattens to plain text (stack safety on pathological input)
- Pathologically deep nesting spends its time in the upstream parser: a multi-thousand-level nested list (~4MB input) takes seconds end to end, ~90% of it inside commonmark-kotlin parsing; services accepting untrusted input should cap input size themselves
- Over-length content renders fully first, then shrinks (two passes); the shrink retry loop is worst-case O(n²) — negligible at the default 4096 limit
- MarkdownV2 is not plain Markdown: `__` is underline, not bold; don't post-process rendered output as ordinary Markdown

## Development

```bash
git clone https://github.com/kamiiroawase/telegram-markdownv2.git
cd telegram-markdownv2
./gradlew build        # JDK 21+: all targets + host-runnable tests + format checks
./gradlew jvmTest      # one platform
./gradlew benchmark    # perf benchmark: typical messages ~0.1 ms, 1M characters ~10 ms; CI re-runs attach the output to every GitHub Release
```

305 behavior-level tests (input/output assertions); 42 of them depend on HTML parsing and silently skip on JS/Wasm due to the upstream defect. Quality gates hang off `build`: Spotless formatting, `explicitApi()`, binary-compatibility-validator public-API snapshots (run `./gradlew apiDump` for intentional changes). User-visible behavior changes go into the Unreleased section of [CHANGELOG.md](CHANGELOG.md).

## License

[The Unlicense](LICENSE) — public domain.
