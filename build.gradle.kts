import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.spotless)
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

// Dependency repositories live solely in settings.gradle.kts (dependencyResolutionManagement)

group = "io.github.kamiiroawase"

// Version precedence: the exact git tag (CI needs a full clone with fetch-depth=0; only
// HEAD sitting exactly on a v* tag yields that version — the release workflow's tag
// checkout is exactly this shape) > 0.0.0-SNAPSHOT (anything else: no tag at HEAD, or no
// git environment). The nearest-tag fallback is deliberately absent: past the tag it would
// stamp unreleased commits with the released version, and a local publishToMavenLocal
// would then shadow the published artifact under the same coordinates. The version must
// not be hard-coded — the tag is the single source of the released number
version =
    providers
        .of(GitTagVersionSource::class.java) {}
        .orElse("0.0.0-SNAPSHOT")
        .get()

// Configuration-cache-safe git access: the class must not reference script-level members,
// otherwise it becomes a non-static inner class
abstract class GitTagVersionSource : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String? {
        val process =
            try {
                // --exact-match: describe exits non-zero unless HEAD is exactly at a
                // matching tag, which is precisely the release shape
                ProcessBuilder("git", "describe", "--tags", "--exact-match", "--match=v*")
                    .redirectErrorStream(true)
                    .start()
            } catch (_: Exception) {
                return null
            }
        // Drain the output before waiting: a child whose output fills the pipe buffer
        // blocks on write and would deadlock a wait-then-read (git describe emits one
        // short line, but the shape stays correct whatever the child prints)
        val output =
            process
                .inputStream
                .bufferedReader()
                .use { it.readText() }
        // Without a tag at HEAD git describe exits non-zero and writes the error into the
        // output stream — not usable as a version
        if (process.waitFor() != 0) return null
        return output
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
        namespace = "io.github.kamiiroawase.telegram-markdownv2"
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
        nodejs {
            testTask {
                // Mocha's default per-test timeout is 2s wall time: on a contended CI
                // runner (other test tasks and compilers sharing the vCPUs) the stress
                // tests — the 500-level nested list ran 3.0s there while local runs
                // finish in milliseconds — exceed it and flake. Stress tests are
                // deliberately heavy; Gradle's task-level bound still applies.
                useMocha {
                    timeout = "20s"
                }
            }
        }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs {
            testTask {
                // Same wall-clock allowance as the JS side for the same reason
                useMocha {
                    timeout = "20s"
                }
            }
        }
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
    mainClass.set("io.github.kamiiroawase.markdownv2.BenchmarkKt")
}

// Public-API compatibility guard: the api/ directory snapshots every public declaration;
// apiCheck hooks into check (and thus build/CI), so a change that breaks the published
// API fails the build until ./gradlew apiDump is re-run deliberately. klib validation is
// on so every native/wasm target gets its own dump alongside the JVM/classic one — the
// API lives in commonMain, so the dumps stay in sync by construction
apiValidation {
    klib {
        enabled = true
    }
}

// API reference from the KDoc: ./gradlew dokkaGeneratePublicationHtml (CI uploads the
// result as an artifact); configuration is Dokka's multiplatform defaults

// The release workflow supplies the GPG key as ORG_GRADLE_PROJECT_signingInMemoryKey and
// the plugin wires it into Gradle's signing extension by itself (the same property-based
// setup the commonmark-kotlin repo publishes with). This gate adds only two things on
// top: a blank value counts as absent — publishToMavenLocal keeps working without
// secrets, the Build workflow's artifact verification depends on that — and a non-blank
// value must be a complete ASCII-armored key, so a missing/misnamed/mangled secret fails
// with instructions instead of Gradle's cryptic "Could not read PGP secret key"
val signingKey =
    providers
        .gradleProperty("signingInMemoryKey")
        .orNull
        ?.replace("\r\n", "\n")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
if (signingKey != null) {
    require(
        signingKey.startsWith("-----BEGIN PGP PRIVATE KEY BLOCK-----") &&
            signingKey.endsWith("-----END PGP PRIVATE KEY BLOCK-----"),
    ) {
        "signingInMemoryKey is not a complete ASCII-armored PGP secret key (expected the " +
            "-----BEGIN/END PGP PRIVATE KEY BLOCK----- lines). Re-export with " +
            "'gpg --export-secret-keys --armor <key id>' and store the full output in the " +
            "ORG_GRADLE_PROJECT_signingInMemoryKey secret — a partially copied key or one " +
            "stored with literal \\n escapes fails to parse"
    }
}

// Maven Central publishing via the vanniktech plugin: every KMP target's publication
// (plus the root Gradle-module publication KMP consumers reference from commonMain) is
// signed and uploaded to the Central Portal in one `publishToMavenCentral` run. The
// release workflow supplies the credentials and the GPG key as environment-mapped gradle
// properties (ORG_GRADLE_PROJECT_mavenCentralUsername/Password,
// ORG_GRADLE_PROJECT_signingInMemoryKey/KeyPassword)
mavenPublishing {
    // automaticRelease closes and releases the staging deployment right after the upload,
    // so a green tag push needs no manual portal visit
    publishToMavenCentral(automaticRelease = true)

    if (signingKey != null) {
        signAllPublications()
    }

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
