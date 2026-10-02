package com.github.kamiiroawase.markdownv2

// commonmark-kotlin's JS/Wasm variants cannot parse HTML blocks, <a href> anchors,
// nor documents that open with an HTML tag (both hit Regex("]]>") which is invalid in
// the JS RegExp engine, see HtmlBlockParser.kt upstream); tests covering those paths
// are guarded by this flag until it is fixed.
internal expect val htmlParsingSupported: Boolean
