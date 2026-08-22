import com.google.protobuf.gradle.id
import java.util.Properties

/**
 * Version comes from version.properties at the repo root, bumped by hand — there is no git repository
 * to derive a code from. Falls back so a checkout without the file still builds.
 */
val versionProps = Properties().apply {
    val file = rootProject.file("version.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val appVersionName: String = versionProps.getProperty("versionName") ?: "1.0"
val appVersionCode: Int = versionProps.getProperty("versionCode")?.toIntOrNull()
    ?: if (versionProps.getProperty("versionCode") != null) {
        // Present but not an integer: fail loudly rather than shipping version 1 forever.
        throw GradleException("version.properties: versionCode must be an integer")
    } else {
        1
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
