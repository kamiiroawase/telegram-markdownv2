# Changelog

One line per change; versions follow `v*` git tags.

## [Unreleased]

### Added

- `RenderOptions` is a data class (`equals`/`hashCode`/`copy`)

### Fixed

- Nested block quotes glue their markers (`>> x`): Telegram rejects the spaced `> > x` shape with "Character '>' is reserved"
- Chunking an empty heading flattens the `\#` lead within the limit — no over-limit piece, no empty list
- Blank-bodied list items skip in the full render too (ordered numbering still consumes them)
- Ordered-list continuation lines indent to the rendered marker width
- De-formatting strips quote markers from entities spanning lines, and from quotes behind list structure

## [1.3.0] — 2026-10-03

### Changed

- Parser dependency moved to the maintainer's fork `io.github.kamiiroawase:commonmark` (same 0.26.0 code line)

### Fixed

- Code-family tags and code spans never emit glued backticks: nested tags merge into the outer entity, delimiter glue degrades to escaped text, empty pairs vanish, open entities complete before fence-wrapped blocks
- Chunking drops a heading's `\#` lead when the first node fits without it
- Output-time completion of an unclosed code entity keeps its trailing spaces/tabs
- A lone flattened chunk piece stays open for packing
- Thematic breaks no longer drop out of chunking when over the limit

## [1.2.0] — 2026-10-03

### Changed

- **Breaking**: group and Kotlin package `com.github.kamiiroawase` → `io.github.kamiiroawase`; distribution JitPack → Maven Central (≤ 1.1.0 stay resolvable on JitPack)
- Inline-HTML tags parsed by a hand-written linear scanner — no regex anywhere; fixes a 100k-tag stack overflow, adversarial input ~6x faster
- `RenderOptions` rejects a `displayWidthOf` that makes the truncation ellipsis wider than `maxCellWidth`
- Build version derives from a tag only when HEAD sits exactly on it

### Fixed

- Unclosed inline HTML entities complete properly nested (one open-entity stack, closers match innermost-only; also fixes a crash on `_x</i>_`)
- `toPlainText` resolves glued marker runs (`_a__b_` → `ab`)
- Plain-text fallbacks and table cells keep link targets (`label (url)`)
- A quote inside a list item degrades behind its `> ` prefix instead of dropping the item
- Truncation off-by-one on fence-wrapped blocks at exact boundary lengths
- Empty-bodied list items no longer abort the truncation walk
- Plain-text extraction drops `<!`/`<?` markup and comment/declaration HTML blocks
- Chunk flattening preserves edge whitespace and empty-label link URLs
- Whitespace-only link labels degrade to the bare escaped URL
- Tags with `>` or `/` inside a quoted attribute value parse per HTML5
- `renderChunked` no longer crashes on blocks that render to nothing

### Added

- `toPlainText(rendered)` rejection fallback
- JS/Wasm HTML-input platform warning
- API compatibility guard (`api/` snapshots), Dokka API reference, release-workflow checks, toPlainText benchmark

## [1.1.0] — 2026-10-02

### Changed

- Dependency and GitHub Actions updates (Gradle 9.8.0, AGP 9.4.1, Spotless 8.10.3, actions/setup-java 6, actions/checkout 7, actions/upload-artifact 7, gradle/actions 6, action-gh-release 3)

## [1.0.0] — 2026-10-02

Initial release: Kotlin Multiplatform CommonMark (GFM) → Telegram MarkdownV2 converter with structure-preserving truncation, lossless chunking, table degradation, inline-HTML mapping and full 19-character escaping, targeting Android, JVM, JS, Wasm, native desktop and iOS.
