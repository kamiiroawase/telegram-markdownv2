# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed

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
