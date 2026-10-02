import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.spotless)
    `maven-publish`
}

// Dependency repositories live solely in settings.gradle.kts (dependencyResolutionManagement)

group = "com.github.kamiiroawase"

// Version precedence: -Pversion (passed by JitPack on tag builds, e.g. v1.1.0 → 1.1.0)
// > derivation from the git tag (CI needs a full clone with fetch-depth=0; HEAD exactly on
// a tag yields that exact version, afterwards the nearest reachable tag sticks)
// > 0.0.0-SNAPSHOT (no tag or no git environment). The version must not be hard-coded —
// it would override the value JitPack passes in
version =
    providers
        .gradleProperty("version")
        .orElse(providers.of(GitTagVersionSource::class.java) {})
        .orElse("0.0.0-SNAPSHOT")
        .get()

// Configuration-cache-safe git access: the class must not reference script-level members,
// otherwise it becomes a non-static inner class
abstract class GitTagVersionSource : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String? {
        val process =
            try {
                ProcessBuilder("git", "describe", "--tags", "--abbrev=0", "--match=v*")
                    .redirectErrorStream(true)
                    .start()
            } catch (_: Exception) {
                return null
            }
        // Without tags git describe exits non-zero and writes the error into the output
        // stream — not usable as a version
        if (process.waitFor() != 0) return null
        return process
            .inputStream
            .bufferedReader()
            .readText()
            .trim()
            .removePrefix("v")
            .ifEmpty { null }
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

kotlin {
    // Android uses AGP's KMP library plugin (kotlin { android { } } — no top-level android
    // block, no androidTarget)
    android {
        namespace = "com.github.kamiiroawase.telegram-markdownv2"
        compileSdk = 37
        minSdk = 23

        // The AGP KMP plugin does not enable host tests by default; commonTest must run on
        // the Android unit tests
        withHostTest { }

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // The JVM side targets bytecode 11 to widen the consumable range
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    js {
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }
    linuxX64()
    linuxArm64()
    macosArm64()
    mingwX64()
    iosArm64()
    iosX64()
    iosSimulatorArm64()

    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            api(libs.commonmark)
            implementation(libs.commonmark.ext.gfm.strikethrough)
            implementation(libs.commonmark.ext.gfm.tables)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // Drive the customNode/customBlock defensive branches with a real third-party
            // extension
            implementation(libs.commonmark.ext.footnotes)
        }
    }

    explicitApi()
}

// JVM rendering performance benchmark (the data source of the README performance section):
// ./gradlew benchmark — single-threaded, median of size-scaled iterations after warm-up
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Runs the JVM rendering benchmarks backing the README performance table."
    val testCompilation =
        kotlin.targets
            .named<KotlinJvmTarget>("jvm")
            .get()
            .compilations
            .getByName("test")
    dependsOn(testCompilation.compileTaskProvider)
    classpath = testCompilation.runtimeDependencyFiles
    mainClass.set("com.github.kamiiroawase.markdownv2.BenchmarkKt")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("telegram-markdownv2")
            description.set(
                "Kotlin Multiplatform CommonMark (GFM) to Telegram MarkdownV2 converter " +
                    "with structure-preserving truncation",
            )
            url.set("https://github.com/kamiiroawase/telegram-markdownv2")
            licenses {
                license {
                    name.set("The Unlicense")
                    url.set("https://unlicense.org")
                }
            }
            developers {
                developer {
                    id.set("kamiiroawase")
                    name.set("kamiiroawase")
                    url.set("https://github.com/kamiiroawase")
                }
            }
            scm {
                url.set("https://github.com/kamiiroawase/telegram-markdownv2")
                connection.set("scm:git:https://github.com/kamiiroawase/telegram-markdownv2.git")
                developerConnection.set("scm:git:git@github.com:kamiiroawase/telegram-markdownv2.git")
            }
        }
    }
}
