# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.2.0] — 2026-10-03

### Changed

- **Breaking**: the Maven group and the Kotlin package moved from `com.github.kamiiroawase`
  to `io.github.kamiiroawase` — imports change from
  `com.github.kamiiroawase.markdownv2.MarkdownV2` to `io.github.kamiiroawase.markdownv2.MarkdownV2`,
  and coordinates from `com.github.kamiiroawase.telegram-markdownv2:…` to
  `io.github.kamiiroawase:…`. Versions up to 1.1.0 remain resolvable on JitPack under the
  old coordinates
- **Breaking**: distribution moved from JitPack to Maven Central (jitpack.yml removed;
  the release workflow now signs and publishes to the Central Portal via the
  vanniktech plugin and auto-releases). The JitPack gap that forced per-variant
  coordinates is gone: the root coordinate `io.github.kamiiroawase:telegram-markdownv2`
  carries Gradle module metadata and serves KMP consumers from commonMain directly

### Fixed

- Unclosed inline entities no longer complete into crossed, Telegram-rejected output:
  open HTML emphasis markers and `<a>` anchors now share one entity stack ordered by
  opening, and every completion point — an emphasis/strikethrough/link-label boundary
  or output time — closes what was opened inside it, latest-opened first. Pre-fix the
  two kinds lived on separate stacks whose closers could not interleave: an anchor
  opened inside Markdown emphasis completed past the emphasis closer
  (`*<a href="u">x*` rendered the crossed `_[x_](u)`), output-time completion always
  emitted emphasis closers before link closers (crossing `<b><a href="u">x` into
  `*[x*](u)`), and a closing tag could pop across the other stack
  (`<a href="u"><b>x</a>` → the crossed `[*x](u)*`)
- Closing HTML tags match only the innermost open entity, which also fixes a crash: an
  HTML closer that matched the marker pushed by a Markdown emphasis' own visit —
  `_x</i>_`, `**x</b>**`, `~~x</s>~~` — stole that entry off the stack and the visit's
  own pop then threw `NoSuchElementException` ("ArrayDeque is empty"). Such closers,
  and closers crossing a still-open anchor (`</b>` in `<b><a href="u">x</b>`), now
  escape literally and the crossed entity completes properly nested instead
- `toPlainText` resolves runs of glued emphasis markers: `_a__b_` — the render of two
  italics side by side (`*a*_b_`) — de-formats to `ab`, where the old char-greedy read
  took the middle `__` for one underline marker, matched nothing on the marker stack,
  and re-inserted all three markers literally. Runs now resolve close-then-open against
  the open-marker stack (`_a___b__`, `___x___` and the glued shapes inside link labels
  included)
- The truncation/chunking plain-text fallbacks keep link targets: a link or image with a
  visible label extracts as `label (url)` — the same shape `toPlainText` gives the
  rendered form — instead of silently dropping the URL (table cells included); nested
  links (hand-built ASTs only; CommonMark cannot produce them) keep the outer target
  alone, matching the renderer's degradation
- A quote inside a list item that does not fit the shrinking budget degrades to escaped
  plain text behind its `> ` prefix instead of dropping the whole item: pre-fix the
  nested quote's tail shrink returned empty, so `render("- > *abc\ndef*", 13)` abandoned
  the structural path and returned the document as plain text (`abc def`), losing the
  bullet and quote markers a fitting `• > …` prefix could have kept
- Truncation no longer overshoots the limit by one character on a fence-wrapped block
  (fenced/indented code, table, HTML): the shrink's line-fitting check forgot the
  newline joining the line, so at exact boundary lengths `render(content, maxLength)`
  returned `maxLength + 1` characters — a four-column table at limit 23 came back 24
  long — which the Bot API then rejects as too long, the very failure truncation
  exists to prevent
- Truncation no longer stops at a list item whose body renders to nothing: the empty
  item skips (consuming its number on ordered lists, matching the full render's
  numbering) and the items after it still pack with their markers — the same trade the
  chunking path always made. Pre-fix `render("-\n- zzz…", 10)` gave up on the structural
  path entirely and fell back to plain text, losing the bullet markers
- The plain-text extraction behind the truncation/chunking fallbacks and table cells now
  drops `<!`/`<?` inline markup and comment/declaration/PI HTML blocks, matching the
  renderer's own filtering: pre-fix only inline `<!--` comments were filtered, so
  declarations and whole comment blocks leaked into flattened chunks as text the
  renderer itself never emits
- Chunking's flattening fallback no longer drops the flattened text's trailing whitespace
  at the flush boundaries: the last escaped piece stayed untrimmed inside the in-progress
  chunk (deliberately — edge whitespace is content), but the next flush trimmed or
  blank-skipped it anyway. Code content is the realistic trigger (fenced blocks keep
  trailing spaces where prose cannot): `renderChunked` of an oversized list item / quote
  child / inline node whose text ends in spaces used to drop them, and a whitespace-only
  tail piece was dropped outright
- Chunking and truncation no longer drop the content of an empty-label link/image whose
  bare-URL degradation exceeds the limit: the plain-text fallback paths now extract the
  destination like the renderer does (`renderChunked("![](<URL longer than the limit>)")`
  used to return an empty list; `render` used to return a lone ellipsis)
- A whitespace-only link label (Markdown link, image alt, closed or unclosed HTML anchor)
  degrades to the bare escaped URL on every path, matching the output-time completion of
  unclosed anchors; the closed paths used to emit `[ ](url)`-shaped entities Telegram
  may reject
- Chunking no longer drops whitespace at piece boundaries: flattening an oversized block,
  list item, quote child or inline node to escaped text preserves every character, as the
  losslessness guarantee always claimed (`renderChunked("aa *b* cc *d*", 4)` now yields
  `"aa "`, not `"aa"`)
- Tags with `>` or `/` inside a quoted attribute value (`<a href="a>b">`) render as
  anchors/emphasis per CommonMark/HTML5 attribute semantics instead of degrading the
  whole tag to literal escaped text
- `renderChunked` no longer crashes on blocks that render to nothing (comment-only HTML
  blocks, row-less tables)
- Link entities with an empty label (Markdown link, image alt, HTML anchor) degrade to
  the bare escaped URL instead of producing Telegram-rejected empty-text entities
- Unquoted attribute values keep a trailing `/` out of self-closing detection; the NUL
  character reference (`&#0;`) stays literal in href entity decoding; link-label HTML
  state no longer leaks into the links that follow

### Changed

- Inline-HTML tags are parsed by a hand-written single-pass scanner; the library no
  longer uses regex anywhere. Beyond removing the quoted-value limitation above, this
  fixes a latent stack overflow on 100k-scale tags and speeds the adversarial-input
  benchmark ~6x (~4.8 ms → ~0.8 ms)
- `RenderOptions` now rejects a `displayWidthOf` that measures the truncation ellipsis
  wider than `maxCellWidth` (construction-time `IllegalArgumentException`); such
  configurations used to make truncated cells silently overshoot the cap
- The build version derives from a git tag only when HEAD sits exactly on it; every other
  build is `0.0.0-SNAPSHOT`. Previously all commits after a tag reused the released
  version number, letting a local `publishToMavenLocal` shadow published artifacts under
  the same coordinates

### Added

- `MarkdownV2.toPlainText(rendered)`: de-formats this library's rendered MarkdownV2 (a
  full render or one renderChunked piece) into the text Telegram would display — the
  last-resort fallback for a message the Bot API rejects, sent without parse_mode.
  Entity markers drop, code content stays, quote prefixes strip (a code block inside a
  quote de-forms to its content with the per-line markers stripped), escapes resolve per
  context, and `[label](url)` becomes `label (url)` so no link target is lost (an empty
  label or URL degrades to the non-empty piece); malformed input degrades best-effort
  without losing characters and the call never throws
- Platform warning: Kotlin/JS and Kotlin/Wasm throw on inputs containing HTML blocks,
  `<a href>` anchors or documents opening with an HTML tag (upstream parser defect) —
  documented on the public API and in both READMEs with the try/catch plain-text fallback
- Public-API compatibility guard via binary-compatibility-validator: the `api/` snapshots
  are checked on every build; intentional API changes require `./gradlew apiDump`
- API reference generation via Dokka (`./gradlew dokkaGeneratePublicationHtml`); CI
  uploads the HTML as a workflow artifact
- Release workflow hardening: README version-coordinate checks match version tokens
  exactly (no substring hits), CHANGELOG.md must carry the tag's version section, and
  the published version is asserted to equal the tag
- A toPlainText benchmark scenario (the README performance table gains a row), and the
  dependabot coverage documented: the Kotlin/JS yarn.lock stays manual because
  dependabot's npm ecosystem requires a package.json beside the lockfile
- This changelog

## [1.1.0] — 2026-10-02

### Changed

- Dependency and GitHub Actions updates (Gradle wrapper 9.8.0, AGP 9.4.1, Spotless
  8.10.3, actions/setup-java 6, actions/checkout 7, actions/upload-artifact 7,
  gradle/actions 6, action-gh-release 3)

## [1.0.0] — 2026-10-02

Initial release: a Kotlin Multiplatform CommonMark (GFM tables, strikethrough) to
Telegram MarkdownV2 converter with structure-preserving truncation, lossless chunking,
table degradation to aligned code blocks, inline-HTML entity mapping and full
19-character escaping. Targets Android (minSdk 23), JVM 11+, JS, Wasm, Linux (x64 and
Arm64), macOS (Arm64), Windows (mingwX64) and iOS (device and simulator); published via
JitPack and GitHub Release assets.
