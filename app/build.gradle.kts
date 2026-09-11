import com.google.protobuf.gradle.id
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject

/**
 * The commit count, and whether this clone is shallow, read from git at configuration time.
 *
 * **A `ValueSource` rather than `providers.exec()`, and that is about failure rather than taste.**
 * `providers.exec()` throws out of `.get()` when the binary is missing, and there is no
 * configuration-cache-compatible way to catch it (gradle/gradle#23914). `obtain()` returning null is
 * the same information with a fallback attached.
 *
 * **It cannot go stale, which is the whole reason deriving a version this way is safe.** A
 * `ValueSource` read at configuration time is a *build configuration input*, so Gradle re-executes it
 * at the start of every build to decide whether the cached configuration still holds — move `HEAD`
 * and the entry is invalidated and the new number reaches the manifest merger. Verified rather than
 * assumed: an empty commit makes Gradle say "a build logic input of type 'GitRepositoryState' has
 * changed" and the APK's code moves with it. The cost is one configuration phase per commit, which is
 * seconds here and is not worth "optimising" into a cached file: a `versionCode` that lagged behind
 * `HEAD` would be silently wrong, and an APK shipped at the wrong code is wrong on every phone that
 * takes it.
 *
 * **The `.git` probe lives in here, not in the script body**, for two reasons. A bare `exists()` at
 * configuration time is an *undeclared* input. And `git` walks **upwards** looking for a repository,
 * so a Logline checkout sitting inside some other clone would otherwise take that repository's commit
 * count without a word. A worktree's `.git` is a file rather than a directory, hence `exists()`.
 */
abstract class GitRepositoryState : ValueSource<String, GitRepositoryState.Parameters> {
    interface Parameters : ValueSourceParameters {
        val rootDir: Property<String>
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String? {
        val root = File(parameters.rootDir.get())
        if (!File(root, ".git").exists()) return null
        val count = git(root, "rev-list", "--count", "HEAD") ?: return null
        // Prints "true"/"false". A `--filter=blob:none` clone is *not* shallow and counts correctly,
        // so this catches the truncated-history case and nothing else.
        val shallow = git(root, "rev-parse", "--is-shallow-repository") ?: "false"
        return "$count|$shallow"
    }

    private fun git(root: File, vararg args: String): String? {
        val out = ByteArrayOutputStream()
        val result = try {
            execOperations.exec {
                workingDir = root
                commandLine("git", *args)
                standardOutput = out
                errorOutput = ByteArrayOutputStream()
                isIgnoreExitValue = true
            }
        } catch (_: Exception) {
            return null // No git on PATH at all: a source archive still builds.
        }
        if (result.exitValue != 0) return null
        return out.toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * `versionName` is hand-bumped in version.properties; `versionCode` is derived from the commit count.
 *
 * The split is deliberate. A version *name* is an editorial claim about what changed and nothing
 * should derive it. A version *code* only has to increase, and hand-bumping it is how it stayed at 1
 * through 187 commits — which made the About screen, whose entire job is answering "which build is
 * this phone holding?", answer `1` for every build ever made.
 *
 * See version.properties for the two rules that come with this: the shallow-clone trap, and the
 * ratchet on `versionCodeBase`.
 */
val versionProps = Properties().apply {
    val file = rootProject.file("version.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val appVersionName: String = versionProps.getProperty("versionName") ?: "1.0"

// A leftover `versionCode=` would now be ignored while still looking authoritative to whoever wrote
// it. Disagreeing quietly with the file is worse than refusing to build.
if (versionProps.getProperty("versionCode") != null) {
    throw GradleException(
        "version.properties: versionCode is derived from the git commit count now and must be " +
            "removed. Set versionCodeBase instead — see the comments in that file."
    )
}

val versionCodeBase: Int = versionProps.getProperty("versionCodeBase")?.toIntOrNull()
    ?: throw GradleException("version.properties: versionCodeBase must be an integer")
val versionCodeFallback: Int = versionProps.getProperty("versionCodeFallback")?.toIntOrNull()
    ?: throw GradleException("version.properties: versionCodeFallback must be an integer")

val gitState: String? = providers.of(GitRepositoryState::class.java) {
    parameters.rootDir.set(rootProject.projectDir.absolutePath)
}.orNull

val appVersionCode: Int = when {
    gitState == null -> {
        // No repository and no git binary — a source archive. Never a release path, since CI always
        // has both, so a low number and a warning is the honest answer.
        logger.warn(
            "Logline: no git repository, versionCode falls back to $versionCodeFallback. " +
                "An APK built this way cannot upgrade a real install."
        )
        versionCodeFallback
    }
    gitState.substringAfter('|') == "true" -> {
        // **The loud failure, and it is loud on purpose.** `git rev-list --count HEAD` in a shallow
        // clone does not error: it succeeds and returns the clone depth, which is 1 under
        // actions/checkout's default. Falling back here would ship versionCodeBase + 1 from a green
        // build that looks entirely healthy, and an install at that code blocks every later upgrade
        // until somebody uninstalls — which wipes the mTLS certificates and the entity id.
        throw GradleException(
            "Logline: this is a shallow clone, so the commit count is the clone depth rather than " +
                "the history. Set `fetch-depth: 0` on actions/checkout, or `git fetch --unshallow`."
        )
    }
    else -> {
        val count = gitState.substringBefore('|').toIntOrNull()
            ?: throw GradleException("Logline: could not parse the git commit count from '$gitState'")
        versionCodeBase + count
    }
}

/**
 * Release signing, from the environment first, then local.properties (git-ignored, already holds
 * sdk.dir). Absent or incomplete credentials leave the release unsigned rather than failing the
 * build — most developers here never need the key.
 */
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingInput(env: String, property: String): String? =
    (System.getenv(env) ?: localProps.getProperty(property))?.takeIf { it.isNotBlank() }

val keystorePath = signingInput("LOGLINE_KEYSTORE", "logline.keystore")
val keystorePassword = signingInput("LOGLINE_KEYSTORE_PASSWORD", "logline.keystore.password")
val keyAlias0 = signingInput("LOGLINE_KEY_ALIAS", "logline.key.alias")
val keyPassword0 = signingInput("LOGLINE_KEY_PASSWORD", "logline.key.password")
val keystoreFile = keystorePath?.let { rootProject.file(it) }
val canSignRelease = keystoreFile?.exists() == true &&
    keystorePassword != null && keyAlias0 != null && keyPassword0 != null

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "se.rise.logline"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "se.rise.logline"
        minSdk = 30
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keyAlias0
                keyPassword = keyPassword0
            }
        }
    }

    buildTypes {
        release {
            // Minification is off on purpose, not by oversight. This is an in-house tool for
            // developers and is never published to a store, so there is no size ceiling to meet and
            // no reason to obfuscate — readable stack traces are worth more here. Turning R8 on would
            // also need hand-written keeps in a proguard-rules.pro for GeneratedMessageLite subclasses
            // (protobuf-lite ships none and resolves generated fields reflectively) and for the Zenoh
            // JNI classes, whose native methods are bound by name. Both of those fail at runtime, not
            // at build time. The APK is mostly native libraries R8 cannot touch in any case.
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // `android.util.Log` is a stub that throws in a JVM test, and the track reader logs when a
            // walk stops early — the one path a truncated-file test has to go down. Returning defaults
            // rather than throwing keeps that path testable. Note this is only safe because nothing
            // here *depends* on a stub throwing; the parsers that take foreign input deliberately use
            // kotlinx-serialization rather than `org.json` so the tested code is the shipped code.
            isReturnDefaultValues = true
        }
    }
}

protobuf {
    protoc {
        artifact = libs.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                id("java") {
                    option("lite")
                }
                id("kotlin") {
                    option("lite")
                }
            }
            // MCAP schemas need a serialised FileDescriptorSet, and the *lite* runtime strips
            // descriptors entirely — there is no getDescriptor() on a generated lite class. So protoc
            // emits one at build time and it ships as a raw resource; nothing parses it at runtime, the
            // bytes go straight into the MCAP Schema record.
            //
            // includeImports is mandatory: without it google/protobuf/timestamp.proto is absent and no
            // reader can resolve the schema.
            // Only the main variants. `all()` also covers the unit-test and androidTest proto tasks,
            // and they would race to write the same descriptor file.
            if (!name.contains("UnitTest") && !name.contains("AndroidTest")) {
                generateDescriptorSet = true
                descriptorSetOptions.includeImports = true
                descriptorSetOptions.includeSourceInfo = false
            // Written into src/main/assets so it is packaged like any other asset. AGP 9 rejects
            // Provider-based source dirs, and routing a build/ directory through the Variant API for one
            // 3 KB file is more machinery than it is worth. Git-ignored — it is generated, not authored.
                descriptorSetOptions.path =
                    file("src/main/assets/keelson_payloads.desc").path
            }
        }
    }
}

if (!canSignRelease) {
    logger.warn(
        "Logline: release builds will be UNSIGNED — " +
            when {
                keystorePath == null -> "no keystore configured (set LOGLINE_KEYSTORE or logline.keystore)"
                keystoreFile?.exists() != true -> "keystore not found at ${'$'}keystorePath"
                else -> "keystore password, key alias or key password is missing"
            }
    )
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    // zstd for the MCAP recording. `@aar` because the artifact ships the per-ABI .so; the plain jar
    // carries desktop natives that would not load on a phone.
    implementation("${libs.zstd.jni.get()}@aar")
    // The plain jar for JVM unit tests: the @aar above carries only the Android .so, so the format
    // tests — which decompress what the writer produced — would fail to load the native library.
    testImplementation(libs.zstd.jni)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.zenoh.kotlin.android)
    // Reading platform-geometry documents: imports, bus discovery, the shared library.
    // Already on the runtime classpath via zenoh-kotlin; declared so the parser's unit
    // tests run the same implementation the device does. Runtime API only — no plugin.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.protobuf.javalite)
    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)
    // OpenStreetMap tiles for the live view. No API key, and it caches tiles to app-private storage so
    // a pre-loaded area still renders offshore. A plain Android View, used through AndroidView.
    implementation(libs.osmdroid.android)
    // CameraX, for the time-lapse subject. `camera-core` is the use cases, `camera-camera2` the
    // implementation behind them, `camera-lifecycle` the bindToLifecycle entry point — all three are
    // needed even though only ImageCapture is used.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    // `camera-view` is only for the QR scanner's PreviewView — the time-lapse needs no preview at all,
    // which is why it was not here before.
    implementation(libs.androidx.camera.view)

    // QR codes, for handing a phone its connection settings without typing them. The pure-Java core:
    // no Play Services, no scanner Activity, no second camera stack beside CameraX.
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// The descriptor set is produced by protoc, so asset merging has to wait for it. Without this the
// first clean build packages an APK with no descriptor and every MCAP schema comes out empty.
androidComponents {
    onVariants { variant ->
        val capitalised = variant.name.replaceFirstChar { it.uppercase() }
        tasks.matching { it.name == "merge${capitalised}Assets" }.configureEach {
            dependsOn("generate${capitalised}Proto")
        }
    }
}
