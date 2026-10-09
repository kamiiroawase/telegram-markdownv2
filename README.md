# telegram-markdownv2

[![Build](https://github.com/kamiiroawase/telegram-markdownv2/actions/workflows/build.yml/badge.svg)](https://github.com/kamiiroawase/telegram-markdownv2/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.kamiiroawase/telegram-markdownv2.svg)](https://central.sonatype.com/artifact/io.github.kamiiroawase/telegram-markdownv2)
[![License: Unlicense](https://img.shields.io/badge/license-Unlicense-blue.svg)](LICENSE)

Kotlin Multiplatform 库：把 **CommonMark（含 GFM 表格与删除线）转换为 Telegram MarkdownV2**，并对超长内容做保持结构完整的截断或无损分片（Telegram 消息上限 4096 字符，分片按序发送即可完整送达——文本不丢字符，接缝处实体按段闭合）。为 LLM 机器人输出而生——模型回复是任意长度、任意结构的 Markdown，还经常带 Telegram 渲染不了的构造。

[English version](README.en.md)

## 使用

发布于 [Maven Central](https://central.sonatype.com/artifact/io.github.kamiiroawase/telegram-markdownv2)，版本跟随 `v*` git tag。支持 Android（minSdk 23）、JVM 11+、JS、Wasm、Linux（x64/Arm64）、macOS（Arm64）、Windows（mingwX64）、iOS（设备与模拟器）。

```kotlin
repositories { mavenCentral() }

dependencies {
    // KMP 消费方在 commonMain 引用根坐标（Gradle module metadata 自动解析平台变体）；
    // 需要钉住具体变体时加后缀：-android / -jvm / -js / -wasm-js / -linuxx64 /
    // -linuxarm64 / -macosarm64 / -mingwx64 / -iosarm64 / -iosx64 / -iossimulatorarm64
    implementation("io.github.kamiiroawase:telegram-markdownv2:2.1.1")
}
```

```kotlin
import io.github.kamiiroawase.markdownv2.MarkdownV2

val text = MarkdownV2.render("**hello** world")                        // 直接配 parse_mode = MarkdownV2 发送
val clipped = MarkdownV2.render(reply, MarkdownV2.MAX_MESSAGE_LENGTH)  // 超限截断，仍是合法 MarkdownV2
val parts = MarkdownV2.renderChunked(reply)                            // 无损分片，每段 ≤ 4096 且各自合法，按序发送
val literal = MarkdownV2.escape(userInput)                             // 手动拼 MarkdownV2 时转义动态文本
val fallback = MarkdownV2.toPlainText(rejectedPart)                    // 被拒收时还原纯文本、不带 parse_mode 重发
```

- 官方 18 个特殊字符加反斜杠共 19 个全量转义；行内代码 / 代码块 / 链接 URL 按各自规则转义
- 不支持的构造自动降级：表格转等宽对齐的代码块（单元格截断上限与字符宽度度量可经 `RenderOptions` 定制）、HTML 映射为实体或字面文本
- 截断与分片保结构：代码围栏闭合、未闭合实体补全、引用与列表前缀逐行保留，绝不劈开转义序列和代理对；结构化内容完全放不下时回退为转义纯文本
- 需要更多 Markdown 扩展（footnotes、autolink 等）时在 `MarkdownV2.defaultExtensions` 之上传入自建 `Parser`；未知扩展节点按纯内容遍历，不崩溃

> ⚠️ **JS/Wasm 平台注意**：上游 commonmark-kotlin 在这两个平台无法解析 HTML——输入含 HTML 块、`<a href>` 锚点或以 HTML 标签开头时 `render`/`renderChunked` 会直接抛异常，务必 try/catch 兜底（捕获后按纯文本发送）或预先滤除 HTML；段落中间的 `<b>`、`<br>` 等行内标签不受影响。

## 已知限制

- 解析后端与 commonmark-java 的行为差异：HTML 块识别较弱（`<!-- -->`、`<!DOCTYPE>` 按段落内联处理）；强调内部的闭合 HTML 标签（如 `*a<b>x</b>*` 中的 `</b>`）不被识别为行内 HTML
- 超过 100 层的引用 / 列表 / 行内强调平铺为纯文本（防病态输入栈溢出）
- 病态深嵌套的主要耗时在上游解析器：数千层嵌套列表（约 4MB 输入）端到端秒级，九成在 commonmark-kotlin 解析；接受不可信输入的服务应自行限制输入规模
- 超长内容先完整渲染再收缩（两遍策略）；截断收缩按重试收敛，最坏 O(n²)——4096 默认上限下无感
- MarkdownV2 不是普通 Markdown：`__` 是下划线不是粗体，不要把渲染结果再当普通 Markdown 二次加工

## 开发

```bash
git clone https://github.com/kamiiroawase/telegram-markdownv2.git
cd telegram-markdownv2
./gradlew build        # JDK 21+：编译全部 target + 宿主可执行测试 + 格式检查
./gradlew jvmTest      # 单独跑某个平台
./gradlew benchmark    # 性能基准：常规消息 ~0.1ms 量级，百万字符 ~10ms；发版时 CI 复跑输出附在 GitHub Release
```

310 个行为级测试（输入/输出断言）；其中 42 个依赖 HTML 解析，在 JS/Wasm 上因上游缺陷静默跳过。质量门禁全挂在 `build` 上：Spotless 格式、`explicitApi()` 显式 API、binary-compatibility-validator 公开 API 快照（有意变更时跑 `./gradlew apiDump`）。

## 许可

[The Unlicense](LICENSE) —— 公共领域，随意使用。
