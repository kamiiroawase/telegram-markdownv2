# telegram-markdownv2

[![Build](https://github.com/kamiiroawase/telegram-markdownv2/actions/workflows/build.yml/badge.svg)](https://github.com/kamiiroawase/telegram-markdownv2/actions/workflows/build.yml)
[![JitPack](https://jitpack.io/v/kamiiroawase/telegram-markdownv2.svg)](https://jitpack.io/#kamiiroawase/telegram-markdownv2)
[![License: Unlicense](https://img.shields.io/badge/license-Unlicense-blue.svg)](LICENSE)

Kotlin Multiplatform library that converts **CommonMark (incl. GFM tables & strikethrough) into Telegram MarkdownV2**, with structure-preserving truncation for over-length content (Telegram message limit: 4096 characters). Built for LLM bot output — model replies are arbitrary Markdown of unpredictable structure, frequently containing constructs Telegram cannot render.

[中文版](README.md)

## Part 1: Usage Guide

### What it is

In one sentence: Markdown in, Telegram-renderable text out. Hand any CommonMark/GFM content to `MarkdownV2.render(...)` and get back message text ready for the Bot API with `parse_mode = MarkdownV2`; content beyond 4096 characters is truncated when you pass a positive `maxLength` (the default 0 disables truncation), and the truncated result is still valid MarkdownV2 — bold, code blocks and links are never cut mid-entity. The full feature list:

- Parses with [commonmark-kotlin](https://github.com/darriousliu/commonmark-kotlin) (the Kotlin Multiplatform port of commonmark-java), emits Telegram-flavoured MarkdownV2
- All 19 escapable characters (Telegram's 18 official special characters plus the backslash) escaped; inline code / code blocks / link URLs follow their own escaping rules
- Unsupported constructs degrade gracefully: tables render as aligned fenced code blocks (CJK/emoji aligned at double display width, combining marks and variation selectors cost zero columns; cells truncated at 32 display width by default, with both the width measure and the cap customizable via `RenderOptions`), HTML blocks become code blocks, inline HTML maps to entities (`<b>` → `*`, `<br>` → newline, `<a href>` → link; content of HTML code-family tags — `<code>`, `<kbd>`, … — escapes with code-entity rules, escaping only the backtick and backslash, and Markdown code spans nested inside escape their backticks too, keeping entity boundaries intact) or is escaped literally — unmatched closing, self-closing and nested tags are always escaped literally, empty-URL links degrade to plain text, empty link text, image alt text or an empty anchor degrades to the bare escaped URL (Telegram rejects empty-text link entities), and images/links/anchors nested inside a link degrade to plain text or literal tags (Telegram link entities cannot nest), HTML emphasis left unclosed inside a link label completes before the link closes, and a leftover nested anchor never swallows the links that follow; the `href` attribute name is case-insensitive, its value may be double-quoted, single-quoted or unquoted (per HTML5 a trailing `/` in an unquoted value belongs to the value and is never mistaken for a self-closing tag), HTML entities in hrefs (`&amp;`, `&#38;`, `&#x26;`, …) are decoded before linking, unrecognized references (e.g. `&#0;`, whose NUL is not valid message text) stay literal, and an `href=` inside another attribute's value is never mistaken for the real attribute
- Optional truncation (enabled by a positive `maxLength`; the default 0 renders in full) stays valid MarkdownV2: code fences remain closed, unclosed emphasis / links are auto-completed, quote and list prefixes are preserved line by line, escape sequences and surrogate pairs are never split; falls back to escaped plain text when no structural content fits; quotes / lists / inline emphasis nested deeper than 100 levels flatten to plain text, so pathological nesting never exhausts the call stack
- Lossless chunking of over-length content (`renderChunked`): the render is split into pieces of at most the limit, each independently valid MarkdownV2 — blocks are packed whole, oversized blocks split along their natural seams (code lines, list items, quote children, paragraph lines, then inline nodes; heading `#` prefixes and list markers glue to the first piece and are dropped outright when the limit cannot even fit marker plus text), and a seam still exceeding the limit flattens to escaped plain text (text lossless); send the pieces in order to deliver the full content

Supported platforms: Android (minSdk 23), JVM 11+, JS, Wasm, Linux (x64/Arm64), macOS (Arm64), Windows (mingwX64), iOS (device and simulator).

### Step 1: Add the dependency

Consume via [JitPack](https://jitpack.io/#kamiiroawase/telegram-markdownv2); versions follow `v*` git tags.

```kotlin
repositories {
    maven("https://jitpack.io")
    mavenCentral() // transitive dependencies of commonmark-kotlin
}
```

Version catalog:

```toml
[versions]
telegramMarkdownv2 = "1.1.0"

[libraries]
telegram-markdownv2-jvm = { module = "com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-jvm", version.ref = "telegramMarkdownv2" }
```

Reference the variant per platform:

```kotlin
dependencies {
    implementation(libs.telegram.markdownv2.jvm)
}
```

Per-platform variant coordinates (as observed at v1.1.0, 2026-10: JitPack lacks common metadata, so the **root coordinate is unusable** — reference variants; if JitPack fixes this, the root coordinate becomes usable):

| Platform | Coordinate |
| --- | --- |
| Android | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-android:1.1.0` |
| JVM | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-jvm:1.1.0` |
| JS | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-js:1.1.0` |
| Wasm | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-wasm-js:1.1.0` |
| Linux x64 | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-linuxx64:1.1.0` |
| Linux Arm64 | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-linuxarm64:1.1.0` |
| macOS Arm64 | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-macosarm64:1.1.0` |
| Windows | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-mingwx64:1.1.0` |
| iOS arm64 (device) | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-iosarm64:1.1.0` |
| iOS x64 (simulator) | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-iosx64:1.1.0` |
| iOS arm64 (simulator) | `com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-iossimulatorarm64:1.1.0` |

KMP consumers cannot reference this library's API from commonMain directly (same v1.1.0 observation: JitPack has no common metadata); reference per-platform variants in each source set, or self-host the GitHub Release attachments as a Maven repository:

```kotlin
kotlin {
    sourceSets {
        jvmMain.dependencies { implementation("com.github.kamiiroawase.telegram-markdownv2:telegram-markdownv2-jvm:1.1.0") }
        // swap the variant for other platforms accordingly
    }
}
```

### Step 2: Call it

```kotlin
import com.github.kamiiroawase.markdownv2.MarkdownV2

val mdv2 = MarkdownV2.render("**hello** world")
// *hello* world — ready for sendMessage with parse_mode = MarkdownV2

val full = MarkdownV2.render(longModelReply)                                   // no truncation by default (maxLength = 0)
val clipped = MarkdownV2.render(longModelReply, MarkdownV2.MAX_MESSAGE_LENGTH) // truncated to the Telegram limit
val clipped100 = MarkdownV2.render(longModelReply, 100)                        // any positive custom limit; <= 0 disables truncation

val literal = MarkdownV2.escape("a*b_c")                 // a\*b\_c — renders any text literally
```

Render once before editing and re-sending a message; if the result is still rejected by Telegram (theoretically it should not be), sending the original text as plain text is the last resort.

### Going further: over-length content as multiple messages (renderChunked)

When dropping over-limit content is not acceptable, `renderChunked` splits the render into pieces of at most the limit (4096 by default), each independently valid MarkdownV2 — send them in order:

```kotlin
val parts = MarkdownV2.renderChunked(longModelReply) // List<String>, each piece <= 4096
parts.forEach { part -> sendMessage(chatId, part) }  // delivering in order reproduces the full content
```

Blocks are packed whole; a block that does not fit splits along its natural seams (code lines, list items, quote children, paragraph lines, then inline nodes) with list numbering continuing across pieces; a seam still exceeding the limit flattens to escaped plain text — structure degrades, not a single character is lost. A `maxLength` of 1 or less returns the full render as a single-element list (a chunk needs room for at least one escaped character — two chars); content that renders to nothing yields an empty list.

### Going further: custom table degradation (RenderOptions)

Table degradation involves two measurement choices with no single right answer: the display width of a character and the truncation cap of a cell. The default measures an East Asian Width approximation (CJK/emoji wide characters count 2, combining marks/variation selectors count 0, everything else 1; astral code points count 2) and caps cells at 32 display columns. Both are overridable via `RenderOptions`:

```kotlin
// E.g. align Ambiguous characters (×, ©, …) as wide in a CJK-font context, cap cells at 40 columns
val options = RenderOptions(
    maxCellWidth = 40,
    displayWidthOf = { codePoint ->
        if (codePoint == '×'.code) 2 else defaultDisplayWidth(codePoint)
    },
)
val text = MarkdownV2.render("| a | × |\n| --- | --- |", options = options)
```

### Going further: custom parser and AST rendering

The default parser is CommonMark + GFM tables + strikethrough. When you need more extensions (e.g. footnotes, autolink, task lists), pass a custom parser; you can also render an already-parsed AST node (unknown extension nodes are traversed as plain content and never crash):

```kotlin
// build on top of the default extensions
val parser = Parser.builder()
    .extensions(MarkdownV2.defaultExtensions + FootnotesExtension.create())
    .build()
val text = MarkdownV2.render(content, parser = parser)

// or render any already-parsed node (Node overload); truncation preserves the
// structure of a single block node too (list markers, table fences survive)
val document: Node = parser.parse(content)!!
val text2 = MarkdownV2.render(document, MarkdownV2.MAX_MESSAGE_LENGTH)
```

> Note: the parsing backend of this library is [commonmark-kotlin](https://github.com/darriousliu/commonmark-kotlin), whose HTML block detection is weaker than official commonmark-java (`<!-- -->`, `<!DOCTYPE>` etc. are treated as inline paragraph content). Additionally, **closing HTML tags inside emphasis** (e.g. the `</b>` in `*a<b>x</b>*`) are not recognized as inline HTML — their `>` is merged into the adjacent text (an upstream emphasis/inline-HTML scanning interaction). Inputs relying on this behavior may render differently between the two backends.

### Known limitations and pitfalls

- **HTML is limited on JS/Wasm**: the commonmark-kotlin parser crashes when scanning HTML blocks on these platforms (`Regex("]]>")` is invalid in the JS RegExp engine); `<a href>` anchors and documents opening with an HTML tag are affected too, pending an upstream fix — inline tags mid-paragraph such as `<b>` and `<br>` are not affected
- **MarkdownV2 is not Markdown**: `__` is underline, not bold; Telegram's 18 official special characters plus the backslash (19 total) must be escaped outside entities — this library handles all of it, but do not post-process the rendered output as regular Markdown
- **Tables degrade to code blocks**: Telegram has no tables; this library emits monospaced aligned fenced code blocks, cells are truncated at 32 display width, and over-length tables are truncated whole rows at a time. The width table is an East Asian Width approximation (Ambiguous characters count as narrow, astral code points always count 2) — swap in your own measure via `RenderOptions.displayWidthOf` when alignment matters
- **HTML anchors across emphasis boundaries**: inputs like `*<a href="u">x*` (anchor opened inside Markdown emphasis, completed outside it) produce cross-nested output such as `_[x_](u)` which Telegram rejects — fall back to plain text; purely emphasis-tag interleaving (e.g. `*a<b>b* </b>`) is auto-completed at the emphasis boundary and yields valid output
- **Ultra-deep nesting flattens**: quotes / lists / inline emphasis nested deeper than 100 levels degrade to flattened plain text (guarding against stack overflow on pathological input); when truncated, over-budget deep structures fall back to escaped plain text wholesale instead of being re-shrunk level by level (preventing exponential re-rendering)
- **Over-length content is rendered twice**: once fully to measure, then block-wise when shrinking; negligible under the 4096-character limit, so no single-pass optimization is made for huge inputs
- **Version numbers come from git tags**: builds without tags are `0.0.0-SNAPSHOT`; CI needs a full clone (`fetch-depth: 0`) to derive the version

## Part 2: Contributing

### Prerequisites

```bash
git clone https://github.com/kamiiroawase/telegram-markdownv2.git
cd telegram-markdownv2
./gradlew build
```

Building from source requires JDK 21+ (Gradle toolchains auto-provisioning is enabled).

### Project layout

```
src/
├── commonMain/kotlin/com/github/kamiiroawase/markdownv2/
│   ├── MarkdownV2.kt      public API and top-level flow (render / escape / default parser)
│   ├── Visitor.kt         full AST → MarkdownV2 rendering (inline-HTML mapping, entity completion)
│   ├── Truncation.kt      structure-preserving truncation of over-length content
│   ├── Chunking.kt        lossless chunking of over-length content (renderChunked)
│   ├── Escape.kt          escaping rules and atomic-unit truncation (escape sequences / surrogate pairs)
│   ├── PlainText.kt       iterative plain-text extraction (table cells and fallback paths)
│   ├── RenderOptions.kt   rendering customization (table cell cap, display-width measure)
│   └── TableRenderer.kt   GFM tables degraded to aligned monospaced text
├── commonTest/            behavior-level tests (input/output assertions, AST-agnostic)
├── <platform>Test/        per-platform expect/actual switches (HTML cases skipped on JS/Wasm)
└── jvmTest/               also hosts the performance benchmark (./gradlew benchmark)
```

### Build and test

222 behavior-level tests (input/output assertions, AST-agnostic), covering all escaping rules, rendering, truncation and chunking paths of every block type, surrogate-pair and escape boundaries (including linearity regression tests feeding 100k-scale adversarial inputs — the library's only regex stays linear-time with no catastrophic backtracking). Caveat: 28 of them depend on HTML parsing and silently no-op on JS/Wasm due to an upstream commonmark-kotlin parser defect (see the test class KDoc and `htmlParsingSupported`); they activate automatically once the upstream fix lands. In CI: JVM and Android unit tests plus JS, Wasm (Node) and Linux x64 native tests run in the ubuntu job; iOS simulator and macOS Arm64 tests run in the macOS job; Windows and Linux Arm64 native tests run in their own host jobs — every native target has CI test coverage:

```bash
./gradlew build             # compile all targets + host-runnable tests + format check
./gradlew jvmTest           # run a single platform
```

### Quality gates (PRs must pass them all)

- **Formatting**: Spotless + ktlint run as part of `build`; run `./gradlew spotlessApply` to auto-format before committing
- **API**: commonMain uses `explicitApi()`; public declarations need explicit visibility modifiers and KDoc
- **CI**: `build.yml` runs all of the above on main pushes and every PR (plus macOS, Windows and arm64 Linux jobs for the iOS simulator, macOS Arm64, mingwX64 and Linux Arm64 native tests) and asserts the publishing artifacts; `release.yml` publishes per-platform artifacts as GitHub Release attachments on `v*` tags, attaches the CI benchmark output, and verifies both READMEs' version coordinates were bumped with the tag (a missing new version or a leftover previous version fails the release)

### Submitting a PR

1. Fork the repository and branch off main
2. Make the change with accompanying tests; ensure `./gradlew build` is green locally before pushing
3. Describe the motivation and behavioral changes in the PR; attach a minimal reproducing input and expected output for bug fixes
4. For HTML-related changes, mind the upstream commonmark-kotlin JS/Wasm parsing defect (the `htmlParsingSupported` test switch) and spell out the trade-off in the PR

## Part 3: Performance

Reproducible via `./gradlew benchmark` (implementation: [Benchmark.kt](src/jvmTest/kotlin/com/github/kamiiroawase/markdownv2/Benchmark.kt) — single-threaded JVM, global warm-up, then the whole suite repeats several rounds with the cases interleaved and each scenario keeps its best (minimum) round median; interference such as CPU frequency scaling or thermal windows can skew a single round several-fold, so the best round is far more reproducible; figures cover the full commonmark parse + render/truncate pipeline, throughput counted in UTF-8 bytes). Reference measurements on Windows 11 / JDK 21 / Intel x86-64 (2026-10, best-round methodology); absolute values vary per machine — treat them as order-of-magnitude. To keep the table from silently aging, every release from now on attaches the CI's (ubuntu runner) fresh benchmark output (`benchmark.txt`) to the GitHub Release, ready for order-of-magnitude comparison against the table:

| Scenario | Input | Median (best round) | Throughput |
| --- | --- | --- | --- |
| Short text (typical chat message) | 0.1 KB | ~7 µs | ~14 MB/s |
| Typical reply (headings/lists/code/table) | 2.0 KB | ~70 µs | ~29 MB/s |
| Plain paragraph near the limit (no truncation) | 3.6 KB | ~16 µs | ~220 MB/s |
| Overlong multi-paragraph (structure-preserving truncation to 4096) | 42.7 KB | ~0.35 ms | ~120 MB/s |
| Overlong single line (escaped plain-text fallback) | 50.0 KB | ~0.5 ms | ~100 MB/s |
| Large table (60 rows × 4 cols, degraded alignment) | 3.2 KB | ~0.13 ms | ~24 MB/s |
| Large code block (500 lines, truncated) | 21.3 KB | ~0.1 ms | ~220 MB/s |
| Inline HTML mix | 4.7 KB | ~0.13 ms | ~36 MB/s |
| Deeply nested quote (200 levels, flattened) | 0.3 KB | ~43 µs | ~7.4 MB/s |
| 100k-scale unclosed tag (regex linearity regression) | 100 KB | ~4.8 ms | ~21 MB/s |
| Huge document (1M characters, render-twice strategy) | 1.0 MB | ~9.8 ms | ~100 MB/s |

Reading notes:

- Typical messages (≤ 4096 chars) render mostly within a 0.1 ms order of magnitude — negligible on a bot's send path
- Fragmented structure costs: per byte, documents dense in headings/lists/tables parse and render several times slower than a single plain paragraph
- Adversarial input (100k-char unclosed tag) stays linear (~5 ms) with no catastrophic backtracking — the design goal of keeping the library's only regex linear-time
- Huge documents pay the render-full-then-shrink strategy (see known limitations): ~10 ms per million characters

## License

[The Unlicense](LICENSE) — public domain, use freely.
