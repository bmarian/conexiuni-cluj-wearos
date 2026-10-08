import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

// The CARTO key stays out of the repository: put `carto.key=...` in local.properties.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// The upload key for Google Play, also kept out of the repository. See the README.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "online.bmarian.bus"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "online.bmarian.bus"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "API_BASE", "\"https://bus.bmarian.online/api/\"")
        buildConfigField("String", "SITE_ORIGIN", "\"https://bus.bmarian.online\"")
        buildConfigField("String", "CARTO_KEY", "\"${localProperties.getProperty("carto.key", "")}\"")
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("upload") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Without the upload key the debug one still lets the fast build go on a watch;
            // Play refuses anything signed with it.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// The baseline profile plugin copies release but only turns off the old minify flag. Recorded
// on an obfuscated build, the profile names classes the real one doesn't have.
androidComponents {
    finalizeDsl { android ->
        android.buildTypes.findByName("nonMinifiedRelease")?.optimization?.enable = false
    }
}

// Generated with `gradlew :app:generateBaselineProfile` on an emulator; see the README.
baselineProfile {
    dexLayoutOptimization = true
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.navigation)
    implementation(libs.compose.ui.tooling)
    implementation(libs.core.splashscreen)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.play.services.location)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.wear.tooling.preview)
    implementation(libs.zxing.core)
    implementation(libs.wear)
    implementation(libs.wear.ongoing)
    implementation(libs.wear.input)
    implementation(libs.material.icons.extended)
    implementation(libs.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.ui.tooling)
}
