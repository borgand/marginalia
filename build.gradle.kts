import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    compilerOptions {
        // emit JVM default methods instead of DefaultImpls bridges — otherwise Kotlin
        // generates synthetic overrides of @ApiStatus.Internal interface defaults
        // (ToolWindowFactory.getAnchor/getIcon) that the plugin verifier rejects
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
        // The plugin runs on the platform's Kotlin 2.1.21 runtime. Kotlin 2.3+ bumped the
        // coroutine @DebugMetadata version to 2, which that runtime's debug agent rejects
        // ("Debug metadata version mismatch. Expected: 1, got 2") the moment a suspend function
        // we compiled actually suspends — surfaced by get_pending_comments long-poll under the
        // IntelliJ test coroutine agent. Pinning to language version 2.2 keeps emitted metadata
        // at v1, matching the runtime, consistent with the binary-compat rule above.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

// The IntelliJ Platform owns the kotlin.* and kotlinx.coroutines packages at runtime:
// it serves its OWN bundled copies regardless of what the plugin bundles. So every
// library we ship must be binary-compatible with the platform's versions.
//   * coroutines: the platform's patched fork breaks if a stock copy is bundled
//     (NoSuchMethodError in the service container) -> exclude, compile against compileOnly.
//   * kotlin-stdlib: platform 2025.2 ships Kotlin 2.2.0; a dependency compiled against
//     Kotlin 2.3.x inlines 2.3-only stdlib internals (e.g. Duration.fromRawValue) that
//     2.2.0 lacks -> NoSuchMethodError at MCP server start. Hence MCP SDK 0.10.0 +
//     Ktor 3.2.3, both built with Kotlin 2.2.21 (binary-compatible with 2.2.0).
//     Don't bundle stdlib/reflect — the platform provides them.
fun ModuleDependency.excludePlatformKotlin() {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-slf4j")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

dependencies {
    // MCP server (Streamable HTTP) — official Kotlin SDK; Ktor engine is not transitive.
    // Pinned to the last release built with Kotlin 2.2.x so it runs on IntelliJ 2025.2.
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.10.0") { excludePlatformKotlin() }
    implementation("io.ktor:ktor-server-cio:3.5.0") { excludePlatformKotlin() }
    // SDK 0.10.0 does NOT install ContentNegotiation itself (0.13.0 does); without it every
    // JSON response on the POST endpoint returns an empty-body HTTP 406. We install it.
    implementation("io.ktor:ktor-server-content-negotiation:3.5.0") { excludePlatformKotlin() }
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0") { excludePlatformKotlin() }
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Test sources call delay()/runBlocking directly; the platform supplies the
    // patched coroutines runtime, so this is compile-only like the main dependency.
    testCompileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Hunk extraction for the merge engine
    implementation("io.github.java-diff-utils:java-diff-utils:4.16")

    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        bundledPlugin("org.intellij.plugins.markdown")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    // The searchable-options indexer launches a headless IDE that, on teardown, trips a
    // platform slow-op assertion (PSI work on EDT during dynamic plugin unload) and fails
    // the build. The index only powers Settings search pre-indexing — not functionality —
    // so disabling it is the standard, safe choice for this plugin.
    buildSearchableOptions = false
}
