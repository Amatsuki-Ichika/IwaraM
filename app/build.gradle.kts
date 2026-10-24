import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// App version — single source of truth. CI may override the name from the release tag,
// e.g. ./gradlew assembleRelease -Papp.versionName=0.2.0
val appVersionName: String = findProperty("app.versionName") as String?
    ?: error("app.versionName is not set; define it in gradle.properties or pass -Papp.versionName=")

// versionCode derives from versionName (major * 10000 + minor * 100 + patch) so the
// numeric code can never drift from the single version source in gradle.properties.
val appVersionCode: Int = appVersionName.split(".").let { parts ->
    require(parts.size == 3) { "app.versionName must be major.minor.patch, got \"$appVersionName\"" }
    val (major, minor, patch) = parts.map { part ->
        part.toIntOrNull() ?: error("Non-numeric part in app.versionName: \"$appVersionName\"")
    }
    major * 10000 + minor * 100 + patch
}

// Release signing material is read from a git-ignored keystore.properties.
// The file is optional: debug and test builds work without it, only `assembleRelease` requires it.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "io.github.amatsukiichika.iwaramanager"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "io.github.amatsukiichika.iwaramanager"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            // The app targets modern 64-bit devices only; shipping a single ABI keeps the APK small.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            } else if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
                throw GradleException(
                    "keystore.properties is missing, so the release build cannot be signed. " +
                        "Copy keystore.properties.example and fill in your own values; " +
                        "see CONTRIBUTING.md, section \"Signing\"."
                )
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // R8 is intentionally off: the app has no device-verified keep rules yet, and the
            // Android Studio project template ships release with minification disabled.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

// Export Room's schema JSON per database version so future migrations can be
// regression-tested against the exact historical schema.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

composeCompiler {
    metricsDestination = layout.buildDirectory.dir("compose-metrics")
    reportsDestination = layout.buildDirectory.dir("compose-reports")
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // material-icons-extended is no longer managed by recent Compose BOMs, so it is pinned.
    implementation(libs.compose.material.icons.extended)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)

    implementation(libs.coil.compose)
    implementation(libs.backdrop)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.effect)
    implementation(libs.media3.ui)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.documentfile)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
