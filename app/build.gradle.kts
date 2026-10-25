import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Browser distributions are packaged as assets, never put on the Android classpath.
// Primary metadata: central.sonatype.com/artifact/org.webjars.npm/{katex,mermaid}
val responseRendererLibraries by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false // The dist bundles contain their own browser dependencies.
}
val responseRendererVersions = mapOf("katex" to "0.17.0", "mermaid" to "11.14.0")
val responseRendererExpected = rootProject.file("config/response-renderer-sha256.properties")
fun responseRendererDigest(archive: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    archive.inputStream().use { input ->
        val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
val verifyResponseRendererDistributions by tasks.registering {
    inputs.files(responseRendererLibraries)
    inputs.file(responseRendererExpected)
    doLast {
        val pinned = Properties().apply { responseRendererExpected.inputStream().use { load(it) } }
        for ((name, version) in responseRendererVersions) {
            val expected = pinned.getProperty("$name.$version", "").trim()
            check(expected.matches(Regex("[0-9a-f]{64}"))) {
                "Unapproved renderer $name:$version. Follow REVIEW_34_37.md to approve exact SHA-256 before Release."
            }
            val archive = responseRendererLibraries.files.single { it.name == "$name-$version.jar" }
            check(responseRendererDigest(archive) == expected) { "Renderer SHA-256 mismatch: $name:$version" }
        }
    }
}
// Debug compilation may establish the first trusted baseline; Release cannot use unapproved bytes.
tasks.matching { it.name.contains("Release") }.configureEach { dependsOn(verifyResponseRendererDistributions) }
val responseRendererAssets = layout.buildDirectory.dir("generated/responseRendererAssets")
val prepareResponseRendererAssets by tasks.registering {
    inputs.files(responseRendererLibraries)
    inputs.file(responseRendererExpected)
    outputs.dir(responseRendererAssets)
    doLast {
        val root = responseRendererAssets.get().asFile.resolve("response-renderer")
        root.deleteRecursively()
        root.mkdirs()
        val required = responseRendererVersions
        val pinned = Properties().apply { responseRendererExpected.inputStream().use { load(it) } }
        val hashes = mutableListOf<String>()
        for ((name, version) in required) {
            val archive = responseRendererLibraries.files.single { it.name == "$name-$version.jar" }
            val actual = responseRendererDigest(archive)
            val expected = pinned.getProperty("$name.$version", "").trim()
            if (expected.isBlank()) logger.warn("Renderer $name:$version SHA-256 is not approved; Debug baseline only, Release is blocked (REVIEW_34_37.md).")
            else check(expected.matches(Regex("[0-9a-f]{64}")) && actual == expected) { "Renderer SHA-256 mismatch: $name:$version" }
            hashes.add("$name $version $actual")
            val target = root.resolve(name)
            var total = 0L
            var licenses = 0
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                val prefix = "META-INF/resources/webjars/$name/$version/"
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                    val relative = entry.name.removePrefix(prefix)
                    val license = relative.substringAfterLast('/').startsWith("LICENSE", true)
                    val browserFile = if (name == "katex") relative in setOf("dist/katex.min.js", "dist/katex.min.css") || relative.startsWith("dist/fonts/")
                        else relative == "dist/mermaid.esm.min.mjs" ||
                            relative.startsWith("dist/chunks/mermaid.esm.min/") && relative.endsWith(".mjs")
                    if (!browserFile && !license) continue
                    val path = if (relative.startsWith("dist/")) relative.removePrefix("dist/") else relative
                    check(path.isNotBlank() && path.split('/').none { it == ".." || it.isEmpty() } && !path.contains('\\'))
                    val file = target.resolve(path)
                    check(file.canonicalPath.startsWith(target.canonicalPath + File.separator))
                    file.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            total += n
                            check(total <= 200L * 1024 * 1024) { "Renderer archive exceeds extraction budget" }
                            output.write(buffer, 0, n)
                        }
                    } }
                    if (license && '/' !in relative) licenses++
                }
            }
            check(responseRendererDigest(archive) == actual) { "Renderer jar changed during extraction: $name:$version" }
            check(licenses > 0) { "Missing $name license: inspect the pinned distribution" }
            val main = if (name == "katex") "katex.min.js" else "mermaid.esm.min.mjs"
            check(target.resolve(main).isFile) { "Missing browser entry point $name/$main" }
            if (name == "katex") check(target.resolve("katex.min.css").isFile && target.resolve("fonts").isDirectory)
        }
        root.resolve("distribution-sha256.txt").writeText(hashes.joinToString("\n", postfix = "\n"))
    }
}
tasks.matching { it.name == "preBuild" || it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(prepareResponseRendererAssets) }

android {
    namespace = "com.example.openaichat"
    compileSdk = 35
    sourceSets.getByName("main").assets.srcDir(responseRendererAssets)

    defaultConfig {
        // Independent installation and data directory alongside the original 000Chat app.
        applicationId = "com.example.openaichat.openrouter"
        minSdk = 26
        targetSdk = 35
        versionCode = 64
        versionName = "4.17.0"
        // Branch [ci] workflows set GITHUB_SHA; local installs must not claim a CI commit.
        val githubSha = System.getenv("GITHUB_SHA")
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) } ?: "local-unknown"
        buildConfigField("String", "BUILD_GIT_SHA", "\"$githubSha\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        // AGP 8+ does not generate BuildConfig unless explicitly enabled.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Emit actionable failures in GitHub Actions without flooding successful test output.
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
            )
        }
    }

    signingConfigs {
        getByName("debug") {
            val keystorePath = System.getenv("DEBUG_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("DEBUG_KEY_PASSWORD") ?: "android"
            }
            // If DEBUG_KEYSTORE_PATH isn't set (e.g. local builds without the CI secret),
            // this silently keeps AGP's normal auto-generated ~/.android/debug.keystore.
        }
    }
}

dependencies {
    add(responseRendererLibraries.name, "org.webjars.npm:katex:0.17.0")
    add(responseRendererLibraries.name, "org.webjars.npm:mermaid:11.14.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.14")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    implementation("androidx.annotation:annotation:1.11.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("io.github.commons-app:ajpegtran:0.1.14")
}
